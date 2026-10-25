/**
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
 */
package comp3011;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The catalogue of every {@link FrameProcessor} the user can request from the command line.
 * 
 * <p>
 * Each entry holds everything the command line needs to know about one effect: its short flag, its long flag,
 * the one-line description shown in the help text, and how to create a fresh instance. 
 * {@link CommandLineController} builds both its option parsing and its help text from this enum, so it never
 * mentions a concrete effect class itself.
 * </p>
 * 
 * <p>
 * Previously, every effect was named in two places in the controller: the if/else chain that parsed the flags
 * and the hand-written help text. Adding an effect meant editing both and keeping track of each. An enum keeps
 * each effect's flags, description, and constructor on a single line, so the parser and the help text are
 * generated from the same data and cannot get out of step with each other.
 * </p>
 * 
 * <p>
 * Two maps are used: they're built once when the class loads and the flags passed here are translated to its
 * corresponding entry. At 11 entries (FrameProcessor type), they are no faster than a loop in practice. But 
 * this would scale nicely if more effects are added later on. Duplicate flags are not allowed: two effects
 * with the same flag stop this class from loading (see the static block), so the mistake cannot go unnoticed.
 * Effect flags must also differ from the general options (-h -a -x -1 -2); CommandLineControllerTest checks this.
 * </p>
 * 
 * <p>
 * <b>To add a new effect:</b>
 * <ol>
 * <li>Write a class that extends {@link FrameProcessor} (it must be thread-safe, see that class's comment).</li>
 * <li>Add one entry below. The order of the entries is the order the effects appear in the help text.</li>
 * </ol>
 * <b>Note that: a duplicate flag stops this class from loading, and {@code CommandLineControllerTest} fails if an
 * effect's short flag clashes with a general option.</b>
 * </p>
 */

public enum FrameProcessorType {
	NUMBER('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
	SCRATCH('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
	FLICKER('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
	BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
	YELLOW('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
	VIGNETTE('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
	DUST('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
	JITTER('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
	MOTTLE('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
	BLEED('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
	PEPPER('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);
	
	// Maps must be declared after the constant enums are constructed first. These maps are never modified
	// afterwards, so sharing them between threads is safe.
	private static final Map<Character, FrameProcessorType> BY_SHORT_FLAG = new HashMap<>();
	private static final Map<String, FrameProcessorType> BY_LONG_FLAG = new HashMap<>();
	
	static {
		for (FrameProcessorType type : values()) {
			// put() returns the previous entry for that key, so a non-null result means two effects were
			// given the same flag. This ensures that duplicate flags, names, and effects are avoided.
			if (BY_SHORT_FLAG.put(type.shortFlag, type) != null || BY_LONG_FLAG.put(type.longFlag, type) != null) {
				throw new IllegalStateException("Duplicate flag on " + type);
			}
		}
	}
	
	private final char shortFlag;
	private final String longFlag;
	private final String description;
	
	// A factory (Supplier) is used because every use needs its own processor object. Effects keep per-instance state
	// (such as their seed index or per-thread scratch buffers) separated between repeated use of the same flag.
	private final Supplier<FrameProcessor> factory;
	
	FrameProcessorType(char shortFlag, String name, String description, Supplier<FrameProcessor> factory) {
		this.shortFlag = shortFlag;
		// Stored with its dashes so the parser can compare a whole command-line argument directly.
		this.longFlag = "--" + name;
		this.description = description;
		this.factory = factory;
	}
	
	public char shortFlag() {
		return shortFlag;
	}
	
	public String longFlag() {
		return longFlag;
	}
	
	public String description() {
		return description;
	}
	
	/** The flags as they appear in the help text, e.g. {@code -n, --number-frames}. */
	public String flags() {
		return "-" + shortFlag + ", " + longFlag;
	}
	
	/**
	 * Creates a new instance of this effect. Every call returns a different frameProcessor instance, because 
	 * an effect that is repeated on the command line (for example {@code -ss}) must be two independent processors instance.
	 */
	public FrameProcessor create() {
		return factory.get();
	}
	
	// Finds the effect for a single-character flag. Map.get returns null for a missing key. Optional.ofNullable
	// turns that into an explicit "not found" the caller must handle, so a bad flag is reported rather than
	// causing a NullPointerException later.
	public static Optional<FrameProcessorType> fromShortFlag(char flag) {
		return Optional.ofNullable(BY_SHORT_FLAG.get(flag));
	}
	
	// Finds the effect for a long flag.
	public static Optional<FrameProcessorType> fromLongFlag(String flag) {
		return Optional.ofNullable(BY_LONG_FLAG.get(flag));
	}
}
