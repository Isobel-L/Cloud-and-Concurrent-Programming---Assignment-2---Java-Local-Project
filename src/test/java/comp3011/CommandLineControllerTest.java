
package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CommandLineControllerTest {

    @TempDir
    Path tempDir;

    private File createVideoFile() throws IOException {
        return Files.createFile(tempDir.resolve("test-video.mp4")).toFile();
    }

    @Test
    void testAllShortFrameEffectOptions() throws IOException {
        File video = createVideoFile();

        String[] options = {
                "-n", "-s", "-f", "-w", "-y", "-v",
                "-d", "-j", "-m", "-b", "-p"
        };

        for (String option : options) {
            CommandLineController controller =
                    new CommandLineController(new String[] {
                            option, video.getPath()
                    });

            assertTrue(controller.shouldLaunchApplication(), option);
            assertEquals(List.of(option),
                    controller.getFrameEffectOptions(), option);
        }
    }

    @Test
    void testAllLongFrameEffectOptions() throws IOException {
        File video = createVideoFile();

        String[] longOptions = {
                "--number-frames",
                "--scratch-frames",
                "--flicker-frames",
                "--black-and-white",
                "--yellow-frames",
                "--vignette-frames",
                "--dust-frames",
                "--jitter-frames",
                "--mottle-frames",
                "--bleed-frames",
                "--pepper-frames"
        };

        String[] shortOptions = {
                "-n", "-s", "-f", "-w", "-y", "-v",
                "-d", "-j", "-m", "-b", "-p"
        };

        for (int i = 0; i < longOptions.length; i++) {
            CommandLineController controller =
                    new CommandLineController(new String[] {
                            longOptions[i], video.getPath()
                    });

            assertTrue(controller.shouldLaunchApplication(),
                    longOptions[i]);
            assertEquals(List.of(shortOptions[i]),
                    controller.getFrameEffectOptions(),
                    longOptions[i]);
        }
    }

    @Test
    void testStackedShortOptions() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-sfn", video.getPath()
                });

        assertTrue(controller.shouldLaunchApplication());
        assertEquals(List.of("-s", "-f", "-n"),
                controller.getFrameEffectOptions());
    }

    @Test
    void testFrameEffectOrder() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-w", "--scratch-frames", "-fn",
                        video.getPath()
                });

        assertEquals(List.of("-w", "-s", "-f", "-n"),
                controller.getFrameEffectOptions());
    }

    @Test
    void testRepeatedFrameEffects() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-s", "--scratch-frames", "-ss",
                        video.getPath()
                });

        assertEquals(List.of("-s", "-s", "-s", "-s"),
                controller.getFrameEffectOptions());
    }

    @Test
    void testAllEffectsCombined() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-nsfwyvdjmbp", video.getPath()
                });

        assertTrue(controller.shouldLaunchApplication());
        assertEquals(List.of(
                "-n", "-s", "-f", "-w", "-y", "-v",
                "-d", "-j", "-m", "-b", "-p"),
                controller.getFrameEffectOptions());
    }

    @Test
    void testExistingLaunchOptions() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-a", "-x", "-1", "-w", video.getPath()
                });

        assertTrue(controller.shouldLaunchApplication());
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(1, controller.getDisplayId());
        assertEquals(List.of("-w"),
                controller.getFrameEffectOptions());
        assertEquals(video, controller.getVideoFile());
    }

    @Test
    void testLongLaunchOptions() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "--audio", "--maximise", "--monitor-2",
                        video.getPath()
                });

        assertTrue(controller.shouldLaunchApplication());
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(2, controller.getDisplayId());
        assertTrue(controller.getFrameEffectOptions().isEmpty());
    }

    @Test
    void testInvalidShortOption() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-z", video.getPath()
                });

        assertFalse(controller.shouldLaunchApplication());
        assertEquals("Unknown option: -z",
                controller.getErrorMessage());
        assertEquals(1, controller.getExitCode());
    }

    @Test
    void testInvalidLongOption() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "--invalid-effect", video.getPath()
                });

        assertFalse(controller.shouldLaunchApplication());
        assertEquals("Unknown option: --invalid-effect",
                controller.getErrorMessage());
    }

    @Test
    void testInvalidStackedOption() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-swz", video.getPath()
                });

        assertFalse(controller.shouldLaunchApplication());
        assertEquals("Unknown option: -z",
                controller.getErrorMessage());
    }

    @Test
    void testConflictingDisplayOptions() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-1", "-2", video.getPath()
                });

        assertFalse(controller.shouldLaunchApplication());
        assertEquals("Only one display option can be used",
                controller.getErrorMessage());
    }

    @Test
    void testMissingVideoFile() {
        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-s"
                });

        assertFalse(controller.shouldLaunchApplication());
        assertEquals("No video file specified.",
                controller.getErrorMessage());
    }

    @Test
    void testNonexistentVideoFile() {
        Path missingFile = tempDir.resolve("missing.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-n", missingFile.toString()
                });

        assertFalse(controller.shouldLaunchApplication());
        assertNotNull(controller.getErrorMessage());
        assertTrue(controller.getErrorMessage()
                .startsWith("File not found:"));
    }

    @Test
    void testMultipleVideoFiles() throws IOException {
        File first = createVideoFile();
        File second = Files.createFile(
                tempDir.resolve("second-video.mp4")).toFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        first.getPath(), second.getPath()
                });

        assertFalse(controller.shouldLaunchApplication());
        assertEquals("Usage: VideoPlayer [options] [video-file]",
                controller.getErrorMessage());
    }

    @Test
    void testHelpOption() {
        CommandLineController controller =
                new CommandLineController(new String[] {
                        "--help"
                });

        assertEquals(0, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
        assertNull(controller.getErrorMessage());
    }

    @Test
    void testNoFrameEffects() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        video.getPath()
                });

        assertTrue(controller.shouldLaunchApplication());
        assertTrue(controller.getFrameEffectOptions().isEmpty());
    }

    @Test
    void testFrameEffectListCannotBeModified() throws IOException {
        File video = createVideoFile();

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-sn", video.getPath()
                });

        List<String> effects = controller.getFrameEffectOptions();

        assertThrows(UnsupportedOperationException.class,
                () -> effects.add("-w"));

        assertEquals(List.of("-s", "-n"),
                controller.getFrameEffectOptions());
    }
}
