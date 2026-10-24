/**
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
 */
 
package comp3011;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
 
import org.junit.jupiter.api.Test;
 
/**
 * Tests for {@link FramePipeline}, the part of the player that moves frames between the decoder thread, the worker
 * threads that apply effects, and the consumer that displays them.
 *
 * <p>
 * <b>Why these tests and not others.</b> The pipeline is where the multithreaded design replaced the old single
 * threaded one. Each
 * of those is a silent bug (the video just looks wrong) and none of them is visible from the effect code itself, so
 * each gets one test. The tests deliberately stay at the pipeline's public behaviour and do not test its private
 * queues or locks: a test tied to those would break whenever the internals were reworked even though playback was
 * still correct.
 * </p>
 *
 * <p>
 * <b>Why the real decoder and real effects are not used.</b> A real decoder needs media files and its timing varies
 * between machines. The tests use a {@link FakeSource} instead, which produces numbered frames, so every assertion
 * can name the exact frames expected ("0, 1, 2, 3, 4") rather than checking that "something" was shown. Real effects
 * are replaced by jobs that sleep or wait on a latch, which gives the tests control over when each frame finishes.
 * </p>
 *
 * <p>
 * <b>Why the error handler is ignored.</b> None of these tests is about failure reporting, so a no-op handler keeps
 * each test focused on the one rule it names, and a failure here cannot be caused by the error path.
 * </p>
 */
public class FramePipelineTest {
	private static final FramePipeline.ErrorHandler IGNORE_ERRORS = (failure, stillCurrent) -> { };
	
	/**
	 *  A numbered frame source. {@code work} turns a frame number into the job a worker will run.
	 *
	 *  <p>
	 *  Frames are plain integers because the tests only care about <em>which</em> frame came out and <em>when</em>,
	 *  not about pixels. A mocking library was not used: a small hand-written fake is easier to read, records the
	 *  order of events (see {@code events}) which a mock would need extra set-up to do, and adds no dependency.
	 *  </p>
	 */
	private static final class FakeSource implements FramePipeline.FrameSource<Integer> {
		private final int total;
		private final IntFunction<Callable<Integer>> work;
        final AtomicInteger reads = new AtomicInteger();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        
        // No synchronisation is needed here because the pipeline promises that only its decoder thread ever calls
        // readNext() and seek(). Keeping this field plain also means the fake behaves like a real decoder, which
        // is not thread-safe either; if the pipeline ever called it from two threads the frame numbers would go
        // wrong and the tests would fail.
        private int next; // only touched on the decoder thread
        
        FakeSource(int total, IntFunction<Callable<Integer>> work) {
        	this.total = total;
            this.work = work;
        }
        
        @Override
        public Callable<Integer> readNext(BooleanSupplier cancelled) {
        	if (next >= total) {
        		return null;
        	}
        	int number = next++;
        	events.add("read " + number); // recorded before the counter moves, so a test that waits on the
        	reads.incrementAndGet();      // counter can rely on the event already being there
        	return work.apply(number);
        }
        
        @Override
        public void seek(long timestampUs) {
        	// The thread name is recorded so a test can prove WHICH thread ran the seek, not just that it ran.
        	events.add("seek " + timestampUs + " on " + Thread.currentThread().getName());
        }
	}
	
	/**
	 * Pulls every frame out of the pipeline the way the real consumer does: by repeatedly asking for decoding and
	 * polling for a finished frame. Polling is used (instead of a blocking take) because {@code pollReady()} is how
	 * the display side reads frames without ever stalling the UI thread, so the tests exercise that same path.
	 * The deadline turns a pipeline that never finishes into a clear test failure instead of a build that hangs.
	 */
	private static List<Integer> drain(FramePipeline<Integer> pipeline) throws Exception {
		List<Integer> results = new ArrayList<>();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!pipeline.isDrained()) {
			assertTrue(System.nanoTime() < deadline, "timed out waiting for the pipeline to drain");
			
			pipeline.requestDecoding();
			Integer result = pipeline.pollReady();
			if (result != null) {
				results.add(result);
			} else {
				Thread.sleep(1);
			}
		}
		return results;
	}
	
	/**
	 * Waits until something that happens on another thread has happened. Waiting for a condition (with a deadline)
	 * was chosen over one fixed {@code Thread.sleep}: a fixed sleep must be long enough for the slowest machine, so
	 * it makes every run slow, and it is still wrong on a machine slower than expected. Here a fast machine moves on
	 * after a millisecond, and a slow one gets up to 10 seconds before failing.
	 */
	private static void await(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
			Thread.sleep(1);
		}
	}
	
	/**
	 * Makes earlier frames take longer to process to test whether frames are displayed in the correct order.
	 *
	 * <p>
	 * Needed because with several workers the frame that finishes first is not the frame that was decoded first,
	 * and showing frames in finish order would make the video jump backwards. Plain in-order work (every frame
	 * taking the same time) would pass even for a broken pipeline, since frames would finish in order by luck. The
	 * delay {@code (7 - n % 8) * 3} makes the first frame of every group of eight the slowest and the last the
	 * quickest, so the order of completion is close to the reverse of the order of decoding. There are 40 frames
	 * and 4 workers so that reordering has to happen many times, not once. The sleeps are short (at most 21ms) to
	 * keep the test quick; they only need to be different from each other, not long.
	 * </p>
	 */
	@Test
	void framesComeOutInDecodeOrderEvenWhenWorkersFinishOutOfOrder() throws Exception {
		FramePipeline<Integer> pipeline = new FramePipeline<>(4, IGNORE_ERRORS);
		try {
			List<Integer> expected = new ArrayList<>();
			
			for (int n = 0; n < 40; n++) {
				expected.add(n);
			}
			pipeline.begin(new FakeSource(40, n -> () -> {
				Thread.sleep((7 - n % 8) * 3L);
				return n;
			}));
			
			assertEquals(expected, drain(pipeline));
		} finally {
			// Every test closes its pipeline in a finally block so a failing assertion cannot leave worker
			// threads running and slow down or interfere with the tests that follow.
			pipeline.close();
		}
	}
 
	/**
	 * Test that the decoder stops reading frames when the pipeline reaches its capacity and resumes when the consumer
	 * takes a frame. This prevents the decoder from getting too far ahead, which could waste memory by storing too
	 * many frames at once.
	 *
	 * <p>
	 * Needed because without a limit the decoder is much faster than playback and would decode the whole file into
	 * memory. The test gives it 100 frames and a limit of 2 and checks it stops at exactly 2, then that taking one
	 * frame lets exactly one more through (not zero, which would stall playback, and not many, which would mean
	 * the limit is not enforced). The checks use equality rather than "at most" so that a decoder that stops too
	 * early also fails.
	 * </p>
	 *
	 * <p>
	 * The two {@code Thread.sleep} calls are a deliberate exception to the "wait for a condition" approach used
	 * elsewhere. These steps assert that something does <em>not</em> happen, and there is no event to wait for when
	 * nothing happens, so the test can only give the decoder time to misbehave and then check that it did not. A
	 * pass therefore means "did not overrun in that time" rather than a proof, which is why the first loop gives it
	 * many chances and the second wait is a full 100ms.
	 * </p>
	 */
	@Test
	void decoderNeverRunsMoreThanTheLimitAheadOfTheConsumer() throws Exception {
		FramePipeline<Integer> pipeline = new FramePipeline<>(2, IGNORE_ERRORS);
		try {
			FakeSource source = new FakeSource(100, n -> () -> n);
			pipeline.begin(source);
			
			// Nobody takes any frames: the decoder must stop at the limit instead of decoding the whole file.
			for (int i = 0; i < 100; i++) {
				pipeline.requestDecoding();
                Thread.sleep(3);
			}
			assertEquals(pipeline.maxFramesInFlight(), source.reads.get());
			
			// Taking one frame frees exactly one place.
			Integer first = null;
            while (first == null) {
                first = pipeline.pollReady();
            }
            pipeline.requestDecoding();
            await(() -> source.reads.get() == pipeline.maxFramesInFlight() + 1, "one more frame to be decoded");
            Thread.sleep(100);
            assertEquals(pipeline.maxFramesInFlight() + 1, source.reads.get());
		} finally {
			pipeline.close();
		}
	}
	
	/**
	 * Test that flushing the pipeline discards all old frames still being processed and prevents them from appearing
	 * after a new source starts. The new frames must still be delivered in the correct order. This prevents outdated frames
	 * from appearing after a seek or source change.
	 *
	 * <p>
	 * Needed because this is the hardest case for a multithreaded design: a worker can still be running on a frame
	 * from before the seek when the new frames start arriving. The old frames are made numerically different
	 * ({@code 1000 + n}) so that if one leaks into the output it shows up as an obviously wrong value instead of
	 * hiding among the expected 0 to 4. A {@link CountDownLatch} holds the old workers inside their job until the
	 * flush has happened, which makes the "finished too late" timing certain rather than dependent on luck, and
	 * is why the test does not rely on sleeping. The pipeline is checked to be full first, so we know there really
	 * are frames in flight to discard.
	 * </p>
	 */
	@Test
	void flushDiscardsEverythingInFlightAndOldWorkNeverSurfaces() throws Exception {
		FramePipeline<Integer> pipeline = new FramePipeline<>(2, IGNORE_ERRORS);
		try {
			CountDownLatch release = new CountDownLatch(1);
            FakeSource old = new FakeSource(100, n -> () -> {
                release.await(); // hold the worker so this frame is still "in flight" during the flush
                return 1000 + n;
            });
            pipeline.begin(old);
            pipeline.requestDecoding();
            await(() -> old.reads.get() == pipeline.maxFramesInFlight(), "the old frames to be in flight");
            
            pipeline.flush();
            pipeline.begin(new FakeSource(5, n -> () -> n));
            release.countDown(); // the old frames now finish, too late to matter
            
            assertEquals(List.of(0, 1, 2, 3, 4), drain(pipeline));
		} finally {
			pipeline.close();
		}
	}
	
	/**
	 * Test that the pipeline is only considered drained when the source has finished decoding and all frames have been
	 * taken by the consumer. This prevents playback from being marked as finished while decoded frames are still waiting to
	 * be displayed.
	 *
	 * <p>
	 * Needed because "the decoder has reached the end of the file" and "playback is over" are different moments
	 * once frames are buffered: the last frames are still queued and have not been shown. Declaring the end early
	 * would cut off the final moments of every video. The test gets the pipeline into exactly the dangerous state
	 * (all three frames decoded, none taken) before asserting {@code isDrained()} is false, then checks it only
	 * becomes true after the last frame is taken. The 100ms pause gives the decoder time to notice the end of the
	 * stream; without it a pipeline that wrongly reports drained as soon as the source is exhausted could still
	 * pass, because it might not have noticed yet.
	 * </p>
	 */
	@Test
	void streamIsOnlyDrainedOnceEveryFrameHasBeenTaken() throws Exception {
		FramePipeline<Integer> pipeline = new FramePipeline<>(2, IGNORE_ERRORS);
		try {
			FakeSource source = new FakeSource(3, n -> () -> n);
            pipeline.begin(source);
            pipeline.requestDecoding();
            await(() -> source.reads.get() == 3, "all three frames to be decoded");
            Thread.sleep(100); // long enough for the decoder to notice the end of the stream
            
            assertFalse(pipeline.isDrained(), "frames are decoded but none has been shown yet");
            List<Integer> taken = new ArrayList<>();
            while (taken.size() < 3) {
            	Integer frame = pipeline.pollReady();
            	if (frame != null) {
            		taken.add(frame);
            	}
            }
            assertEquals(List.of(0, 1, 2), taken);
            assertTrue(pipeline.isDrained());
		} finally {
			pipeline.close();
		}
	}
	
	/**
	 * Test that a seek is performed on the decoder thread before any frames are read. This ensures the decoder starts reading
	 * from the requested position instead of accidentally decoding frames from the beginning first.
	 *
	 * <p>
	 * Needed for two reasons. The decoder is not thread-safe, so a seek issued from the UI thread while the decoder
	 * thread is reading would corrupt it; the seek must be handed over and run on the decoder thread itself, which
	 * is why the test checks the thread name and not just that a seek happened. And the seek must come before the
	 * first read, or the first frame shown would be from the wrong position. Both are checked from the single
	 * ordered {@code events} list, so the test does not depend on how fast either thread runs.
	 * </p>
	 */
	@Test
	void aSeekHappensOnTheDecoderThreadBeforeAnyFrameIsRead() throws Exception {
		FramePipeline<Integer> pipeline = new FramePipeline<>(2, IGNORE_ERRORS);
		try {
			FakeSource source = new FakeSource(3, n -> () -> n);
			pipeline.begin(source);
			pipeline.seek(500_000);
			pipeline.requestDecoding();
			await(() -> source.reads.get() > 0, "the first frame to be read");
			
			String first = source.events.get(0);
			assertTrue(first.startsWith("seek 500000 on decoder-"), "first event was: " + first);
			assertEquals("read 0", source.events.get(1));
		} finally {
			pipeline.close();
		}
	}
	
	/**
	 * Regression test for the old static executors: closing one player must not break another.
	 *
	 * <p>
	 * Needed because the old design shared its thread pools between all players through static fields, so closing
	 * one player shut down the threads that every other player was using. Each pipeline now owns its own threads.
	 * Two pipelines exist at the same time and the first is closed (twice, since closing must be safe to repeat
	 * when a window is closed during shutdown) before the second is used. The second must still deliver every
	 * frame. A single-pipeline test could never detect this, because the bug only exists when two are alive.
	 * </p>
	 */
    @Test
    void closingOnePipelineDoesNotAffectAnother() throws Exception {
        FramePipeline<Integer> first = new FramePipeline<>(2, IGNORE_ERRORS);
        FramePipeline<Integer> second = new FramePipeline<>(2, IGNORE_ERRORS);
        try {
            first.close();
            first.close(); // closing twice is harmless
 
            second.begin(new FakeSource(4, n -> () -> n));
            assertEquals(List.of(0, 1, 2, 3), drain(second));
        } finally {
            second.close();
        }
    }
 
    /**
     * The point of the design: frames whose work takes time are finished several at once. The work here sleeps
     * rather than burning CPU, so the result does not depend on how many cores the test machine has or how busy it
     * is; it checks that the pipeline really overlaps frames. (How the real effects scale with cores is a separate
     * question, measured with the real effects.)
     *
     * <p>
     * Needed because every other test would still pass if the pipeline quietly ran everything on one thread: the
     * output would be correct, just no faster than the old single-threaded design, and the whole point of the
     * change would be lost. This is the only test that checks the benefit rather than the correctness.
     * </p>
     *
     * <p>
     * <b>Why the threshold is "less than half" and not "four times faster".</b> Four workers could ideally be
     * four times faster, but scheduling and startup costs mean a strict ratio would fail randomly on a busy machine.
     * Requiring only a 2x gain still fails clearly if the work is not overlapped (the ratio would then be about 1),
     * and leaves room for noise. The failure message prints both timings so a failure can be diagnosed without
     * re-running. Timing was chosen over inspecting thread counts because it measures what the user actually gets
     * (frames delivered sooner), whatever the internal structure.
     * </p>
     */
    @Test
    void severalWorkersDeliverFramesMuchFasterThanOne() throws Exception {
        long oneWorkerNs = timeToDeliver(1, 100);
        long fourWorkersNs = timeToDeliver(4, 100);
 
        assertTrue(fourWorkersNs * 2 < oneWorkerNs,
                "1 worker took " + oneWorkerNs / 1_000_000 + "ms, 4 workers took " + fourWorkersNs / 1_000_000 + "ms");
    }
 
    /**
     * Measures how long a pipeline with the given number of workers takes to deliver all frames. Each frame sleeps
     * 10ms, so one worker needs about a second for 100 frames and four need about a quarter of that. The clock
     * starts after {@code begin()} so set-up cost is not counted, and the frame count is asserted so a fast result
     * cannot come from silently dropping frames.
     */
    private static long timeToDeliver(int workers, int frames) throws Exception {
        FramePipeline<Integer> pipeline = new FramePipeline<>(workers, IGNORE_ERRORS);
        try {
            pipeline.begin(new FakeSource(frames, n -> () -> {
                Thread.sleep(10);
                return n;
            }));
            long start = System.nanoTime();
            assertEquals(frames, drain(pipeline).size());
            return System.nanoTime() - start;
        } finally {
            pipeline.close();
        }
    }
}