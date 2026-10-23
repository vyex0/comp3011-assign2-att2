/*
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
 */
package comp3011;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
 
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PlaybackClock}. Time is passed in, so these tests use exact made-up nanosecond values and never
 * sleep; the one concurrency test checks that racing threads cannot anchor the clock twice.
 *
 * <p>
 * <b>Why this class is tested.</b> The clock was chosen for testing because it is part of the multithreaded system 
 * most likely to have timing problems that are hard to notice. The decoder, audio, and UI threads all rely on the
 * clock to agree on when each frame should be played. 
 * </p>
 *
 * <p>
 * <b>Why pass the time in instead of reading it inside the clock?</b>Every clock method receives the current time as
 * parameter instead of calling {@code System.nanoTime()} itself. This makes testing easier because we control the time
 * ourselves and calculate the exact expected results. Tests can then run quickly without waiting for real time to pass.
 * We could use a mocking library for {@code System.nanoTime()}, but it couldn't be done cleanly, because 
 * {@code System.nanoTime()} is a static native method and would require an extra mocking library when the project
 * only needs JUnit.
 * 
 * <p>
 * <b>Why each test checks only one behavior?</b>Each test creates a clock and checks one specific rule to make it
 * easier to identify what went wrong when a test fails. This is better than the alternative of writing one long
 * test that simulates playing an entire video, because it would be more difficult to determine which step caused
 * the problem if the test fails at the end.
 * </p>
 */
public class PlaybackClockTest {
	private static final long PRE_ROLL = PlaybackClock.PRE_ROLL_NS;
    private static final long START = 1_000_000_000L; // an arbitrary "now" in nanoseconds
 
    // A fresh clock per test (JUnit creates a new instance of this class for every test method), so no test can
    // pass or fail because of state left behind by another one.
    private final PlaybackClock clock = new PlaybackClock();
 
    /**
     * Needed because the first decoded frame is used to set the starting point for the video clock. It connects the
     * video's timeline to real-world time, and every later frame is scheduled relative to this starting point. The
     * clock adds a pre-roll delay, which gives the pipeline time to prepare upcoming frames before playback begins.
     */
    @Test
    void firstFrameBecomesTheAnchorDuePreRollInTheFuture() {
        clock.reset(0, false, START);
 
        PlaybackClock.FrameTiming timing = clock.schedule(40_000, false, START);
 
        assertEquals(START + PRE_ROLL, timing.targetTimeNs());
        assertEquals(0, timing.logicalTimestampUs());
    }
 
    /**
     * Needed to ensure the video plays at the correct speed, even if decoding is delayed. Each frame is scheduled
     * at a fixed time relative to the original clock anchor, regardless of when the decoder requests it. The later
     * "now" is passed in deliberately to check that the clock does not calculate the due time as "now + delta",
     * which would cause slow decoding to delay every subsequent frame and make the video play slower than intended.
     */
    @Test
    void laterFramesAreDueByTheirDistanceFromTheAnchor() {
        clock.reset(0, false, START);
        clock.schedule(40_000, false, START);
 
        PlaybackClock.FrameTiming timing = clock.schedule(120_000, false, START + 5);
 
        // 80,000us after the anchor frame = 80,000,000ns after its due time. The later "now" is ignored.
        assertEquals(START + PRE_ROLL + 80_000_000L, timing.targetTimeNs());
        assertEquals(80_000, timing.logicalTimestampUs());
    }
 
    /**
     * Needed because after a seek, the decoder starts from the nearest keyframe, which may be slightly earlier than
     * the position requested. For example, a 5-second seek might produce a frame timestamped at 4,960,000us.
     * The clock must still use the requested position so the video progress and audio do not jump backwards.
     * Checking a following frame also ensures the correct timing offset is maintained throughout playback after the seek.
     */
    @Test
    void logicalTimeStartsFromTheSeekPositionNotTheMediaTimestamp() {
        clock.reset(5_000_000, false, START);
 
        PlaybackClock.FrameTiming timing = clock.schedule(4_960_000, false, START);
        PlaybackClock.FrameTiming next = clock.schedule(5_000_000, false, START);
 
        assertEquals(5_000_000, timing.logicalTimestampUs());
        assertEquals(5_040_000, next.logicalTimestampUs());
    }
 
    /**
     * Needed because each frame has a scheduled playback time. When playback is paused, these times must be
     * moved forward by the pause duration. Otherwise, the player may think frames are overdue when playback
     * resumes and skip frames or play them too quickly to catch up. The test checks a frame scheduled before
     * the pause to make sure its playback time is adjusted correctly.
     */
    @Test
    void resumingShiftsEveryFrameByTheLengthOfThePause() {
        clock.reset(0, false, START);
        clock.schedule(0, false, START);
        long dueBefore = clock.dueTimeNs(200_000);
 
        clock.pause(START + 1_000);
        clock.resume(START + 1_000 + 3_000_000L); // paused for 3ms
 
        assertEquals(dueBefore + 3_000_000L, clock.dueTimeNs(200_000));
    }
 
    /**
     * Needed because the UI can call resume() when playback was never paused (for example a play button pressed
     * twice, or play after a seek). Without a recorded pause start, a naive "now - pauseStart" would produce a huge
     * bogus shift. This is the edge case of the test above, so it is kept separate to give a clear failure.
     */
    @Test
    void resumingWithoutAPauseChangesNothing() {
        clock.reset(0, false, START);
        clock.schedule(0, false, START);
        long dueBefore = clock.dueTimeNs(200_000);
 
        clock.resume(START + 9_000_000L);
 
        assertEquals(dueBefore, clock.dueTimeNs(200_000));
    }
 
    /**
     * Needed because pause and resume can both happen before the decoder has produced any frame (the user presses
     * pause straight after opening a file). The pause length must not be applied to an anchor that did not exist
     * yet. Doing so would shift the first frame's due time by 5ms in this test, so the anchor must be created
     * from the time of the first frame only, which is what the expected value checks.
     */
    @Test
    void aPauseTakenBeforeTheAnchorExistsDoesNotShiftTheLaterAnchor() {
        clock.reset(0, false, START);
        clock.pause(START + 10);
        clock.resume(START + 5_000_000L);          // resumed before any frame was decoded
 
        PlaybackClock.FrameTiming timing = clock.schedule(0, false, START + 6_000_000L);
 
        assertEquals(START + 6_000_000L + PRE_ROLL, timing.targetTimeNs());
    }
 
    /**
     * Needed because a seek while paused restarts the decoder in the paused state, so the first frame arrives
     * after the pause began. The pause has to be counted from the anchor (not from when the pause button was
     * pressed, which is earlier and unrelated to this frame), or resuming would over-shift the timeline by the
     * time between the button press and the first frame.
     */
    @Test
    void anchoringWhilePausedStartsThePauseAtTheAnchor() {
        clock.reset(0, true, START);
        clock.schedule(0, true, START + 100);       // first frame decoded while paused
 
        clock.resume(START + 100 + 2_000_000L);     // paused for 2ms since the anchor
 
        assertEquals(START + 100 + PRE_ROLL + 2_000_000L, clock.dueTimeNs(0));
    }
 
    /**
     * Needed because the render and audio threads may ask "when is this due?" before the decoder has anchored the
     * clock. The answer must be a value that means "never" (Long.MAX_VALUE) instead of a number computed from
     * unset fields, which would look like a real time in the distant past and make a frame play immediately.
     * Long.MAX_VALUE was used instead of throwing an exception or returning null because a waiting thread can
     * compare against it normally and simply keep waiting.
     */
    @Test
    void nothingIsDueBeforeTheClockIsAnchored() {
        clock.reset(0, false, START);
 
        assertEquals(Long.MAX_VALUE, clock.dueTimeNs(0));
        assertTrue(clock.mediaTimestampAtUs(START).isEmpty());
    }
 
    /**
     * Needed because a stale anchor surviving a seek or a stop would time the new frames against the old
     * position, which would be silently wrong rather than visibly broken. Both ways of dropping the anchor are
     * checked, because they are separate methods used in different places (seek versus stop/close) and either
     * could be broken independently.
     */
    @Test
    void resetAndClearForgetTheAnchor() {
        clock.reset(0, false, START);
        clock.schedule(0, false, START);
 
        clock.reset(1_000_000, false, START);
        assertTrue(clock.mediaTimestampAtUs(START).isEmpty());
 
        clock.schedule(1_000_000, false, START);
        clock.clear();
        assertTrue(clock.mediaTimestampAtUs(START).isEmpty());
    }
 
    /**
     * Needed because the audio position is the master reference that the rest of the player is measured
     * against, so it has to move at wall-clock speed from the anchor. Two sample points are used (at the anchor
     * and 10ms later) so the test would catch both a wrong starting value and a wrong rate; a single point could
     * pass with either mistake alone.
     */
    @Test
    void audioPositionAdvancesWithWallClockTime() {
        clock.reset(0, false, START);
        clock.schedule(40_000, false, START); // anchor: due at START + PRE_ROLL
 
        // Exactly at the anchor's due time the position is the anchor's timestamp; 10ms later it is 10,000us on.
        assertEquals(40_000, clock.mediaTimestampAtUs(START + PRE_ROLL).getAsLong());
        assertEquals(50_000, clock.mediaTimestampAtUs(START + PRE_ROLL + 10_000_000L).getAsLong());
    }
 
    /**
     * The decoder thread is the only caller of schedule() in the player, but the clock promises it is safe anyway.
     * Many threads are released at the same instant to race to be first, each "seeing" a different wall-clock time.
     * If the check-then-anchor were not atomic, two threads would each set an anchor and the frames they time would
     * disagree about when the same media timestamp is due. All of them must instead receive the same due time,
     * whichever thread wins.
     *
     * <p>
     * Every thread submits the same media timestamp so the outcome does not depend on who wins: frames earlier than
     * the anchor are (correctly) clamped to the anchor's due time, which would blur what this test is checking.
     * A race window this small cannot be hit every time, so the test repeats the race many times; a pass is evidence
     * rather than proof, and the real guarantee comes from every access being inside the lock.
     * </p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void racingThreadsAgreeOnASingleAnchor() throws Exception {
        final int threads = 8;
        final int rounds = 20_000;
        final long timestampUs = 1_000_000L;
        final PlaybackClock[] racing = { new PlaybackClock() };
        final Set<Long>[] targets = new Set[] { ConcurrentHashMap.newKeySet() };
        // The barrier lines the threads up at the start of every round, and its action runs once per round
        // (on the last thread to arrive) to check the previous round and set up the next.
        final AtomicReference<String> failure = new AtomicReference<>();
        final int[] round = { 0 };
        CyclicBarrier barrier = new CyclicBarrier(threads, () -> {
            if (round[0] > 0 && targets[0].size() != 1 && failure.get() == null) {
                failure.set("threads disagreed about the anchor in round " + round[0]);
            }
            racing[0] = new PlaybackClock();
            racing[0].reset(0, false, START);
            targets[0] = ConcurrentHashMap.newKeySet();
            round[0]++;
        });
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
 
        for (int id = 0; id < threads; id++) {
            final long nowNs = START + id * 1_000_000_000L;   // every thread "sees" a different time
            pool.execute(() -> {
                try {
                    for (int r = 0; r < rounds; r++) {
                        barrier.await();
                        targets[0].add(racing[0].schedule(timestampUs, false, nowNs).targetTimeNs());
                    }
                } catch (Exception e) {
                    failure.compareAndSet(null, e.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
 
        assertNull(failure.get());
    }
 
    /**
     * One thread keeps resetting and re-anchoring the clock while another asks when a frame is due. Every answer
     * must be either "never" (the clock is not anchored at that instant) or the single correct time. Any other value
     * means the reader saw the clock half-way through an update, for example because it read the fields after
     * releasing the lock. Like the race test above, this is evidence rather than proof.
     */
    @Test
    void dueTimeIsNeverReadFromAHalfUpdatedClock() throws Exception {
        final long now = 10_000_000_000L;
        final long timestampUs = 1_000_000L;
        final long correct = now + PRE_ROLL;
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong wrongAnswers = new AtomicLong();
 
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                clock.reset(0, false, now);
                clock.schedule(timestampUs, false, now);
            }
        });
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                long due = clock.dueTimeNs(timestampUs);
                if (due != Long.MAX_VALUE && due != correct) {
                    wrongAnswers.incrementAndGet();
                }
            }
        });
        writer.start();
        reader.start();
        Thread.sleep(1_000);
        stop.set(true);
        writer.join();
        reader.join();
 
        assertEquals(0, wrongAnswers.get());
    }
}
