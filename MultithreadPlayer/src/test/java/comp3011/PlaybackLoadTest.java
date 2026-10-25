/**
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
 */
package comp3011;
 
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
 
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
 
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
 
/**
 * Load test: shows that the multi-threaded player does more work per second than the original single-threaded
 * design, using the real effects on synthetic high definition frames. Runs as part of {@code mvn verify}
 * (and {@code mvn test}), so a future developer gets a performance regression check with no set-up.
 *
 * <p>
 * <b>Why this test.</b> {@link FramePipelineTest} proves the pipeline overlaps work, but with sleeping fake
 * frames. It cannot say whether the real effects, on real frame sizes, actually play faster than the original
 * design. This test measures that, which is the non-functional requirement of the assignment: how many effects can
 * be stacked before playback falls behind real time.
 * </p>
 *
 * <p>
 * <b>What "the original design" is here.</b> The original player decoded, applied every effect and displayed on
 * one thread. {@link #runSequentially} is that model: one thread, frame after frame, using the same effect chain.
 * {@link #runThroughPipeline} is the player's model: the same chain, run through {@link FramePipeline} with its
 * default worker count and the same copy-from-a-pool step that {@code VideoPlayerModel} uses. The two are fed the
 * same input and must produce the same output (checked with a sampled checksum per frame), so a speed-up cannot
 * come from dropping or skipping work.
 * </p>
 *
 * <p>
 * <b>What is not measured.</b> FFmpeg decoding, conversion to a JavaFX image and the screen itself are left out,
 * so the test needs no video file, no display and no JavaFX toolkit. Only the effects (the part the design
 * parallelises) are timed. Decoding stays on one thread in both designs, so leaving it out does not favour the
 * pipeline; it makes the real player's absolute numbers a little lower than the ones printed here.
 * </p>
 *
 * <p>
 * <b>The two tests.</b>
 * <ul>
 * <li>{@link #pipelineIsFasterThanTheSingleThreadedModelWithAllEffects}: same chain, both models, compare total
 * time (the spec's example {@code -nsfwyvdjmbp}, all eleven effects). Both timings are printed.</li>
 * <li>{@link #pipelinePlaysMoreEffectsInRealTimeThanTheSingleThreadedModel}: the assignment's own measure.
 * Effects are stacked one at a time. The single-threaded model's limit is the last stack that still fits one
 * frame's time budget at 24 fps (41,667us). The pipeline must still fit that budget with one more effect on top.
 * The effects it can carry beyond that are printed for information.</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Why the thresholds are loose.</b> Timings depend on the machine and its load, so a strict ratio would fail
 * at random. The first test asks for 1.5x (1.2x when only two workers exist). Perfect scaling would give roughly
 * the worker count, so a pipeline that does not really overlap work (ratio about 1) fails clearly, while a busy
 * machine has room. Each measurement is the best of {@value #REPEATS} runs to drop one-off pauses (garbage
 * collection, another process).
 * </p>
 *
 * <p>
 * <b>When it is skipped.</b> With fewer than two effects workers (fewer than four cores, see
 * {@link FramePipeline#defaultWorkerCount()}) the design cannot beat the original on effects work, so the
 * tests are skipped (JUnit "aborted") rather than failed. Skipped is printed in the Maven report.
 * </p>
 */
class PlaybackLoadTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int STRIDE = WIDTH * 3;
    private static final int FIRST_FRAME_NUMBER = 40;
    private static final int DISTINCT_SOURCE_FRAMES = 4; // cycled, to keep memory low (each frame is ~6MB)
    private static final int REPEATS = 2; // best-of, to reduce noise
    private static final int WARMUP_FRAMES = 4;  // lets the JIT compile the effect loops first
 
    private static final int COMPARE_FRAMES = 24;
    private static final int PROBE_FRAMES = 16;
    private static final int MAX_EFFECTS = 33; // three passes through the eleven-effect catalogue
 
    private static final long FRAME_BUDGET_NS = 1_000_000_000L / 24; // 41,666,666ns, as in the player's trace
 
    private static final InfoVideo INFO = new InfoVideo("load-test.mp4", 5000, WIDTH, HEIGHT,
            Frame.DEPTH_UBYTE, 3, STRIDE, 24.0, 24, 41_666, 0);
 
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void pipelineIsFasterThanTheSingleThreadedModelWithAllEffects() throws Exception {
        int workers = FramePipeline.defaultWorkerCount();
        assumeTrue(workers >= 2, "needs at least 2 effects workers (4 cores); this machine has " + workers);
 
        List<FrameProcessor> chain = buildChain(FrameProcessorType.values().length);
        byte[][] sources = sourceFrames();
 
        // Warm up both paths so neither is timed while the JIT is still compiling.
        runSequentially(chain, sources, WARMUP_FRAMES);
        runThroughPipeline(chain, sources, WARMUP_FRAMES, workers);
 
        Run sequential = best(() -> runSequentially(chain, sources, COMPARE_FRAMES));
        Run pipelined = best(() -> runThroughPipeline(chain, sources, COMPARE_FRAMES, workers));
 
        // Same chain, same input: the faster model must not have got there by doing less.
        assertArrayEquals(sequential.checksums(), pipelined.checksums(),
                "the pipeline produced different pixels from the sequential run");
 
        double speedUp = (double) sequential.elapsedNs() / pipelined.elapsedNs();
        double required = workers >= 3 ? 1.5 : 1.2;
        System.out.printf("%nLoad test (%d effects, %dx%d, %d frames, %d workers)%n"
                + "  single-threaded model: %6.1f ms/frame (%5.1f fps)%n"
                + "  multi-threaded player: %6.1f ms/frame (%5.1f fps)%n"
                + "  speed-up: %.2fx (required %.1fx)%n",
                FrameProcessorType.values().length, WIDTH, HEIGHT, COMPARE_FRAMES, workers,
                msPerFrame(sequential, COMPARE_FRAMES), fps(sequential, COMPARE_FRAMES),
                msPerFrame(pipelined, COMPARE_FRAMES), fps(pipelined, COMPARE_FRAMES),
                speedUp, required);
 
        assertTrue(speedUp >= required, String.format(
                "single-threaded %.1f ms/frame, multi-threaded %.1f ms/frame: speed-up %.2fx is below %.1fx",
                msPerFrame(sequential, COMPARE_FRAMES), msPerFrame(pipelined, COMPARE_FRAMES), speedUp, required));
    }
 
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void pipelinePlaysMoreEffectsInRealTimeThanTheSingleThreadedModel() throws Exception {
        int workers = FramePipeline.defaultWorkerCount();
        assumeTrue(workers >= 2, "needs at least 2 effects workers (4 cores); this machine has " + workers);
 
        byte[][] sources = sourceFrames();
        runSequentially(buildChain(FrameProcessorType.values().length), sources, WARMUP_FRAMES);   // warm up
 
        System.out.printf("%nEffects playable in real time at %dx%d (budget %.1f ms/frame at 24 fps, %d workers)%n"
                + "  effects  single-threaded  multi-threaded%n", WIDTH, HEIGHT, FRAME_BUDGET_NS / 1e6, workers);
 
        // Effects are added one at a time in catalogue order, repeating if needed, the way a user would stack
        // them with repeated flags. Effects are initialised once and reused, so only the new one costs set-up.
        List<FrameProcessor> effects = new ArrayList<>();
        int singleThreadedLimit = -1;       // last stack size the single-threaded model played in time
        int pipelineLimit = 0;              // last stack size the pipeline played in time (no gaps allowed)
        boolean pipelineStillKeepingUp = true;
        boolean checkedOneMoreEffect = false;
        boolean oneMoreEffectFits = false;
 
        for (int count = 1; count <= MAX_EFFECTS; count++) {
            effects.add(initialised(FrameProcessorType.values()[(count - 1) % FrameProcessorType.values().length]));
            List<FrameProcessor> chain = FusedPass.fuse(effects, INFO);
 
            double sequentialMs = -1;
            if (singleThreadedLimit < 0) {
                sequentialMs = msPerFrame(best(() -> runSequentially(chain, sources, PROBE_FRAMES)), PROBE_FRAMES);
                if (sequentialMs * 1e6 > FRAME_BUDGET_NS) {
                    singleThreadedLimit = count - 1;      // this stack is the first one that falls behind
                }
            }
 
            double pipelineMs = -1;
            if (pipelineStillKeepingUp) {
                pipelineMs = msPerFrame(best(() -> runThroughPipeline(chain, sources, PROBE_FRAMES, workers)),
                        PROBE_FRAMES);
                if (pipelineMs * 1e6 <= FRAME_BUDGET_NS) {
                    pipelineLimit = count;
                } else {
                    pipelineStillKeepingUp = false;
                }
            }
 
            System.out.printf("  %7d  %s  %s%n", count, describe(sequentialMs), describe(pipelineMs));
 
            // The assertion is made at the first stack the single-threaded model could not play.
            if (singleThreadedLimit >= 0 && count == singleThreadedLimit + 1) {
                checkedOneMoreEffect = true;
                oneMoreEffectFits = pipelineMs >= 0 && pipelineMs * 1e6 <= FRAME_BUDGET_NS;
            }
            if (singleThreadedLimit >= 0 && !pipelineStillKeepingUp) {
                break;      // both have fallen behind: nothing more to learn
            }
        }
 
        System.out.printf("Single-threaded model plays %s effects in real time; the pipeline up to %d.%n",
                singleThreadedLimit < 0 ? "all " + MAX_EFFECTS + " tried" : "up to " + singleThreadedLimit,
                pipelineLimit);
 
        assumeTrue(checkedOneMoreEffect,
                "this machine plays " + MAX_EFFECTS + " effects single-threaded in real time; nothing to compare");
        assertTrue(oneMoreEffectFits, "the single-threaded model fell behind real time at " + (singleThreadedLimit + 1)
                + " effects, and the pipeline did not hold " + FRAME_BUDGET_NS / 1_000 + "us/frame there either");
    }
 
    /** What one run produced: how long it took, and a small checksum per frame to prove the same output. */
    private record Run(long elapsedNs, long[] checksums) { }
 
    @FunctionalInterface
    private interface Measured {
        Run run() throws Exception;
    }
 
    /** Several runs, keeping the fastest: the one least disturbed by garbage collection or other processes. */
    private static Run best(Measured measured) throws Exception {
        Run best = null;
        for (int i = 0; i < REPEATS; i++) {
            Run run = measured.run();
            if (best == null || run.elapsedNs() < best.elapsedNs()) {
                best = run;
            }
        }
        return best;
    }
 
    /** The original model: one thread does every frame's work in turn. */
    private static Run runSequentially(List<FrameProcessor> chain, byte[][] sources, int frames) throws Exception {
        long[] checksums = new long[frames];
        Frame work = newFrame();
        long start = System.nanoTime();
        for (int n = 0; n < frames; n++) {
            copyInto(work, sources[n % sources.length]);
            applyChain(chain, work, n);
            checksums[n] = sampledChecksum(work);
        }
        return new Run(System.nanoTime() - start, checksums);
    }
 
    /**
     * The player's model: the decoder thread copies each frame from a pool and hands the effect work to the
     * pipeline's workers, and the frames are taken back in order, polled the way the JavaFX heartbeat does.
     */
    private static Run runThroughPipeline(List<FrameProcessor> chain, byte[][] sources, int frames, int workers)
            throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        FramePipeline<Long> pipeline = new FramePipeline<>(workers, (e, stillCurrent) -> failure.compareAndSet(null, e));
        try {
            pipeline.begin(new SyntheticSource(chain, sources, frames));
 
            long[] checksums = new long[frames];
            int taken = 0;
            long start = System.nanoTime();
            long deadline = start + TimeUnit.SECONDS.toNanos(120);
            while (!pipeline.isDrained()) {
                if (failure.get() != null) {
                    throw failure.get();
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("timed out: only " + taken + " of " + frames + " frames arrived");
                }
                pipeline.requestDecoding();
                Long checksum = pipeline.pollReady();
                if (checksum != null) {
                    checksums[taken++] = checksum;      // frames arrive in decode order, so index = frame number
                } else {
                    LockSupport.parkNanos(100_000);
                }
            }
            long elapsed = System.nanoTime() - start;
            if (failure.get() != null) {
                throw failure.get();
            }
            assertTrue(taken == frames, "expected " + frames + " frames but received " + taken);
            return new Run(elapsed, checksums);
        } finally {
            pipeline.close();
        }
    }
 
    /**
     * Stands in for {@code VideoPlayerModel.GrabberSource}: on the decoder thread it copies the next source frame
     * into a pooled frame, so the "grabber" buffer is free straight away, then returns the effects job for a
     * worker. Workers return the frame to the pool when finished, as the real player does.
     */
    private static final class SyntheticSource implements FramePipeline.FrameSource<Long> {
        private final List<FrameProcessor> chain;
        private final byte[][] sources;
        private final int total;
        private final Queue<Frame> pool = new ConcurrentLinkedQueue<>();
        private int next;       // only touched on the decoder thread
 
        SyntheticSource(List<FrameProcessor> chain, byte[][] sources, int total) {
            this.chain = chain;
            this.sources = sources;
            this.total = total;
        }
 
        @Override
        public Callable<Long> readNext(BooleanSupplier cancelled) {
            if (next >= total) {
                return null;
            }
            int n = next++;
            Frame copy = pool.poll();
            if (copy == null) {
                copy = newFrame();
            }
            copyInto(copy, sources[n % sources.length]);
            Frame mine = copy;
            return () -> {
                try {
                    applyChain(chain, mine, n);
                    return sampledChecksum(mine);
                } finally {
                    pool.offer(mine);
                }
            };
        }
 
        @Override
        public void seek(long timestampUs) {
            // Not used: the load test plays straight through.
        }
    }
    
    private static FrameProcessor initialised(FrameProcessorType type) throws Exception {
        FrameProcessor effect = type.create();
        effect.initialise(INFO);
        return effect;
    }
 
    /** The first {@code count} effects of the catalogue, initialised and fused as the player builds its chain. */
    private static List<FrameProcessor> buildChain(int count) throws Exception {
        List<FrameProcessor> effects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            effects.add(initialised(FrameProcessorType.values()[i % FrameProcessorType.values().length]));
        }
        return FusedPass.fuse(effects, INFO);
    }
 
    private static void applyChain(List<FrameProcessor> chain, Frame frame, int n) throws Exception {
        InfoFrame info = new InfoFrame(FIRST_FRAME_NUMBER + n, (FIRST_FRAME_NUMBER + n) * 41_666L);
        for (FrameProcessor effect : chain) {
            effect.process(frame, info);
        }
    }
 
    private static Frame newFrame() {
        return new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3, STRIDE);
    }
 
    private static void copyInto(Frame destination, byte[] source) {
        ByteBuffer to = (ByteBuffer) destination.image[0];
        to.clear();
        to.put(source);
        to.clear();
    }
 
    // Noise, seeded by index, so every run starts from the same bytes. Real footage is not noise, but the effects'
    // cost does not depend on pixel values, so noise gives the same timings without needing a video file.
    private static byte[][] sourceFrames() {
        byte[][] frames = new byte[DISTINCT_SOURCE_FRAMES][STRIDE * HEIGHT];
        for (int i = 0; i < frames.length; i++) {
            new Random(i).nextBytes(frames[i]);
        }
        return frames;
    }
 
    // Every 997th byte (about 6,000 reads for a 6MB frame): costs almost nothing but still changes if any effect
    // in the chain produced a different picture.
    private static long sampledChecksum(Frame frame) {
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        long sum = 17;
        for (int i = 0; i < pixels.capacity(); i += 997) {
            sum = sum * 31 + pixels.get(i);
        }
        return sum;
    }
 
    private static double msPerFrame(Run run, int frames) {
        return run.elapsedNs() / 1e6 / frames;
    }
 
    private static double fps(Run run, int frames) {
        return frames * 1e9 / run.elapsedNs();
    }
 
    private static String describe(double msPerFrame) {
        if (msPerFrame < 0) {
            return "      -        ";
        }
        return String.format("%6.1f ms %s", msPerFrame, msPerFrame * 1e6 <= FRAME_BUDGET_NS ? "ok  " : "LATE");
    }
}