package net.trdlo.wtf.studio;

import net.trdlo.wtf.compose.WtfFile;
import net.trdlo.wtf.studio.project.ProjectIo;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a start does with the recovery copies it finds – asks about each, newest first,
 * until one is opened or the question is put off, and keeps whatever it did not throw away.
 */
class RecoveryOfferTest {

    @TempDir
    Path dir;

    private final List<Recovery> starts = new ArrayList<>();
    private final List<Recovery.Orphan> found = new ArrayList<>();
    /** What the starts after the first found, let go at the end with the rest. */
    private final List<Recovery.Orphan> later = new ArrayList<>();

    /** Three windows that went, the one called "c" last. */
    @BeforeEach
    void threeWindowsWent() throws IOException {
        final Instant morning = Instant.parse("2026-09-28T08:00:00Z");
        int hour = 0;
        for (final String title : List.of("a", "b", "c")) {
            final Recovery gone = new Recovery(dir);
            gone.write(ProjectIo.toFile(RecoveryTest.changed(title)), null);
            gone.close();
            try (var copies = Files.newDirectoryStream(dir, "*" + ProjectIo.EXTENSION)) {
                for (final Path copy : copies) {
                    if (title.equals(title(copy))) {
                        Files.setLastModifiedTime(copy, FileTime.from(morning.plus(Duration.ofHours(hour))));
                    }
                }
            }
            hour++;
        }
        found.addAll(start());
    }

    @AfterEach
    void letGo() {
        found.forEach(Recovery.Orphan::keep);
        later.forEach(Recovery.Orphan::keep);
        starts.forEach(Recovery::close);
    }

    private @NotNull List<Recovery.Orphan> start() {
        final Recovery start = new Recovery(dir);
        starts.add(start);
        return start.orphans();
    }

    private static @NotNull String title(final @NotNull Path copy) {
        try {
            return String.valueOf(WtfFile.read(copy).title());
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static @NotNull String title(final Recovery.@NotNull Orphan orphan) {
        return title(orphan.copy());
    }

    /** What the next start would be offered, once this one has let go. */
    private @NotNull List<String> offeredNext() {
        found.forEach(Recovery.Orphan::keep);
        final List<Recovery.Orphan> next = start();
        later.addAll(next);
        return next.stream().map(RecoveryOfferTest::title).toList();
    }

    @Test
    @DisplayName("newest first, until one is opened: the ones thrown away go, and the rest wait for the next start")
    void newestFirstUntilOneOpens() {
        assertEquals(List.of("c", "b", "a"), found.stream().map(RecoveryOfferTest::title).toList());
        final List<String> asked = new ArrayList<>();
        final Recovery.Orphan opened = RecoveryOffer.decide(found, o -> {
            asked.add(title(o));
            return "c".equals(title(o)) ? Dialogs.Recovered.DISCARD : Dialogs.Recovered.OPEN;
        }, o -> true);

        assertEquals(List.of("c", "b"), asked, "once one is open, nothing more is asked");
        assertNotNull(opened);
        assertEquals("b", title(opened));
        assertEquals(List.of("a"), offeredNext(),
                "c was thrown away, b is this window's own now, and a waits for the next start");
    }

    @Test
    @DisplayName("Cancel puts off the copy it was asked about and every one after it")
    void cancelPutsOffTheRest() {
        final List<String> asked = new ArrayList<>();
        assertNull(RecoveryOffer.decide(found, o -> {
            asked.add(title(o));
            return Dialogs.Recovered.LATER;
        }, o -> fail("nothing is opened")));
        assertEquals(List.of("c"), asked);
        assertEquals(List.of("c", "b", "a"), offeredNext(), "all three are offered again");
    }

    @Test
    @DisplayName("a copy that will not open is kept, and nothing more is asked")
    void aCopyThatWillNotOpenIsKept() {
        final List<String> asked = new ArrayList<>();
        assertNull(RecoveryOffer.decide(found, o -> {
            asked.add(title(o));
            return Dialogs.Recovered.OPEN;
        }, o -> false));
        assertEquals(List.of("c"), asked);
        assertEquals(List.of("c", "b", "a"), offeredNext(), "the error was said, and the copy is not lost to it");
    }

    @Test
    @DisplayName("with nobody there, a copy is kept for a start that has somebody")
    void batchModeKeepsIt() {
        final boolean was = Dialogs.isBatchMode();
        Dialogs.setBatchMode(true);
        try {
            assertEquals(Dialogs.Recovered.LATER, Dialogs.askRecovery(null, dir.resolve("wall.wtf"), Instant.now()));
            assertEquals(Dialogs.Recovered.LATER, Dialogs.askRecovery(null, null, Instant.now()));
        } finally {
            Dialogs.setBatchMode(was);
        }
    }
}
