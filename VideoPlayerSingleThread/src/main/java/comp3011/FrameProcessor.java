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

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;

/**
 * Base class for all the video player's frame-based compositing effects.
 *
 * <p>
 * {@link VideoPlayerModel} initialises each processor once with video metadata,
 * then invokes {@link #process(Frame, InfoFrame)} for every image frame in a set order
 * before it is converted for display. Note that all FrameProcessors should be written
 * to be deterministic - that is they apply the same effect to the same frame number of
 * the same media name, but otherwise can look 'random'. This allows for the reliable
 * determination of accurately applied effects in the correct order free of any race
 * conditions, frame overwrites or frame tearing etc. Some helper methods are provided
 * for this.
 * </p>
 */
public abstract class FrameProcessor {
    protected InfoVideo videoInfo;

    public FrameProcessor() {
    }

    public void initialise(InfoVideo videoInfo) throws Exception {
        this.videoInfo = videoInfo;
    }

    // Obtain a unique seed for this media name and effect instance index. That is, for a given media name, say
    // 'movie.mp4' and a given position in the list of effects being applied, this will return a unique and different
    // seed for each effect. This is useful for creating the illusion of randomness whilst keeping everything
    // deterministic.
    protected int saltedMediaSeed(String mediaName, int instanceIndex) {
        return hashMediaName(mediaName) + instanceIndex;
    }

    private int hashMediaName(String mediaName) {
        CRC32 crc = new CRC32();
        byte[] bytes = mediaName.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    public abstract void process(Frame frame, InfoFrame info) throws Exception;
}
