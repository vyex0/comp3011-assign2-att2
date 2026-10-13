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
 * Warms frame colours to simulate a lower colour-temperature film image. This is not really a film stock thing, more an
 * old projector thing. Definitely gives playback a more ye olde look.
 *
 * <p>
 * Initialisation converts the source and destination colour temperatures to
 * RGB scales and builds channel lookup tables. {@link #yellowFrame(ByteBuffer)}
 * applies those tables to every BGR pixel.
 * </p>
 */
public class FrameYellower extends FrameProcessor {
    private static final double SOURCE_KELVIN = 3200.0;
    private static final double DESTINATION_KELVIN = 2700.0;

    private byte[] redLookup;
    private byte[] greenLookup;
    private byte[] blueLookup;

    public FrameYellower() {
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
            throw new Exception("FrameYellower requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameYellower requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameYellower requires at least three image channels");
        }

        yellowFrame(pixels);
    }

    private void buildLookups() {
        // Approximate each colour temperature as the RGB colour of a black-body radiator.
        Rgb sourceWhite = kelvinToRgb(SOURCE_KELVIN);
        Rgb destinationWhite = kelvinToRgb(DESTINATION_KELVIN);

        // Use a diagonal white-balance transform: each channel is scaled by the ratio
        // between the destination white point and the source white point.
        double redScale = destinationWhite.red() / sourceWhite.red();
        double greenScale = destinationWhite.green() / sourceWhite.green();
        double blueScale = destinationWhite.blue() / sourceWhite.blue();

        redLookup = buildLookup(redScale);
        greenLookup = buildLookup(greenScale);
        blueLookup = buildLookup(blueScale);
    }

    private byte[] buildLookup(double scale) {
        byte[] lookup = new byte[256];
        for (int value = 0; value < lookup.length; value++) {
            lookup[value] = (byte) clamp((int) Math.round(value * scale), 0, 255);
        }
        return lookup;
    }

    private Rgb kelvinToRgb(double kelvin) {
        // Empirical Kelvin-to-RGB approximation. Dividing by 100 is part of the
        // standard curve fit, whose branches approximate red-hot to blue-white light.
        double temperature = kelvin / 100.0;
        double red;
        double green;
        double blue;

        if (temperature <= 66.0) {
            red = 255.0;
            green = 99.4708025861 * Math.log(temperature) - 161.1195681661;
            blue = temperature <= 19.0
                    ? 0.0
                    : 138.5177312231 * Math.log(temperature - 10.0) - 305.0447927307;
        } else {
            red = 329.698727446 * Math.pow(temperature - 60.0, -0.1332047592);
            green = 288.1221695283 * Math.pow(temperature - 60.0, -0.0755148492);
            blue = 255.0;
        }

        return new Rgb(
                clamp(red, 0.0, 255.0),
                clamp(green, 0.0, 255.0),
                clamp(blue, 0.0, 255.0));
    }

    private void yellowFrame(ByteBuffer pixels) {
        int height = videoInfo.imageHeight();
        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;
            for (int x = 0; x < width; x++) {
                int base = rowStart + x * channels;
                // JavaCV supplies these decoded frames in BGR byte order.
                pixels.put(base, blueLookup[Byte.toUnsignedInt(pixels.get(base))]);
                pixels.put(base + 1, greenLookup[Byte.toUnsignedInt(pixels.get(base + 1))]);
                pixels.put(base + 2, redLookup[Byte.toUnsignedInt(pixels.get(base + 2))]);
            }
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private record Rgb(
            double red,
            double green,
            double blue) {
    }
}
