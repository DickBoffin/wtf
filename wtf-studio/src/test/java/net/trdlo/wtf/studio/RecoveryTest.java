package net.trdlo.wtf.studio;

import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.WtfFile;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recovery copies of unsaved work on disk – one for each window, left alone while its
 * window runs, offered once it has gone, and opened as the project its window left.
 */
class RecoveryTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);

    /** Where the copies go. */
    @TempDir
    Path dir;

    /** Where the projects themselves are saved, apart from the copies. */
    @TempDir
    Path projects;

    /** Everything a test made that holds a lock, let go at its end so the folders can be deleted. */
    private final List<Recovery> windows = new ArrayList<>();
    private final List<Recovery.Orphan> offered = new ArrayList<>();

    @AfterEach
    void letGo() {
        offered.forEach(Recovery.Orphan::keep);
        windows.forEach(Recovery::close);
    }

    private @NotNull Recovery window() {
        final Recovery r = new Recovery(dir);
        windows.add(r);
        return r;
    }

    private @NotNull List<Recovery.Orphan> start() {
        final List<Recovery.Orphan> found = window().orphans();
        offered.addAll(found);
        return found;
    }

    /** A project somebody has changed: a provider, a map, an area and a title. */
    static @NotNull ProjectModel changed(final @NotNull String title) {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        m.startOver(() -> {
            m.providerProperty().set(p);
            m.themeProperty().set(p.themes().getFirst());
        });
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1)));
        m.titleProperty().set(title);
        return m;
    }

    /** What a window that went leaves behind: its copy, with the time given, and its lock let go. */
    private @NotNull Path leftBy(final @NotNull ProjectModel m, final @Nullable Path of, final @NotNull Instant when)
            throws IOException {
        final List<Path> before = copies();
        final Recovery gone = new Recovery(dir);
        gone.write(ProjectIo.toFile(m), of);
        gone.close();
        final List<Path> after = new ArrayList<>(copies());
        after.removeAll(before);
        assertEquals(1, after.size(), "the window left one copy");
        Files.setLastModifiedTime(after.getFirst(), FileTime.from(when));
        return after.getFirst();
    }

    private @NotNull List<Path> copies() throws IOException {
        return files(ProjectIo.EXTENSION);
    }

    private @NotNull List<Path> files(final @NotNull String ending) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> all = Files.list(dir)) {
            return all.filter(p -> p.getFileName().toString().endsWith(ending)).sorted().toList();
        }
    }

    private static @NotNull String title(final @NotNull Path copy) {
        try {
            return String.valueOf(WtfFile.read(copy).title());
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static @NotNull List<String> titles(final @NotNull List<Recovery.Orphan> found) {
        return found.stream().map(o -> title(o.copy())).toList();
    }

    @Test
    @DisplayName("a second window keeps a copy of its own, and nobody is offered the first one's while it runs")
    void aSecondWindowLeavesTheFirstOnesCopyAlone() throws IOException {
        final Recovery first = window();
        first.write(ProjectIo.toFile(changed("the first window's")), null);
        final Recovery second = window();
        assertEquals(List.of(), second.orphans(), "the first window is still running");

        second.write(ProjectIo.toFile(changed("the second window's")), null);
        assertEquals(List.of("the first window's", "the second window's"),
                copies().stream().map(RecoveryTest::title).sorted().toList(), "a copy each, side by side");
        assertEquals(2, files(Recovery.LOCK).size(), "and a lock each");

        first.close();           // the first window goes as a crash leaves it: its copy stays, unlocked
        final List<Recovery.Orphan> found = start();
        assertEquals(List.of("the first window's"), titles(found), "only the copy whose window has gone");
        assertEquals(List.of(), window().orphans(), "and no other start offers it while this one asks");
    }

    @Test
    @DisplayName("newest first; thrown away it is gone, put off it is offered again, taken it is the window's own")
    void thrownAwayPutOffAndTaken() throws IOException {
        final Path wall = projects.resolve("wall.wtf");
        final Instant morning = Instant.parse("2026-09-28T08:00:00Z");
        leftBy(changed("older"), null, morning);
        leftBy(changed("newer"), wall, morning.plus(Duration.ofHours(3)));

        final List<Recovery.Orphan> found = start();
        assertEquals(List.of("newer", "older"), titles(found), "newest first");
        assertEquals(wall.toAbsolutePath(), found.get(0).of(), "the file the copy is of, from its lock");
        assertNull(found.get(1).of(), "none for a project that was never saved");
        assertEquals(morning.plus(Duration.ofHours(3)), found.get(0).changed());

        found.get(1).discard();
        found.get(0).keep();
        assertEquals(List.of("newer"), copies().stream().map(RecoveryTest::title).toList(),
                "the one thrown away is gone");
        assertEquals(1, files(Recovery.LOCK).size(), "and its lock with it");

        final Recovery next = window();
        final List<Recovery.Orphan> again = next.orphans();
        offered.addAll(again);
        assertEquals(List.of("newer"), titles(again), "the one put off is offered by the next start");

        again.getFirst().take();
        assertEquals(List.of(), window().orphans(), "taken, it is this window's own, and locked as its own");
        next.write(ProjectIo.toFile(changed("newer, and then some")), wall);
        assertEquals(List.of("newer, and then some"), copies().stream().map(RecoveryTest::title).toList(),
                "the window writes its copy where it took it from");

        next.delete();           // saved
        assertEquals(List.of(), files(""), "saved: the copy and its lock are gone");
    }

    @Test
    @DisplayName("taking a copy opens the project with its changes, unsaved, on its own file, with nothing to undo")
    void takingItOpensTheProjectUnsaved() throws IOException {
        final Path wall = projects.resolve("wall.wtf");
        final ProjectModel before = changed("Bohemia");
        ProjectIo.save(before, wall);
        before.titleProperty().set("Bohemia, and the changes nobody saved");
        before.zoomProperty().set(14);
        leftBy(before, wall, Instant.now());

        final ProjectModel after = new ProjectModel();
        final Recovery.Orphan opened = RecoveryOffer.decide(start(), o -> Dialogs.Recovered.OPEN, o -> {
            try {
                ProjectIo.recover(after, o.copy(), o.of(), CATALOG);
                return true;
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });

        assertNotNull(opened);
        assertEquals("Bohemia, and the changes nobody saved", after.titleProperty().get(), "with the changes");
        assertEquals(14, after.zoomProperty().get());
        assertTrue(after.dirtyProperty().get(), "unsaved, as it was when its window went");
        assertEquals(wall.toAbsolutePath(), after.fileProperty().get(), "and Save writes where the work belongs");
        assertFalse(after.history().canUndoProperty().get(), "there is nothing to undo back to");
        assertEquals("Bohemia", WtfFile.read(wall).title(), "the file itself as it was saved");

        ProjectIo.save(after, wall);
        assertFalse(after.dirtyProperty().get(), "a save makes it what is on disk, as for any project");
    }

    @Test
    @DisplayName("a lock left without a copy is cleared once it is old, and a name cut short names no file")
    void whatACrashLeaves() throws IOException {
        Files.createDirectories(dir);
        // A crash in the middle of a first copy: a lock and the part being written, an hour ago
        final Path old = dir.resolve("00112233445566ff" + Recovery.LOCK);
        Files.writeString(old, projects.resolve("wall.wtf").toString());
        final Path part = dir.resolve("00112233445566ff.wtf.part");
        Files.writeString(part, "{ \"format\": \"wt");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        // A window that has made its lock this moment and not yet locked it
        final Path young = dir.resolve("ffeeddccbbaa0099" + Recovery.LOCK);
        Files.writeString(young, "");
        // A copy whose lock was cut short in the middle of the name, by a power cut
        leftBy(changed("cut short"), null, Instant.now());
        final Path cut = files(Recovery.LOCK).stream()
                .filter(p -> !p.equals(old) && !p.equals(young)).findFirst().orElseThrow();
        Files.writeString(cut, projects.resolve("wall.wt").toString());

        final List<Recovery.Orphan> found = start();
        assertEquals(List.of("cut short"), titles(found), "the copy is offered all the same");
        assertNull(found.getFirst().of(), "as a project never saved, not on a file that is not one");
        assertFalse(Files.exists(old), "an old lock with no copy is what a crash left");
        assertFalse(Files.exists(part), "and so is the part it was writing");
        assertTrue(Files.exists(young), "a young one may be a window about to lock it");
    }
}
