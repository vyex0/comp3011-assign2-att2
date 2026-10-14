/*
 * Authors:
 * 1. Vanessa Soetjiadi
 */

package comp3011;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.io.TempDir;

/**
 * Created the CommandLineController's test cases to test the method's methods under normal
 * circumstances and edge-cases.
 */
public class CommandLineControllerTest {
  /**
   * Tests the normal conditions of the CommandLineController
   */

  @TempDir
  Path tempDir;

  @Test
  void longOptionVersionsEqualEachOther () throws IOException {
    File fakeMovie = tempDir.resolve("movies.mp4").toFile();
    fakeMovie.createNewFile();

    String[] options = "--jitter-frames --flicker-frames --dust-frames --dust-frames --number-frames".split(" ");

    String[] args = new String[options.length + 1];
    System.arraycopy(options, 0, args, 0, options.length);
    args[options.length] = fakeMovie.getPath();
    
    CommandLineController commandLineController = new CommandLineController(args);

    List<FrameProcessor> processors = commandLineController.getFrameProcessor();

    assertEquals(5, processors.size());
    assertInstanceOf(FrameJitterer.class, processors.get(0));
    assertInstanceOf(FrameFlickerer.class, processors.get(1));
    assertInstanceOf(FrameDuster.class, processors.get(2));
    assertInstanceOf(FrameDuster.class, processors.get(3));
    assertInstanceOf(FrameNumberer.class, processors.get(4));
  }
  
  @Test
  void shortOptionVersionsEqualEachOther () throws IOException {
    File fakeMovie = tempDir.resolve("movies.mp4").toFile();
    fakeMovie.createNewFile();

    String[] options = "-j -f -d -d -n".split(" ");

    String[] args = new String[options.length + 1];
    System.arraycopy(options, 0, args, 0, options.length);
    args[options.length] = fakeMovie.getPath();
    
    CommandLineController commandLineController = new CommandLineController(args);

    List<FrameProcessor> processors = commandLineController.getFrameProcessor();

    assertEquals(5, processors.size());
    assertInstanceOf(FrameJitterer.class, processors.get(0));
    assertInstanceOf(FrameFlickerer.class, processors.get(1));
    assertInstanceOf(FrameDuster.class, processors.get(2));
    assertInstanceOf(FrameDuster.class, processors.get(3));
    assertInstanceOf(FrameNumberer.class, processors.get(4));
  }
  
  @Test
  void stackedOptionVersionsEqualEachOther () throws IOException {
    File fakeMovie = tempDir.resolve("movies.mp4").toFile();
    fakeMovie.createNewFile();

    String[] args = {"-jfddn", fakeMovie.getPath()};
    
    CommandLineController commandLineController = new CommandLineController(args);

    List<FrameProcessor> processors = commandLineController.getFrameProcessor();

    assertEquals(5, processors.size());
    assertInstanceOf(FrameJitterer.class, processors.get(0));
    assertInstanceOf(FrameFlickerer.class, processors.get(1));
    assertInstanceOf(FrameDuster.class, processors.get(2));
    assertInstanceOf(FrameDuster.class, processors.get(3));
    assertInstanceOf(FrameNumberer.class, processors.get(4));
  }
  
  @Test
  void combinedOptionVersionsEqualEachOther () throws IOException {
    File fakeMovie = tempDir.resolve("movies.mp4").toFile();
    fakeMovie.createNewFile();

    String[] options = "-jf --dust-frames -d --number-frames".split(" ");

    String[] args = new String[options.length + 1];
    System.arraycopy(options, 0, args, 0, options.length);
    args[options.length] = fakeMovie.getPath();
    
    CommandLineController commandLineController = new CommandLineController(args);

    List<FrameProcessor> processors = commandLineController.getFrameProcessor();

    assertEquals(5, processors.size());
    assertInstanceOf(FrameJitterer.class, processors.get(0));
    assertInstanceOf(FrameFlickerer.class, processors.get(1));
    assertInstanceOf(FrameDuster.class, processors.get(2));
    assertInstanceOf(FrameDuster.class, processors.get(3));
    assertInstanceOf(FrameNumberer.class, processors.get(4));
  }

  /**
   * Tests the failing conditions for command line controllers
   */
}