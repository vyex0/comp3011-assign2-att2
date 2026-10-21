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
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * on several worker threads at once. Everything in this class runs on the JavaFX thread except {@link GrabberSource},
 * which runs on the decoder thread. It publishes decoded frames and playback state through callbacks supplied by its
 * {@link VideoPlayerController}, keeping it decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    
	// Provides one converter per worker thread. Each worker reuses its own, so nothing is shared
	// between threads and nothing is allocated per frame.
	private static final ThreadLocal<JavaFXFrameConverter> WORKER_CONVERTER =
			ThreadLocal.withInitial(JavaFXFrameConverter::new);
	
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
	
	private volatile List<FrameProcessor> effectChain = List.of();
	
	
    private final List<FrameProcessor> frameProcessors;
    // Modified to use ConcurrentLinkedQueue because ArrayDeque is not thread safe for concurrent
    // add/poll of elements. Decoder thread adds audio, GUI thread drains it.
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
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.

    // Made to be volatile to guarantee that decoder sees change immediately instead of using stale cached value. 
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
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

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
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        // preparedFrame = null;
        displayWhilePaused = true;
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources(); // this uses DECODER, so shut it down after
        pipeline.close(); // releases this player's threads only
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

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
        	pipeline.seek(startTimestampUs);   // the decoder thread performs it on its first job
        }
    }

    // Resets the playback clock so the next decoded frame becomes the new time-zero anchor at the given
    // position. Called on a seek (and when playback is restarted). Runs in the JavaFX thread, after 
    // stopDecoder, so the decoder is idle, and no old frames are still being produced.
    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        clock.reset(logicalTimestampUs, pauseRequested, System.nanoTime());
    }
    
    // Called by the pipeline, on the decoder thread, when reading or decoding fails.
    private void onDecodeFailure(Exception e, BooleanSupplier stillCurrent) {
    	// Handle it on the JavaFX thread. By the time that runs the user may have sought away from the run that
    	// failed, in which case the error is about frames nobody will see and is ignored.
    	Platform.runLater(() -> {
    		if (stillCurrent.getAsBoolean()) {
    			handlePlaybackError(e);
    		}
    	});
    }

    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1)
        writeDueAudio(now);

        // Task (2): show the next frame if it's due. The due time is computed from the live clock,
        // so a pause/resume shifts every queued frame.
        if (!pauseRequested || displayWhilePaused) {
            if (preparedFrame == null) {
            	try {
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
        
        if (pipeline.isDrained() && preparedFrame == null) {
            finishPlayback(); return;
        }

        // Task (3): top up the frame queue. Does nothing if the queue is full or a decode job is already running.
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
     * audioOutputEnabled check is removed so that audio is ALWAYS queued, and let 
     * writeDueAudio() decides whether to play or discard each chunk when it comes due.
     * Which means unmuting resumes sound immediately from the correct position.
     */
    private void queueAudio(long timestampUs, Frame frame) {
        if (audioPlayer == null) {
            return;
        }

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
        
        // Nothing is due while paused
        if (pauseRequested) {
        	return;
        }
        
        // Timestamp to which audio should have been played by now, including small lead so audio never
        // runs dry. The clock answers from one consistent snapshot, and is empty until the first frame 
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
            
            // When muted: discards each chunk as it comes due, this keeps the queue current
            // and keeps position correct when the user unmutes.
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
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

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

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false; // 1. tell the decoder loop to stop
        pipeline.end(); // 2. wait until the decoder is idle. MUST happen before the grabber is closed
        pendingAudio.clear();
        clock.clear();
        audioAvailable = false;
        frameProcessorsInitialised = false;
        
        if (audioPlayer != null) { audioPlayer.close(); audioPlayer = null; }

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
    	
    	// Modified to grab one picture frame and hand the effect work to a worker.
    	@Override
    	public Callable<PreparedFrame> readNext(BooleanSupplier cancelled) throws Exception {
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
    			PlaybackClock.FrameTiming timing = clock.schedule(timestampUs, pauseRequested, System.nanoTime());
                final long logicalTimestampUs = timing.logicalTimestampUs();
                final long targetTimeNs = timing.targetTimeNs();
                
                int frameNumber = grabber.getFrameNumber();
                InfoFrame info = new InfoFrame(frameNumber, timestampUs);
                Frame copy = frame.clone();
                
                // This runs on an effects worker, later. (The pipeline refuses to start it if the run is stale.)
                return () -> {
                	processFrame(copy, info);
                	// Each worker thread is modified to use their own converter instead
                    // of creating a new one for every frame.
                	Image image = WORKER_CONVERTER.get().convert(copy);
                	return new PreparedFrame(image, frameNumber, timestampUs,logicalTimestampUs, targetTimeNs, System.nanoTime());
                };
    		}
    		
    		return null; // stopped while reading
    	}
    }
    
    // === Fusing effects passes === //
    private static final boolean FUSE_EFFECTS = !Boolean.getBoolean("videoplayer.noFusion");
    
    static final class FusedPass extends FrameProcessor {
        private interface Step { }
        private record LutStep(byte[] blue, byte[] green, byte[] red) implements Step { }
        private record FlickerStep(int mediaSeed) implements Step { }
        private record GrayStep() implements Step { }
        private record VignetteStep(byte[][] xLookups, byte[][] yLookups) implements Step { }

        private static final int LUT = 0, VIGNETTE = 1, GRAY = 2;

        private record Program(int count, int shape, int[] kind, byte[][] blue, byte[][] green, byte[][] red,
                byte[][][] xLookups, byte[][][] yLookups) { }

        private static final int GENERAL = 0, TABLES_GRAY_TABLES_VIGNETTE = 1, VIGNETTE_TABLES_GRAY_TABLES = 2;

        private final List<FrameProcessor> originals;
        private final List<Step> steps;
        private final double flickerPercentageSd;

        private FusedPass(List<FrameProcessor> run, InfoVideo info) throws ReflectiveOperationException {
            this.originals = run;
            this.videoInfo = info;
            this.flickerPercentageSd = (double) readStatic(FrameFlickerer.class, "FLICKER_PERCENTAGE_SD");
            List<Step> built = new ArrayList<>();
            for (FrameProcessor effect : run) {
                Class<?> type = effect.getClass();
                if (type == FrameFlickerer.class) {
                    built.add(new FlickerStep((int) read(effect, "mediaSeed")));
                } else if (type == FrameBlackAndWhiter.class) {
                    built.add(new GrayStep());
                } else if (type == FrameYellower.class) {
                    built.add(new LutStep((byte[]) read(effect, "blueLookup"), (byte[]) read(effect, "greenLookup"),
                            (byte[]) read(effect, "redLookup")));
                } else {
                    built.add(new VignetteStep((byte[][]) read(effect, "xLookups"), (byte[][]) read(effect, "yLookups")));
                }
            }
            this.steps = List.copyOf(built);
        }

        static List<FrameProcessor> fuse(List<FrameProcessor> chain, InfoVideo info) {
            if (info.imageChannels() != 3
                    || (info.imageDepth() != Frame.DEPTH_UBYTE && info.imageDepth() != Frame.DEPTH_BYTE)) {
                return List.copyOf(chain);
            }

            List<FrameProcessor> result = new ArrayList<>();
            List<FrameProcessor> run = new ArrayList<>();
            try {
                for (FrameProcessor effect : chain) {
                    if (isFusable(effect)) {
                        run.add(effect);
                    } else {
                        endRun(run, result, info);
                        result.add(effect);
                    }
                }
                endRun(run, result, info);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.out.println("Effect fusion unavailable (" + e + "), running the effects as separate passes.");
                return List.copyOf(chain);
            }

            if (result.size() < chain.size()) {
                System.out.println("Effects: " + chain.size() + " run as " + result.size() + " passes (neighbouring "
                        + "per-pixel effects fused).");
            }
            return List.copyOf(result);
        }

        private static void endRun(List<FrameProcessor> run, List<FrameProcessor> result, InfoVideo info)
                throws ReflectiveOperationException {
            if (run.size() >= 2) {
                result.add(new FusedPass(List.copyOf(run), info));
            } else {
                result.addAll(run);
            }
            run.clear();
        }

        private static boolean isFusable(FrameProcessor effect) {
            Class<?> type = effect.getClass();
            return type == FrameFlickerer.class || type == FrameBlackAndWhiter.class
                    || type == FrameYellower.class || type == FrameVignetter.class;
        }

        @Override
        public void process(Frame frame, InfoFrame info) throws Exception {
            if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) {
                for (FrameProcessor effect : originals) {
                    effect.process(frame, info);
                }
                return;
            }

            Program program = resolve(info.frameNumber());
            int width = videoInfo.imageWidth();
            int height = videoInfo.imageHeight();
            int stride = videoInfo.imageStride();
            int widthBytes = width * 3;
            int count = program.count();
            byte[] row = new byte[widthBytes];

            for (int y = 0; y < height; y++) {
                int rowStart = y * stride;
                pixels.get(rowStart, row, 0, widthBytes);

                switch (program.shape()) {
                    case TABLES_GRAY_TABLES_VIGNETTE -> tablesGrayTablesVignette(row, widthBytes, program,
                            program.yLookups()[3][y]);
                    case VIGNETTE_TABLES_GRAY_TABLES -> vignetteTablesGrayTables(row, widthBytes, program,
                            program.yLookups()[0][y]);
                    default -> {
                        for (int k = 0; k < count; k++) {
                            switch (program.kind()[k]) {
                                case LUT -> applyTables(row, widthBytes, program.blue()[k], program.green()[k],
                                        program.red()[k]);
                                case VIGNETTE -> applyVignette(row, widthBytes, program.xLookups()[k],
                                        program.yLookups()[k][y]);
                                default -> applyGray(row, widthBytes);
                            }
                        }
                    }
                }

                pixels.put(rowStart, row, 0, widthBytes);
            }
        }

        private static void tablesGrayTablesVignette(byte[] row, int length, Program p, byte[] yLookup) {
            byte[] firstBlue = p.blue()[0], firstGreen = p.green()[0], firstRed = p.red()[0];
            byte[] lastBlue = p.blue()[2], lastGreen = p.green()[2], lastRed = p.red()[2];
            byte[][] xLookups = p.xLookups()[3];
            int x = 0;
            for (int i = 0; i < length; i += 3, x++) {
                int b = firstBlue[row[i] & 0xff] & 0xff;
                int g = firstGreen[row[i + 1] & 0xff] & 0xff;
                int r = firstRed[row[i + 2] & 0xff] & 0xff;
                int luminance = (54 * r + 183 * g + 19 * b) / 256;
                byte[] xLookup = xLookups[x];
                row[i] = yLookup[xLookup[lastBlue[luminance] & 0xff] & 0xff];
                row[i + 1] = yLookup[xLookup[lastGreen[luminance] & 0xff] & 0xff];
                row[i + 2] = yLookup[xLookup[lastRed[luminance] & 0xff] & 0xff];
            }
        }

        private static void vignetteTablesGrayTables(byte[] row, int length, Program p, byte[] yLookup) {
            byte[][] xLookups = p.xLookups()[0];
            byte[] midBlue = p.blue()[1], midGreen = p.green()[1], midRed = p.red()[1];
            byte[] lastBlue = p.blue()[3], lastGreen = p.green()[3], lastRed = p.red()[3];
            int x = 0;
            for (int i = 0; i < length; i += 3, x++) {
                byte[] xLookup = xLookups[x];
                int b = midBlue[yLookup[xLookup[row[i] & 0xff] & 0xff] & 0xff] & 0xff;
                int g = midGreen[yLookup[xLookup[row[i + 1] & 0xff] & 0xff] & 0xff] & 0xff;
                int r = midRed[yLookup[xLookup[row[i + 2] & 0xff] & 0xff] & 0xff] & 0xff;
                int luminance = (54 * r + 183 * g + 19 * b) / 256;
                row[i] = lastBlue[luminance];
                row[i + 1] = lastGreen[luminance];
                row[i + 2] = lastRed[luminance];
            }
        }

        private static void applyTables(byte[] row, int length, byte[] blue, byte[] green, byte[] red) {
            for (int i = 0; i < length; i += 3) {
                row[i] = blue[row[i] & 0xff];
                row[i + 1] = green[row[i + 1] & 0xff];
                row[i + 2] = red[row[i + 2] & 0xff];
            }
        }

        private static void applyGray(byte[] row, int length) {
            for (int i = 0; i < length; i += 3) {
                int luminance = (54 * (row[i + 2] & 0xff) + 183 * (row[i + 1] & 0xff) + 19 * (row[i] & 0xff)) / 256;
                byte grey = (byte) luminance;
                row[i] = grey;
                row[i + 1] = grey;
                row[i + 2] = grey;
            }
        }

        private static void applyVignette(byte[] row, int length, byte[][] xLookups, byte[] yLookup) {
            int x = 0;
            for (int i = 0; i < length; i += 3, x++) {
                byte[] xLookup = xLookups[x];
                row[i] = yLookup[xLookup[row[i] & 0xff] & 0xff];
                row[i + 1] = yLookup[xLookup[row[i + 1] & 0xff] & 0xff];
                row[i + 2] = yLookup[xLookup[row[i + 2] & 0xff] & 0xff];
            }
        }

        private Program resolve(int frameNumber) {
            int capacity = steps.size();
            int[] kind = new int[capacity];
            byte[][] blue = new byte[capacity][];
            byte[][] green = new byte[capacity][];
            byte[][] red = new byte[capacity][];
            byte[][][] xLookups = new byte[capacity][][];
            byte[][][] yLookups = new byte[capacity][][];
            int count = 0;
            byte[] pendingBlue = null;
            byte[] pendingGreen = null;
            byte[] pendingRed = null;

            for (Step step : steps) {
                byte[] tableBlue = null;
                byte[] tableGreen = null;
                byte[] tableRed = null;
                if (step instanceof LutStep lut) {
                    tableBlue = lut.blue();
                    tableGreen = lut.green();
                    tableRed = lut.red();
                } else if (step instanceof FlickerStep flicker) {
                    tableBlue = flickerTable(flicker.mediaSeed(), frameNumber);
                    tableGreen = tableBlue;
                    tableRed = tableBlue;
                }

                if (tableBlue != null) {
                    if (pendingBlue == null) {
                        pendingBlue = tableBlue;
                        pendingGreen = tableGreen;
                        pendingRed = tableRed;
                    } else {
                        pendingBlue = compose(pendingBlue, tableBlue);
                        pendingGreen = compose(pendingGreen, tableGreen);
                        pendingRed = compose(pendingRed, tableRed);
                    }
                    continue;
                }

                if (pendingBlue != null) {
                    kind[count] = LUT;
                    blue[count] = pendingBlue;
                    green[count] = pendingGreen;
                    red[count] = pendingRed;
                    count++;
                    pendingBlue = null;
                    pendingGreen = null;
                    pendingRed = null;
                }

                if (step instanceof VignetteStep vignette) {
                    kind[count] = VIGNETTE;
                    xLookups[count] = vignette.xLookups();
                    yLookups[count] = vignette.yLookups();
                } else {
                    kind[count] = GRAY;
                }
                count++;
            }

            if (pendingBlue != null) {
                kind[count] = LUT;
                blue[count] = pendingBlue;
                green[count] = pendingGreen;
                red[count] = pendingRed;
                count++;
            }
            return new Program(count, shapeOf(count, kind), kind, blue, green, red, xLookups, yLookups);
        }

        private static int shapeOf(int count, int[] kind) {
            if (count == 4 && kind[0] == LUT && kind[1] == GRAY && kind[2] == LUT && kind[3] == VIGNETTE) {
                return TABLES_GRAY_TABLES_VIGNETTE;
            }
            if (count == 4 && kind[0] == VIGNETTE && kind[1] == LUT && kind[2] == GRAY && kind[3] == LUT) {
                return VIGNETTE_TABLES_GRAY_TABLES;
            }
            return GENERAL;
        }

        private static byte[] compose(byte[] first, byte[] second) {
            byte[] combined = new byte[256];
            for (int value = 0; value < 256; value++) {
                combined[value] = second[first[value] & 0xff];
            }
            return combined;
        }

        private byte[] flickerTable(int mediaSeed, int frameNumber) {
            Random random = new Random((long) mediaSeed + frameNumber);
            double dimmingPercentage = Math.abs(random.nextGaussian()) * flickerPercentageSd;
            double brightnessScale = Math.max(0.0, 100.0 - dimmingPercentage) / 100.0;
            byte[] table = new byte[256];
            for (int value = 0; value < table.length; value++) {
                table[value] = (byte) Math.max(0, Math.min(255, (int) Math.round(value * brightnessScale)));
            }
            return table;
        }

        private static Object read(Object owner, String fieldName) throws ReflectiveOperationException {
            Field field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(owner);
        }

        private static Object readStatic(Class<?> owner, String fieldName) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        }
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

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
