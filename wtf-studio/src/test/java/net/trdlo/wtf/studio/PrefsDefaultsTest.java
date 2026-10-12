package net.trdlo.wtf.studio;

import net.trdlo.wtf.tiles.TileDownloader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each preference read in more than one place has its default said once, beside its key, in a
 * preference node of the test's own. The update check was defaulted in three places and the
 * download threads were a literal 6 in the Settings dialog against the downloader's constant, so
 * two readers could disagree about a first run.
 */
class PrefsDefaultsTest {

    private final String path = "trdlo.net/wtf-test-" + UUID.randomUUID();
    private final Prefs prefs = new Prefs(path);

    @AfterEach
    void removeTheNode() throws BackingStoreException {
        Preferences.userRoot().node(path).removeNode();
    }

    private static List<Object> typedKeys() throws IllegalAccessException {
        final List<Object> keys = new ArrayList<>();
        for (final Field f : Prefs.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && (f.getType() == Prefs.Flag.class || f.getType() == Prefs.Count.class)) {
                keys.add(f.get(null));
            }
        }
        return keys;
    }

    @Test
    @DisplayName("with nothing stored, each preference reads as its one default")
    void nothingStoredIsTheDefault() throws IllegalAccessException {
        final List<Object> keys = typedKeys();
        assertEquals(6, keys.size(), "the switches and the count read in more than one place");
        for (final Object key : keys) {
            switch (key) {
                case Prefs.Flag flag -> assertEquals(flag.byDefault(), prefs.get(flag), flag.key());
                case Prefs.Count count -> assertEquals(count.byDefault(), prefs.get(count), count.key());
                default -> fail(key.toString());
            }
        }
        assertTrue(prefs.get(Prefs.CHECK_UPDATES), "the update check is on until it is switched off");
        assertTrue(prefs.get(Prefs.SHOW_SPLASH));
        assertTrue(prefs.get(Prefs.SHOW_WHATS_NEW));
        assertTrue(prefs.get(Prefs.DARK_THEME));
        assertTrue(prefs.get(Prefs.OPEN_AFTER_EXPORT));
        assertEquals(TileDownloader.DEFAULT_CONCURRENCY, prefs.get(Prefs.DOWNLOAD_THREADS),
                "the downloader's own default, not a number of the dialog's");
    }

    @Test
    @DisplayName("what is stored is what is read")
    void storedIsRead() {
        prefs.put(Prefs.CHECK_UPDATES, false);
        prefs.put(Prefs.DOWNLOAD_THREADS, 3);
        assertFalse(new Prefs(path).get(Prefs.CHECK_UPDATES), "read back by a new store");
        assertEquals(3, new Prefs(path).get(Prefs.DOWNLOAD_THREADS));
    }

    @Test
    @DisplayName("the update check, switched off, asks nobody")
    void updateCheckReadsTheSameSwitch() throws Exception {
        prefs.put(Prefs.CHECK_UPDATES, false);
        assertEquals(UpdateCheck.Status.UNKNOWN, UpdateCheck.check(prefs, false).get().status());
    }

    /**
     * Wherever it is read: no source hands one of these keys to the store by its string, which is
     * how a second default would get in – {@code getBoolean("startup.checkUpdates", true)} – nor
     * by its {@code key()}, outside {@link Prefs} itself. The string may appear elsewhere:
     * {@code export.openAfter} is a bundle key too.
     */
    @Test
    @DisplayName("no code reads one of these keys past its default")
    void readOnlyThroughTheKey() throws IOException, IllegalAccessException {
        final List<String> names = new ArrayList<>();
        for (final Object key : typedKeys()) {
            names.add(key instanceof final Prefs.Flag f ? f.key() : ((Prefs.Count) key).key());
        }
        final Path main = Path.of("src/main/java");
        assertTrue(Files.isDirectory(main), "run from the module");
        final List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final String code = Files.readString(file);
                for (final String name : names) {
                    if (Pattern.compile("(get|put)(Boolean|Int)\\(\\s*\"" + Pattern.quote(name) + '"')
                            .matcher(code).find()) {
                        found.add(file.getFileName() + ": " + name);
                    }
                }
                if (!file.endsWith("Prefs.java")
                        && Pattern.compile("(get|put)(Boolean|Int)\\([\\w.()]*\\.key\\(\\)").matcher(code).find()) {
                    found.add(file.getFileName() + ": a key() read past its default");
                }
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    @DisplayName("the decoded tiles' budget is no preference")
    void budgetIsNoPreference() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(file).contains("tileCacheMb"), file.toString());
            }
        }
    }
}
