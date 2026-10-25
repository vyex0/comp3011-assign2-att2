/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Darkens image edges to create a vignette. Older projectors struggle to provide uniform illumination over the full
 * projection bed, often showing fall away at the edges. This effect nicely simulates this.
 *
 * <p>
 * Initialisation builds Gaussian-like fading lookup tables for each axis.
 * {@link #vignetteFrame(ByteBuffer)} combines the horizontal and vertical
 * factors to scale each pixel's colour channels.
 * </p>
 */
public class FrameVignetter extends FrameProcessor {
    private static final double EDGE_FADE_PERCENTAGE = 40.0;

    private byte[][] xLookups;
    private byte[][] yLookups;

    public FrameVignetter() {
        super();
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        buildLookups();
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameVignetter requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameVignetter requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FrameVignetter requires at least one image channel");
        }

        vignetteFrame(pixels);
    }

    private void buildLookups() {
        xLookups = buildAxisLookups(videoInfo.imageWidth());
        yLookups = buildAxisLookups(videoInfo.imageHeight());
    }

    private byte[][] buildAxisLookups(int size) {
        byte[][] lookups = new byte[size][256];
        double[] envelope = buildGaussianEnvelope(size);

        for (int position = 0; position < size; position++) {
            for (int value = 0; value < 256; value++) {
                lookups[position][value] = (byte) clamp(
                        (int) Math.round(value * envelope[position]),
                        0,
                        255);
            }
        }

        return lookups;
    }

    private double[] buildGaussianEnvelope(int size) {
        double[] envelope = new double[size];
        if (size <= 0) {
            return envelope;
        }
        if (size <= 1) {
            envelope[0] = 1.0;
            return envelope;
        }

        double edgeScale = 1.0 - EDGE_FADE_PERCENTAGE / 100.0;
        edgeScale = clamp(edgeScale, 0.0001, 1.0);

        double centre = (size - 1) / 2.0;
        if (edgeScale == 1.0) {
            for (int position = 0; position < size; position++) {
                envelope[position] = 1.0;
            }
            return envelope;
        }

        // Gaussian envelope: exp(-d^2 / (2 sigma^2)).
        // Choose sigma so that the centre of each edge has the requested fade.
        double sigma = centre / Math.sqrt(-2.0 * Math.log(edgeScale));

        for (int position = 0; position < size; position++) {
            double distance = position - centre;
            envelope[position] = Math.exp(-(distance * distance) / (2.0 * sigma * sigma));
        }

        return envelope;
    }

    private void vignetteFrame(ByteBuffer pixels) {
        int height = videoInfo.imageHeight();
        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;
            byte[] yLookup = yLookups[y];

            for (int x = 0; x < width; x++) {
                int base = rowStart + x * channels;
                byte[] xLookup = xLookups[x];

                for (int channel = 0; channel < channels; channel++) {
                    int index = base + channel;
                    int value = Byte.toUnsignedInt(pixels.get(index));
                    value = Byte.toUnsignedInt(xLookup[value]);
                    pixels.put(index, yLookup[value]);
                }
            }
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
