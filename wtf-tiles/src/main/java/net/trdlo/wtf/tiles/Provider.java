package net.trdlo.wtf.tiles;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A tile provider, as read from {@code providers.json}. Data, not code: adding a provider is
 * editing a file, which is the point – the best tiles for a place are usually local, and no shipped
 * shortlist will ever contain every national mapping agency. See docs/design/providers.md.
 *
 * @param languages the codes the server takes for {@code {lang}}, which are all a map language may
 *                  be chosen from; null for a provider that names none, where any may be typed.
 *                  Only a theme whose address has {@code {lang}} has a language at all. The first
 *                  is the provider's own, which a map is in where nothing names one
 *                  ({@link #defaultLanguage}).
 * @param logo      the logo its credit is printed with, for a provider whose terms ask for one with
 *                  the attribution, as Mapy.com's do: the name of an SVG file WTF bundles beside the
 *                  shipped definitions ({@link ProviderCatalog#logo}). Null for a credit in words
 *                  only, which is every other provider's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Provider(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String urlTemplate,
        @Nullable Auth auth,
        int minZoom,
        int maxZoom,
        @Nullable TileFormat expectedFormat,
        @NotNull List<Theme> themes,
        @Nullable List<String> subdomains,
        @NotNull String attribution,
        @Nullable String signupUrl,
        @Nullable String signupHint,
        @Nullable String termsUrl,
        boolean bulkDownload,
        @Nullable String quotaNote,
        @Nullable LonLatBounds coverage,
        @Nullable List<String> languages,
        @Nullable String logo) {

    /**
     * The device names Windows reserves, in any case and with any extension: {@code con.pdf} is the
     * console, and no folder can be called {@code aux}. One pattern for every name WTF makes – the
     * cache's folders here, an export's files in wtf-compose.
     */
    public static final Pattern DEVICE_NAME =
            Pattern.compile("(?i:con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\..*)?");

    /**
     * What a provider id may be. It names a folder in the tile cache, so it has to survive every
     * file system this runs on and be one folder, never a path: lower case, because Windows would
     * not tell {@code Esri} and {@code esri} apart while Linux would, and no separator and no
     * {@code ..}, because a hand-edited file must not be able to send tiles outside the cache. Not
     * a {@linkplain #DEVICE_NAME device name}, which Windows will not make a folder of, and no dot
     * at the end, which Windows drops: {@code a.} would be the folder {@code a} there.
     */
    public static final Pattern ID = Pattern.compile(
            "(?!" + DEVICE_NAME.pattern() + "$)[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9_-])?");

    /**
     * What a map id may be: the same, in either case.
     * <p>
     * The case is the server's. The id goes into the address through {@code {theme}}, and USGS
     * serves {@code USGSTopo} and Esri {@code World_Imagery}, not their lower-case forms. It is
     * compared ignoring case all the same, because its folder in the cache is lower case, and two
     * maps told apart only by their case would share one.
     */
    public static final Pattern THEME_ID = Pattern.compile(
            "(?!" + DEVICE_NAME.pattern() + "$)[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62}[A-Za-z0-9_-])?");

    /** How the key reaches the server. */
    public enum AuthKind { NONE, HEADER, QUERY, BASIC }

    /**
     * Where the key goes, and in what.
     *
     * @param name  the header, for {@code HEADER}; the parameter, for {@code QUERY}, where it is
     *              only a name for the {@code {key}} in the address
     * @param value what a {@code HEADER} carries, with {@code {key}} where the key goes – Stadia
     *              wants {@code Stadia-Auth {key}} in {@code Authorization}. Null sends the key alone.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Auth(@NotNull AuthKind kind, @Nullable String name, @Nullable String value) {
        public static final Auth NONE = new Auth(AuthKind.NONE, null);

        public Auth(final @NotNull AuthKind kind, final @Nullable String name) {
            this(kind, name, null);
        }

        /** What the header says for this key. */
        public @NotNull String headerValue(final @NotNull String apiKey) {
            return value == null ? apiKey : value.replace("{key}", apiKey);
        }
    }

    /**
     * One map style of a provider. Everything nullable overrides the provider's value, which is why
     * an aerial theme can declare its own {@code maxZoom} and its own format without a second
     * provider entry.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Theme(
            @NotNull String id,
            @NotNull String displayName,
            @Nullable Integer minZoom,
            @Nullable Integer maxZoom,
            @Nullable TileFormat expectedFormat,
            @Nullable String urlTemplate,
            boolean transparent,
            @Nullable String note) {
    }

    public Provider {
        if (id.isBlank()) {
            throw new IllegalArgumentException("Provider id is required");
        }
        // A hand-edited entry may leave the list out; the catalog then refuses the provider with a
        // reason a person can read, rather than Jackson refusing the file
        themes = themes == null ? List.of() : List.copyOf(themes);
        if (auth == null) {
            auth = Auth.NONE;
        }
        // Empty is the same as absent, and absent is what the store writes nothing for
        final List<String> named = languages == null ? List.of() : languages.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        languages = named.isEmpty() ? null : named;
        // The same of a logo: a credit in words only
        if (logo != null && logo.isBlank()) {
            logo = null;
        }
    }

    public @NotNull Auth authOrNone() {
        return auth == null ? Auth.NONE : auth;
    }

    public boolean needsKey() {
        return authOrNone().kind() != AuthKind.NONE;
    }

    /**
     * Whether this provider has tiles everywhere.
     * <p>
     * <b>Absent means global</b>, which is the honest default: a provider whose coverage nobody
     * has written down is one nothing should assume about, and assuming the world is the
     * assumption that fails visibly rather than silently. A national provider drawn outside its
     * country is a white rectangle, and that is what this field exists to predict – a world map
     * asked of Swisstopo is Switzerland in the middle of nothing, which is a poor first
     * impression and an unanswerable bug report.
     * <p>
     * The box is deliberately a box. The real coverage of a national agency is its border, and
     * carrying every border here to answer "roughly, where is this useful" would be carrying a
     * geometry library into the tile module for one question. Where the exact outline matters –
     * the shape of a print – the subject outline library has it.
     */
    public boolean isGlobal() {
        return coverage == null;
    }

    /** The coverage box, or the whole world for a provider that did not state one. */
    public @NotNull LonLatBounds coverageOrWorld() {
        return coverage == null ? WORLD : coverage;
    }

    /** The whole of Web Mercator, which is where a global provider is expected to have tiles. */
    public static final LonLatBounds WORLD =
            new LonLatBounds(-180, -Mercator.MAX_LATITUDE, 180, Mercator.MAX_LATITUDE);

    /**
     * A place this provider is expected to have a tile for, for a request that only has to come
     * back with an image – testing a key, probing a format.
     * <p>
     * The middle of the coverage box, which for a country is inside it; for a global provider it
     * is the middle of the world, which is open ocean, so that one is nudged onto land. A key
     * test that fails because the probe asked for a tile the provider legitimately does not have
     * teaches the user nothing and blames the wrong thing.
     */
    public @NotNull LonLatBounds.Point probePoint() {
        if (coverage == null) {
            return new LonLatBounds.Point(2.35, 48.86);     // Paris: land, and everybody has it
        }
        return new LonLatBounds.Point(coverage.centerLon(), coverage.centerLat());
    }

    public @NotNull Optional<Theme> theme(final @NotNull String themeId) {
        return themes.stream().filter(t -> t.id().equalsIgnoreCase(themeId)).findFirst();
    }

    public @NotNull Theme requireTheme(final @NotNull String themeId) {
        return theme(themeId).orElseThrow(() -> new IllegalArgumentException(
                "Provider " + id + " has no theme '" + themeId + "'. Known: "
                        + themes.stream().map(Theme::id).toList()));
    }

    public int minZoomOf(final @NotNull Theme theme) {
        return theme.minZoom() != null ? theme.minZoom() : minZoom;
    }

    public int maxZoomOf(final @NotNull Theme theme) {
        return theme.maxZoom() != null ? theme.maxZoom() : maxZoom;
    }

    public @Nullable TileFormat expectedFormatOf(final @NotNull Theme theme) {
        return theme.expectedFormat() != null ? theme.expectedFormat() : expectedFormat;
    }

    public @NotNull String templateOf(final @NotNull Theme theme) {
        return theme.urlTemplate() != null ? theme.urlTemplate() : urlTemplate;
    }

    /**
     * Whether the theme has {@code @2x} tiles: its address takes {@code {scale}}, which is where the
     * provider puts {@code @2x}. A theme of the same provider that has none says so with an address
     * of its own without the token, as Mapy.com's aerial map does.
     */
    public boolean offersRetina(final @NotNull Theme theme) {
        return templateOf(theme).contains("{scale}");
    }

    /** Whether the theme's address takes {@code {lang}}, which makes the language part of its tiles (D25). */
    public boolean takesLanguage(final @NotNull Theme theme) {
        return templateOf(theme).contains("{lang}");
    }

    /**
     * The language a map of this provider is lettered in where nothing names one (D40): the first
     * its definition lists – for Mapy.com Czech, its server's own default – or English for a
     * provider that lists none. A project file without {@code source.lang} is read in it, by the
     * command line and the editor alike, {@code wtf-tiles} takes it without {@code --lang}, and the
     * two dialogs' Tests ask in it.
     * <p>
     * <b>The definition's, not the machine's.</b> The engine has no locale, and a file has to mean
     * the same print wherever it is opened; the system's language is what the window starts a new
     * project in, where somebody is looking and the toolbar shows it. {@link #key} still refuses a
     * lettered map without a language, since this is to be asked only where nothing names one.
     */
    public @NotNull String defaultLanguage() {
        return languages == null ? "en" : languages.getFirst();
    }

    /**
     * One tile of a theme of this provider, with the language where the theme has one and without
     * it where it does not – the one place that decides, so that a tile of OpenStreetMap is one
     * file whatever language the map was being looked at in.
     *
     * @param lang the map language, which a theme without {@code {lang}} ignores
     * @throws IllegalArgumentException when the theme takes a language and none was given
     */
    public @NotNull TileKey key(final @NotNull String themeId, final @Nullable String lang,
                                final int zoom, final int x, final int y) {
        final Theme theme = requireTheme(themeId);
        return new TileKey(id, themeId, takesLanguage(theme) ? requireLang(theme, lang) : null, zoom, x, y);
    }

    private @NotNull String requireLang(final @NotNull Theme theme, final @Nullable String lang) {
        if (lang == null) {
            throw new IllegalArgumentException("The map " + id + "/" + theme.id()
                    + " is lettered in a language, and none was given");
        }
        return lang;
    }

    /**
     * Fills the template for one tile. Tokens are named, not positional, which is what lets one
     * mechanism serve plain XYZ, RESTful WMTS, KVP WMTS query parameters and ArcGIS's
     * {@code {z}/{y}/{x}} without a line of per-provider code.
     * <p>
     * The key goes in {@linkplain #encoded encoded}. It is whatever its provider issued, and a
     * space, an {@code &}, a {@code #} or a {@code +} put in as it is ends the address or changes
     * what it asks. The other tokens are numbers, and ids and language codes held to characters an
     * address takes as they are; a subdomain is the definition's.
     *
     * @param key the tile, whose language fills {@code {lang}}
     * @param apiKey the credential, or null when the provider needs none
     */
    public @NotNull String url(final @NotNull TileKey key, final @Nullable String apiKey) {
        final Theme theme = requireTheme(key.themeId());
        final String template = templateOf(theme);
        if (key.scale() != 1 && !offersRetina(theme)) {
            throw new IllegalArgumentException("The map " + id + "/" + theme.id() + " has no @2x tiles");
        }
        final String lang = takesLanguage(theme) ? requireLang(theme, key.lang()) : "";
        String url = template
                .replace("{theme}", theme.id())
                .replace("{z}", Integer.toString(key.zoom()))
                .replace("{x}", Integer.toString(key.x()))
                .replace("{y}", Integer.toString(key.y()))
                .replace("{scale}", key.scale() == 1 ? "" : "@" + key.scale() + "x")
                .replace("{lang}", lang);
        if (url.contains("{s}")) {
            final List<String> subs = subdomains == null || subdomains.isEmpty() ? List.of("a") : subdomains;
            // deterministic, so the same tile always goes to the same host and caches predictably
            final int i = Math.floorMod(key.x() * 31 + key.y(), subs.size());
            url = url.replace("{s}", subs.get(i));
        }
        if (url.contains("{key}")) {
            url = url.replace("{key}", apiKey == null ? "" : encoded(apiKey));
        }
        return url;
    }

    /**
     * A value as one part of an address: its UTF-8 bytes percent-encoded, all but the letters,
     * digits and {@code - . _ ~} that RFC 3986 leaves unreserved, so that it means itself in a path
     * and in a query alike. A key of only those, as most are, makes the same address as before.
     * A space is {@code %20}, not the {@code +} of a form, which a path takes as a plus.
     */
    static @NotNull String encoded(final @NotNull String value) {
        final StringBuilder out = new StringBuilder(value.length());
        for (final byte b : value.getBytes(StandardCharsets.UTF_8)) {
            final int c = b & 0xFF;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(HEX.toHexDigits(b));
            }
        }
        return out.toString();
    }

    /** Upper case, as RFC 3986 asks of the digits of a percent-encoding. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** The template with the credential removed, for logging. Never log a resolved URL. */
    public @NotNull String redactedTemplate() {
        return urlTemplate.replaceAll("(?i)([?&](?:api_?key|key|access_token|apikey)=)[^&]*", "$1***");
    }

    public @NotNull String cacheDirName() {
        return id.toLowerCase(Locale.ROOT);
    }
}
