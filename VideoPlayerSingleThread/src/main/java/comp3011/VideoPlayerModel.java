/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Vanessa Soetjiadi, a1945309
 *   
 * Modifications:
 * - The playback clock (anchor, pre-roll, pause shift and its lock) moved out of this class into PlaybackClock.
 * - The decoder thread, effects worker pool, in-flight frame queue and stop/flush logic moved out of this class
 *   into FramePipeline; the grabber-specific decoder work is the inner class GrabberSource.
 * - Frames copied for the effects workers are reused through a pool instead of cloned every time.
 * - Each effects worker thread reuses its own frame converter instead of creating one per frame.
 * - pendingAudio is a ConcurrentLinkedQueue because the decoder thread adds to it while the JavaFX thread drains it.
 * - playbackOpen and pauseRequested are volatile because the decoder thread reads them.
 * - Audio is always queued by the decoder, and the mute decision is made on the JavaFX thread when a chunk is due.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;
 
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
 
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;
 
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;
 
/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg grabber, seeking logic and audio scheduling, and coordinates the rest: {@link PlaybackClock}
 * decides when each frame is due, and {@link FramePipeline} decodes frames on one thread and applies the effects
 * on several worker threads at once. It publishes decoded frames and playback state through callbacks supplied by
 * its {@link VideoPlayerController}, keeping it decoupled from the JavaFX view.
 * </p>
 *
 * <p>
 * <b>Which thread runs what.</b> Three kinds of thread touch this class:
 * <ul>
 * <li>The JavaFX thread runs almost everything: the public controls, the heartbeat ({@link #pumpPlayback}), audio
 * output, and all calls to the view callbacks.</li>
 * <li>The decoder thread runs only {@link GrabberSource}. The FFmpeg grabber is not thread-safe, so it is
 * confined to this one thread, and the JavaFX thread never touches it while the decoder could be running.</li>
 * <li>The effects worker threads run only the job that {@link GrabberSource} hands back (apply the effects, convert
 * to an image). They get their own copy of the frame, so they never share data with the decoder.</li>
 * </ul>
 * Fields that more than one of these threads reads are marked volatile, or are accessed through a thread-safe
 * object ({@code clock}, {@code pipeline}, {@code pendingAudio}, {@code framePool}).
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel meaning "no value": no seek is pending, or the duration is unknown.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    // Audio is written to the device this far ahead of the video clock so the device buffer never runs dry,
    // which would be heard as a crackle.
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    
    // Spare frames for the decoder thread to copy into. Workers return each frame after converting it.
    // Reusing frames avoids allocating (and later garbage collecting) a full-size image for every frame played.
    private final Queue<Frame> framePool = new ConcurrentLinkedQueue<>();
    
	// Provides one converter per worker thread. Each worker reuses its own, so nothing is shared
	// between threads (no locking needed) and nothing is allocated per frame.
	private static final ThreadLocal<JavaFXFrameConverter> WORKER_CONVERTER =
			ThreadLocal.withInitial(JavaFXFrameConverter::new);
	
	// A switch for the effect fusion optimisation: run with -Dvideoplayer.noFusion=true to turn it off and use
	// the plain effect chain instead. Kept switchable so the two can be compared for speed and for identical output.
	private static final boolean FUSE_EFFECTS = !Boolean.getBoolean("videoplayer.noFusion");
	
	/**
	 * All playback timing (anchor, pre-roll, pause shift). The decoder thread and the JavaFX thread both use it,
	 * so every access goes through this object, which guards its own state with a lock. See PlaybackClock.
	 */
	private final PlaybackClock clock = new PlaybackClock();
	
	/**
	 * The multi-threaded frame production line: the decoder thread, the effects workers and the ordered queue of
	 * frames in flight. See FramePipeline for how the threads are divided up and why that is safe.
	 */
	private final FramePipeline<PreparedFrame> pipeline = new FramePipeline<>(this::onDecodeFailure);
	
	// The effects the workers actually run (possibly fused, see FUSE_EFFECTS). Built once on the decoder thread when
	// the first frame arrives and read by the workers, so it is volatile to make sure they see the finished list.
	private volatile List<FrameProcessor> effectChain = List.of();
	
	
    // The effects as configured by the controller; effectChain is derived from these.
    private final List<FrameProcessor> frameProcessors;
    // Modified to use ConcurrentLinkedQueue because ArrayDeque is not thread safe for concurrent
    // add/poll of elements. The decoder thread adds audio and the JavaFX thread drains it.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
 
    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };
 
    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects. Decoder thread only.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added. Taken from the pipeline, held until due.
 
    // playbackOpen and pauseRequested are volatile because they are written on the JavaFX thread but read by the
    // decoder thread; without it the decoder could keep using a stale cached value and carry on decoding after a
    // stop or pause.
    private volatile boolean playbackOpen; // True when playback is happening.
    private volatile boolean pauseRequested;
    private volatile boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    
    private boolean displayWhilePaused; // Lets exactly one frame appear after a seek while paused
 
    public VideoPlayerModel(
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler,
            List<FrameProcessor> frameProcessors) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = frameProcessors;
    }
 
    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }
 
    public void startOver() {
        if (videoFile == null) {
            return;
        }
 
        seekTo(0);
    }
 
    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }
 
    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }
 
    public void togglePause() {
        // After the video has finished (or been stopped) the pipeline is closed, so "play" has to reopen it
        // at the position we ended on rather than just clear the pause flag.
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }
 
        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;
 
        // The clock records when the pause began and, on resume, pushes every frame's due time back by the pause
        // length. Audio already buffered in the device is flushed on pause so the sound stops immediately.
        if (pauseRequested) {
        	clock.pause(System.nanoTime());
            flushAudioOutput();
        } else {
        	clock.resume(System.nanoTime());
        }
 
        notifyPlaybackStateChanged();
    }
 
    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        // Drop queued and buffered sound so the change is heard straight away rather than after the buffer empties.
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }
 
    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }
 
    public void stopPlayback() {
        closePlaybackResources();
        displayWhilePaused = true;
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }
 
    public void shutdown() {
        // Order matters: closePlaybackResources() stops this player's decoder through the pipeline, so it has to
        // run before the pipeline itself is closed.
        closePlaybackResources();
        pipeline.close(); // releases this player's threads only (nothing is shared with other players)
    }
 
    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }
 
        // Repeated presses before the next frame appears build on the last requested position, not on the
        // (stale) frame still on screen, so pressing "+5s" three times quickly moves 15 seconds.
        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }
 
    private void seekTo(long timestampUs) {
        // "Logical" is the position the user asked for and the progress display shows; "grab" is what we ask the
        // decoder for. They differ only at the very end of the video, where seeking to the exact end would
        // produce no frame (see displayableSeekTimestamp).
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);
 
        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }
 
        // The order of these steps matters. Flushing first means no old frame can arrive after the clock is
        // reset, and the seek is only handed to the decoder at the end so that the first frame it produces is
        // the one that becomes the new clock anchor.
        pipeline.flush(); // the decoder is now idle and its old frames are discarded
        resetPlaybackClock(logicalTimestampUs);
        if (pauseRequested) {
            displayWhilePaused = true;
        }
        flushAudioOutput();
        pipeline.seek(grabTimestampUs); // hand the actual seek to the decoder thread
        pipeline.requestDecoding();
        notifyPlaybackStateChanged();
    }
 
    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();
 
        pauseRequested = initiallyPaused;
        displayWhilePaused = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;
 
        notifyStatusChanged(videoFile.getName());
 
        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            pipeline.requestDecoding();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }
 
    private void openPlaybackResources(long startTimestampUs) throws Exception {
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();
 
        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());
 
        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();
 
        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;
 
        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }
        
        pipeline.begin(new GrabberSource());
        if (startTimestampUs > 0) {
        	// Not done directly on the grabber here: the grabber belongs to the decoder thread, so the seek is
        	// queued and the decoder thread performs it as its first job.
        	pipeline.seek(startTimestampUs);
        }
    }
 
    // Resets the playback clock so the next decoded frame becomes the new time-zero anchor at the given
    // position. Called on a seek (and when playback is restarted). Runs on the JavaFX thread while the decoder
    // is idle (after pipeline.flush() for a seek, or before the first requestDecoding() on a start), so no old
    // frames are still being produced.
    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        clock.reset(logicalTimestampUs, pauseRequested, System.nanoTime());
    }
    
    // Called by the pipeline, on the decoder thread, when reading or decoding fails.
    private void onDecodeFailure(Exception e, BooleanSupplier stillCurrent) {
    	// Handled on the JavaFX thread because it changes player state and calls the view. By the time that runs
    	// the user may have sought away from the run that failed, in which case the error is about frames nobody
    	// will see and is ignored.
    	Platform.runLater(() -> {
    		if (stillCurrent.getAsBoolean()) {
    			handlePlaybackError(e);
    		}
    	});
    }
 
    // The heartbeat. Runs on the JavaFX thread once per screen refresh and does three small jobs; none of them
    // waits for the decoder or the workers, so a slow frame never freezes the UI.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }
 
        // Task (1): play any audio that is now due.
        writeDueAudio(now);
 
        // Task (2): show the next frame if it's due. The due time is computed from the live clock,
        // so a pause/resume shifts every queued frame.
        if (!pauseRequested || displayWhilePaused) {
            if (preparedFrame == null) {
            	try {
            		// Takes the next frame in order, if it is ready. Can throw if that frame's effect work failed.
            		preparedFrame = pipeline.pollReady();
            	} catch (Exception e) {
            		handlePlaybackError(e);
            		return;
            	}
            }
            if (preparedFrame != null && clock.dueTimeNs(preparedFrame.mediaTimestampUs()) <= now) {
                displayPreparedFrame(now);
            }
        }
        
        // Playback is over only when the pipeline has nothing left AND the frame already taken from it
        // (preparedFrame, waiting for its due time) has been shown. Checking only the pipeline would cut off
        // the last frame.
        if (pipeline.isDrained() && preparedFrame == null) {
            finishPlayback(); return;
        }
 
        // Task (3): top up the frame queue. Does nothing if the queue is full or a decode job is already running.
        // Skipped while paused so the decoder does not run ahead for no reason (a paused seek asks for its own
        // frame in seekTo).
        if (!pauseRequested) {
        	pipeline.requestDecoding();
        }
    }
 
    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        displayWhilePaused = false;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;
 
        // Dump some logging to the console once per second so that real time performance can be monitored.
        // "Headroom" is how long before its due time the frame was ready; negative (red) means the frame was
        // late, meaning decoding and effects together took longer than one frame's time.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }
 
        notifyFrameReady(frame.image());
    }
 
    /**
     * Called on the decoder thread. Copies the audio from a decoded frame and adds it to
     * pendingAudio for the JavaFX thread to play.
     *
     * <p>
     * Audio is always queued, even while muted. Whether a chunk is played or thrown away is decided later by
     * writeDueAudio(), when the chunk comes due. That way the decoder thread does not need to know about the mute
     * state, and unmuting resumes sound immediately from the correct position.
     * </p>
     */
    private void queueAudio(long timestampUs, Frame frame) {
        if (audioPlayer == null) {
            return;
        }
 
        // The samples are copied because the grabber reuses its buffer for the next grab.
        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }
 
    /**
     * Called on the JavaFX thread by the heartbeat. Writes any queued audio that is due
     * to the audio player, or discards it when audio output is disabled.
     */
    private void writeDueAudio(long now) {
    	// No audio device: nothing can ever be played, so drop whatever is queued.
        if (audioPlayer == null) {
            pendingAudio.clear(); return;
        }
        
        // Nothing is due while paused.
        if (pauseRequested) {
        	return;
        }
        
        // The media timestamp that audio should have been played up to by now, including a small lead so audio
        // never runs dry. The clock answers from one consistent snapshot, and is empty until the first frame
        // has set its anchor.
        OptionalLong due = clock.mediaTimestampAtUs(now + AUDIO_LEAD_NS);
        if (due.isEmpty()) {
        	return;
        }
        
        long dueTimestampUs = due.getAsLong();
        
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }
            
            // When muted, each chunk is discarded as it comes due. This keeps the queue current
            // and keeps the position correct when the user unmutes.
            if (!audioOutputEnabled) {
                pendingAudio.remove();
                continue;
            }
 
            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return; // device buffer is full; try again next heartbeat
            }
 
            audio.advance(written);
            if (!audio.finished()) {
                return; // only part of the chunk fitted; the rest is written next heartbeat
            }
            pendingAudio.remove();
        }
    }
 
    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;
 
        // Left in the "paused at the end" state so the controls show a play button, and so that play or a seek
        // reopens the video from this position (see togglePause).
        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }
 
    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }
 
    // Shuts down everything belonging to the current playback. The order is important:
    // the decoder thread must be idle before the grabber it is using is closed.
    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false; // 1. tell the decoder loop to stop
        pipeline.end(); // 2. wait until the decoder is idle. MUST happen before the grabber is closed
        pendingAudio.clear();
        clock.clear();
        audioAvailable = false;
        frameProcessorsInitialised = false;
        
        if (audioPlayer != null) { audioPlayer.close(); audioPlayer = null; }
 
        // 3. only now is it safe to close the grabber
        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }
    }
 
    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }
 
    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }
 
    // Seeking to exactly the end of the video (or past it) returns no frame, so the user would see nothing.
    // For that case this backs up by one frame so the last picture can still be shown. Positions before the end
    // are used as they are.
    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }
 
        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }
 
        return Math.max(0, durationUs - frameDurationUs);
    }
 
    // Runs on an effects worker thread (from the job built in GrabberSource.readNext), on that frame's private copy.
    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : effectChain) {
            processor.process(frame, info);
        }
    }
 
    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
 
        // Fusing reads the tables the effects have just built, so it must come after they are initialised.
        effectChain = FUSE_EFFECTS ? FusedPass.fuse(frameProcessors, videoInfo) : List.copyOf(frameProcessors);
    }
 
    // With audio present the grabber must return audio and picture frames alike so no sound is skipped;
    // without audio, grabImage() skips straight to the next picture.
    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }
 
    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }
 
    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }
 
    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }
 
    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }
 
    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }
 
    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }
    
    /**
     * Everything the decoder thread does with the FFmpeg grabber. The pipeline calls it only on the decoder thread,
     * which is what keeps the (not thread-safe) grabber confined to one thread. It does the work that has to be done
     * in order on that thread (grab, queue the audio, time the frame, copy it so the grabber can reuse its buffer),
     * and hands back the part that can run in parallel on a worker: apply the effects and convert for display.
     */
    private final class GrabberSource implements FramePipeline.FrameSource<PreparedFrame> {
    	@Override
    	public void seek(long timestampUs) throws Exception {
    		grabber.setTimestamp(timestampUs);
    	}
    	
    	// Modified to grab one picture frame per call and hand the effect work to a worker. One frame at a time
    	// lets the pipeline decide when to read the next one, which is how it limits how far the decoder runs ahead.
    	@Override
    	public Callable<PreparedFrame> readNext(BooleanSupplier cancelled) throws Exception {
    		// Loops because some grabs are audio only (no picture) and must not produce a frame. It re-checks for
    		// a stop or seek on every pass so the decoder thread gives up promptly.
    		while (playbackOpen && !cancelled.getAsBoolean()) {
    			Frame frame = grabFrame();
    			if (frame == null) {
    				return null; // end of the stream; the pipeline keeps showing the frames still in flight
    			}
    			
    			long timestampUs = grabber.getTimestamp();
    			if (audioAvailable && frame.samples != null) {
    				queueAudio(timestampUs, frame);
    			}
    			
    			if (frame.image == null) {
    				continue;
    			}
    			
    			// Set up lazily on the first picture because the effects need the real frame size and format,
    			// which are only known once a frame has been decoded.
    			if (!frameProcessorsInitialised) {
                    initialiseFrameProcessors(new InfoVideo(
                            mediaName(videoFile),
                            totalVideoFrames,
                            frame.imageWidth,
                            frame.imageHeight,
                            frame.imageDepth,
                            frame.imageChannels,
                            frame.imageStride,
                            frameRate,
                            intFrameRate,
                            videoFrameDurationUs,
                            grabber.getPixelFormat()));
                    frameProcessorsInitialised = true;
                }
    			
    			// The clock anchors itself on the first frame after a start/seek and times every later frame from it.
    			// This is done here, in decode order, and not on the workers, because workers finish in any order
    			// and a frame's due time must not depend on how fast its effects happened to run.
    			PlaybackClock.FrameTiming timing = clock.schedule(timestampUs, pauseRequested, System.nanoTime());
                final long logicalTimestampUs = timing.logicalTimestampUs();
                final long targetTimeNs = timing.targetTimeNs();
                
                int frameNumber = grabber.getFrameNumber();
                InfoFrame info = new InfoFrame(frameNumber, timestampUs);
                Frame copy = copyIntoPooledFrame(frame);
    			
                // This runs on an effects worker, later. (The pipeline refuses to start it if the run is stale.)
                return () -> {
                    Image image;
                    try {
                        processFrame(copy, info);
                        // Each worker thread is modified to use their own converter instead
                        // of creating a new one for every frame.
                        image = WORKER_CONVERTER.get().convert(copy);
                    } finally {
                        // In a finally so the frame goes back to the pool even if an effect throws, or the
                        // pool would slowly run dry.
                        framePool.offer(copy);   // exactly once, after convert has finished
                    }
 
                    return new PreparedFrame(image, frameNumber, timestampUs, logicalTimestampUs,
                            targetTimeNs, System.nanoTime());
                };
    		}
    		
    		return null; // stopped while reading
    	}
    }
    
    // Copies a decoded frame into a spare frame from the pool. The copy is needed because the grabber reuses its
    // own buffer for the next grab, while a worker is still reading this one. A frame is only created when the
    // pool is empty or the video's size or format has changed (for example after opening a different file).
    private Frame copyIntoPooledFrame(Frame src) {
        Frame dst = framePool.poll();
        if (dst == null
                || dst.imageWidth != src.imageWidth || dst.imageHeight != src.imageHeight
                || dst.imageDepth != src.imageDepth || dst.imageChannels != src.imageChannels
                || dst.imageStride != src.imageStride) {
            dst = new Frame(src.imageWidth, src.imageHeight, src.imageDepth,
                    src.imageChannels, src.imageStride);
        }
        // duplicate() gives a view with its own position, so the grabber's buffer is left exactly as it was.
        ByteBuffer to = (ByteBuffer) dst.image[0];
        ByteBuffer from = ((ByteBuffer) src.image[0]).duplicate();
        to.clear();
        from.clear();
        to.put(from);
        to.clear();
        return dst;
    }
 
    /**
     * A finished frame on its way from a worker to the screen.
     * {@code mediaTimestampUs} is the timestamp stored in the file; {@code logicalTimestampUs} is the position on
     * the player's own timeline (these differ after a seek). {@code targetTimeNs} is when the frame is due and
     * {@code preparedAtNs} is when the worker finished it, so the difference is the headroom that is logged.
     */
    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }
 
    // A chunk of decoded sound waiting for its turn. offset tracks how much has already been written, because
    // the device may accept only part of a chunk at a time.
    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;
 
        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }
 
        long timestampUs() {
            return timestampUs;
        }
 
        byte[] samples() {
            return samples;
        }
 
        int offset() {
            return offset;
        }
 
        int remaining() {
            return samples.length - offset;
        }
 
        void advance(int byteCount) {
            offset += byteCount;
        }
 
        boolean finished() {
            return offset >= samples.length;
        }
    }
}