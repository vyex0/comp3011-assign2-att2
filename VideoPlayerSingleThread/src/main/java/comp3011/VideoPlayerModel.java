/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Vanessa Soetjiadi, a1945309
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.List;
import java.util.Queue;
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
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    
    // Identifies the computer's available core, and creates roughly (cores - 1) threads from it.
	private static final int EFFECTS_WORKER_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
	
	/**
	 * Creates a thread factory that names each thread and marks it as a daemon thread (daemon threads
	 * don't keep the JVM alive, so the app can exit when the JavaFX window closes even when the pool
	 * hasn't been shut down).
	 */
	private static ThreadFactory daemonThreads(String name) {
		// Used to keep track of the number of different effect worker threads.
		AtomicInteger count = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
	
	// Creates the effect workers thread pool that is responsible for applying effects to the frames.
	private static final ExecutorService EFFECTS_WORKERS = 
			Executors.newFixedThreadPool(EFFECTS_WORKER_COUNT, daemonThreads("effects-worker"));
	
	// Decoded thread that owns the FFmpeg grabber and is the only one that calls grab() during playback.
	// The graber isn't thread safe, so decoding is confined to this thread.
	private static final ExecutorService DECODER = 
			Executors.newSingleThreadExecutor(daemonThreads("decoder"));
	
	// Provides one converter per worker thread. Each worker reuses its own, so nothing is shared
	// between threads and nothing is allocated per frame.
	private static final ThreadLocal<JavaFXFrameConverter> WORKER_CONVERTER =
			ThreadLocal.withInitial(JavaFXFrameConverter::new);
	
	// How many frames can be decoded but not yet displayed. It is made WORKER_COUNT + 2 so that
	// the wokers are kept busy.
	private static final int MAX_FRAMES_IN_FLIGHT = EFFECTS_WORKER_COUNT + 2;
	
	private static final long PRE_ROLL_NS = 150_000_000L; // delay after a restart so the first frames can be processed
	
	/**
	 * Guards the playback clocks: firstTimestampUs, logicalPlaybackBaseUs, playbackStartNs and pauseStartedNs.
	 * These fields are written by the decoder thread, and the JavaFX thread. Every read or write must happen
	 * inside synchronized (clockLock) so no thread ever sees a  half-updated clock.
	 */
	private final Object clockLock = new Object();
	
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
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    // private boolean prepareNextFrameQueued; // True only when the event loop needs to call our prepareNextFrame method.
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
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    
    private volatile boolean endOfStreamReached; // grabber returned null, but queued frames may still need showing
    private boolean displayWhilePaused; // Lets exactly one frame appear after a seek while paused

    /**
     * Bumped every time the decoder must be stopped (e.g., pause, seek, restart) and old work. Each decode 
     * loops and effects task remembers the value it started under and abandon its work if the current
     * generation value has changed. A counter is used instead of a boolean because a stale task may wake up
     * long after the reset: a flag would already be back to valid' by then, whereas an old
     * old number can never match again.
     */
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean decodeScheduled = new AtomicBoolean(); // Marks whether a decoding job is running or not.
    
    // List of PreparedFrames fetched by the readNextVideoFrame(). ConcurrentLinkedQueue is used so several
    // threads can run at the same time.
    private final ConcurrentLinkedQueue<Future<PreparedFrame>> preparedFramesQueue = new ConcurrentLinkedQueue<Future<PreparedFrame>>();
    
    // JavaFX writes the target position (microseconds) here after stopping the decoder, the decoder thread
    // reads it at the start of its next job and performs the slow grabber. Volatile so the decoder
    // always sees the latest write.
    private volatile long pendingGrabSeekUs = NO_SEEK_REQUEST;

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
        	synchronized (clockLock) { pauseStartedNs = System.nanoTime(); }
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
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
        displayWhilePaused = true;
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();	// this uses DECODER, so shut it down after
        DECODER.shutdownNow();
        EFFECTS_WORKERS.shutdownNow();
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

        stopDecoder();                             // the decoder is now idle and its old frames are discarded
        resetPlaybackClock(logicalTimestampUs);
        if (pauseRequested) {
            displayWhilePaused = true;
        }
        flushAudioOutput();
        pendingGrabSeekUs = grabTimestampUs;       // hand the actual seek to the decoder thread
        requestDecoding();
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
            requestDecoding();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        // converter = new JavaFXFrameConverter();
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

        if (startTimestampUs > 0) {
        	pendingGrabSeekUs = startTimestampUs;   // the decoder thread performs it on its first job
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
        synchronized (clockLock) {
            firstTimestampUs = NO_SEEK_REQUEST;
            logicalPlaybackBaseUs = logicalTimestampUs;
            playbackStartNs = 0;
            pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
        }
    }

    // Shifts the playback clock forward by the length of a pause so that playback resumes where it left
    // off instead of jumping ahead. Called on the JavaFX thread when the user resumes.
    private void resumePlaybackClock(long now) {
    	synchronized (clockLock) {
            if (pauseStartedNs > 0 && playbackStartNs > 0) {
                playbackStartNs += now - pauseStartedNs;
            }
            pauseStartedNs = 0;
        }
    }
    
    /**
     * Asks the decoder to decode more frames in the preparedFramesQueue. It is called repeatedly
     * on every heartbeat and returns if the queue is full. The decodeScheduled ensures at most
     * one decode job exists at a time.
     */
    private void requestDecoding() {
    	if (!playbackOpen || endOfStreamReached
    			|| preparedFramesQueue.size() >= MAX_FRAMES_IN_FLIGHT
    			|| !decodeScheduled.compareAndSet(false, true)) {
    		return;
    	}
    	int gen = generation.get();
    	DECODER.execute(() -> decodeLoop(gen));
    }
    
    /**
     * Runs on the decoder thread. Reads frames and hands each one to an effects worker until the
     * queue is full, the stream ends, or this job becomes stale.
     */
    private void decodeLoop(int gen) {
    	try {
    		long seekUs = pendingGrabSeekUs;
    		if (seekUs != NO_SEEK_REQUEST && gen == generation.get()) {
    	        pendingGrabSeekUs = NO_SEEK_REQUEST;
    	        grabber.setTimestamp(seekUs);
    	    }
    		while (gen == generation.get() && playbackOpen && !endOfStreamReached
                    && preparedFramesQueue.size() < MAX_FRAMES_IN_FLIGHT) {
    			// Grabs one frame, submits it to the worker, and queues the future, so the decoder
    			// can move on while the worker is still processing.
    			Future<PreparedFrame> next = readNextVideoFrame(gen);
    			if (next == null) {
    				break;
    			}
    			preparedFramesQueue.offer(next);
    		}
    	} catch (Exception e) {
    		Platform.runLater(() -> {
                if (gen == generation.get()) {
                    handlePlaybackError(e);
                }
            });
    	} finally {
    		decodeScheduled.set(false);
    	}
    }
    
    /**
     * Stops the decoder, waits until it's idle, and discards every queued frame. Only called from the JavaFX thread.
     */
    private void stopDecoder() {
    	generation.incrementAndGet(); // 1. decoder loop and queued effects tasks now see they're stale
    	try {
    		DECODER.submit(() -> { }).get(); // 2. runs after any in-progress decode, so it is a barrier
    	} catch (InterruptedException e) {
    		Thread.currentThread().interrupt();
    	} catch (ExecutionException | RejectedExecutionException e) {
    		// Executor already shut down; nothing left to wait for.
    	}
    	clearPreparedFrames(); // 3. now nothing can add to the queue behind our back
    }
    
    private void clearPreparedFrames() {
    	for (Future<PreparedFrame> frames : preparedFramesQueue) {
    		frames.cancel(true);
    	}
    	
    	preparedFramesQueue.clear();
    	preparedFrame = null;
    	endOfStreamReached = false;
    }

    // Modified to grab one picture frame and hand the effect work to a worker.
    private Future<PreparedFrame> readNextVideoFrame(int gen) throws Exception {
        while (playbackOpen && gen == generation.get()) {
            Frame frame = grabFrame();
            if (frame == null) {
            	endOfStreamReached = true; // don't finish yet, some frames might still in flight
            	return null;
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


            final long logicalTimestampUs;
            final long targetTimeNs;
            synchronized (clockLock) {
            	if (firstTimestampUs == NO_SEEK_REQUEST) {
                    firstTimestampUs = timestampUs;
                    playbackStartNs = System.nanoTime() + PRE_ROLL_NS;
                    if (pauseRequested) {
                    	pauseStartedNs = System.nanoTime();
                    }
                }
                long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
                logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
                targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
            }
            
            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            Frame copy = frame.clone();
            
            return EFFECTS_WORKERS.submit(() -> {
            	if (gen != generation.get()) {
            		throw new CancellationException("stale frame from before a seek/stop");
            	}
            	processFrame(copy, info);
            	// Each worker thread is modified to use their own converter instead 
            	// of creating a new one for every frame.
            	Image image = WORKER_CONVERTER.get().convert(copy);
        		return new PreparedFrame(image, frameNumber, timestampUs, 
        				logicalTimestampUs, targetTimeNs, System.nanoTime());
            });
        }
        
        return null;
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
                Future<PreparedFrame> head = preparedFramesQueue.peek();
                if (head != null && head.isDone()) {
                    preparedFramesQueue.poll();
                    try {
                        preparedFrame = head.get();
                    } catch (Exception e) {
                        handlePlaybackError(e);
                        return;
                    }
                }
            }
            if (preparedFrame != null && dueTimeNs(preparedFrame.mediaTimestampUs()) <= now) {
                displayPreparedFrame(now);
            }
        }
        
        // Everything grabbed and shown: now it's safe to finish.
        if (endOfStreamReached && preparedFrame == null && preparedFramesQueue.isEmpty()) {
            finishPlayback(); return;
        }

        // Task (3): top up the frame queue. Does nothing if the queue is full or a decode job is already running.
        if (!pauseRequested) {
        	requestDecoding();
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
     * Called on the decoder thread. Copies the audio from a decoded frame and adds
     * it to pendingAudio for the JavaFX thread to play.
     *
     * Deliberately does NOT check audioOutputEnabled. Audio is always queued, and
     * writeDueAudio() decides whether to play or discard each chunk when it comes
     * due. That keeps this method free of mute logic, and means unmuting resumes
     * sound immediately from the correct position, because the upcoming chunks are
     * already queued.
     */

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
    	// Snapshot the clock under the lock so both values come from the same consistent state.
    	long first, start;
    	synchronized (clockLock) { first = firstTimestampUs; start = playbackStartNs; }
    	
    	// No audio device: nothing can ever be played, so drop whatever is queued.
        if (audioPlayer == null) {
            pendingAudio.clear(); return;
        }
        
        // Nothing is due while paused or before the first frame has set the clock anchor.
        if (pauseRequested || first == NO_SEEK_REQUEST || start <= 0) {
    	    return;
    	}
        
        // Timestamp to which audio should have been played by now, including small lead
        // so audio device never runs dry.
        long dueTimestampUs = first + (now + AUDIO_LEAD_NS - start) / 1_000L;
        
        
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
        stopDecoder();		  // 2. wait until it's idle. MUST happen before the grabber is closed
        pendingGrabSeekUs = NO_SEEK_REQUEST;
        pendingAudio.clear();
        clearPlaybackClock();
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

//        if (converter != null) {
//            converter.close();
//            converter = null;
//        }
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
    
    /**
     * Returns a wall-clock time at which the frame with the given media timsestamp is due
     * on scree: the anchor's start time plus the frame's offset from the anchor. Reads the clock
     * under clockLock so the two fields are always from the same consistent state.
     */
    private long dueTimeNs(long mediaTimestampUs) {
        synchronized (clockLock) {
            long relativeUs = Math.max(0, mediaTimestampUs - firstTimestampUs);
            return playbackStartNs + relativeUs * 1_000L;
        }
    }
    
    /**
     * Clears the playback clock when playback is closed, so a later restart doesn't inherit
     * stale timing. Fields are cleared together under clockLock so the decoder never sees a
     * partial reset.
     */
    private void clearPlaybackClock() {
        synchronized (clockLock) {
            firstTimestampUs = NO_SEEK_REQUEST;
            playbackStartNs = 0;
            pauseStartedNs = 0;
        }
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
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
