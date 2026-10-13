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
 * Adds dark dust and hair-like marks to simulate damaged film.
 *
 * <p>{@link #process(Frame, InfoFrame)} uses a deterministic, media-specific
 * random sequence and a Poisson-distributed mark count. {@link #drawRandomDustMark(ByteBuffer, Random)}
 * draws each curved mark as a series of softly darkened points.</p>
 */
public class FrameDuster extends FrameProcessor {
    private static final double DUST_MARKS_PER_SECOND = 20.0;
    private static final double MIN_DUST_LENGTH_PERCENTAGE = 1.0;
    private static final double MAX_DUST_LENGTH_PERCENTAGE = 8.0;
    private static final double S_SHAPE_PROBABILITY = 0.35;
    private static final double MIN_BRUSH_RADIUS = 0.75;
    private static final double MAX_BRUSH_RADIUS = 1.35;
    private static final double MIN_OPACITY = 0.45;
    private static final double MAX_OPACITY = 0.85;
    private static final double CURVE_AMOUNT = 0.35;
    private static final double SAMPLE_SPACING_PIXELS = 0.5;
    private static final double VERTICAL_ANGLE_SD_RADIANS = Math.toRadians(28.0);
    private static final double UNIFORM_ANGLE_PROBABILITY = 0.18;
    private static final int SEED_SALT = 0x44555354;

    private static int nextInstanceIndex;

    private final int instanceIndex;
    private int mediaSeed;

    public FrameDuster() {
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
            throw new Exception("FrameDuster requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameDuster requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FrameDuster requires at least one image channel");
        }

        if (videoInfo.imageWidth() <= 0 || videoInfo.imageHeight() <= 0) {
            return;
        }

        Random random = new Random((long) mediaSeed + info.frameNumber());
        int markCount = nextPoisson(random, dustMarksPerFrame());
        for (int mark = 0; mark < markCount; mark++) {
            drawRandomDustMark(pixels, random);
        }
    }

    private double dustMarksPerFrame() {
        if (videoInfo.frameRate() <= 0.0) {
            return DUST_MARKS_PER_SECOND / Math.max(1, videoInfo.intFrameRate());
        }
        return DUST_MARKS_PER_SECOND / videoInfo.frameRate();
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

    private void drawRandomDustMark(ByteBuffer pixels, Random random) {
        double length = randomLength(random);
        double angle = randomAngle(random);
        double x0 = random.nextDouble() * videoInfo.imageWidth();
        double y0 = random.nextDouble() * videoInfo.imageHeight();
        double x3 = x0 + Math.cos(angle) * length;
        double y3 = y0 + Math.sin(angle) * length;

        double normalX = -Math.sin(angle);
        double normalY = Math.cos(angle);
        double bend = signedRandom(random) * length * CURVE_AMOUNT;

        boolean sShape = random.nextDouble() < S_SHAPE_PROBABILITY;
        double x1 = x0 + (x3 - x0) / 3.0 + normalX * bend;
        double y1 = y0 + (y3 - y0) / 3.0 + normalY * bend;
        double x2 = x0 + 2.0 * (x3 - x0) / 3.0 + normalX * (sShape ? -bend : bend);
        double y2 = y0 + 2.0 * (y3 - y0) / 3.0 + normalY * (sShape ? -bend : bend);

        double radius = randomBetween(random, MIN_BRUSH_RADIUS, MAX_BRUSH_RADIUS);
        double opacity = randomBetween(random, MIN_OPACITY, MAX_OPACITY);
        int sampleCount = Math.max(4, (int) Math.ceil(length / SAMPLE_SPACING_PIXELS));

        for (int sample = 0; sample <= sampleCount; sample++) {
            double t = (double) sample / sampleCount;
            drawSoftDarkPoint(
                    pixels,
                    cubicBezier(x0, x1, x2, x3, t),
                    cubicBezier(y0, y1, y2, y3, t),
                    radius,
                    opacity);
        }
    }

    private double randomLength(Random random) {
        double percentage = randomBetween(random, MIN_DUST_LENGTH_PERCENTAGE, MAX_DUST_LENGTH_PERCENTAGE);
        return videoInfo.imageWidth() * percentage / 100.0;
    }

    private double randomAngle(Random random) {
        if (random.nextDouble() < UNIFORM_ANGLE_PROBABILITY) {
            return random.nextDouble() * 2.0 * Math.PI;
        }

        double verticalAxis = random.nextBoolean() ? Math.PI / 2.0 : 3.0 * Math.PI / 2.0;
        return verticalAxis + random.nextGaussian() * VERTICAL_ANGLE_SD_RADIANS;
    }

    private double signedRandom(Random random) {
        return random.nextDouble() * 2.0 - 1.0;
    }

    private double randomBetween(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private double cubicBezier(double p0, double p1, double p2, double p3, double t) {
        double u = 1.0 - t;
        return u * u * u * p0
                + 3.0 * u * u * t * p1
                + 3.0 * u * t * t * p2
                + t * t * t * p3;
    }

    private void drawSoftDarkPoint(ByteBuffer pixels, double centreX, double centreY, double radius, double opacity) {
        int minX = clamp((int) Math.floor(centreX - radius), 0, videoInfo.imageWidth() - 1);
        int maxX = clamp((int) Math.ceil(centreX + radius), 0, videoInfo.imageWidth() - 1);
        int minY = clamp((int) Math.floor(centreY - radius), 0, videoInfo.imageHeight() - 1);
        int maxY = clamp((int) Math.ceil(centreY + radius), 0, videoInfo.imageHeight() - 1);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double dx = x + 0.5 - centreX;
                double dy = y + 0.5 - centreY;
                double distance = Math.sqrt(dx * dx + dy * dy);

                if (distance <= radius) {
                    double coverage = 1.0 - distance / radius;
                    double alpha = opacity * coverage * coverage * (3.0 - 2.0 * coverage);
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
