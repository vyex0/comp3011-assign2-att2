/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Vanessa Soetjiadi, a1945309
 *   
 * Modifications (Vanessa Soetjiadi):
 * - Effects are looked up in the FrameProcessorType catalogue instead of a hard-coded if/else chain, and the
 *   "Frame processors:" help section is generated from the same catalogue.
 * - Every short flag (general options and effects) now goes through the same code, so any of them can be stacked.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video
 * player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to
 * the
 * {@link VideoPlayerApp}.
 * </p>
 * 
 * <p>
 * Frame processors (effects) are not named here, they come from {@link FrameProcessorType}, so adding an effect
 * never requires editing this class. This keeps the controller to a single job (turning arguments into a launch
 * configuration) instead of also being the registry of every effect. This means that controller depends on the
 * {@link FrameProcessor} abstraction too rather than on eleven concrete classes.
 * </p>
 * 
 * <p>
 * The five general options (help, audio, maximise and the two monitors) are deliberately kept as a plain 
 * {@code switch} rather than a second options catalogue. They are fixed, they set fields rather than create objects,
 * and nothing is expected to extend them, so a catalogue there would be extra structure with no payoff.
 * </p>
 */
public class CommandLineController {
	// One constant because the same line is printed as the first line of the help and as the error for a wrong
	// number of video files. This is just to keep the text consistent across multiple usages.
	private static final String USAGE = "Usage: VideoPlayer [options] [video-file]";
	
	// The effect lines are generated, so their descriptions must still line up in a column. This is wide enough for
	// the longest flags ("-w, --black-and-white", 21 characters) plus two spaces, and reproduces the layout the
	// specification requires exactly (checked by CommandLineControllerTest).
	private static final int FLAG_COLUMN_WIDTH = 23;
	
    private final String[] args;
    private final PrintStream out;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    
    // Effects in command-line order. The controller keeps the mutable list private and only ever hands out
    // a copy (see getFrameProcessors).
    private List<FrameProcessor> frameProcessors = new ArrayList<>();
    
    /** Convenience constructor for the application: prints to the console. */
    public CommandLineController(String[] args) {
        this(args, System.out);
    }

    /**
     * @param out where errors and help are printed. It is a parameter than a direct System.out so a test can
     * capture and check the exact text. Parsing in the constructor is kept because the application starts
     * from a fully parsed, immutable result and never sees a half-built controller.
     */
    public CommandLineController(String[] args, PrintStream out) {
        this.args = args.clone();
        this.out = out;
        parse();
        if (errorMessage != null) {
        	out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }
    
    // The requested effects in command-line order. The returned list CANNOT be modified. Player's worker threads
    // reads this list while playing.
    public List<FrameProcessor> getFrameProcessors() {
        return List.copyOf(frameProcessors);
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
        	if (arg.startsWith("--")) {
        		applyLongOption(arg);
        	} else if (arg.length() > 1 && arg.startsWith("-")) {
        		// One or more stacked short flags (e.g., -j or -jfddn). Every flag in the stack goes through
        		// the same method as a flag on its own, so stacking works for ALL short flags. Previously a 
        		// stack was only checked against the effects, so -an (audio then numberer effect) was rejected
        		// even though the specification allows mixing and matching.
        		for (int i = 1; i < arg.length(); i++) {
        			applyShortOption(arg.charAt(i), arg);
        		}
        	} else if (arg.equals("-")) {
        		fail("Unknown option: " + arg);
        	} else {
        		videoFiles.add(arg);
        	}
        }
        
        checkVideoFiles(videoFiles);
    }
    
    private void checkVideoFiles(List<String> videoFiles) {
    	if (videoFiles.size() > 1) {
    		fail(USAGE);
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
            	fail("File not found: " + videoFile.getPath());
            	videoFile = null;
            }
        } else {
            if (!helpRequested) {
            	fail("No video file specified.");
            }
        }
    }
    
    private void applyLongOption(String arg) {
    	switch (arg) {
    		case "--help" -> helpRequested = true;
    		case "--audio" -> audioRequested = true;
    		case "--maximise" -> maximiseRequested = true;
    		case "--monitor-1" -> setDisplayId(1);
    		case "--monitor-2" -> setDisplayId(2);
    		default -> addFrameProcessor(FrameProcessorType.fromLongFlag(arg), arg);
    	}
    }
    
    // Stacked flags have lost their leading "-", so the wrapping argument is passed along for error messages.
    // An unknown flag reports the whole argument (as the original did) so existing error text is unchanged.
    private void applyShortOption(char flag, String wholeArg) {
    	switch (flag) {
    		case 'h' -> helpRequested = true;
    		case 'a' -> audioRequested = true;
	        case 'x' -> maximiseRequested = true;
	        case '1' -> setDisplayId(1);
	        case '2' -> setDisplayId(2);
	        default -> addFrameProcessor(FrameProcessorType.fromShortFlag(flag), wholeArg);
    	}
    }
    
    private void addFrameProcessor (Optional<FrameProcessorType> type, String arg) {
    	if (type.isPresent()) {
    		frameProcessors.add(type.get().create());
        } else {
        	fail("Unknown option: " + arg);
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
        	fail("Only one display option can be used");
            return;
        }
        this.displayId = displayId;
    }

    // Only the first problem is reported. Fail is separated into its own function to make it easier to
    // write the errors, rather than repeatedly writing System.out.println()
    private void fail(String message) {
    	if (errorMessage == null) {
    		errorMessage = message;
    	}
    }
    
    // Everything is printed through "out" (not System.out) so a test that captures "out" sees the whole help text.
    private void printHelp() {
    	out.println(USAGE);
        out.println();
        out.println("Options:");
        out.println("  -h, --help         Show this help message");
        out.println("  -a, --audio        Play audio");
        out.println("  -x, --maximise     Open the player maximised");
        out.println("  -1, --monitor-1    Open the player on display 1");
        out.println("  -2, --monitor-2    Open the player on display 2");
        out.println();
        out.println("Frame processors:");
        for (FrameProcessorType type : FrameProcessorType.values()) {
        	out.printf("  %-" + FLAG_COLUMN_WIDTH + "s%s%n", type.flags(), type.description());
        }
        out.println();
        out.println("Frame processors are applied in command-line order and may be repeated.");
        out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
