/*
 * Authors:
 * 1. Vanessa Soetjiadi
 */

package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for {@link CommandLineController}: the effect sequence it builds, flag stacking, the exact help 
 * text required by the specification, and each kind of error.
 * 
 * <p>
 * The controller was restructured and the specification requires specific behaviors from the CommandLineController,
 * these tests pin that behavior down so any later change, such as adding an effect, is checked automatically. They
 * need no video, no JavaFX and no threads, so they run in milliseconds under {@code mvn verify}.
 * </p>
 * 
 * <p>
 * The controller checks that the video file exists, so tests that need a valid command line create an empty file in
 * a JUnit-managed temporary directory (which JUnit deletes afterwards).
 * </p>
 */
public class CommandLineControllerTest {
  @TempDir
  Path tempDir;
  
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  
  private String existingVideo() throws IOException {
	  Path video = tempDir.resolve("movie.mp4");
	  if (!Files.exists(video)) {
          Files.createFile(video);
      }
	  return video.toString();
  }
  
  private CommandLineController parse(String... args) {
	  return new CommandLineController(args, new PrintStream(captured, true, StandardCharsets.UTF_8));
  }
  
  private String output() {
	  return captured.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }
  
  private static List<Class<?>> classesOf(CommandLineController controller) {
	  return controller.getFrameProcessors().stream().<Class<?>>map(Object::getClass).toList();
  }
  
  // ---- Effect sequence -------------------------------------------------------------------------------------

  @Test
  void longShortAndStackedSpellingsAreEquivalent() throws IOException {
	  String video = existingVideo();
	  List<Class<?>> expected = List.of(FrameJitterer.class, FrameFlickerer.class, FrameDuster.class,
			  FrameDuster.class, FrameNumberer.class);
	  assertEquals(expected, classesOf(parse("--jitter-frames", "--flicker-frames", "--dust-frames",
			  "--dust-frames", "--number-frames", video)));
	  assertEquals(expected, classesOf(parse("-j", "-f", "-d", "-d", "-n", video)));
	  assertEquals(expected, classesOf(parse("-jfddn", video)));
	  assertEquals(expected, classesOf(parse("-jf", "--dust-frames", "-d", "--number-frames", video)));
  }
  
  @Test
  void specificationExampleBuildsEveryEffectInOrder() throws IOException {
	  CommandLineController controller = parse("-nssnfwyvdjmbp", existingVideo());
	  
	  assertEquals(List.of(FrameNumberer.class, FrameScratcher.class, FrameScratcher.class, FrameNumberer.class,
			  FrameFlickerer.class, FrameBlackAndWhiter.class, FrameYellower.class, FrameVignetter.class,
			  FrameDuster.class, FrameJitterer.class, FrameMottler.class, FrameBleeder.class,
			  FramePepperer.class), classesOf(controller));
  }
  
  // Each repeat needs its own object: effects hold per-instance state (seed index, per-thread scratch buffers),
  // and the specification says a repeated effect is applied again, not once.
  @Test
  void repeatedEffectsAreIndependentInstances() throws IOException {
	  List<FrameProcessor> processors = parse("-ss", existingVideo()).getFrameProcessors();
	  
	  assertEquals(2, processors.size());
	  assertNotSame(processors.get(0), processors.get(1));
  }
  
  @Test
  void noEffectsRequestedGivesAnEmptyList() throws IOException {
	  assertTrue(parse(existingVideo()).getFrameProcessors().isEmpty());
  }
  
  @Test
  void returnedProcessorListCannotBeModified() throws IOException {
	  List<FrameProcessor> processors = parse("-n", existingVideo()).getFrameProcessors();
	  
	  assertThrows(UnsupportedOperationException.class, () -> processors.add(new FrameNumberer()));
  }
  
  //---- General options -------------------------------------------------------------------------------------

  //Regression test for the bug that -an used to be rejected: general flags and effect flags must mix in a stack.
  @Test
  void generalOptionsCanBeStackedWithEffects() throws IOException {
	  CommandLineController controller = parse("-axn", existingVideo());
	  
	  assertTrue(controller.isAudioRequested());
      assertTrue(controller.isMaximiseRequested());
      assertEquals(List.of(FrameNumberer.class), classesOf(controller));
      assertTrue(controller.shouldLaunchApplication());
      assertEquals(0, controller.getExitCode());
  }
  
  @Test
  void longGeneralOptionsAreRecognised() throws IOException {
	  CommandLineController controller = parse("--audio", "--maximise", "--monitor-2", existingVideo());
	  
	  assertTrue(controller.isAudioRequested());
      assertTrue(controller.isMaximiseRequested());
      assertEquals(2, controller.getDisplayId());
  }
  
  @Test
  void displayOptionCanBeStackedAndRepeated() throws IOException {
	  assertEquals(1, parse("-1s", "--monitor-1", existingVideo()).getDisplayId());
  }
  
  @Test
  void conflictingDisplaysAreRejected() throws IOException {
	  CommandLineController controller = parse("-1", "-2", existingVideo());
	  
	  assertEquals("Only one display option can be used", controller.getErrorMessage());
      assertFalse(controller.shouldLaunchApplication());
  }
  
  //---- Help text -------------------------------------------------------------------------------------------
  
  // Copied from the specification rather than built from FrameProcessorType. If it were generated by the same
  // code it is meant to check, a mistake in that code would produce a matching, wrong expectation.
  
  private static final String EXPECTED_HELP = """
          Usage: VideoPlayer [options] [video-file]

          Options:
            -h, --help         Show this help message
            -a, --audio        Play audio
            -x, --maximise     Open the player maximised
            -1, --monitor-1    Open the player on display 1
            -2, --monitor-2    Open the player on display 2

          Frame processors:
            -n, --number-frames    Render the frame number onto each frame
            -s, --scratch-frames   Render vertical film scratches
            -f, --flicker-frames   Randomly dim frames
            -w, --black-and-white  Convert frames to black and white
            -y, --yellow-frames    Apply a warmer colour temperature
            -v, --vignette-frames  Darken the frame edges
            -d, --dust-frames      Render dust and hair marks
            -j, --jitter-frames    Randomly displace frames by a few pixels
            -m, --mottle-frames    Add cloudy emulsion mottling
            -b, --bleed-frames     Bleed light into frames
            -p, --pepper-frames    Pepper frames with dark spots/blotches

          Frame processors are applied in command-line order and may be repeated.
          Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,
          converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.
          """;
  
  @Test
  void helpTextMatchesTheSpecificationExactly() {
	  CommandLineController controller = parse("--help");
	  
	  assertEquals(EXPECTED_HELP, output());
      assertEquals(0, controller.getExitCode());
      assertFalse(controller.shouldLaunchApplication());
  }
  
  @Test
  void shortHelpFlagGivesTheSameHelp() {
	  parse("-h");
	  
	  assertEquals(EXPECTED_HELP, output());
  }
  
  //---- Errors ----------------------------------------------------------------------------------------------
  @Test
  void unknownOptionsAreRejected() throws IOException {
	  String video = existingVideo();
	  
	  for (String bad : List.of("-q", "--bogus", "-nq", "-")) {
          CommandLineController controller = parse(bad, video);

          assertEquals("Unknown option: " + bad, controller.getErrorMessage(), bad);
          assertEquals(1, controller.getExitCode(), bad);
          assertFalse(controller.shouldLaunchApplication(), bad);
      }
  }
  
  @Test
  void missingVideoFileIsReported() {
	  CommandLineController controller = parse("-n", "no-such-file.mp4");
	  
	  assertEquals("File not found: no-such-file.mp4", controller.getErrorMessage());
      assertNull(controller.getVideoFile());
      assertEquals(1, controller.getExitCode());
  }
  
  @Test
  void noVideoFileIsReported() {
	  CommandLineController controller = parse("-n");
	  
	  assertEquals("No video file specified.", controller.getErrorMessage());
      assertFalse(controller.shouldLaunchApplication());
  }
  
  @Test
  void twoVideoFilesGiveTheUsageMessage() {
	  CommandLineController controller = parse("a.mp4", "b.mp4");
	  
	  assertEquals("Usage: VideoPlayer [options] [video-file]", controller.getErrorMessage());
      assertEquals(1, controller.getExitCode());
  }
  
  //The first error is the cause; reporting a later one would send the user after a symptom.
  @Test
  void onlyTheFirstErrorIsReported() {
	  assertEquals("Unknown option: -q", parse("-q", "--bogus").getErrorMessage());
  }
  
  //Protects the "add an effect" workflow. The maps in FrameProcessorType already reject duplicate effect flags when
  //the class loads, but they cannot see the general options (-h -a -x -1 -2), which the controller matches first and
  // would therefore hide a clashing effect. It also checks every effect is listed in the help text.
  @Test
  void effectFlagsDoNotClashWithGeneralOptionsAndAreDocumentedInTheHelp() {
      parse("--help");
      for (FrameProcessorType type : FrameProcessorType.values()) {
          assertFalse("hax12".indexOf(type.shortFlag()) >= 0, type.shortFlag() + " clashes with a general option");
          assertTrue(output().contains(type.flags() + " "), "help is missing " + type.flags());
          assertNotSame(type.create(), type.create());
      }
  }
}