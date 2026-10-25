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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bytedeco.javacv.Frame;

/**
 * Draws moving vertical scratches to simulate damaged film stock. Some scratches are one pixel wide, some are two. They
 * move at different rates horizontally and come and go from the film stock.
 *
 * <p>
 * {@link #generateScratches()} creates a deterministic set of scratches over
 * the video timeline. {@link #process(Frame, InfoFrame)} finds the scratch
 * segment visible in the current frame and draws it as a black vertical line.
 * </p>
 * 
 * <p>
 * Thread safety: {@link VideoPlayerModel} processes several frames at once, each on a different worker thread.
 * The scratch timeline is built once per run in {@link #initialise(InfoVideo)} and only read afterwards. It is held as
 * an immutable list that is replaced as a whole, never modified in place, because {@code initialise} runs again after 
 * every restart while a worker may still be finishing a frame from the previous run.
 * </p>
 */
public class FrameScratcher extends FrameProcessor {
    private static final int FRAMES_PER_NEW_SCRATCH = 48;
    private static final double MEAN_SCRATCH_LENGTH = 96;
    private static final double THICK_SCRATCH_PROBABILITY = 0.15;
    private static final int SCRATCH_MAX_TRAVEL = 32;

    private static int nextInstanceIndex;

    private final int instanceIndex;
    private int mediaSeed;
    
    // The scratch list for this run, it is replaced with one volatile write rather than cleared and refilled.
    // initialise() runs again on every restart while a worker may still be finishing a frame from the
    // previous run, and clearing a list another thread is iterating on would throw ConcurrentModificationException.
    private volatile List<Scratch> scratches = List.of();

    public FrameScratcher() {
        super();
        instanceIndex = nextInstanceIndex++;
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        mediaSeed = saltedMediaSeed(videoInfo.mediaName(), instanceIndex);
        generateScratches();
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameScratcher requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameScratcher requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FrameScratcher requires at least one image channel");
        }

        double frameStart = info.frameNumber();
        double frameEnd = frameStart + 1.0;
        
        // Take one snapshot of the scratch list and use it for the whole frame, so a restart that publishes
        // a new list part-way through cannot give this frame a mixture of two lists.
        List<Scratch> current = scratches;

        for (Scratch scratch : current) {
            double scratchStart = Math.max(scratch.startFrame(), frameStart);
            double scratchEnd = Math.min(scratch.endFrame(), frameEnd);

            if (scratchStart < scratchEnd) {
                int yStart = frameOffsetToY(scratchStart - frameStart);
                int yEnd = frameOffsetToYCeiling(scratchEnd - frameStart);
                int x = scratchXAtFrameStart(scratch, frameStart);
                drawVerticalLine(pixels, x, yStart, yEnd, scratch.thick());
            }
        }
    }

    private void generateScratches() {
    	// Nothing to scratch (no frames or no width): publish an empty list rather than leaving a stale one.
        if (videoInfo.totalVideoFrames() <= 0 || videoInfo.imageWidth() <= 0) {
        	scratches = List.of();
        	return;
        }
        
        List<Scratch> built = new ArrayList<>();

        Random random = new Random(mediaSeed);
        double scratchStartProbability = 1.0 / FRAMES_PER_NEW_SCRATCH;

        for (int frameNumber = 0; frameNumber < videoInfo.totalVideoFrames(); frameNumber++) {
            if (random.nextDouble() < scratchStartProbability) {
                double startFrame = frameNumber;
                double scratchLength = nextExponential(random, MEAN_SCRATCH_LENGTH);
                double endFrame = Math.min(videoInfo.totalVideoFrames(), startFrame + scratchLength);
                int xStart = random.nextInt(videoInfo.imageWidth());
                int xEnd = xStart + randomScratchTravel(random);
                boolean thick = random.nextDouble() < THICK_SCRATCH_PROBABILITY;

                xEnd = clamp(xEnd, 0, videoInfo.imageWidth() - 1);
                built.add(new Scratch(startFrame, endFrame, xStart, xEnd, thick));
            }
        }
        
        // Built completely into a local list first, then published with a single volatile write, so another
        // thread sees either the old timeline or the new one, never a half-filled list. copyOf also makes it
        // immutable, which makes it safe to share.
        scratches = List.copyOf(built);
    }

    private double nextExponential(Random random, double mean) {
        return -mean * Math.log(1.0 - random.nextDouble());
    }

    private int frameOffsetToY(double frameOffset) {
        int y = (int) Math.floor(frameOffset * videoInfo.imageHeight());
        return clamp(y, 0, videoInfo.imageHeight());
    }

    private int frameOffsetToYCeiling(double frameOffset) {
        int y = (int) Math.ceil(frameOffset * videoInfo.imageHeight());
        return clamp(y, 0, videoInfo.imageHeight());
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private int randomScratchTravel(Random random) {
        return random.nextInt(2 * SCRATCH_MAX_TRAVEL + 1) - SCRATCH_MAX_TRAVEL;
    }

    private int scratchXAtFrameStart(Scratch scratch, double frameStart) {
        double scratchDuration = scratch.endFrame() - scratch.startFrame();
        if (scratchDuration <= 0) {
            return scratch.xStart();
        }

        double progress = (frameStart - scratch.startFrame()) / scratchDuration;
        progress = Math.max(0.0, Math.min(progress, 1.0));
        double x = scratch.xStart() + progress * (scratch.xEnd() - scratch.xStart());

        return clamp((int) Math.round(x), 0, videoInfo.imageWidth() - 1);
    }

    private void drawVerticalLine(ByteBuffer pixels, int x, int yStart, int yEnd, boolean thick) {
        drawSinglePixelVerticalLine(pixels, x, yStart, yEnd);

        if (thick && videoInfo.imageWidth() > 1) {
            int secondX = x + 1 < videoInfo.imageWidth()
                    ? x + 1
                    : x - 1;
            drawSinglePixelVerticalLine(pixels, secondX, yStart, yEnd);
        }
    }

    private void drawSinglePixelVerticalLine(ByteBuffer pixels, int x, int yStart, int yEnd) {
        for (int y = yStart; y < yEnd; y++) {
            setPixel(pixels, x, y, 0);
        }
    }

    private void setPixel(ByteBuffer pixels, int x, int y, int value) {
        int base = y * videoInfo.imageStride() + x * videoInfo.imageChannels();
        byte byteValue = (byte) value;

        for (int channel = 0; channel < videoInfo.imageChannels(); channel++) {
            pixels.put(base + channel, byteValue);
        }
    }

    private record Scratch(
            double startFrame,
            double endFrame,
            int xStart,
            int xEnd,
            boolean thick) {
    }
}
