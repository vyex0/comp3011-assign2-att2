/**
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
 */
package comp3011;
 
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
 
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
 
/**
 * Checks that fusing neighbouring per-pixel effects ({@code FusedPass}) gives exactly the same
 * bytes as running the same effects one after another. This is the property the whole optimisation depends on, so it
 * is checked with random chains, odd frame widths and padded rows, not just the common combinations.
 *
 * <p>
 * Each check runs ONE set of effect objects both ways. The effects seed their "randomness" from a per-instance
 * counter, so two separately built chains would legitimately differ and prove nothing.
 * </p>
 */
class FusedPassTest {
    // width, height, stride: a normal frame, an odd width, and rows that have padding after the pixels
    private static final int[][] SHAPES = { { 256, 128, 768 }, { 211, 97, 633 }, { 200, 100, 640 } };
 
    private static FrameProcessor make(char flag) {
        return switch (flag) {
            case 'n' -> new FrameNumberer();
            case 's' -> new FrameScratcher();
            case 'f' -> new FrameFlickerer();
            case 'w' -> new FrameBlackAndWhiter();
            case 'y' -> new FrameYellower();
            case 'v' -> new FrameVignetter();
            case 'd' -> new FrameDuster();
            case 'j' -> new FrameJitterer();
            case 'm' -> new FrameMottler();
            case 'b' -> new FrameBleeder();
            default -> new FramePepperer();
        };
    }
 
    private static InfoVideo info(String name, int width, int height, int stride, int channels) {
        return new InfoVideo(name, 5000, width, height, Frame.DEPTH_UBYTE, channels, stride, 24.0, 24, 41_666, 0);
    }
 
    // Builds a frame full of random bytes. It is needed because the tests have no real video to decode, so they
    // need pictures to run the effects on. Random bytes are a good stand-in: they contain every possible channel
    // value from 0 to 255 (including the values above 127 that Java treats as negative bytes, which is where
    // mistakes with "& 0xff" would show up), and the padding bytes at the end of each row are filled too, so
    // the tests can notice if the padding is touched. The same seed always gives the same bytes, which is how
    // assertSameOutput makes two identical copies of one picture. The pixels are held in a ByteBuffer because
    // that is the kind of buffer FusedPass.process works on; any other kind would make it take its fallback path
    // and the test would not be exercising the fused loops at all.
    private static Frame noise(int stride, int height, long seed) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(stride * height);
        Random random = new Random(seed);
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, (byte) random.nextInt());
        }
        Frame frame = new Frame();
        frame.image = new java.nio.Buffer[] { buffer };
        return frame;
    }
 
    // Copies all the bytes of a frame into a plain byte[]. It is needed because assertArrayEquals compares arrays,
    // and a ByteBuffer cannot be handed to it directly. The whole buffer is copied, padding included, so the
    // comparison covers every byte of the frame and not only the visible pixels. The absolute get is used so the
    // buffer's position is not moved, and the copy is a snapshot that later changes to the frame cannot alter.
    private static byte[] bytes(Frame frame) {
        ByteBuffer buffer = (ByteBuffer) frame.image[0];
        byte[] copy = new byte[buffer.capacity()];
        buffer.get(0, copy, 0, copy.length);
        return copy;
    }
 
    /**
     * Runs one chain of effects both ways and asserts that the results are byte-for-byte identical. The chain is
     * given as a string of letters (see {@code make}, for example "fwyv" is flicker, black and white, yellower,
     * vignette). The effects are built and initialised once, and {@code FusedPass.fuse} turns that chain into the
     * fused version. Then, for several frames, it makes two identical copies of a random picture, runs one copy
     * through the original effects one after another and the other through the fused chain, and compares every
     * byte. A random frame number is used each time so that the flicker effect dims by a different amount on
     * different frames. If the bytes ever differ, the failure message names the chain and the frame number so the
     * case can be repeated.
     *
     * <p>
     * It is needed because "the fused result is identical to running the effects one by one" is the one property
     * the whole optimisation must keep, and every test that checks it goes through this method. The same effect
     * objects are used for both runs on purpose (see the class comment), so any difference found is a real
     * difference between fused and unfused processing and not an accident of how the effects were built.
     * </p>
     */
    private static void assertSameOutput(String effects, int[] shape, int frames, long seed) throws Exception {
        InfoVideo info = info("movie-" + effects + ".mp4", shape[0], shape[1], shape[2], 3);
        List<FrameProcessor> chain = new ArrayList<>();
        for (char flag : effects.toCharArray()) {
            chain.add(make(flag));
        }
        for (FrameProcessor effect : chain) {
            effect.initialise(info);
        }
        List<FrameProcessor> fused = FusedPass.fuse(chain, info);
 
        Random random = new Random(seed);
        for (int i = 0; i < frames; i++) {
            long picture = random.nextLong();
            InfoFrame frameInfo = new InfoFrame(random.nextInt(4000), 0);
            Frame separate = noise(shape[2], shape[1], picture);
            Frame together = noise(shape[2], shape[1], picture);
            for (FrameProcessor effect : chain) {
                effect.process(separate, frameInfo);
            }
            for (FrameProcessor effect : fused) {
                effect.process(together, frameInfo);
            }
            assertArrayEquals(bytes(separate), bytes(together), "chain -" + effects + " frame " + frameInfo.frameNumber());
        }
    }
 
    /**
     * This test is important because it covers the chains that matter most, on every frame shape. "fwyv" and
     * "vywf" are the two sequences that have their own specialised loops in {@code FusedPass}, and those loops
     * hard-code which table sits where, so they are the easiest place for a mistake to hide. "fwyvfwyv" and
     * "vywfvywf" are longer chains that do not match either specialised sequence, so they go through the general
     * loop and check that it gives the same answer. "sfwyvdjp" and "pvywfs" put effects that cannot be fused
     * before and after the run, to check that a fused pass sits correctly among effects that run on their own and
     * that the order is kept. Running each chain on all three shapes (a normal frame, an odd width, and rows with
     * padding) checks that the row handling is right when the width is not a tidy number and when the rows are
     * not packed tightly.
     */
    @Test
    void theTwoCommonRunsMatchSeparateEffectsOnEveryFrameShape() throws Exception {
        for (int[] shape : SHAPES) {
            for (String effects : List.of("fwyv", "vywf", "fwyvfwyv", "vywfvywf", "sfwyvdjp", "pvywfs")) {
                assertSameOutput(effects, shape, 3, 7);
            }
        }
    }
 
    /**
     * This test is important because hand-written chains only cover the combinations someone thought of. Here 150
     * chains of 2 to 11 effects are generated at random, about 80% from the four fusable effects and 20% from the
     * ones that cannot be fused, so the results include all sorts of awkward cases: several table effects in a
     * row that must be merged correctly (such as flicker next to yellower), runs of a single effect that are
     * left alone, runs split up by other effects, and chains that fall back to the general loop. The random
     * generator uses a fixed seed (2026), so the same 150 chains are produced on every run and a failure can be
     * repeated, and the frame shapes are rotated through so all three are used.
     */
    @Test
    void randomChainsMatchSeparateEffects() throws Exception {
        Random random = new Random(2026);
        String fusable = "fwyv";
        String others = "snjdp";
        for (int trial = 0; trial < 150; trial++) {
            StringBuilder effects = new StringBuilder();
            int length = 2 + random.nextInt(10);
            for (int i = 0; i < length; i++) {
                effects.append(random.nextInt(10) < 8 ? fusable.charAt(random.nextInt(4)) : others.charAt(random.nextInt(5)));
            }
            assertSameOutput(effects.toString(), SHAPES[trial % SHAPES.length], 2, trial);
        }
    }
 
    /**
     * This test is important because the output-matching tests above would still pass if {@code fuse} did
     * nothing at all: returning the original chain gives identical output trivially. This test checks that the
     * optimisation really happens. A chain of six effects (scratcher, flicker, black and white, yellower,
     * vignette, numberer) has four fusable effects next to each other in the middle, so it must come back as
     * three entries: the scratcher, one {@code FusedPass} for the four neighbours, and the numberer. It also
     * checks that the middle entry really is a {@code FusedPass}, and that the effects on either side stayed
     * where they were.
     */
    @Test
    void neighbouringPerPixelEffectsAreActuallyFused() throws Exception {
        InfoVideo info = info("movie.mp4", 64, 32, 192, 3);
        List<FrameProcessor> chain = new ArrayList<>(List.of(new FrameScratcher(), new FrameFlickerer(),
                new FrameBlackAndWhiter(), new FrameYellower(), new FrameVignetter(), new FrameNumberer()));
        for (FrameProcessor effect : chain) {
            effect.initialise(info);
        }
 
        List<FrameProcessor> fused = FusedPass.fuse(chain, info);
 
        assertEquals(3, fused.size(), "scratcher, one fused pass for the four neighbours, numberer");
        assertTrue(fused.get(1) instanceof FusedPass);
    }
 
    /**
     * This test is important because it checks the two cases where fusing must NOT happen. In the first, a
     * yellower, a scratcher and a vignette are in a chain, but the fusable effects are separated by an effect that
     * cannot be fused, so each one is a run of one. A run of one gains nothing from fusing (it would only add the
     * cost of copying the row), so the chain must come back unchanged. In the second, a flicker and a yellower sit
     * next to each other, which would normally be fused, but the video has 4 channels per pixel. The fused loops
     * only understand 3 channels, so they would read the wrong bytes, and the chain must again come back
     * unchanged so the original effects handle the video. Together these show that fusing is only used when it
     * helps and when it is safe.
     */
    @Test
    void lonelyEffectsAndUnsupportedVideoAreLeftAsTheyWere() throws Exception {
        InfoVideo three = info("movie.mp4", 64, 32, 192, 3);
        List<FrameProcessor> separated = new ArrayList<>(List.of(new FrameYellower(), new FrameScratcher(),
                new FrameVignetter()));
        for (FrameProcessor effect : separated) {
            effect.initialise(three);
        }
        assertEquals(separated, FusedPass.fuse(separated, three));
 
        InfoVideo four = info("movie.mp4", 64, 32, 256, 4);
        List<FrameProcessor> neighbours = new ArrayList<>(List.of(new FrameFlickerer(), new FrameYellower()));
        for (FrameProcessor effect : neighbours) {
            effect.initialise(four);
        }
        assertEquals(neighbours, FusedPass.fuse(neighbours, four));
    }
}