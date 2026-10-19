package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every look Settings offers, light and dark, under the studio's own stylesheet. */
class LookTest {

    private static final Pattern TOKEN = Pattern.compile("-color-[a-z0-9-]+");

    /** A token no look defines leaves its part of the studio in JavaFX's fallback color. */
    @Test
    @DisplayName("every look defines every color token studio.css uses, light and dark")
    void everyLookDefinesTheStudiosTokens() throws IOException {
        final Set<String> used = tokens(read(Windows.STYLESHEET));
        assertFalse(used.isEmpty(), "studio.css uses AtlantaFX's tokens");

        final List<String> missing = new ArrayList<>();
        for (final Look look : Look.values()) {
            for (final boolean dark : new boolean[] {false, true}) {
                // JavaFX's own are given the tokens by the studio, and define what those are made of
                final String sheet = read(look.isJavafx() ? Windows.JAVAFX_COLORS : look.stylesheet(dark));
                for (final String token : used) {
                    if (!sheet.contains(token + ":")) {
                        missing.add(look + (dark ? " dark " : " light ") + token);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> "undefined: " + missing);
    }

    @Test
    @DisplayName("a look with one variant shows it, whatever the switch says")
    void aLookWithOneVariantShowsIt() {
        for (final Look look : Look.values()) {
            final boolean both = look.hasLight() && look.hasDark();
            assertEquals(both ? false : !look.hasLight(), look.dark(false), look.name());
            assertEquals(both ? true : look.hasDark(), look.dark(true), look.name());
        }
        assertFalse(Look.MODENA.dark(true), "Modena is light only");
        assertTrue(Look.DRACULA.dark(false), "Dracula is dark only");
        assertEquals(Look.DRACULA.stylesheet(false), Look.DRACULA.stylesheet(true));
    }

    /** The colors go on the studio's lists and come off them as the look changes, once each. */
    @Test
    @DisplayName("the colors for JavaFX's own stylesheets follow the look in open windows")
    void theColorsFollowTheLook() throws Exception {
        Headless.start();
        final List<List<String>> sheets = Headless.onFx(() -> {
            final javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane()));
            Windows.style(stage.getScene());
            stage.show();
            try {
                final List<List<String>> out = new ArrayList<>();
                WtfApp.applyTheme(Look.MODENA, true);
                out.add(List.copyOf(stage.getScene().getStylesheets()));
                WtfApp.applyTheme(Look.PRIMER, true);
                out.add(List.copyOf(stage.getScene().getStylesheets()));
                return out;
            } finally {
                stage.hide();
                WtfApp.applyTheme(Look.PRIMER, true);
            }
        });
        assertEquals(List.of(Windows.JAVAFX_COLORS, Windows.STYLESHEET), sheets.get(0));
        assertEquals(List.of(Windows.STYLESHEET), sheets.get(1));
    }

    @Test
    @DisplayName("a look is saved by name, and none or an unknown one is Primer")
    void aLookIsSavedByName() {
        for (final Look look : Look.values()) {
            assertSame(look, Look.named(look.name()));
        }
        assertSame(Look.PRIMER, Look.named(null));
        assertSame(Look.PRIMER, Look.named("SOLARIZED"));
    }

    private static Set<String> tokens(final String css) {
        final Set<String> out = new TreeSet<>();
        final Matcher m = TOKEN.matcher(css);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** A stylesheet by the URL or class-path name JavaFX is given. */
    private static String read(final String sheet) throws IOException {
        try (InputStream in = sheet.contains(":")
                ? URI.create(sheet).toURL().openStream()
                : LookTest.class.getResourceAsStream(sheet.startsWith("/") ? sheet : "/" + sheet)) {
            assertNotNull(in, sheet);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
