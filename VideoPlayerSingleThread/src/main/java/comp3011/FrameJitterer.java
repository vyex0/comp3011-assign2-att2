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
import java.util.Random;

import org.bytedeco.javacv.Frame;

/**
 * Randomly offsets each image frame to simulate unstable film transport. The sprockets driving film through old
 * projectors always had a tiny bit of slack or take-up in them, causing frames to jitter up and down or left and right
 * slightly as they went through the projection bed. Simple but effective little effect.
 *
 * <p>
 * A deterministic per-frame random sequence chooses horizontal and vertical
 * offsets. {@link #jitterFrame(ByteBuffer, int, int)} copies the original pixels
 * into the shifted position and fills exposed areas with black.
 * </p>
 * 
 * 
 * <p>
 * Thread safety: {@link VideoPlayerModel} processes several frames at once, each on a different worker thread.
 * The copy of the unshifted frame that the shifted frame is rebuilt from is therefore per-thread scratch space
 * (see {@link #sourceScratch(int)}) rather than a field shared by every worker.
 * </p>
 */
public class FrameJitterer extends FrameProcessor {
    private static final int SEED_SALT = 0x4a495454;

    private static int nextInstanceIndex;

    private final int instanceIndex;
    private int mediaSeed;

    // A row of zeroes used to fill the edges that are empty due to the shift. Built once in initialise()
    // and only ever read afterwards so it is safe to share between worker threads.
    private byte[] blackRow;
    
    // Per-thread copy of the unshifted frame. The shifted frame is rebuilt from this copy, so a
    // buffer shared between workers would let one frame be drawn from another frame's pixels.
    private final ThreadLocal<byte[]> sourceScratch = new ThreadLocal<>();

    public FrameJitterer() {
        super();
        instanceIndex = nextInstanceIndex++;
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        mediaSeed = saltedMediaSeed(videoInfo.mediaName(), instanceIndex + SEED_SALT);

        // Read-only after this point, so every worker can share it.
        blackRow = new byte[videoInfo.imageWidth() * videoInfo.imageChannels()];
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameJitterer requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameJitterer requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FrameJitterer requires at least one image channel");
        }

        Random random = new Random(seedForFrame(info.frameNumber()));
        int yOffset = chooseVerticalOffset(random);
        int xOffset = chooseHorizontalOffset(random);

        if (xOffset == 0 && yOffset == 0) {
            return;
        }

        jitterFrame(pixels, xOffset, yOffset);
    }

    /**
     * Return this thread's scratch copy buffer, creating it on first use on this thread or when the video's
     * frame size changes. The buffer is fully overwritten for every frame, so it never carries data over.
     */
    private byte[] sourceScratch(int frameBytes) {
    	byte[] buffer = sourceScratch.get();
    	if (buffer == null || buffer.length != frameBytes) {
    		buffer = new byte[frameBytes];
    		sourceScratch.set(buffer);
    	}
    	return buffer;
    }

    private long seedForFrame(int frameNumber) {
        long seed = Integer.toUnsignedLong(mediaSeed) + Integer.toUnsignedLong(frameNumber);

        // Mix adjacent frame numbers so Random's first samples are not correlated
        // across consecutive frames.
        seed ^= seed >>> 30;
        seed *= 0xbf58476d1ce4e5b9L;
        seed ^= seed >>> 27;
        seed *= 0x94d049bb133111ebL;
        seed ^= seed >>> 31;
        return seed;
    }

    private int chooseVerticalOffset(Random random) {
        double sample = random.nextDouble();
        if (sample < 0.02) {
            return -2;
        }
        if (sample < 0.10) {
            return -1;
        }
        if (sample < 0.90) {
            return 0;
        }
        if (sample < 0.98) {
            return 1;
        }
        return 2;
    }

    private int chooseHorizontalOffset(Random random) {
        double sample = random.nextDouble();
        if (sample < 0.02) {
            return -1;
        }
        if (sample < 0.98) {
            return 0;
        }
        return 1;
    }

    private void jitterFrame(ByteBuffer pixels, int xOffset, int yOffset) {
        int height = videoInfo.imageHeight();
        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();
        int frameBytes = stride * height;
        int widthBytes = width * channels;
        
        byte[] sourcePixels = sourceScratch(frameBytes);

        /**
         * Absolute get/put calls are used rather than duplicate() because they take an explicit index, so they allocate
         * nothing and leave the buffer's position and limit untouched for the code that uses the frame next.
         * This is because duplicate() allocates a new small object every time it's called, and we're calling it once per row
         * which is roughly 800 rows per frame. get/put is used to improve the performance.
         */
        // Snapshots the unshifted frame, then blank the real frame and copy the snapshot
        // back in at the jittered offset.
        pixels.get(0, sourcePixels, 0, frameBytes);
        
        for (int y = 0; y < height; y++) {
        	pixels.put(y * stride, blackRow, 0, widthBytes);
        }
        
        int sourceYStart = Math.max(0, -yOffset);
        int destinationYStart = Math.max(0, yOffset);
        int rowsToCopy = height - Math.abs(yOffset);

        int sourceXStart = Math.max(0, -xOffset);
        int destinationXStart = Math.max(0, xOffset);
        int pixelsToCopy = width - Math.abs(xOffset);

        int sourceXByteStart = sourceXStart * channels;
        int destinationXByteStart = destinationXStart * channels;
        int rowBytesToCopy = pixelsToCopy * channels;

        for (int row = 0; row < rowsToCopy; row++) {
            int sourceIndex = (sourceYStart + row) * stride + sourceXByteStart;
            int destinationIndex = (destinationYStart + row) * stride + destinationXByteStart;
            pixels.put(destinationIndex, sourcePixels, sourceIndex, rowBytesToCopy);
        }
    }
}
