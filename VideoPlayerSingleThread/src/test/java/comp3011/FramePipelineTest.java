/**
 * New file for Adelaide University COMP3011 Assignment 2.
 * 
 * 1. Vanessa Soetjiadi, a1945309
 * 
 */

package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

public class FramePipelineTest {
	private static final FramePipeline.ErrorHandler IGNORE_ERRORS = (failure, stillCurrent) -> { };
	
	/**
	 *  A numbered frame source. {@code work} turns a frame number into the job a worker will run.
	 */
	private static final class FakeSource implements FramePipeline.FrameSource<Integer> {
		private final int total;
		private final IntFunction<Callable<Integer>> work;
        final AtomicInteger reads = new AtomicInteger();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        
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
        	events.add("seek " + timestampUs + " on " + Thread.currentThread().getName());
        }
	}
	
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
	
	private static void await(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
			Thread.sleep(1);
		}
	}
	
	/** Makes earlier frames take longer to process to test whether frames are displayed in the correct order. */
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
			pipeline.close();
		}
	}

	/**
	 * Test that the decoder stops reading frames when the pipeline reaches its capacity and resumes when the consumer
	 * takes a frame. This prevents the decoder from getting too far ahead, which could waste memory by storing too
	 * many frames at once.
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
	
	
}
