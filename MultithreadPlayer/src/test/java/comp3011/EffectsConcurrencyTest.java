/*
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
 */
package comp3011;
 
import static org.junit.jupiter.api.Assertions.assertTrue;
 
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
 
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
 
/**
 * Checks that the effects give exactly the same pixels whether frames are processed one at a time or several at
 * once on different threads, which is what the effects workers in {@link FramePipeline} do during playback.
 *
 * <p>
 * <b>Why this test.</b> The effects are the only part of the player where many threads run the SAME object at the
 * same time. Each effect has its own way of making that safe (per-thread scratch buffers in {@code FrameBleeder} and
 * {@code FrameJitterer}, an immutable published snapshot in {@code FrameMottler}, local variables in
 * {@code FusedPass}). A mistake in any of them does not crash: two frames quietly draw on each other's buffer and
 * the video just looks wrong now and then. Nothing else in the test suite would notice, because
 * {@link FramePipelineTest} uses fake frame sources and {@link FusedPassTest} runs on one thread.
 * </p>
 *
 * <p>
 * <b>How it works.</b> For each effect, the same effect object processes the same set of random frames twice
 * sequentially (the reference), and then several times with {@value #THREADS} threads working at once. A barrier
 * releases the threads together before every group of {@value #THREADS} neighbouring frames, so they really do
 * enter {@code process()} at the same moment instead of politely taking turns. Every concurrent result must match the
 * reference byte for byte. The reference is computed twice so that an effect which is not repeatable even on one
 * thread is reported as that, instead of being mistaken for a race.
 * </p>
 *
 * <p>
 * <b>Why these frame numbers.</b> They start at {@value #FIRST_FRAME_NUMBER} and run across two damage-mask segment
 * boundaries of {@code FrameMottler} (every 72 frames). Frames from different segments are then in flight at the
 * same time, which is the case where workers publish and replace its shared snapshot.
 * </p>
 *
 * <p>
 * <b>Why every effect is taken from {@link FrameProcessorType}.</b> A newly added effect is then covered without
 * editing this test. One scenario also runs all the effects as the player would (fused where possible), because
 * the combination is what actually runs on a worker.
 * </p>
 *
 * <p>
 * <b>Limits.</b> Like the clock race tests, a pass is evidence rather than proof, since a race may not show on every
 * run. To check that this test can fail, temporarily change {@code FrameJitterer}'s {@code ThreadLocal} scratch
 * buffer into one plain shared {@code byte[]} field: this test should then fail for {@code JITTER} and for the
 * all-effects chain.
 * </p>
 */
class EffectsConcurrencyTest {
    private static final int THREADS = 8;
    private static final int ROUNDS = 20; // groups of THREADS frames
    private static final int FRAME_COUNT = THREADS * ROUNDS;
    private static final int FIRST_FRAME_NUMBER = 40;
    private static final int CONCURRENT_RUNS = 3; // repeats, because one run may miss a narrow race
    private static final int WIDTH = 256;
    private static final int HEIGHT = 144;
    private static final int STRIDE = WIDTH * 3 + 16; // rows are padded, as a real decoder may do
 
    // One thing to test: a name for the failure message, and the effect objects built for it.
    private record Scenario(String name, List<FrameProcessor> effects) { }
 
    @Test
    void everyEffectGivesTheSameResultWhenFramesAreProcessedAtTheSameTime() throws Exception {
        byte[][] inputs = noiseFrames();
        List<String> problems = new ArrayList<>();
 
        for (Scenario scenario : scenarios()) {
            InfoVideo info = new InfoVideo("movie-" + scenario.name() + ".mp4", 5000, WIDTH, HEIGHT,
                    Frame.DEPTH_UBYTE, 3, STRIDE, 24.0, 24, 41_666, 0);
            for (FrameProcessor effect : scenario.effects()) {
                effect.initialise(info);
            }
            // Built the same way as the player builds its chain, so a fused pass is exercised too.
            List<FrameProcessor> chain = FusedPass.fuse(scenario.effects(), info);
 
            byte[][] reference = runSequentially(chain, inputs);
            String unrepeatable = difference(reference, runSequentially(chain, inputs));
            if (unrepeatable != null) {
                problems.add(scenario.name() + ": not repeatable even on ONE thread (" + unrepeatable
                        + "), so this is not a race; it depends on something other than the frame");
                continue;
            }
 
            for (int run = 1; run <= CONCURRENT_RUNS; run++) {
                try {
                    String wrong = difference(reference, runConcurrently(chain, inputs));
                    if (wrong != null) {
                        problems.add(scenario.name() + ": concurrent run " + run + " differs from the reference ("
                                + wrong + ")");
                        break;      // one report per effect is enough to find it
                    }
                } catch (Exception e) {
                    problems.add(scenario.name() + ": concurrent run " + run + " threw " + e);
                    break;
                }
            }
        }
 
        assertTrue(problems.isEmpty(), "\n" + String.join("\n", problems));
    }
 
    // Every effect on its own, then all of them in command-line-style order as one chain.
    private static List<Scenario> scenarios() {
        List<Scenario> scenarios = new ArrayList<>();
        List<FrameProcessor> all = new ArrayList<>();
        for (FrameProcessorType type : FrameProcessorType.values()) {
            scenarios.add(new Scenario(type.name(), List.of(type.create())));
            all.add(type.create());
        }
        scenarios.add(new Scenario("ALL_EFFECTS_IN_ONE_CHAIN", all));
        return scenarios;
    }
 
    // Random bytes: every channel value (including those Java reads as negative) and the row padding, so a wrong
    // read or a write into the padding shows up. Seeded by frame index, so the input is the same on every run.
    private static byte[][] noiseFrames() {
        byte[][] frames = new byte[FRAME_COUNT][STRIDE * HEIGHT];
        for (int i = 0; i < FRAME_COUNT; i++) {
            new Random(i).nextBytes(frames[i]);
        }
        return frames;
    }
 
    private static byte[][] runSequentially(List<FrameProcessor> chain, byte[][] inputs) throws Exception {
        byte[][] outputs = new byte[FRAME_COUNT][];
        for (int i = 0; i < FRAME_COUNT; i++) {
            outputs[i] = run(chain, inputs[i], FIRST_FRAME_NUMBER + i);
        }
        return outputs;
    }
 
    /**
     * Processes the same frames with THREADS threads at once. Thread t takes frame (round * THREADS + t), and the
     * barrier makes all threads start each round together, so neighbouring frame numbers are always in flight
     * at the same moment. If an effect throws, the thread records it and still takes part in the remaining
     * barriers, so one failure cannot leave the others waiting.
     */
    private static byte[][] runConcurrently(List<FrameProcessor> chain, byte[][] inputs) throws Exception {
        byte[][] outputs = new byte[FRAME_COUNT][];   // each slot is written by exactly one thread
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> finished = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int thread = t;
                finished.add(pool.submit(() -> {
                    for (int round = 0; round < ROUNDS; round++) {
                        barrier.await(30, TimeUnit.SECONDS);
                        int i = round * THREADS + thread;
                        try {
                            outputs[i] = run(chain, inputs[i], FIRST_FRAME_NUMBER + i);
                        } catch (Exception e) {
                            failure.compareAndSet(null, e);
                        }
                    }
                    return null;
                }));
            }
            // Future.get() also makes the threads' writes to "outputs" visible to this thread.
            for (Future<?> future : finished) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        if (failure.get() != null) {
            throw new Exception(failure.get());
        }
        return outputs;
    }
 
    // Runs the whole chain on a private copy of one frame and returns the resulting bytes, as a worker would.
    private static byte[] run(List<FrameProcessor> chain, byte[] input, int frameNumber) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocateDirect(input.length);
        buffer.put(0, input, 0, input.length);
        Frame frame = new Frame();
        frame.image = new Buffer[] { buffer };
 
        InfoFrame info = new InfoFrame(frameNumber, 0);
        for (FrameProcessor effect : chain) {
            effect.process(frame, info);
        }
 
        byte[] result = new byte[buffer.capacity()];
        buffer.get(0, result, 0, result.length);
        return result;
    }
 
    // Describes the first difference between two sets of frames, or returns null if they are identical.
    private static String difference(byte[][] expected, byte[][] actual) {
        for (int i = 0; i < expected.length; i++) {
            int at = Arrays.mismatch(expected[i], actual[i]);
            if (at >= 0) {
                return "frame " + (FIRST_FRAME_NUMBER + i) + " first differs at byte " + at;
            }
        }
        return null;
    }
}