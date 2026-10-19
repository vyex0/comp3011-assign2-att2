/**
 * New file for Adelaide University COMP3011 Assignment 2.
 * 
 * 1. Vanessa Soetjiadi, a1945309
 * 
 */

package comp3011;

import java.util.OptionalLong;

/**
 * The playback clock: decides the wall-clock time at which each decoded frame is due on screen, and which
 * media position the audio should have reached.
 * 
 * <p>
 * <b>How it works:</b> The first frame decoded after a start or a seek becomes the anchor (starting point) for playback. It is
 * scheduled to display shortly in the future, and every later frame is due at that start time plus its distance in media
 * time from the anchor. When playback is paused, the pause time is remembered. When playback resumes, the schedule is
 * adjusted to account for the pause, so every frame still queued shifts by the same amount and playback continues
 * exactly where it stopped.
 * </p>
 * 
 * <p>
 * <b>PlaybackClock is its own class because:</b> Two threads use this state at the same time: the decoder thread that asks for
 * a frame's timing ({@link #schedule}), and the JavaFX thread that pauses, resumes, seeks, and checks what is due. The
 * These timing values depend on each other and must stay in sync. If one thread changes them while the other is reading
 * them, a frame would be given a due time that is wrong, which shows up as a frame displayed far too early or never.
 * A {@code volatile} field cannot fix that because it only makes a single value visible, not a group of values consistent.
 * So every access to the fields goes through one lock, and the whole group is updated or read inside it.
 * </p>
 * 
 * <p>
 * <b>This can't deadlock:</b> This class uses only one private lock, its methods do not call other code, wait for
 * anything, or try to acquire another lock while holding it. They only perform a few quick calculations before releasing
 * the lock, this means threads spend very little time waiting for each other, even when the decoder checks every frame and
 * the JavaFX thread checks regularly during playback.
 * </p>
 * 
 * <p>
 * <b>Time is passed in:</b> Methods takes the current {@code System.nanoTime()} as a parameter instead of reading it themselves
 * This makes the code easier to ttest because tests can use specific times without having to wait for real time to pass.
 * </p>
 */
public class PlaybackClock {
	/** A short delay before the first frame is displayed, giving it time to be processed. */
	static final long PRE_ROLL_NS = 150_000_000L;
	
	private static final long NOT_ANCHORED = -1;
	
	/** The timing assigned to one decoded frame. */
	public record FrameTiming(long logicalTimestampUs, long targetTimeNs) {
	}
	
	private final Object lock = new Object();

	// Everything below is guarded by "lock". Never read or write it outside a synchronized (lock) block.
	private long firstTimestampUs = NOT_ANCHORED; // The timestamp of the first frame, or NOT_ANCHORED if playback has not started.
	private long playbackStartNs; // The real-world time when the first frame should be displayed.
	private long logicalPlaybackBaseUs; // The playback position shown to the user when playback starts or seeks.
	
	private boolean pausePending; // True if playback is paused and has not yet resumed.
	private long pauseStartedNs; // The real-world time when playback was paused.
	
	/**
	 * Resets the playback timeline when playback restarts or seeks to a new position. The next frame passed to {@link #schedule}
	 * becomes the new the starting point for timing (new anchor). 
	 * 
	 * @param paused whether playback is paused, so the pause is remembered even if no frame has been scheduled yet.
	 */
	public void reset(long logicalTimestampUs, boolean paused, long nowNs) {
		synchronized (lock) {
			firstTimestampUs = NOT_ANCHORED;
			logicalPlaybackBaseUs = logicalTimestampUs;
			playbackStartNs = 0;
			pausePending = paused;
			pauseStartedNs = nowNs;
		}
	}
	
	/**
	 * Clears the playback timing information (anchor, pause) when playback is closed, so a later restart doesn't use stale
	 * timing. All of it is cleared under the lock, so the decoder never sees a half-cleared clock.
	 */
	public void clear() {
		synchronized (lock) {
			firstTimestampUs = NOT_ANCHORED;
            playbackStartNs = 0;
            pausePending = false;
		}
	}
	
	/**
	 * Decoder thread. Gives a decoded frame its timing. The first call after {@link #reset} or {@link #clear} anchors the
	 * clock on this frame.
	 * 
	 * @param timestampUs the frame's media timestamp
	 * @param paused      whether playback is currently paused (an anchor taken while paused starts a pause)
	 */
	public FrameTiming schedule(long timestampUs, boolean paused, long nowNs) {
		synchronized (lock) {
			if (firstTimestampUs == NOT_ANCHORED) {
				firstTimestampUs = timestampUs;
                playbackStartNs = nowNs + PRE_ROLL_NS;
                if (paused) {
                	pausePending = true;
                    pauseStartedNs = nowNs;
                }
			}
			long relativeUs = Math.max(0, timestampUs - firstTimestampUs);
            return new FrameTiming(logicalPlaybackBaseUs + relativeUs, playbackStartNs + relativeUs * 1_000L);
		}
	}
	
	/** JavaFX thread. Records the moment the user paused. */
	public void pause(long nowNs) {
		synchronized (lock) {
			pausePending = true;
            pauseStartedNs = nowNs;
		}
	}
	
	/**
	 * JavaFX thread. Moves the start time forward by the length of the pause so playback resumes where it left
	 * off instead of jumping ahead. If the clock was not anchored yet there is nothing to shift: the anchor will be
	 * taken after the pause.
	 */
	public void resume(long nowNs) {
		synchronized (lock) {
			if (pausePending && firstTimestampUs != NOT_ANCHORED) {
				playbackStartNs += nowNs - pauseStartedNs;
			}
			pausePending = false;
		}
	}
	
	/**
	 * JavaFX thread. The wall-clock time at which a frame with this media timestamp is due. Returns
	 * {@link Long#MAX_VALUE} (never due) before the clock is anchored.
	 */
	public long dueTimeNs(long mediaTimestampUs) {
		 synchronized (lock) {
			 if (firstTimestampUs == NOT_ANCHORED) {
				 return Long.MAX_VALUE;
			 }
			 long relativeUs = Math.max(0, mediaTimestampUs - firstTimestampUs);
	         return playbackStartNs + relativeUs * 1_000L;
		 }
	}
	
	/**
	 * JavaFX thread. The media timestamp that playback has reached at the given wall-clock time, or empty before
	 * the clock is anchored. The audio code uses this to decide which queued audio is due.
	 */
	public OptionalLong mediaTimestampAtUs(long wallClockNs) {
		synchronized (lock) {
			if (firstTimestampUs == NOT_ANCHORED) {
				return OptionalLong.empty();
			}
			return OptionalLong.of(firstTimestampUs + (wallClockNs - playbackStartNs) / 1_000L);
		}
	}
}
