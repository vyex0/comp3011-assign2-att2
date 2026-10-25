/**
 * New file for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vanessa Soetjiadi, a1945309
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
 * The controller was restructured and the specification requires specific behaviours from it, so these tests pin
 * that behaviour down. Any later change, such as adding an effect, is then checked automatically. They need no
 * video, no JavaFX and no threads, so they run in milliseconds under {@code mvn verify}.
 * </p>
 * 
 * <p>
 * The controller checks that the video file exists, so tests that need a valid command line create an empty file in
 * a JUnit-managed temporary directory (which JUnit deletes afterwards).
 * </p>
 *
 * <p>
 * <b>Why this class is tested.</b> The command line is the only input the user controls, and the effect list it
 * produces decides what the whole multithreaded pipeline runs, in what order, and how many times. Playing a real 
 * video from the command line is deliberately not tested here: it needs JavaFX, a display and a media file, and 
 * the parts that matter for correctness are tested at the level where they can be controlled 
 * ({@link PlaybackClockTest} and {@link FramePipelineTest}).
 * </p>
 */
public class CommandLineControllerTest {
  // An empty directory that JUnit creates before each test and deletes afterwards (with whatever the test
  // put in it). Using it means the tests never depend on files on the developer's machine and leave nothing behind.
  // The field must not be private: JUnit fills it in from outside the class.
  @TempDir
  Path tempDir;
  
  // Collects everything the controller prints (the help text and error output), so tests can check it as a
  // string instead of it going to the console. JUnit creates a new instance of this class for every test, so each
  // test starts with an empty buffer and output from one test cannot leak into another.
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  
  // Returns the path of a real (empty) video file. The controller only checks that the file exists, not what is in
  // it, so an empty file is enough and avoids shipping a real video with the tests. The exists check lets a
  // test call this more than once without failing on the second call.
  private String existingVideo() throws IOException {
	  Path video = tempDir.resolve("movie.mp4");
	  if (!Files.exists(video)) {
          Files.createFile(video);
      }
	  return video.toString();
  }
  
  // Builds a controller from the given arguments exactly as main() would, but with its output redirected into
  // 'captured'. The PrintStream is set to flush on every write so nothing is left in a buffer when a test reads it,
  // and UTF-8 is stated so the bytes are decoded the same way on every operating system.
  private CommandLineController parse(String... args) {
	  return new CommandLineController(args, new PrintStream(captured, true, StandardCharsets.UTF_8));
  }
  
  // Everything printed so far, as text. Windows line endings are converted to "\n" because the expected help text
  // below is written with "\n" and println uses the platform's line ending; without this the help test would
  // pass on Linux and macOS and fail on Windows.
  private String output() {
	  return captured.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }
  
  // The kinds of effect the controller built, in order. Effects are compared by class because the effect classes
  // do not define equals(), so two separately created effects of the same kind would never compare equal as
  // objects. The explicit <Class<?>> on map() is needed so Java infers a List<Class<?>> and not a list of some
  // more specific type that would not match the expected lists in the tests.
  private static List<Class<?>> classesOf(CommandLineController controller) {
	  return controller.getFrameProcessors().stream().<Class<?>>map(Object::getClass).toList();
  }
  
  /**
   * The same five effects are asked for in four different spellings (long names, short names, one stacked group,
   * and a mix of stacked and long). All four must produce the same list. Needed because the specification allows
   * all of these forms, and the easiest bug to introduce is handling one form differently from another. Repeating
   * "dust" twice also checks that a repeat is kept and not merged.
   */
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
  
  /**
   * The example given in the help text and the specification: every effect once or more, in a deliberately
   * scrambled order. Needed because the order of the effects is part of the result (for example, numbering before
   * or after the vignette looks different), so the test checks the exact order, not just which effects are present.
   * It also catches an effect that was added to the help text but not wired up in the controller.
   */
  @Test
  void specificationExampleBuildsEveryEffectInOrder() throws IOException {
	  CommandLineController controller = parse("-nssnfwyvdjmbp", existingVideo());
	  
	  assertEquals(List.of(FrameNumberer.class, FrameScratcher.class, FrameScratcher.class, FrameNumberer.class,
			  FrameFlickerer.class, FrameBlackAndWhiter.class, FrameYellower.class, FrameVignetter.class,
			  FrameDuster.class, FrameJitterer.class, FrameMottler.class, FrameBleeder.class,
			  FramePepperer.class), classesOf(controller));
  }
  
  /**
   * Each repeat needs its own object: effects hold per-instance state (seed index, per-thread scratch buffers),
   * and the specification says a repeated effect is applied again, not once.
   * assertNotSame checks object identity (the same object twice would pass an equality check on the class but
   * would share that state), which is the thing that actually goes wrong if one instance is reused.
   */
  @Test
  void repeatedEffectsAreIndependentInstances() throws IOException {
	  List<FrameProcessor> processors = parse("-ss", existingVideo()).getFrameProcessors();
	  
	  assertEquals(2, processors.size());
	  assertNotSame(processors.get(0), processors.get(1));
  }
  
  // The boundary case of "no effects": the player must still start and simply show the video unchanged, so the
  // controller has to return an empty list and not null, which would fail later with a confusing error.
  @Test
  void noEffectsRequestedGivesAnEmptyList() throws IOException {
	  assertTrue(parse(existingVideo()).getFrameProcessors().isEmpty());
  }
  
  /**
   * The list is handed to the player, which uses it from other threads once playback starts. Making it read-only
   * means nobody can change it behind the player's back, which would be a data race. The tests checks the exception
   * because that is the only visible sign that the list is protected.
   */
  @Test
  void returnedProcessorListCannotBeModified() throws IOException {
	  List<FrameProcessor> processors = parse("-n", existingVideo()).getFrameProcessors();
	  
	  assertThrows(UnsupportedOperationException.class, () -> processors.add(new FrameNumberer()));
  }
  
  /**
   *Regression test for the bug that -an used to be rejected: general flags and effect flags must mix in a stack.
   * "-axn" combines audio (a), maximise (x) and numbering (n) in one group, so it exercises all three outcomes:
   * two general options set and one effect built. The last two checks confirm that a valid command line is
   * allowed to launch and reports a success exit code.
   */
  @Test
  void generalOptionsCanBeStackedWithEffects() throws IOException {
	  CommandLineController controller = parse("-axn", existingVideo());
	  
	  assertTrue(controller.isAudioRequested());
      assertTrue(controller.isMaximiseRequested());
      assertEquals(List.of(FrameNumberer.class), classesOf(controller));
      assertTrue(controller.shouldLaunchApplication());
      assertEquals(0, controller.getExitCode());
  }
  
  // The long forms of the general options, separately from the short forms above, because each long name is its
  // own string in the controller and could be misspelt independently.
  @Test
  void longGeneralOptionsAreRecognised() throws IOException {
	  CommandLineController controller = parse("--audio", "--maximise", "--monitor-2", existingVideo());
	  
	  assertTrue(controller.isAudioRequested());
      assertTrue(controller.isMaximiseRequested());
      assertEquals(2, controller.getDisplayId());
  }
  
  // A display option inside a stack ("-1s") and the same display named again in long form is accepted, not treated
  // as a conflict, because both ask for the same display.
  @Test
  void displayOptionCanBeStackedAndRepeated() throws IOException {
	  assertEquals(1, parse("-1s", "--monitor-1", existingVideo()).getDisplayId());
  }
  
  // Two different displays cannot both be honoured, so this must be an error, and the application must not
  // launch. This is the counterpart of the test above: repeating one display is fine, asking for two is not.
  @Test
  void conflictingDisplaysAreRejected() throws IOException {
	  CommandLineController controller = parse("-1", "-2", existingVideo());
	  
	  assertEquals("Only one display option can be used", controller.getErrorMessage());
      assertFalse(controller.shouldLaunchApplication());
  }
  
  //Copied from the specification rather than built from FrameProcessorType.
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
  
  // The help text is compared character for character because the specification gives the exact wording, and
  // anything that checks the player's output may compare it the same way. Asking for help must also exit
  // successfully (code 0) without launching the player, which is different from an error.
  @Test
  void helpTextMatchesTheSpecificationExactly() {
	  CommandLineController controller = parse("--help");
	  
	  assertEquals(EXPECTED_HELP, output());
      assertEquals(0, controller.getExitCode());
      assertFalse(controller.shouldLaunchApplication());
  }
  
  // The short form is a separate string in the controller, so it gets its own check against the same text.
  @Test
  void shortHelpFlagGivesTheSameHelp() {
	  parse("-h");
	  
	  assertEquals(EXPECTED_HELP, output());
  }
  
  /**
   * Four kinds of bad option: an unknown short flag, an unknown long option, a bad letter hidden inside an otherwise
   * @throws IOException
   */
 
  // Four kinds of bad option: an unknown short flag, an unknown long option, a bad letter hidden inside an otherwise
  // good stack ("-nq"), and a lone dash. For each, the message names the whole offending argument exactly as typed.
  // The loop passes the option as the message of each assertion (the last argument) so that when one of the four
  // fails, the failure says which.
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
  
  // A file name that does not exist. The video file must also be reported as absent (null), so no later code can
  // try to open it.
  @Test
  void missingVideoFileIsReported() {
	  CommandLineController controller = parse("-n", "no-such-file.mp4");
	  
	  assertEquals("File not found: no-such-file.mp4", controller.getErrorMessage());
      assertNull(controller.getVideoFile());
      assertEquals(1, controller.getExitCode());
  }
  
  // Options but no file name at all, which is a different mistake from a wrong file name and has its own message.
  @Test
  void noVideoFileIsReported() {
	  CommandLineController controller = parse("-n");
	  
	  assertEquals("No video file specified.", controller.getErrorMessage());
      assertFalse(controller.shouldLaunchApplication());
  }
  
  // Two file names. Neither file exists, so the usage message (and not "File not found") shows that the number of
  // files is checked first.
  @Test
  void twoVideoFilesGiveTheUsageMessage() {
	  CommandLineController controller = parse("a.mp4", "b.mp4");
	  
	  assertEquals("Usage: VideoPlayer [options] [video-file]", controller.getErrorMessage());
      assertEquals(1, controller.getExitCode());
  }
  
  //The first error is the cause; reporting a later one would send the user after a symptom.
  // Both arguments here are invalid, so the test shows which one wins.
  @Test
  void onlyTheFirstErrorIsReported() {
	  assertEquals("Unknown option: -q", parse("-q", "--bogus").getErrorMessage());
  }
  
  //Protects the "add an effect" workflow. The maps in FrameProcessorType already reject duplicate effect flags when
  //the class loads, but they cannot see the general options (-h -a -x -1 -2), which the controller matches first and
  // would therefore hide a clashing effect. It also checks every effect is listed in the help text.
  // The string "hax12" is the list of general-option letters; if a new effect reuses one of them, indexOf finds
  // it and the test fails with the clashing letter in the message. Looping over FrameProcessorType.values() means
  // a newly added effect is covered automatically without editing this test. The last check creates the effect
  // twice and requires two different objects, so an effect cannot be registered with a shared instance.
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