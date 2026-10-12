package net.trdlo.wtf.studio;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the API keys are kept: the Credential Manager on Windows, the preference store elsewhere,
 * and in memory once asked.
 * <p>
 * Every name here is the test's own. The node is {@code trdlo.net/wtf-test-<random>}, not WTF's
 * {@code trdlo.net/wtf}, and the credentials are named after the node, so nothing here reads,
 * writes or deletes a key of whoever runs it; and each test deletes what it made.
 */
class ApiKeysTest {

    private static final String PROVIDER = "probe";

    private final String path = "trdlo.net/wtf-test-" + UUID.randomUUID();
    private final String secret = "test-key-" + UUID.randomUUID();

    @AfterEach
    void removeWhatTheTestMade() throws BackingStoreException, IOException {
        if (OS.WINDOWS.isCurrentOs()) {
            new WindowsCredentials(path).write(PROVIDER, null);
        }
        Preferences.userRoot().node(path).removeNode();
    }

    // -------------------------------------------------------------------------------------------
    // The platform's store
    // -------------------------------------------------------------------------------------------

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("on Windows a key lands in the Credential Manager, not the preference store, and goes when cleared")
    void onWindowsTheCredentialManager() throws IOException, BackingStoreException {
        final WindowsCredentials credentials = new WindowsCredentials(path);
        assertEquals(path + "/key.probe", credentials.nameOf(PROVIDER), "a credential of the test's own");

        final Prefs prefs = new Prefs(path);
        prefs.setApiKey(PROVIDER, secret);
        assertEquals(secret, credentials.read(PROVIDER), "in the Credential Manager");
        assertNotInTheNode(secret);
        assertEquals(secret, new Prefs(path).apiKey(PROVIDER), "read back by a new store");

        prefs.setApiKey(PROVIDER, null);
        assertNull(credentials.read(PROVIDER), "gone from the Credential Manager");
        assertNull(new Prefs(path).apiKey(PROVIDER), "and so from a new store");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a key longer than a credential holds is kept for the session, and the log says so without it")
    void aKeyWindowsWillNotKeep() throws IOException {
        final String tooLong = secret + "x".repeat(1_280);
        final List<ILoggingEvent> logged = logOf(() -> {
            final Prefs prefs = new Prefs(path);
            prefs.setApiKey(PROVIDER, tooLong);
            assertEquals(tooLong, prefs.apiKey(PROVIDER), "kept in memory all the same");
        });
        assertNull(new WindowsCredentials(path).read(PROVIDER), "Windows keeps nothing of it");
        assertSaidWithoutTheKey(logged, "Windows did not keep");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("elsewhere a key is kept in the preference store, as before")
    void elsewhereThePreferenceStore() {
        final Prefs prefs = new Prefs(path);
        prefs.setApiKey(PROVIDER, secret);
        assertEquals(secret, Preferences.userRoot().node(path).get("key.probe", null));
        assertEquals(secret, new Prefs(path).apiKey(PROVIDER), "read back by a new store");

        prefs.setApiKey(PROVIDER, null);
        assertNull(Preferences.userRoot().node(path).get("key.probe", null));
        assertNull(new Prefs(path).apiKey(PROVIDER));
    }

    @Test
    @DisplayName("the preference store keeps key.<provider> in its node, on any system")
    void thePreferenceStoreOnAnySystem() {
        final Preferences node = Preferences.userRoot().node(path);
        final ApiKeys keys = new ApiKeys(new ApiKeys.PreferenceKeys(node));
        keys.set(PROVIDER, secret);
        assertEquals(secret, node.get("key.probe", null));
        assertEquals(secret, new ApiKeys(new ApiKeys.PreferenceKeys(node)).get(PROVIDER), "read back by a new store");

        keys.set(PROVIDER, null);
        assertNull(node.get("key.probe", null));
    }

    // -------------------------------------------------------------------------------------------
    // In memory
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the store is asked once for each provider, and written only when a key changes")
    void askedOnceAndWrittenOnChange() {
        final Recording store = new Recording();
        final ApiKeys keys = new ApiKeys(store);
        assertNull(keys.get("Mapy-com"));
        assertNull(keys.get("mapy-com"));
        assertEquals(List.of("read mapy-com"), store.calls, "one question, by the id in lower case");

        keys.set("mapy-com", secret);
        keys.set("mapy-com", secret);          // what the Providers dialog does when it shows the key
        assertEquals(secret, keys.get("mapy-com"));
        keys.set("mapy-com", "  ");            // a field emptied but for spaces
        assertNull(keys.get("mapy-com"));
        keys.set("mapy-com", null);
        assertEquals(List.of("read mapy-com", "write mapy-com", "clear mapy-com"), store.calls);
    }

    @Test
    @DisplayName("a key the store will not keep is kept for the session")
    void aKeyTheStoreRefusesIsKeptForTheSession() {
        final ApiKeys keys = new ApiKeys(new ApiKeys.Store() {
            @Override
            public @Nullable String read(final @NotNull String providerId) throws IOException {
                throw new IOException("no store here");
            }

            @Override
            public void write(final @NotNull String providerId, final @Nullable String key) throws IOException {
                throw new IOException("no store here");
            }
        });
        assertNull(keys.get(PROVIDER), "a store that cannot be read has no key to give");
        keys.set(PROVIDER, secret);
        assertEquals(secret, keys.get(PROVIDER), "kept in memory all the same");
    }

    @Test
    @DisplayName("a value the preference store refuses is not logged, though its exception quotes it")
    void theLogNeverHoldsTheKey() {
        // AbstractPreferences refuses a value over 8 192 characters with "Value too long: <value>"
        final String tooLong = secret + "x".repeat(Preferences.MAX_VALUE_LENGTH);
        final ApiKeys keys = new ApiKeys(new ApiKeys.PreferenceKeys(Preferences.userRoot().node(path)));
        final List<ILoggingEvent> logged = logOf(() -> keys.set(PROVIDER, tooLong));
        assertEquals(tooLong, keys.get(PROVIDER), "kept for the session");
        assertSaidWithoutTheKey(logged, "did not keep it");
    }

    // -------------------------------------------------------------------------------------------

    /** No value of the test's node is the key, under any name. */
    private void assertNotInTheNode(final @NotNull String key) throws BackingStoreException {
        final Preferences node = Preferences.userRoot().node(path);
        for (final String name : node.keys()) {
            assertNotEquals(key, node.get(name, null), () -> name + " holds the key");
        }
    }

    /** What {@link ApiKeys} logged while {@code run} ran. */
    private static @NotNull List<ILoggingEvent> logOf(final @NotNull Runnable run) {
        final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ApiKeys.class);
        final ListAppender<ILoggingEvent> seen = new ListAppender<>();
        seen.start();
        logger.addAppender(seen);
        try {
            run.run();
        } finally {
            logger.detachAppender(seen);
        }
        return seen.list;
    }

    /** One warning, naming the provider and saying why, with no key in it and no exception behind it. */
    private void assertSaidWithoutTheKey(final @NotNull List<ILoggingEvent> logged, final @NotNull String why) {
        assertEquals(1, logged.size(), () -> "one warning, not " + logged);
        final String line = logged.getFirst().getFormattedMessage();
        assertTrue(line.contains(PROVIDER) && line.contains(why), line);
        assertFalse(line.contains(secret), "the key is in the log");
        assertNull(logged.getFirst().getThrowableProxy(), "an exception is logged, and its messages with it");
    }

    /** A store in memory that writes down each question by provider – never by the key. */
    private static final class Recording implements ApiKeys.Store {

        final Map<String, String> kept = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public @Nullable String read(final @NotNull String providerId) {
            calls.add("read " + providerId);
            return kept.get(providerId);
        }

        @Override
        public void write(final @NotNull String providerId, final @Nullable String key) {
            calls.add((key == null ? "clear " : "write ") + providerId);
            if (key == null) {
                kept.remove(providerId);
            } else {
                kept.put(providerId, key);
            }
        }
    }
}
