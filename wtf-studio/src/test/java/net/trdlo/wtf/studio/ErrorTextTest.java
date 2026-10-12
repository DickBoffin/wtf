package net.trdlo.wtf.studio;

import net.trdlo.wtf.studio.project.ProjectRefusal;
import net.trdlo.wtf.tiles.NothingThereException;
import net.trdlo.wtf.tiles.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.UnknownHostException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What the error dialog says, which is a handful of kinds rather than one sentence per exception. */
class ErrorTextTest {

    @Test
    @DisplayName("a known cause is found however deep it is wrapped")
    void theCauseChainIsSearched() {
        final var e = ErrorText.explain(new RuntimeException(
                new UncheckedIOException(new NoSuchFileException("C:/prints/europe.wtf"))));
        assertEquals(I18n.get("error.file.missing.title"), e.headline());
        assertTrue(e.body().contains("europe.wtf"), "names the file, not the path: " + e.body());
    }

    @Test
    @DisplayName("the system's own reason is passed on")
    void theDiskSaysWhy() {
        final var e = ErrorText.explain(new FileSystemException("out.pdf", null,
                "There is not enough space on the disk"));
        assertTrue(e.body().contains("not enough space"), e.body());
    }

    @Test
    @DisplayName("the editor's own refusal is what the user reads, under the refusal's headline")
    void ownChecksSpeakForThemselves() {
        final var e = ErrorText.explain(new ProjectRefusal("There is no provider to save"));
        assertEquals(I18n.get("error.refused.title"), e.headline());
        assertEquals("There is no provider to save", e.body());
    }

    /**
     * An {@code IllegalArgumentException} or {@code IllegalStateException} is not a refusal, however
     * it is worded: a JavaFX "Not on FX application thread" or JTS's own English is shown as
     * unexpected, not as "That did not work" with the bug's wording under it.
     */
    @Test
    @DisplayName("an IllegalStateException is unexpected, however well it is worded")
    void aMessageIsNotARefusal() {
        for (final RuntimeException ex : List.of(
                new IllegalStateException("Not on FX application thread; currentThread = Thread-7"),
                new IllegalArgumentException("Invalid number of points in LinearRing (found 3 - must be 0 or >= 4)"))) {
            final var e = ErrorText.explain(ex);
            assertEquals(I18n.get("error.generic"), e.headline(), ex::toString);
            assertEquals(I18n.get("error.unexpected.body"), e.body());
            assertTrue(e.details().contains(ex.getMessage()), "the wording is in the details, for the report");
        }
    }

    @Test
    @DisplayName("no network is said as no network")
    void network() {
        assertEquals(I18n.get("error.network.title"),
                ErrorText.explain(new IOException(new UnknownHostException("tile.openstreetmap.org"))).headline());
    }

    @Test
    @DisplayName("a provider with nothing there is said as that, naming the provider")
    void nothingThere() {
        final Provider p = new Provider("kartverket", "Kartverket", "https://x/{z}/{x}/{y}.png",
                null, 0, 19, null, List.of(new Provider.Theme("topo", "Topo", null, null, null, null, false, null)),
                null, "none", null, null, null, true, null, null, null, null);
        final var e = ErrorText.explain(new NothingThereException(p, 12));
        assertEquals(I18n.get("download.nothing.title"), e.headline());
        assertTrue(e.body().contains("Kartverket") && e.body().contains("12"), e.body());
    }

    @Test
    @DisplayName("anything else is honest about being unexpected, and carries the details")
    void theRest() {
        final var e = ErrorText.explain(new ArithmeticException());
        assertEquals(I18n.get("error.generic"), e.headline());
        assertTrue(e.details().contains("ArithmeticException"));
        assertTrue(e.details().startsWith(AppInfo.get().name()), "the version comes first");
        assertTrue(e.details().contains(", commit " + AppInfo.get().commit() + ","),
                "and the commit it was built from, whole, rather than the day it was built: " + e.details());
    }
}
