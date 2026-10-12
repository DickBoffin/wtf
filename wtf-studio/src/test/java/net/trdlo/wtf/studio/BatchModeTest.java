package net.trdlo.wtf.studio;

import javafx.application.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a capture run exits with before it has taken a picture, and which flags it knows – off the
 * toolkit.
 */
class BatchModeTest {

    private static Application.Parameters args(final String... raw) {
        return new Application.Parameters() {
            @Override
            public List<String> getRaw() {
                return List.of(raw);
            }

            @Override
            public List<String> getUnnamed() {
                return List.of();
            }

            @Override
            public Map<String, String> getNamed() {
                return Map.of();
            }
        };
    }

    @AfterEach
    void ordinary() {
        BatchMode.arm(args());
    }

    @Test
    @DisplayName("a run with a capture flag says failure until a picture is written, an ordinary start does not")
    void aCaptureFailsUntilItHasAPicture() {
        for (final String flag : List.of("--screenshot=temp/a.png", "--screenshot-steps=temp/s",
                "--screenshot-preview=temp/p.png@calibrate", "--screenshot-outlines=temp/o.png#alps",
                "--screenshot-splash=temp/s.png@2600", "--screenshot")) {
            BatchMode.arm(args("--lang=cs", flag));
            assertEquals(1, BatchMode.exitCode(), flag);
        }
        BatchMode.arm(args("--lang=cs", "Alps.wtf"));
        assertEquals(0, BatchMode.exitCode(), "an ordinary start");
        BatchMode.arm(null);
        assertEquals(0, BatchMode.exitCode(), "no parameters at all");
    }

    @Test
    @DisplayName("a flag that looks like a capture and is none is found, the five captures are not")
    void anUnknownCaptureIsFound() {
        assertNull(BatchMode.unknownFlag(args("--screenshot", "--screenshot-steps=d@edit",
                "--screenshot-preview=p.png", "--screenshot-outlines=o.png#america",
                "--screenshot-splash", "--lang=cs", "Alps.wtf")));
        assertEquals("--screenshot-step=d", BatchMode.unknownFlag(args("--lang=cs", "--screenshot-step=d")));
        assertEquals("--screenshots", BatchMode.unknownFlag(args("--screenshots")));
        assertNull(BatchMode.unknownFlag(null));
    }

    @Test
    @DisplayName("an example is opened by its name after the @, beside other modifiers, and by nothing else")
    void anExampleByItsName() {
        assertEquals(net.trdlo.wtf.studio.project.Example.HIKE,
                BatchMode.exampleOf(args("--screenshot-steps=temp/hike@hike", "--lang=cs")));
        assertEquals(net.trdlo.wtf.studio.project.Example.CITY,
                BatchMode.exampleOf(args("--screenshot=temp/c.png@faded,city")));
        assertEquals(net.trdlo.wtf.studio.project.Example.WALLPAPER,
                BatchMode.exampleOf(args("--screenshot-preview=temp/w.png@wallpaper@calibrate")));
        assertNull(BatchMode.exampleOf(args("--screenshot-steps=temp/poster")), "a folder named for one is no modifier");
        assertNull(BatchMode.exampleOf(args("--screenshot-steps=temp/s@posters")), "a whole word");
        assertNull(BatchMode.exampleOf(args("--seed-view=7.7,46,12", "Alps.wtf")));
        assertNull(BatchMode.exampleOf(null));
    }
}
