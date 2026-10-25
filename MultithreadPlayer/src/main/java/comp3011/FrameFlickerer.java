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
import java.util.Random;

import org.bytedeco.javacv.Frame;

/**
 * Varies a frame's brightness to simulate film-projector flicker. Because of slight variations in timing and lamp
 * incandescence, old movie projectors would cause the movies to flicker, hence movies became known as 'flicks'.
 *
 * <p>
 * The media name and frame number seed a deterministic random brightness
 * scale, so the same frame always receives the same variation.
 * {@link #dimFrame(ByteBuffer, double)} applies that scale to each pixel.
 * </p>
 */
public class FrameFlickerer extends FrameProcessor {
    private static final double FLICKER_PERCENTAGE_SD = 5.0;

    private static int nextInstanceIndex;

    private final int instanceIndex;
    private int mediaSeed;

    public FrameFlickerer() {
        super();
        instanceIndex = nextInstanceIndex++;
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        mediaSeed = saltedMediaSeed(videoInfo.mediaName(), instanceIndex);
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameFlickerer requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameFlickerer requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FrameFlickerer requires at least one image channel");
        }

        Random random = new Random((long) mediaSeed + info.frameNumber());
        double dimmingPercentage = Math.abs(random.nextGaussian()) * FLICKER_PERCENTAGE_SD;
        double brightnessScale = Math.max(0.0, 100.0 - dimmingPercentage) / 100.0;

        dimFrame(pixels, brightnessScale);
    }

    private void dimFrame(ByteBuffer pixels, double brightnessScale) {
        byte[] dimLookup = new byte[256];
        for (int value = 0; value < dimLookup.length; value++) {
            dimLookup[value] = (byte) clamp((int) Math.round(value * brightnessScale), 0, 255);
        }

        int height = videoInfo.imageHeight();
        int widthBytes = videoInfo.imageWidth() * videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;
            for (int offset = 0; offset < widthBytes; offset++) {
                int index = rowStart + offset;
                pixels.put(index, dimLookup[Byte.toUnsignedInt(pixels.get(index))]);
            }
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
