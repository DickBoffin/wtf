package net.trdlo.wtf.tiles;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The providers WTF knows about: the shipped {@code providers.json}, with a file of the same
 * shape from the user's configuration directory merged over it by id.
 * <p>
 * The merge is per provider, not per field: a user entry with an existing id replaces the built-in
 * one entirely. Partial overrides would be a pleasant feature and a miserable one to reason about
 * when a shipped definition later changes underneath a user's half-override.
 */
public final class ProviderCatalog {

    private static final Logger log = LoggerFactory.getLogger(ProviderCatalog.class);

    private static final String RESOURCE = "providers.json";

    /** Tokens a template may contain. Anything else is a typo, and is rejected at load time. */
    private static final Set<String> KNOWN_TOKENS =
            Set.of("theme", "z", "x", "y", "scale", "lang", "key", "s");

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    /**
     * The deepest zoom a definition may name, the Providers dialog's limit. Past it nobody serves
     * tiles, and a hand-edited 31 or a hundred million would reach the Detail step, where
     * {@link Mercator#tileCount} overflows or a loop over the zooms does not end.
     */
    static final int MAX_ZOOM = 22;

    private final Map<String, Provider> byId;

    /**
     * Ids the user has chosen not to see.
     * <p>
     * Hidden is not removed. A hidden provider still resolves by id, because a {@code .wtf}
     * that names it must still open and a cache directory full of its tiles must still be
     * readable; it simply stays out of the pickers.
     */
    private final Set<String> hidden;

    private ProviderCatalog(final @NotNull Map<String, Provider> byId, final @NotNull Set<String> hidden) {
        this.byId = Collections.unmodifiableMap(byId);
        this.hidden = Set.copyOf(hidden);
    }

    /**
     * How a provider file is read, the shipped one and the user's alike.
     * <p>
     * Enums in either case. The design documents write {@code "kind": "header"} and the shipped file
     * {@code "HEADER"}, and somebody who copies an example from one into the other should not find
     * out which is meant by the provider silently not loading.
     */
    static @NotNull ObjectMapper mapper() {
        return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .build();
    }

    /** The shipped catalog only. */
    public static @NotNull ProviderCatalog builtIn() {
        return load(null);
    }

    /**
     * The shipped catalog with {@code userFile} merged over it.
     *
     * @param userFile a file of the same shape, or null; a missing file is not an error
     */
    public static @NotNull ProviderCatalog load(final @Nullable Path userFile) {
        final Map<String, Provider> merged = new LinkedHashMap<>();
        try (final InputStream in = ProviderCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Built-in " + RESOURCE + " is missing from the jar");
            }
            for (final Provider p : mapper().readValue(in, ProviderStore.UserFile.class)
                    .providersOrEmpty()) {
                merged.put(p.id(), p);
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Cannot read the built-in " + RESOURCE, ex);
        }

        final Set<String> hidden = new LinkedHashSet<>();
        if (userFile != null && Files.isReadable(userFile)) {
            try {
                // Read as the Providers dialog reads it, so what it lists is what loads here
                final ProviderStore.Contents contents = ProviderStore.read(Files.readAllBytes(userFile));
                int read = 0;
                for (final ProviderStore.Found found : contents.entries()) {
                    switch (found) {
                        case ProviderStore.Found.Read r -> {
                            read++;
                            if (merged.put(r.provider().id(), r.provider()) != null) {
                                log.info("Provider '{}' overridden by {}", r.provider().id(), userFile);
                            }
                        }
                        // One entry the parser cannot read costs that entry, not the file
                        case ProviderStore.Found.Kept k -> log.error("Dropping {} of {}: {}",
                                k.why().named(), userFile, k.why().reason());
                    }
                }
                hidden.addAll(contents.hidden());
                log.info("Merged {} provider definition(s) and {} hidden from {}",
                        read, hidden.size(), userFile);
            } catch (final IOException | RuntimeException ex) {
                // A broken user file must not stop the application starting; it must be loud.
                log.error("Ignoring {}: {}", userFile, ex.getMessage());
            }
        }

        // One bad user entry should cost that entry, not every provider in the list. A built-in
        // that fails is a fault in the shipped file and still deserves to stop the build.
        merged.values().removeIf(p -> {
            try {
                validate(p);
                return false;
            } catch (final RuntimeException ex) {
                log.error("Dropping provider '{}': {}", p.id(), ex.getMessage());
                return true;
            }
        });
        return new ProviderCatalog(merged, hidden);
    }

    /**
     * Everything that must be true of a definition, wherever it came from: the check the loader
     * runs over every entry, shipped or the user's, and the one the Providers dialog runs before it
     * saves ({@link ProviderStore#check}). One check, so that a file edited by hand and a form
     * filled in meet the same rules – a template without {@code {x}} from a hand-edited file would
     * put one image under four tiles in the cache, for good.
     * <p>
     * Null-safe over what Jackson may leave null, because a hand-edited entry may omit anything.
     *
     * @throws Refusal saying why, for a person to read
     */
    static void validate(final @NotNull Provider p) {
        if (!Provider.ID.matcher(p.id()).matches()) {
            throw new Refusal(ProviderReason.ID_UNFIT, p.id());
        }
        final Provider.Auth auth = p.authOrNone();
        if (auth.kind() == Provider.AuthKind.HEADER) {
            if (auth.name() == null || auth.name().isBlank()) {
                throw new Refusal(ProviderReason.HEADER_NAME_MISSING);
            }
            if (auth.value() != null && !auth.value().contains("{key}")) {
                throw new Refusal(ProviderReason.HEADER_VALUE_WITHOUT_KEY);
            }
        }
        if (p.displayName() == null || p.displayName().isBlank()) {
            throw new Refusal(ProviderReason.NAME_MISSING);
        }
        if (p.attribution() == null || p.attribution().isBlank()) {
            throw new Refusal(ProviderReason.ATTRIBUTION_MISSING);
        }
        if (p.themes().isEmpty()) {
            throw new Refusal(ProviderReason.NO_MAPS);
        }
        for (final int zoom : new int[]{p.minZoom(), p.maxZoom()}) {
            if (zoom < 0 || zoom > MAX_ZOOM) {
                throw new Refusal(ProviderReason.ZOOM_OUT_OF_RANGE, p.id(), String.valueOf(zoom));
            }
        }
        // A null host would fail every tile of the map, each reported as a server not answering
        if (p.subdomains() != null) {
            for (final String host : p.subdomains()) {
                if (host == null || host.isBlank()) {
                    throw new Refusal(ProviderReason.SUBDOMAIN_BLANK, p.id());
                }
            }
        }
        // A language names a folder in the cache (D25), so it is held to what a tile key takes
        if (p.languages() != null) {
            for (final String lang : p.languages()) {
                if (!TileKey.LANG.matcher(lang).matches()) {
                    throw new Refusal(ProviderReason.LANGUAGE_UNFIT, lang);
                }
            }
        }
        final Set<String> themeIds = new HashSet<>();
        for (final Provider.Theme t : p.themes()) {
            final String themeId = Objects.requireNonNullElse(t.id(), "");
            if (!Provider.THEME_ID.matcher(themeId).matches()) {
                throw new Refusal(ProviderReason.MAP_ID_UNFIT, themeId);
            }
            // Ignoring case: the folder is lower case, so USGSTopo and usgstopo would share one
            if (!themeIds.add(themeId.toLowerCase(Locale.ROOT))) {
                throw new Refusal(ProviderReason.MAP_ID_TWICE, themeId);
            }
            final String template = p.templateOf(t);
            if (template == null || !template.startsWith("http://") && !template.startsWith("https://")) {
                throw new Refusal(ProviderReason.ADDRESS_NOT_HTTP, themeId);
            }
            // The typo first: "{zoom} is unknown" says more about "{zoom}/{x}/{y}" than "no {z}"
            final Matcher m = TOKEN.matcher(template);
            while (m.find()) {
                if (!KNOWN_TOKENS.contains(m.group(1))) {
                    throw new Refusal(ProviderReason.ADDRESS_UNKNOWN_TOKEN, p.id(), themeId, m.group(1),
                            String.valueOf(KNOWN_TOKENS));
                }
            }
            for (final String token : new String[]{"{z}", "{x}", "{y}"}) {
                if (!template.contains(token)) {
                    throw new Refusal(ProviderReason.ADDRESS_WITHOUT_TOKEN, themeId, token);
                }
            }
            if (auth.kind() == Provider.AuthKind.QUERY && !template.contains("{key}")) {
                throw new Refusal(ProviderReason.ADDRESS_WITHOUT_KEY, p.id(), themeId);
            }
            for (final int zoom : new int[]{p.minZoomOf(t), p.maxZoomOf(t)}) {
                if (zoom < 0 || zoom > MAX_ZOOM) {
                    throw new Refusal(ProviderReason.MAP_ZOOM_OUT_OF_RANGE, p.id(), themeId, String.valueOf(zoom));
                }
            }
            if (p.maxZoomOf(t) < p.minZoomOf(t)) {
                throw new Refusal(ProviderReason.ZOOMS_INVERTED, p.id(), themeId);
            }
        }
    }

    /** Everything known, hidden or not. What the Providers dialog lists. */
    public @NotNull Collection<Provider> all() {
        return byId.values();
    }

    /** What the pickers offer: everything the user has not hidden. */
    public @NotNull List<Provider> visible() {
        return byId.values().stream().filter(p -> !hidden.contains(p.id())).toList();
    }

    public @NotNull Set<String> hidden() {
        return hidden;
    }

    public boolean isHidden(final @NotNull String id) {
        return hidden.contains(id);
    }

    public @NotNull Optional<Provider> find(final @NotNull String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public @NotNull Provider require(final @NotNull String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException(
                "Unknown provider '" + id + "'. Known: " + byId.keySet()));
    }

    public int size() {
        return byId.size();
    }
}
