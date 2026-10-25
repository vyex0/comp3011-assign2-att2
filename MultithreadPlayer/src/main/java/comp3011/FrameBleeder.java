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

import org.bytedeco.javacv.Frame;

/**
 * Simulates bright light bleeding into nearby pixels, with a warm colour bias.
 * This one is expensive in CPU time!
 * 
 * <p>
 * For each frame, {@link #buildBrightMask(ByteBuffer, double[])} identifies bright regions,
 * {@link #blurHorizontally(double[], double[])} and {@link #blurVertically(double[], double[])}
 * spread their influence and {@link #applyBleed(ByteBuffer, double[])} adds the resulting coloured glow.
 * </p>
 * 
 * <p>
 * Thread safety: {@link VideoPlayerModel} processes several frames at once, each on a different worker thread.
 * Now, the three mask arrays: brightMask, horizontalBlurMask and blurredMask that were once instance arrays, use
 * a per-thread scratch space (see {@link #scratchBuffers()}) rather than fields shared by every threads worker.
 * The sampling lookups {@link #initialise(InfoVideo)} are only read afterwards.
 * </p>
 */
public class FrameBleeder extends FrameProcessor {
    private static final int MASK_BLOCK_SIZE = 4;
    private static final int BLUR_RADIUS = 3;
    private static final int BLEED_LUMINANCE_THRESHOLD = 150;
    private static final double BLEED_GAMMA = 1.25;
    private static final double BLEED_MASK_GAIN = 2.4;
    private static final double RED_BLEED_STRENGTH = 105.0;
    private static final double GREEN_BLEED_STRENGTH = 62.0;
    private static final double BLUE_BLEED_STRENGTH = 18.0;

    private int maskWidth;
    private int maskHeight;
    private int[] xMaskLeft;
    private double[] xMaskBlend;
    private int[] yMaskTop;
    private double[] yMaskBlend;
    
    // One set of scratch mask per worker thread. ThreadLocal means no locking and no per-frame allocation.
    private final ThreadLocal<MaskBuffers> scratch = new ThreadLocal<>();

    public FrameBleeder() {
        super();
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);

        maskWidth = (videoInfo.imageWidth() + MASK_BLOCK_SIZE - 1) / MASK_BLOCK_SIZE;
        maskHeight = (videoInfo.imageHeight() + MASK_BLOCK_SIZE - 1) / MASK_BLOCK_SIZE;
        buildSamplingLookups();
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameBleeder requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameBleeder requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameBleeder requires at least three image channels");
        }

        MaskBuffers buffers = scratchBuffers();
        buildBrightMask(pixels, buffers.bright());
        blurHorizontally(buffers.bright(), buffers.horizontal());
        blurVertically(buffers.horizontal(), buffers.blurred());
        applyBleed(pixels, buffers.blurred());
    }
    
    /**
     * Return this thread's scratch masks, creating them on first use on this thread or when the mask size changes.
     * Each stage rewrites every cell of its output on every frame, so a reused buffer never holds data from the
     * previous frame.
     */
    private MaskBuffers scratchBuffers() {
    	int size = maskWidth * maskHeight;
    	MaskBuffers buffers = scratch.get();
    	if (buffers == null || buffers.bright().length != size) {   // first use on this thread, or video size changed
            buffers = new MaskBuffers(new double[size], new double[size], new double[size]);
            scratch.set(buffers);
        }
        return buffers;
    }

    private void buildSamplingLookups() {
        xMaskLeft = new int[videoInfo.imageWidth()];
        xMaskBlend = new double[videoInfo.imageWidth()];
        yMaskTop = new int[videoInfo.imageHeight()];
        yMaskBlend = new double[videoInfo.imageHeight()];

        buildAxisSamplingLookups(videoInfo.imageWidth(), maskWidth, xMaskLeft, xMaskBlend);
        buildAxisSamplingLookups(videoInfo.imageHeight(), maskHeight, yMaskTop, yMaskBlend);
    }

    private void buildAxisSamplingLookups(int imageSize, int maskSize, int[] maskStarts, double[] maskBlends) {
        if (imageSize <= 1 || maskSize <= 1) {
            for (int position = 0; position < imageSize; position++) {
                maskStarts[position] = 0;
                maskBlends[position] = 0.0;
            }
            return;
        }

        for (int position = 0; position < imageSize; position++) {
            double maskPosition = (position + 0.5) / MASK_BLOCK_SIZE - 0.5;
            maskPosition = clamp(maskPosition, 0.0, maskSize - 1.0);

            int maskStart = (int) Math.floor(maskPosition);
            if (maskStart >= maskSize - 1) {
                maskStart = maskSize - 2;
                maskPosition = maskSize - 1;
            }

            maskStarts[position] = maskStart;
            maskBlends[position] = maskPosition - maskStart;
        }
    }

    private void buildBrightMask(ByteBuffer pixels, double[] brightMask) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int maskY = 0; maskY < maskHeight; maskY++) {
            int blockY = maskY * MASK_BLOCK_SIZE;
            int blockYEnd = Math.min(blockY + MASK_BLOCK_SIZE, height);

            for (int maskX = 0; maskX < maskWidth; maskX++) {
                int blockX = maskX * MASK_BLOCK_SIZE;
                int blockXEnd = Math.min(blockX + MASK_BLOCK_SIZE, width);
                int maximumLuminance = 0;

                for (int y = blockY; y < blockYEnd; y++) {
                    int rowStart = y * stride;
                    for (int x = blockX; x < blockXEnd; x++) {
                        int base = rowStart + x * channels;
                        // JavaCV supplies these decoded frames in BGR byte order.
                        int b = Byte.toUnsignedInt(pixels.get(base));
                        int g = Byte.toUnsignedInt(pixels.get(base + 1));
                        int r = Byte.toUnsignedInt(pixels.get(base + 2));
                        int luminance = (54 * r + 183 * g + 19 * b) / 256;
                        maximumLuminance = Math.max(maximumLuminance, luminance);
                    }
                }

                brightMask[maskY * maskWidth + maskX] = thresholdedBrightness(maximumLuminance);
            }
        }
    }

    private double thresholdedBrightness(int luminance) {
        if (luminance <= BLEED_LUMINANCE_THRESHOLD) {
            return 0.0;
        }

        double normalised = (luminance - BLEED_LUMINANCE_THRESHOLD) / (double) (255 - BLEED_LUMINANCE_THRESHOLD);
        return Math.pow(normalised, BLEED_GAMMA);
    }

    private void blurHorizontally(double[] source, double[] destination) {
        for (int y = 0; y < maskHeight; y++) {
            int rowStart = y * maskWidth;
            for (int x = 0; x < maskWidth; x++) {
                double total = 0.0;
                int samples = 0;

                for (int dx = -BLUR_RADIUS; dx <= BLUR_RADIUS; dx++) {
                    int sampleX = clamp(x + dx, 0, maskWidth - 1);
                    total += source[rowStart + sampleX];
                    samples++;
                }

                destination[rowStart + x] = total / samples;
            }
        }
    }

    private void blurVertically(double[] source, double[] destination) {
        for (int y = 0; y < maskHeight; y++) {
            for (int x = 0; x < maskWidth; x++) {
                double total = 0.0;
                int samples = 0;

                for (int dy = -BLUR_RADIUS; dy <= BLUR_RADIUS; dy++) {
                    int sampleY = clamp(y + dy, 0, maskHeight - 1);
                    total += source[sampleY * maskWidth + x];
                    samples++;
                }

                destination[y * maskWidth + x] = total / samples;
            }
        }
    }

    private void applyBleed(ByteBuffer pixels, double[] blurredMask) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;

            for (int x = 0; x < width; x++) {
            	double bleedStrength = sampleBlurredMask(blurredMask, x, y);
                if (bleedStrength <= 0.0) {
                    continue;
                }

                int blueBoost = (int) Math.round(bleedStrength * BLUE_BLEED_STRENGTH);
                int greenBoost = (int) Math.round(bleedStrength * GREEN_BLEED_STRENGTH);
                int redBoost = (int) Math.round(bleedStrength * RED_BLEED_STRENGTH);
                int base = rowStart + x * channels;

                pixels.put(base, boostedByte(pixels.get(base), blueBoost));
                pixels.put(base + 1, boostedByte(pixels.get(base + 1), greenBoost));
                pixels.put(base + 2, boostedByte(pixels.get(base + 2), redBoost));
            }
        }
    }

    private double sampleBlurredMask(double[] blurredMask, int x, int y) {
        int x0 = xMaskLeft[x];
        int x1 = Math.min(x0 + 1, maskWidth - 1);
        double xBlend = xMaskBlend[x];

        int y0 = yMaskTop[y];
        int y1 = Math.min(y0 + 1, maskHeight - 1);
        double yBlend = yMaskBlend[y];

        double top = lerp(blurredMask[y0 * maskWidth + x0], blurredMask[y0 * maskWidth + x1], xBlend);
        double bottom = lerp(blurredMask[y1 * maskWidth + x0], blurredMask[y1 * maskWidth + x1], xBlend);
        return Math.min(1.0, lerp(top, bottom, yBlend) * BLEED_MASK_GAIN);
    }

    private byte boostedByte(byte value, int boost) {
        return (byte) Math.min(255, Byte.toUnsignedInt(value) + boost);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private double lerp(double a, double b, double blend) {
        return a + (b - a) * blend;
    }
    
    // Scratch space for one frame's bleed calculation. Each worker thread gets its own copy so frames
    // processed at the same time don't overwrite each other's masks.
    private record MaskBuffers(double[] bright, double[] horizontal, double[] blurred) { }
}
