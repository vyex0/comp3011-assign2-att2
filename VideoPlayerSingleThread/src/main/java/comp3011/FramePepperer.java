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
 * Adds irregular dark spots and blotches to simulate damaged film.
 * Reasonable CPU intensive but this one is a great effect.
 *
 * <p>
 * {@link #process(Frame, InfoFrame)} generates a deterministic,
 * Poisson-distributed number of marks for each frame.
 * {@link #drawPepperShape(ByteBuffer, double, double, double, double, double, double, double, PepperShape)}
 * creates each softly edged, warped elliptical spot.
 * </p>
 */
public class FramePepperer extends FrameProcessor {
    private static final double PEPPER_MARKS_PER_SECOND = 10.0;
    private static final double MIN_PEPPER_SIZE_PERCENTAGE = 0.25;
    private static final double MAX_PEPPER_SIZE_PERCENTAGE = 2.5;
    private static final double MAX_ASPECT_RATIO = 2.2;
    private static final double MIN_OPACITY = 0.45;
    private static final double MAX_OPACITY = 0.95;
    private static final double MIN_EDGE_SOFTNESS = 0.04;
    private static final double MAX_EDGE_SOFTNESS = 0.18;
    private static final double OUT_OF_FOCUS_PROBABILITY = 0.35;
    private static final double OUT_OF_FOCUS_SOFTNESS_MULTIPLIER = 2.8;
    private static final double OFFSCREEN_MARGIN_FACTOR = 0.55;
    private static final double MAX_BOUNDARY_WARP = 0.22;
    private static final int SEED_SALT = 0x50455050;

    private static int nextInstanceIndex;

    private final int instanceIndex;
    private int mediaSeed;

    public FramePepperer() {
        super();
        instanceIndex = nextInstanceIndex++;
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        mediaSeed = saltedMediaSeed(videoInfo.mediaName(), instanceIndex + SEED_SALT);
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FramePepperer requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FramePepperer requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FramePepperer requires at least one image channel");
        }

        if (videoInfo.imageWidth() <= 0 || videoInfo.imageHeight() <= 0) {
            return;
        }

        Random random = new Random(seedForFrame(info.frameNumber()));
        int markCount = nextPoisson(random, pepperMarksPerFrame());
        for (int mark = 0; mark < markCount; mark++) {
            drawRandomPepperMark(pixels, random);
        }
    }

    private double pepperMarksPerFrame() {
        if (videoInfo.frameRate() <= 0.0) {
            return PEPPER_MARKS_PER_SECOND / Math.max(1, videoInfo.intFrameRate());
        }
        return PEPPER_MARKS_PER_SECOND / videoInfo.frameRate();
    }

    private long seedForFrame(int frameNumber) {
        long seed = Integer.toUnsignedLong(mediaSeed) + Integer.toUnsignedLong(frameNumber);

        seed ^= seed >>> 30;
        seed *= 0xbf58476d1ce4e5b9L;
        seed ^= seed >>> 27;
        seed *= 0x94d049bb133111ebL;
        seed ^= seed >>> 31;
        return seed;
    }

    private int nextPoisson(Random random, double mean) {
        double stopThreshold = Math.exp(-mean);
        double product = 1.0;
        int count = 0;

        do {
            count++;
            product *= random.nextDouble();
        } while (product > stopThreshold);

        return count - 1;
    }

    private void drawRandomPepperMark(ByteBuffer pixels, Random random) {
        double baseSize = randomSize(random);
        double aspectRatio = randomBetween(random, 1.0, MAX_ASPECT_RATIO);
        double radiusX;
        double radiusY;
        if (random.nextBoolean()) {
            radiusX = baseSize * aspectRatio;
            radiusY = baseSize;
        } else {
            radiusX = baseSize;
            radiusY = baseSize * aspectRatio;
        }

        double maximumRadius = Math.max(radiusX, radiusY);
        double margin = maximumRadius * OFFSCREEN_MARGIN_FACTOR;
        double centreX = randomBetween(random, -margin, videoInfo.imageWidth() + margin);
        double centreY = randomBetween(random, -margin, videoInfo.imageHeight() + margin);
        double rotation = random.nextDouble() * 2.0 * Math.PI;
        double opacity = randomBetween(random, MIN_OPACITY, MAX_OPACITY);
        double edgeSoftness = randomBetween(random, MIN_EDGE_SOFTNESS, MAX_EDGE_SOFTNESS);
        if (random.nextDouble() < OUT_OF_FOCUS_PROBABILITY) {
            edgeSoftness *= OUT_OF_FOCUS_SOFTNESS_MULTIPLIER;
            opacity *= 0.75;
        }

        PepperShape shape = randomPepperShape(random);
        drawPepperShape(
                pixels,
                centreX,
                centreY,
                radiusX,
                radiusY,
                rotation,
                opacity,
                edgeSoftness,
                shape);
    }

    private double randomSize(Random random) {
        double percentage = randomBetween(random, MIN_PEPPER_SIZE_PERCENTAGE, MAX_PEPPER_SIZE_PERCENTAGE);
        return videoInfo.imageWidth() * percentage / 100.0;
    }

    private PepperShape randomPepperShape(Random random) {
        return new PepperShape(
                random.nextDouble() * 2.0 * Math.PI,
                random.nextDouble() * 2.0 * Math.PI,
                random.nextDouble() * 2.0 * Math.PI,
                randomBetween(random, -MAX_BOUNDARY_WARP, MAX_BOUNDARY_WARP),
                randomBetween(random, -MAX_BOUNDARY_WARP, MAX_BOUNDARY_WARP),
                randomBetween(random, -MAX_BOUNDARY_WARP, MAX_BOUNDARY_WARP));
    }

    private void drawPepperShape(
            ByteBuffer pixels,
            double centreX,
            double centreY,
            double radiusX,
            double radiusY,
            double rotation,
            double opacity,
            double edgeSoftness,
            PepperShape shape) {
        double maximumRadius = Math.hypot(radiusX, radiusY) * (1.0 + MAX_BOUNDARY_WARP + edgeSoftness);
        int minX = clamp((int) Math.floor(centreX - maximumRadius), 0, videoInfo.imageWidth() - 1);
        int maxX = clamp((int) Math.ceil(centreX + maximumRadius), 0, videoInfo.imageWidth() - 1);
        int minY = clamp((int) Math.floor(centreY - maximumRadius), 0, videoInfo.imageHeight() - 1);
        int maxY = clamp((int) Math.ceil(centreY + maximumRadius), 0, videoInfo.imageHeight() - 1);

        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double dx = x + 0.5 - centreX;
                double dy = y + 0.5 - centreY;
                double localX = (dx * cos + dy * sin) / radiusX;
                double localY = (-dx * sin + dy * cos) / radiusY;
                double angle = Math.atan2(localY, localX);
                double distance = Math.sqrt(localX * localX + localY * localY);
                double warpedBoundary = shape.radiusScale(angle);
                double edgeStart = Math.max(0.0, warpedBoundary - edgeSoftness);

                if (distance < warpedBoundary) {
                    double coverage = distance <= edgeStart
                            ? 1.0
                            : (warpedBoundary - distance) / Math.max(0.0001, warpedBoundary - edgeStart);
                    double alpha = opacity * smoothstep(coverage);
                    darkenPixel(pixels, x, y, alpha);
                }
            }
        }
    }

    private void darkenPixel(ByteBuffer pixels, int x, int y, double alpha) {
        int base = y * videoInfo.imageStride() + x * videoInfo.imageChannels();
        double scale = 1.0 - alpha;

        for (int channel = 0; channel < videoInfo.imageChannels(); channel++) {
            int value = Byte.toUnsignedInt(pixels.get(base + channel));
            pixels.put(base + channel, (byte) clamp((int) Math.round(value * scale), 0, 255));
        }
    }

    private double randomBetween(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private double smoothstep(double value) {
        value = clamp(value, 0.0, 1.0);
        return value * value * (3.0 - 2.0 * value);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private record PepperShape(
            double phase2,
            double phase3,
            double phase5,
            double harmonic2,
            double harmonic3,
            double harmonic5) {

        private double radiusScale(double angle) {
            double scale = 1.0
                    + harmonic2 * Math.sin(2.0 * angle + phase2)
                    + harmonic3 * Math.sin(3.0 * angle + phase3)
                    + harmonic5 * Math.sin(5.0 * angle + phase5);
            return Math.max(0.35, scale);
        }
    }
}
