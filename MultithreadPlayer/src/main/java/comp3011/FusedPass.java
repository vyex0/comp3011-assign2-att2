/**
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
 */
package comp3011;
 
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
 
import org.bytedeco.javacv.Frame;
 
/**
 * Applies a run of neighbouring per-pixel effects in one trip over the frame instead of one trip per effect.
 * Built once when the effect chain is initialised and never changed afterwards, so several workers can call
 * {@link #process} at the same time.
 *
 * <p>
 * <b>Why fuse at all.</b> A video frame is several megabytes, far bigger than the CPU cache, so an effect that
 * walks the whole frame spends most of its time waiting for memory. Running four effects one after another pays
 * that cost four times. Here each row is copied out once, every effect in the run is applied to it while it is
 * still in cache, and it is written back once. The effects also often reduce to "look the value up in a table", and
 * two tables in a row can be merged into one (see {@link #resolve}), so fusing removes work as well as memory
 * traffic.
 * </p>
 *
 * <p>
 * <b>Why only these four effects.</b> The effects chosen for fusing are the yellower, the flicker, the black and
 * white, and the vignette. Each of them decides a pixel's new value using only that pixel's own value and its x/y
 * position, so a single row can be copied out, finished completely and written back without looking at any other
 * row. Three of the four are also very cheap per pixel (a table lookup, or a few integer multiplications), which
 * is the situation where waiting for memory costs more than the effect itself, so removing repeated trips over the
 * frame pays off the most.
 * </p>
 *
 * <p>
 * <b>Why other effects are not fused, and why including them would be expensive.</b> An effect that needs more
 * than the pixel's own value and position cannot be applied to one row in isolation. Examples are an effect that
 * reads neighbouring pixels (such as a blur or sharpen), one that needs rows above and below the current row, or
 * one that depends on earlier frames. Fusing such an effect would mean either keeping a window of several rows (or
 * the whole frame) in memory, which gives up the benefit of working on one cached row, or recalculating the
 * earlier effects on the neighbouring rows again and again so that their input is correct, which adds work instead
 * of removing it. An effect that depends on earlier frames would also break the rule that frames can be handled by
 * any worker in any order. The extra complexity also raises the risk that the fused output stops matching the
 * unfused output. So these effects are left to run as their own separate passes (see {@link #fuse}).
 * </p>
 *
 * <p>
 * <b>Why only neighbouring per-pixel effects.</b> Effects must run in the order the user chose, so only effects
 * that sit next to each other can be merged; anything else in the chain ends the run and is left to run on its own
 * (see {@link #fuse}). Each fused effect also has to work on one pixel at a time (using only the pixel's own value
 * and its x/y position), because that is what allows a row to be processed in isolation.
 * </p>
 *
 * <p>
 * <b>Thread safety.</b> Nothing in this class changes after the constructor finishes. The only data a call to
 * {@link #process} writes to is the frame it was given and local variables (including its own row buffer), so
 * workers handling different frames cannot interfere with each other and no locking is needed.
 * </p>
 *
 * <p>
 * <b>Correctness requirement.</b> The fused result must be identical to running the original effects one by one.
 * That is why the arithmetic here (the luminance weights and the flicker calculation) deliberately repeats what the
 * original effect classes do, and why the original effects are kept in {@code originals} as a fallback. Fusion can
 * be switched off with {@code -Dvideoplayer.noFusion=true} to compare the two.
 * </p>
 *
 * <p>
 * <b>Why the {@code -Dvideoplayer.noFusion=true} switch exists.</b> It is there to make testing easier. With it,
 * the same build can play the same video once with fusion and once without, so the two outputs can be compared to
 * check that they are identical (the correctness requirement above), and the two playback speeds can be compared
 * to measure how much the fusing actually saves. Without the switch, getting the unfused result would mean editing
 * and recompiling the code each time.
 * </p>
 */
public class FusedPass extends FrameProcessor {
    // One entry per fused effect, describing only the data needed to apply it. Records are used because a step
    // is just a bundle of immutable values, and they make the instanceof tests in resolve() read clearly.
    private interface Step { }
    private record YellowerStep(byte[] blue, byte[] green, byte[] red) implements Step { }
    private record FlickerStep(int mediaSeed) implements Step { }
    private record BlnWhStep() implements Step { }
    private record VignetteStep(byte[][] xLookups, byte[][] yLookups) implements Step { }
 
    // The three kinds of operation a FusedFrameInfo can contain. The yellower step and the flicker step both become
    // YelFlick. They are combined because both change each colour channel using only that channel's own value
    // (the new blue depends only on the old blue, and so on), so each can be written as a 256-entry table. Once
    // the flicker table has been built for the current frame, the two are the same kind of thing, and applying two
    // tables one after the other gives the same result as applying one table made by merging them (see compose).
    // One lookup per value then replaces two.
    private static final int YelFlick = 0, VIGNETTE = 1, BlnWh = 2;
 
    // What to do to one particular frame: the steps after neighbouring table steps have been merged. It is
    // rebuilt for every frame (see resolve) because the flicker brightness changes from frame to frame.
    private record FusedFrameInfo(int count, int shape, int[] kind, byte[][] blue, byte[][] green, byte[][] red,
            byte[][][] xLookups, byte[][][] yLookups) { }
 
    // Which loop applies a FusedFrameInfo. GENERAL handles any combination of steps; the other two are specialised
    // loops for one exact sequence of steps each (see applyTablesBlnWhTablesVignette and applyVignetteTablesBlnWhTables).
    private static final int GENERAL = 0, TABLES_BLNWH_TABLES_VIGNETTE = 1, VIGNETTE_TABLES_BLNWH_TABLES = 2;
 
    private final List<FrameProcessor> originals;
    private final List<Step> steps;
    private final double flickerPercentageSd;
 
    /**
     * Reads what it needs out of the already-initialised effects (their lookup tables, the flicker seed), rather
     * than recalculating it, so the fused result starts from exactly the same data as the originals. The reflection
     * used to do that is explained at read().
     */
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
                built.add(new BlnWhStep());
            } else if (type == FrameYellower.class) {
                built.add(new YellowerStep((byte[]) read(effect, "blueLookup"), (byte[]) read(effect, "greenLookup"),
                        (byte[]) read(effect, "redLookup")));
            } else {
                built.add(new VignetteStep((byte[][]) read(effect, "xLookups"), (byte[][]) read(effect, "yLookups")));
            }
        }
        // List.copyOf makes a list that can never be changed after this point. This matters because the list is
        // read by several worker threads at the same time for the life of the video.
        this.steps = List.copyOf(built);
    }
 
    /**
     * Rewrites an effect chain so that each run of neighbouring fusable effects becomes a single {@code FusedPass}. 
     * Must be called after the effects have been initialised, because it reads the tables they built. Any effect 
     * that cannot be fused is kept as it is, in its original position.
     *
     * <p>
     * If fusion cannot be used for any reason, the original chain is returned unchanged, so a problem here makes
     * playback slower and never makes it wrong or stops it.
     * </p>
     *
     * <p>
     * <b>Why static.</b> This method builds {@code FusedPass} objects out of an existing chain, so it has to be
     * callable before any {@code FusedPass} exists. It also uses no data belonging to a particular {@code FusedPass}
     * (everything it needs arrives as the {@code chain} and {@code info} arguments). The constructor is private, so
     * this method is the only way for other classes in the package to create fused passes, and it is called as
     * {@code FusedPass.fuse(chain, info)}.
     * </p>
     */
    static List<FrameProcessor> fuse(List<FrameProcessor> chain, InfoVideo info) {
        // This check is necessary because it covers the edge case of a video whose pixel format is different from
        // what the fused loops were written for. The loops below assume three interleaved bytes per pixel (blue,
        // green, red) with one byte per value. A video with another layout (for example 4 channels with a
        // transparency value, or 16-bit values) would be read at the wrong positions and produce garbage, so for
        // those videos fusing is skipped and the original effects, which handle the format, are left to run.
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
                    // A non-fusable effect ends the run: the effects on either side of it cannot be merged
                    // because that would change the order they are applied in.
                    endRun(run, result, info);
                    result.add(effect);
                }
            }
            endRun(run, result, info);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Reflection fails at run time (not compile time) if an effect class is changed, so this is the
            // safety net for that. The message tells the person why the speed-up is missing.
            System.out.println("Effect fusion unavailable (" + e + "), running the effects as separate passes.");
            return List.copyOf(chain);
        }
 
        // This message exists to help with testing. It is only printed when fusing actually shortened the chain,
        // and it shows how many effects were merged into how many passes, so a tester can see straight away that
        // fusion happened and how much of the chain it covered. If nothing was fused, nothing is printed.
        if (result.size() < chain.size()) {
            System.out.println("Effects: " + chain.size() + " run as " + result.size() + " passes (neighbouring "
                    + "per-pixel effects fused).");
        }
        return List.copyOf(result);
    }
 
    // Finishes the current run of fusable effects and starts a new one.
    private static void endRun(List<FrameProcessor> run, List<FrameProcessor> result, InfoVideo info)
            throws ReflectiveOperationException {
        // A run of one gains nothing from fusing (it would only add the cost of the row copy), so it is left
        // as the original effect.
        if (run.size() >= 2) {
            result.add(new FusedPass(List.copyOf(run), info));
        } else {
            result.addAll(run);
        }
        run.clear();
    }
 
    /**
     * Returns true if the given effect is one of the four effects that can be fused (the flicker, black and white,
     * yellower or vignette effect), and false for any other effect. It does this by looking up which class the
     * effect object is and comparing that with the four classes.
     * 
     * Exact class comparison (not instanceof) on purpose: a subclass could change what the effect does, and
     * fusing it would silently apply the parent's behaviour instead.
     */
    private static boolean isFusable(FrameProcessor effect) {
        Class<?> type = effect.getClass();
        return type == FrameFlickerer.class || type == FrameBlackAndWhiter.class
                || type == FrameYellower.class || type == FrameVignetter.class;
    }
 
    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        // The fused loops work directly on a byte buffer. For any other kind of frame, fall back to running the
        // original effects one at a time, which gives the same result.
        if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) {
            for (FrameProcessor effect : originals) {
                effect.process(frame, info);
            }
            return;
        }
 
        // Built per frame, not once, because the flicker brightness depends on the frame number.
        FusedFrameInfo fusedFrameInfo = resolve(info.frameNumber());
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int stride = videoInfo.imageStride();
        int widthBytes = width * 3;
        int count = fusedFrameInfo.count();
        // This worker's private scratch row. It is a local variable (not a field) so that workers running at the
        // same time each have their own. Rows are processed in a plain byte[] because that is much faster than
        // reading and writing a ByteBuffer one byte at a time, and the row stays in cache while every step runs.
        byte[] row = new byte[widthBytes];
 
        for (int y = 0; y < height; y++) {
            // Rows are stride bytes apart, which can be more than widthBytes if the decoder pads each row; only
            // the visible pixels are touched. The absolute get/put forms are used so the buffer's position is not
            // moved, leaving the frame exactly as the converter expects to find it.
            int rowStart = y * stride;
            pixels.get(rowStart, row, 0, widthBytes);
 
            switch (fusedFrameInfo.shape()) {
                // The index 3 and 0 below are the position of the vignette step within these exact sequences.
                case TABLES_BLNWH_TABLES_VIGNETTE -> applyTablesBlnWhTablesVignette(row, widthBytes,
                        fusedFrameInfo, fusedFrameInfo.yLookups()[3][y]);
                case VIGNETTE_TABLES_BLNWH_TABLES -> applyVignetteTablesBlnWhTables(row, widthBytes,
                        fusedFrameInfo, fusedFrameInfo.yLookups()[0][y]);
                default -> {
                    // The general case: any sequence of steps, one pass over the row per step. Still correct
                    // for every combination, just without the extra saving of the specialised loops.
                    for (int k = 0; k < count; k++) {
                        switch (fusedFrameInfo.kind()[k]) {
                            case YelFlick -> applyTables(row, widthBytes, fusedFrameInfo.blue()[k],
                                    fusedFrameInfo.green()[k], fusedFrameInfo.red()[k]);
                            case VIGNETTE -> applyVignette(row, widthBytes, fusedFrameInfo.xLookups()[k],
                                    fusedFrameInfo.yLookups()[k][y]);
                            default -> applyBlnWh(row, widthBytes);
                        }
                    }
                }
            }
 
            pixels.put(rowStart, row, 0, widthBytes);
        }
    }
 
    /**
     * Applies the colour tables, then black and white, then the colour tables again, then the vignette. This is a
     * specialised loop made for exactly that sequence of steps: yellower/flicker tables, black and white,
     * yellower/flicker tables, vignette. All four steps are done for each pixel in one go, keeping the values in
     * local variables, so the row array is read once and written once instead of being rewritten after every step.
     * The result is identical to the general loop.
     */
    private static void applyTablesBlnWhTablesVignette(byte[] row, int length, FusedFrameInfo p, byte[] yLookup) {
        byte[] firstBlue = p.blue()[0], firstGreen = p.green()[0], firstRed = p.red()[0];
        byte[] lastBlue = p.blue()[2], lastGreen = p.green()[2], lastRed = p.red()[2];
        byte[][] xLookups = p.xLookups()[3];
        int x = 0;
        for (int i = 0; i < length; i += 3, x++) {
            // "& 0xff" throughout: Java bytes are signed, so this turns a stored value into the 0..255 index
            // that was meant.
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
 
    /**
     * Applies the vignette, then the colour tables, then black and white, then the colour tables again. Like the
     * loop above, this is a specialised loop made for exactly one sequence of steps, but this time the sequence
     * starts with the vignette: vignette, yellower/flicker tables, black and white, yellower/flicker tables. Each
     * pixel goes through all four steps in one go, so the row is read once and written once. The result is
     * identical to the general loop.
     */
    private static void applyVignetteTablesBlnWhTables(byte[] row, int length, FusedFrameInfo p, byte[] yLookup) {
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
 
    // Replaces each channel value by its table entry. Used for the (possibly merged) colour and flicker steps.
    private static void applyTables(byte[] row, int length, byte[] blue, byte[] green, byte[] red) {
        for (int i = 0; i < length; i += 3) {
            row[i] = blue[row[i] & 0xff];
            row[i + 1] = green[row[i + 1] & 0xff];
            row[i + 2] = red[row[i + 2] & 0xff];
        }
    }
 
    // Applies the black and white effect to a row. For each pixel (three bytes: blue, green, red) it works out
    // one brightness value, the luminance, as a weighted sum of the three channels. 
    private static void applyBlnWh(byte[] row, int length) {
        for (int i = 0; i < length; i += 3) {
            int luminance = (54 * (row[i + 2] & 0xff) + 183 * (row[i + 1] & 0xff) + 19 * (row[i] & 0xff)) / 256;
            byte grey = (byte) luminance;
            row[i] = grey;
            row[i + 1] = grey;
            row[i + 2] = grey;
        }
    }
 
    // Applies the vignette to a row. The vignette darkens the picture towards the edges, and how much a pixel is
    // darkened depends only on where it is, so the work was already done in advance as two sets of tables: one
    // table for every column (xLookups, picked with x, the pixel's column) and one table for this row (yLookup,
    // which the caller picks for the current y).
    private static void applyVignette(byte[] row, int length, byte[][] xLookups, byte[] yLookup) {
        int x = 0;
        for (int i = 0; i < length; i += 3, x++) {
            byte[] xLookup = xLookups[x];
            row[i] = yLookup[xLookup[row[i] & 0xff] & 0xff];
            row[i + 1] = yLookup[xLookup[row[i + 1] & 0xff] & 0xff];
            row[i + 2] = yLookup[xLookup[row[i + 2] & 0xff] & 0xff];
        }
    }
 
    /**
     * Turns the list of steps into the FusedFrameInfo for one frame. Neighbouring table steps (colour tint,
     * flicker) are merged into a single table per channel, so two or three lookups per value become one. Black and
     * white and the vignette cannot be merged into a table: black and white mixes the three channels together and
     * the vignette depends on where the pixel is, so each of them ends the current merged table and is kept as its
     * own step.
     */
    private FusedFrameInfo resolve(int frameNumber) {
        // A FusedFrameInfo never has more steps than the list it came from (merging only shortens it), so these
        // arrays are always big enough.
        int capacity = steps.size();
        int[] kind = new int[capacity];
        byte[][] blue = new byte[capacity][];
        byte[][] green = new byte[capacity][];
        byte[][] red = new byte[capacity][];
        byte[][][] xLookups = new byte[capacity][][];
        byte[][][] yLookups = new byte[capacity][][];
        int count = 0;
        // The merged table built up so far from consecutive table steps (null if there is none yet).
        byte[] pendingBlue = null;
        byte[] pendingGreen = null;
        byte[] pendingRed = null;
 
        // Go through the steps in the order the user chose. When a step is a yellower step or a flicker step, its
        // three 256-entry tables (blue, green, red) are taken (for flicker, built fresh for this frame) and
        // combined with the tables already collected from the steps just before it. Each channel's tables are
        // combined into a single 256-entry table, so a run of yellower and flicker steps ends up as one
        // blue table, one green table and one red table. Any other kind of step ends the run of tables, which
        // is handled further down.
        for (Step step : steps) {
            byte[] tableBlue = null;
            byte[] tableGreen = null;
            byte[] tableRed = null;
            if (step instanceof YellowerStep yellower) {
                tableBlue = yellower.blue();
                tableGreen = yellower.green();
                tableRed = yellower.red();
            } else if (step instanceof FlickerStep flicker) {
                // Flicker dims every channel equally, so one table serves all three.
                tableBlue = flickerTable(flicker.mediaSeed(), frameNumber);
                tableGreen = tableBlue;
                tableRed = tableBlue;
            }
 
            if (tableBlue != null) {
                // A table step: merge it into the pending table, or start one.
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
 
            // This step is not a table step (it is black and white or the vignette), so it cannot be merged into
            // the table collected so far. If there is a merged table waiting, it came earlier in the chain, so it
            // is finished now: "emitted" means written into the next free slot (count) of the arrays, labelled
            // YelFlick, and count moves on to the next slot. The pending variables are then cleared so that a
            // fresh merged table can start if more table steps follow.
            if (pendingBlue != null) {
                kind[count] = YelFlick;
                blue[count] = pendingBlue;
                green[count] = pendingGreen;
                red[count] = pendingRed;
                count++;
                pendingBlue = null;
                pendingGreen = null;
                pendingRed = null;
            }
 
            // Now write this step itself into the next free slot, as an operation of its own. A vignette step is
            // labelled VIGNETTE and its column and row tables are stored in that slot. Anything else here must be
            // black and white, which is just labelled BlnWh because it needs no data. Then count moves on.
            if (step instanceof VignetteStep vignette) {
                kind[count] = VIGNETTE;
                xLookups[count] = vignette.xLookups();
                yLookups[count] = vignette.yLookups();
            } else {
                kind[count] = BlnWh;
            }
            count++;
        }
 
        // The loop has finished, but the last steps in the chain may have been table steps, in which case their
        // merged table is still waiting (nothing came after it to trigger writing it out above). It belongs at the
        // end of the FusedFrameInfo, so it is written into the next free slot here.
        if (pendingBlue != null) {
            kind[count] = YelFlick;
            blue[count] = pendingBlue;
            green[count] = pendingGreen;
            red[count] = pendingRed;
            count++;
        }
        return new FusedFrameInfo(count, shapeOf(count, kind), kind, blue, green, red, xLookups, yLookups);
    }
 
    // Picks the specialised loop if the (already merged) steps are exactly one of the two sequences that have one;
    // otherwise the general loop is used. Matching the whole sequence is what makes the specialised loops safe:
    // they hard-code which table sits where.
    private static int shapeOf(int count, int[] kind) {
        if (count == 4 && kind[0] == YelFlick && kind[1] == BlnWh && kind[2] == YelFlick && kind[3] == VIGNETTE) {
            return TABLES_BLNWH_TABLES_VIGNETTE;
        }
        if (count == 4 && kind[0] == VIGNETTE && kind[1] == YelFlick && kind[2] == BlnWh && kind[3] == YelFlick) {
            return VIGNETTE_TABLES_BLNWH_TABLES;
        }
        return GENERAL;
    }
 
    // Combines two 256-entry tables into one table that gives the same result as applying 'first' and then
    // 'second'. For each possible input value it looks up what 'first' turns it into, then looks that result up in
    // 'second', and stores the final answer. So if first turns 200 into 150 and second turns 150 into 120, the
    // combined table turns 200 straight into 120. The order matters: compose(a, b) is not the same as compose(b, a).
    private static byte[] compose(byte[] first, byte[] second) {
        byte[] combined = new byte[256];
        for (int value = 0; value < 256; value++) {
            combined[value] = second[first[value] & 0xff];
        }
        return combined;
    }
 
    // The brightness table for one frame. The random generator is seeded from the media seed plus the frame
    // number, so a given frame always flickers by the same amount no matter which worker thread processes it, in
    // what order, or whether the user seeks back to it. 
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
 
    /**
     * Reads a private field from one of the effect classes. Reflection is used so this optimisation can live in
     * one file, without editing the four effect classes just to expose their tables. The cost is that the field
     * names are plain strings, so renaming one of them fails when the video is opened, not when the code is
     * compiled. fuse() catches that failure and falls back to running the effects unfused.
     * @throws ReflectiveOperationException
     */
    private static Object read(Object owner, String fieldName) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(owner);
    }
 
    // Same as read(), for a static constant.
    private static Object readStatic(Class<?> owner, String fieldName) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }
}