/**
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
 */

package comp3011;

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
import java.util.function.BooleanSupplier;

/**
 * The player's multi-threaded frame production line: one decoder thread reads frames in order, a pool of effects
 * workers processes several frames at the same time, and the results are handed back, still in order, to the
 * controlling thread (the JavaFX thread in the player).
 */

public class FramePipeline<T> {
	/**
	 * Where frames come from. Implemented by the player around the FFmpeg grabber. Both methods are only ever
	 * called on the decoder thread.
	 */
	 public interface FrameSource<T> {
		 /**
		  * Reads and prepares the next frame, and returns the work to run on an effects worker, or {@code null} when
		  * there is nothing more to return. This is the place for everything that must stay on the decoder thread:
		  * grabbing, copying the frame so the grabber can reuse its buffer, and timing it.
		  * 
		  * @param cancelled true once this run has been stopped; a long read should give up and return {@code null}
		  */
		 Callable<T> readNext(BooleanSupplier cancelled) throws Exception;
		 
		 /** Moves the source to the given media position. */
		 void seek(long timestampUs) throws Exception;
	 }
	 
	 /** Told when the decoder thread fails. Called on the decoder thread. */
	 @FunctionalInterface
	 public interface ErrorHandler {
		 /**
		  * @param stillCurrent becomes false once the run that failed has been stopped, so a late report about a
		  *						run the user has already seeked away from can be ignored
		  */
		 void onDecodeFailure(Exception failure, BooleanSupplier stillCurrent);
	 }
	 
	 private static final long NO_SEEK = -1;
	 
	 private final int workerCount;
	 private final int maxFramesInFlight;
	 private final ErrorHandler errorHandler;
	 
	 // The decoder owns the grabber, so decoding is confined to one thread. The workers apply the effects.
	 private final ExecutorService decoder = Executors.newSingleThreadExecutor(daemonThreads("decoder"));
	 private final ExecutorService workers;
	 
	 private final AtomicInteger generation = new AtomicInteger();
	 private final AtomicBoolean decodeScheduled = new AtomicBoolean(); // true while a decode job is queued or running
	 private final Queue<Future<T>> inFlight = new ConcurrentLinkedQueue<>();
	 
	 private volatile boolean endOfStream; // the source has no more frames (queued frames may still be showing)
	 private volatile long pendingSeekUs = NO_SEEK; // controlling thread writes, decoder reads at the start of a job
	 
	 private boolean open; // controlling thread only
	 private FrameSource<T> source; // controlling thread only; each decode job gets its own reference
	 
	 /**
	  * One worker for every core except two, which are left for the threads outside the pool (the decoder, the
	  * JavaFX UI and render threads, and audio). Always at least one.
	  */
	 public static int defaultWorkerCount() {
		 return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
	 }
	 
	 public FramePipeline(ErrorHandler errorHandler) {
		 this(defaultWorkerCount(), errorHandler);
	 }
	 
	 public FramePipeline(int workerCount, ErrorHandler errorHandler) {
		 if (workerCount < 1) {
			 throw new IllegalArgumentException("workerCount must be at least 1");
		 }
		 
		 this.workerCount = workerCount;
		 
		 // Two more than the workers so every worker always has a frame to work on while a finished frame is
		 // waiting to be shown. More than that only adds memory (each frame is several MB) and latency after a seek.
		 this.maxFramesInFlight = workerCount + 3;
		 this.errorHandler = errorHandler;
		 this.workers = Executors.newFixedThreadPool(workerCount, daemonThreads("effects-worker"));
	 }
	 
	 public int workerCount() {
		 return workerCount;
	 }
	 
	 public int maxFramesInFlight() {
		 return maxFramesInFlight;
	 }
	 
	 /**
	  * Starts a run with a new source. The previous run must have been ended. Nothing is decoded until
	  * {@link #requestDecoding} is called.
	  */
	 public void begin(FrameSource<T> newSource) {
		 source = newSource;
		 endOfStream = false;
		 open = true;
	 }
	 
	 /**
	  * Asks the decoder to move the source to this position before it reads anything else. Only call it while the
	  * decoder is idle, which is straight after {@link #begin} or {@link #flush}, then call {@link #requestDecoding}.
	  * The (slow) seek itself happens on the decoder thread, so the controlling thread is not held up by it.
	  */
	 public void seek(long timestampUs) {
		 pendingSeekUs = timestampUs;
	 }
	 
	 /**
	  * Tops up the in-flight frames. Cheap enough to call on every heartbeat: it does nothing if the queue is full,
	  * the stream has ended, or a decode job is already running ({@code decodeScheduled} guarantees at most one).
	  */
	 public void requestDecoding() {
		 if (!open || endOfStream || inFlight.size() >= maxFramesInFlight
				 || !decodeScheduled.compareAndSet(false, true)) {
			 return;
		 }
		 
		 int gen = generation.get();
		 FrameSource<T> src = source;
		 try {
			 decoder.execute(() -> decodeLoop(gen, src));
		 } catch (RejectedExecutionException e) {
			 decodeScheduled.set(false); // closed
		 }
	 }
	 
	 // Runs on the decoder thread. Reads frames and hands each one to a worker until the queue is full, the stream
	 // ends, or this run becomes stale.
	 private void decodeLoop(int gen, FrameSource<T> src) {
		 BooleanSupplier cancelled = () -> gen != generation.get();
		 try {
			 long seekUs = pendingSeekUs;
			 if (seekUs != NO_SEEK && !cancelled.getAsBoolean()) {
				 pendingSeekUs = NO_SEEK;
				 src.seek(seekUs);
			 }
			 
			 while (!cancelled.getAsBoolean() && !endOfStream && inFlight.size() < maxFramesInFlight) {
				 Callable<T> work = src.readNext(cancelled);
				 if (work == null) {
					 if (!cancelled.getAsBoolean()) {
						 endOfStream = true; // not finished yet: frames already in flight still have to be shown
					 }
					 break;
				 }
				 
				 // The decoder moves straight on to the next frame while the worker is still busy with this one.
				 inFlight.offer(workers.submit(() -> {
					 if (cancelled.getAsBoolean()) {
						 throw new CancellationException("stale frame from before a seek/stop");
					 }
					 return work.call();
				 }));
			 }
		 } catch (Exception e) {
			 errorHandler.onDecodeFailure(e, () -> gen == generation.get());
		 } finally {
			 decodeScheduled.set(false);
		 }
	 }
	 
	 /**
	  * Returns the next finished frame, or {@code null} if the frame at the head of the queue is not finished yet
	  * (or there is none). Frames come out in the order they were decoded. If the frame's effects failed, that
	  * failure is thrown here, when it is that frame's turn.
	  */
	 public T pollReady() throws Exception {
		 Future<T> head = inFlight.peek();
		 if (head == null || !head.isDone()) {
			 return null; 
		 }
		 inFlight.poll();
		 try {
			 return head.get();
		 } catch (ExecutionException e) {
			 Throwable cause = e.getCause();
			 throw cause instanceof Exception ex ? ex : e;
		 }
	 }
	 
	 /** True once the stream has ended and every frame has been taken with {@link #pollReady}. */
	 public boolean isDrained() {
		 return endOfStream && inFlight.isEmpty();
	 }
	 
	 /**
	  * Discards everything in flight and waits until the decoder is idle. Used before a seek, and when closing.
	  */
	 public void flush() {
		 generation.incrementAndGet();
		 awaitDecoderIdle();
		 for (Future<T> future : inFlight) {
			 // false: a worker that is already running a frame cannot be interrupted usefully (effects are plain
			 // loops), so let it finish; its result is dropped, and queued tasks refuse to start (they are stale).
			 future.cancel(false);
		 }
		 inFlight.clear();
		 endOfStream = false;
	 }
	 
	 // Anything queued on the single decoder thread runs in order, so when this empty job completes every earlier
	 // decode job (including one that was already running) has finished: it is a barrier.s
	 private void awaitDecoderIdle() {
		 try {
			 decoder.submit(() -> { }).get();
		 } catch (InterruptedException e) {
			 Thread.currentThread().interrupt();
		 } catch (ExecutionException | RejectedExecutionException e) {
			// The executor is already shut down, so there is nothing left to wait for.
		 }
	 }
	 
	 /** Ends the current run: stops decoding and discards everything. The pipeline can be started again. */
	 public void end() {
		 open = false;
		 flush();
		 pendingSeekUs = NO_SEEK;
		 source = null;
	 }
	 
	 /** Ends the run and releases this pipeline's threads. Does not affect any other pipeline. */
	 public void close() {
        end();
        decoder.shutdownNow();
        workers.shutdownNow();
	 }
	 
	// Names each thread (so it is recognisable in a debugger or thread dump) and marks it as a daemon thread, which
    // does not keep the JVM alive: the application can still exit when the window closes.
    private static ThreadFactory daemonThreads(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
