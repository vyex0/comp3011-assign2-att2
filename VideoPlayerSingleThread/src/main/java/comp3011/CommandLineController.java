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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private List<FrameProcessor> frameProcessors = new ArrayList<>(); // List of FrameProcessors to be passed to the
                                                                      // VideoPlayerModel

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
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

    public List<FrameProcessor> getFrameProcessor() {
        return frameProcessors;
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

    private boolean handleOptions(String arg) {
        if ("-n".equals(arg) || "--number-frames".equals(arg)) {
            frameProcessors.add(new FrameNumberer());
        } else if ("-s".equals(arg) || "--scratch-frames".equals(arg)) {
            frameProcessors.add(new FrameScratcher());
        } else if ("-f".equals(arg) || "--flicker-frames".equals(arg)) {
            frameProcessors.add(new FrameFlickerer());
        } else if ("-w".equals(arg) || "--black-and-white".equals(arg)) {
            frameProcessors.add(new FrameBlackAndWhiter());
        } else if ("-y".equals(arg) || "--yellow-frames".equals(arg)) {
            frameProcessors.add(new FrameYellower());
        } else if ("-v".equals(arg) || "--vignette-frames".equals(arg)) {
            frameProcessors.add(new FrameVignetter());
        } else if ("-d".equals(arg) || "--dust-frames".equals(arg)) {
            frameProcessors.add(new FrameDuster());
        } else if ("-j".equals(arg) || "--jitter-frames".equals(arg)) {
            frameProcessors.add(new FrameJitterer());
        } else if ("-m".equals(arg) || "--mottle-frames".equals(arg)) {
            frameProcessors.add(new FrameFlickerer());
        } else if ("-b".equals(arg) || "--bleed-frames".equals(arg)) {
            frameProcessors.add(new FrameBleeder());
        } else if ("-p".equals(arg) || "--pepper-frames".equals(arg)) {
            frameProcessors.add(new FramePepperer());
        } else {
            return false;
        }

        return true;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            } else if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            } else if (arg.startsWith("--")) {
                if (!handleOptions(arg)) {
                    errorMessage = "Unknown option: " + arg;
                    return;
                };
            } else if (arg.startsWith("-")) {
                if (arg.length() > 2) {
                    for (int i = 1; i < arg.length(); i++) {
                        // For stacked options, they don't have "-" at the beginning
                        // so "-" is added here.
                        String innerArg = "-" + arg.charAt(i);

                        if (!handleOptions(innerArg)) {
                            errorMessage = "Unknown option: " + arg;
                            return;
                        }
                        ;
                    }
                } else {
                    if (!handleOptions(arg)) {
                        errorMessage = "Unknown option: " + arg;
                        return;
                    }
                    ;
                }
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");

        System.out.println("Frame processors:");
        System.out.println(" -n, --number-frames    Render the frame number onto each frame");
        System.out.println(" -s, --scratch-frames   Render vertical film scratches");
        System.out.println(" -f, --flicker-frames   Randomly dim frames");
        System.out.println(" -w, --black-and-white  Convert frames to black and white");
        System.out.println(" -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println(" -v, --vignette-frames  Darken the frame edges");
        System.out.println(" -d, --dust-frames      Render dust and hair marks");
        System.out.println(" -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println(" -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println(" -b, --bleed-frames     Bleed light into frames");
        System.out.println(" -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
