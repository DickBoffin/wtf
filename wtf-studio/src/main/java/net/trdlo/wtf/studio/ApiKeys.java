package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The providers' API keys: asked of where they are kept once for each provider, and kept in
 * memory from then on.
 * <p>
 * Where they are kept is the system's store for secrets, where WTF has one for the platform – the
 * Credential Manager on Windows ({@link WindowsCredentials}) – and the preference store everywhere
 * else, as {@code key.<provider>} in the node, until macOS and Linux have theirs. Not the
 * preference store on Windows: that is the registry, where anything running as the user reads a
 * key as plain text.
 * <p>
 * Asked once, because fetching as the map pans asks for the current provider's key on every view
 * it settles on, and a credential store answers through a service of the system's, a
 * round trip each time, where the preference store was read in place. Written only when a key
 * changes: the Providers dialog hands over its field on every change, the key it was just shown
 * among them. A store that will not keep a key leaves
 * it here until WTF closes, and the log says so by the provider's id – never by the key – as a
 * preference that cannot be saved is not worth failing over either.
 */
final class ApiKeys {

    private static final Logger log = LoggerFactory.getLogger(ApiKeys.class);

    /** What a key's name starts with, in the preference store and behind the node's path. */
    static final String KEY_PREFIX = "key.";

    /**
     * Where the keys are kept, by provider id. What either method throws may be logged, so its
     * message never holds a key.
     */
    interface Store {

        /** The key kept for a provider, or null when none is. */
        @Nullable String read(@NotNull String providerId) throws IOException;

        /** Keeps a provider's key, or lets the one kept go when {@code key} is null. */
        void write(@NotNull String providerId, @Nullable String key) throws IOException;
    }

    private final Store store;

    /** What the store said, or was told since, for each provider asked about; empty for none. */
    private final Map<String, Optional<String>> known = new HashMap<>();

    ApiKeys(final @NotNull Store store) {
        this.store = store;
    }

    /** The platform's: the Credential Manager on Windows, the node itself everywhere else. */
    static @NotNull ApiKeys forPlatform(final @NotNull String nodePath, final @NotNull Preferences node) {
        final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return new ApiKeys(windows ? new WindowsCredentials(nodePath) : new PreferenceKeys(node));
    }

    /** A provider's key, or null when it has none; the store is asked the first time only. */
    synchronized @Nullable String get(final @NotNull String providerId) {
        final String id = providerId.toLowerCase(Locale.ROOT);
        Optional<String> key = known.get(id);
        if (key == null) {
            key = Optional.ofNullable(readOrNone(id));
            known.put(id, key);
        }
        return key.orElse(null);
    }

    /** Sets a provider's key, or clears it for null or blank; the store hears of a change only. */
    synchronized void set(final @NotNull String providerId, final @Nullable String key) {
        final String id = providerId.toLowerCase(Locale.ROOT);
        final String value = key == null || key.isBlank() ? null : key;
        if (Objects.equals(get(id), value)) {
            return;
        }
        known.put(id, Optional.ofNullable(value));
        try {
            store.write(id, value);
        } catch (final IOException ex) {
            log.warn("The key for {} is kept only until WTF closes: {}", id, ex.getMessage());
        } catch (final RuntimeException ex) {
            // Not its message, which is somebody else's and may quote what it was handed
            log.warn("The key for {} is kept only until WTF closes: {}", id, ex.getClass().getName());
        }
    }

    private @Nullable String readOrNone(final @NotNull String id) {
        try {
            final String key = store.read(id);
            return key == null || key.isBlank() ? null : key;
        } catch (final IOException ex) {
            log.warn("Could not read the key for {}: {}", id, ex.getMessage());
        } catch (final RuntimeException ex) {
            log.warn("Could not read the key for {}: {}", id, ex.getClass().getName());
        }
        return null;
    }

    /** {@code key.<provider>} in a preference node: where a platform keeps keys that has no better place. */
    record PreferenceKeys(@NotNull Preferences node) implements Store {

        @Override
        public @Nullable String read(final @NotNull String providerId) {
            return node.get(KEY_PREFIX + providerId, null);
        }

        @Override
        public void write(final @NotNull String providerId, final @Nullable String key) throws IOException {
            try {
                if (key == null) {
                    node.remove(KEY_PREFIX + providerId);
                } else {
                    node.put(KEY_PREFIX + providerId, key);
                }
                node.flush();
            } catch (final BackingStoreException | IllegalArgumentException | IllegalStateException ex) {
                // Not the exception's own message: the preferences quote a value too long to keep
                throw new IOException("The preference store did not keep it: " + ex.getClass().getName());
            }
        }
    }
}
