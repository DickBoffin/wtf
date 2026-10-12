package net.trdlo.wtf.studio.outline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.compose.ShapeImport;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.tiles.LonLatBounds;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The country and region outlines that ship with the application.
 * <p>
 * The subject of a print is normally a border, and hunting down a dataset, comparing licenses and
 * converting a shapefile is not a thing anybody should have to do in order to put Bavaria on a
 * wall. Five thousand entries ship inside the jar – the continents, every country, the parts a
 * country is made of, and every state, province and region – from Natural Earth's public domain
 * 1:10m data. Importing your own file stays available for what this does not have; see
 * docs/design/subject-outlines.md.
 * <p>
 * <b>Four levels, and they are not a tree.</b> France is in Europe, and French Guiana is not; the
 * levels say how broad a thing is, not what it is inside. So a search answers level by level –
 * continents, then countries, then parts of countries, then regions – and never pretends that one
 * contains the next.
 * <p>
 * <b>Loaded in two halves.</b> The index is a megabyte and describes everything, so it is read
 * once and kept. The geometry is fifty-one megabytes and is read one country at a time, when an
 * entry is actually chosen – a list box must never cost a parse of the world. Choosing Bavaria
 * reads Germany's file, which also holds its fifteen neighbors' states, and the last three files
 * are remembered because the next thing a person does is usually to try the one next to it.
 * <p>
 * <b>No opinion is taken about what a country is.</b> Every boundary in the source ships, at both
 * levels, including the ones it marks disputed or indeterminate, and selecting one is not a claim.
 * The reasoning is in the design document; the short version is that shipping more boundaries
 * rather than fewer is what keeps the choice with the person doing the printing.
 */
public final class OutlineLibrary {

    private static final Logger log = LoggerFactory.getLogger(OutlineLibrary.class);

    private static final String ROOT = "/net/trdlo/wtf/studio/outlines/";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The levels, broadest first; the numbers are what the index file says. */
    public static final int CONTINENT = 0, COUNTRY = 1, PART = 2, REGION = 3;

    /** Every level, in the order results are shown. */
    public static final List<Integer> LEVELS = List.of(CONTINENT, COUNTRY, PART, REGION);

    /**
     * One level's share of a search.
     *
     * @param total how many entries at this level matched, of which {@code entries} are the best
     */
    public record Group(int level, @NotNull List<Entry> entries, int total) {

        public boolean isTruncated() {
            return entries.size() < total;
        }
    }

    /** One outline, as the index describes it. The geometry is not here; see {@link #geometry}. */
    /**
     * @param name        the English name, which is also what a project file records
     * @param localName   the name in the interface language, when it has one that differs
     * @param localParent the country's name in the interface language, likewise
     */
    public record Entry(@NotNull String id, @NotNull String name, @Nullable String alsoKnownAs,
                        @Nullable String longName,
                        @Nullable String iso, @Nullable String parent, @Nullable String kind,
                        int level, @NotNull LonLatBounds bounds, int points,
                        @NotNull String file, @Nullable String key,
                        @Nullable String localName, @Nullable String localParent) {

        /** The name to show: the interface language's where there is one. */
        public @NotNull String shown() {
            return localName != null ? localName : name;
        }

        /** "Bavaria – Germany" for a region, "Germany" for a country. What the list shows. */
        public @NotNull String label() {
            final String country = localParent != null ? localParent : parent;
            return country == null || country.isBlank() || isMainPart() ? shown() : shown() + " – " + country;
        }

        /**
         * The piece of a country that carries its name – the contiguous United States, mainland
         * Georgia. Labeled "Georgia – Georgia" it read as a mistake; the picker calls it the
         * main part instead.
         */
        public boolean isMainPart() {
            return level == PART && name.equalsIgnoreCase(parent);
        }
    }

    /**
     * An entry's names as a search compares them, folded once – when the library is read, or its
     * language changes – and not for five thousand entries on every keystroke. The words of each
     * name are split out once too, not by a regular expression compiled per entry per keystroke.
     */
    private record Folded(@NotNull String name, @NotNull List<String> nameWords,
                          @Nullable String localName, @NotNull List<String> localWords,
                          @Nullable String alsoKnownAs, @Nullable String longName, @Nullable String iso,
                          @Nullable String parent, @Nullable String localParent) {

        private static final Pattern WORDS = Pattern.compile("[\\s\\-(),.]+");

        static @NotNull Folded of(final @NotNull Entry e) {
            final String name = fold(e.name());
            final String local = foldOrNull(e.localName());
            return new Folded(name, List.of(WORDS.split(name)), local,
                    local == null ? List.of() : List.of(WORDS.split(local)),
                    foldOrNull(e.alsoKnownAs()), foldOrNull(e.longName()), foldOrNull(e.iso()),
                    foldOrNull(e.parent()), foldOrNull(e.localParent()));
        }

        private static @Nullable String foldOrNull(final @Nullable String s) {
            return s == null ? null : fold(s);
        }

        /**
         * How well this entry answers a typed phrase, already folded; lower is better.
         * <p>
         * The name decides first, and within the name a whole match beats a whole word – "america"
         * in North America – which beats a start – "American Samoa" – which beats the start of a
         * later word, which beats a stray substring. A whole word ahead of a prefix is what makes
         * "america" land on the continents rather than on an island that merely begins with it. The other names come after, and the country an entry belongs to comes last:
         * typing "germany" should offer Bavaria, but below Germany and never instead of it.
         * <p>
         * <b>A code has to match exactly.</b> Not a substring like everything else, which would
         * make "de" find every entry whose code merely contained the two letters.
         */
        int rank(final @NotNull String needle) {
            if (needle.isEmpty()) {
                return 4;
            }
            if (equal(alsoKnownAs, needle) || equal(iso, needle)) {
                return 0;
            }
            // Either name leads – the English one and the interface language's – so "germany"
            // and "německo" find the same country in the same place
            final int byName = localName == null ? nameRank(name, nameWords, needle)
                    : Math.min(nameRank(name, nameWords, needle), nameRank(localName, localWords, needle));
            if (byName < NO_MATCH) {
                return byName;
            }
            if (contains(alsoKnownAs, needle) || contains(longName, needle)) {
                return 5;
            }
            if (contains(parent, needle) || contains(localParent, needle)) {
                return 6;
            }
            return NO_MATCH;
        }

        private static int nameRank(final @NotNull String n, final @NotNull List<String> words,
                                    final @NotNull String needle) {
            if (n.equals(needle)) {
                return 0;
            }
            if (words.contains(needle)) {
                return 1;
            }
            if (n.startsWith(needle)) {
                return 2;
            }
            if (n.contains(" " + needle) || n.contains("-" + needle) || n.contains("(" + needle)) {
                return 3;
            }
            return n.contains(needle) ? 4 : NO_MATCH;
        }

        private static boolean contains(final @Nullable String haystack, final @NotNull String needle) {
            return haystack != null && haystack.contains(needle);
        }

        private static boolean equal(final @Nullable String a, final @NotNull String needle) {
            return a != null && a.equals(needle);
        }
    }

    private static final int NO_MATCH = Integer.MAX_VALUE;

    /**
     * The entries in one interface language, with their folded names.
     *
     * @param home the country whose regions go by their local names; see {@link #translate}
     */
    private record Localized(@NotNull Locale ui, @NotNull String home, @NotNull List<Entry> entries,
                             @NotNull Map<Entry, Folded> folded) {

        @NotNull Folded of(final @NotNull Entry e) {
            final Folded f = folded.get(e);
            return f != null ? f : Folded.of(e);    // an entry from before a change of language
        }
    }

    /**
     * Lower case and without accents, for both sides of a search: "cesko" finds Česko and
     * "plzen" Plzeň, because a keyboard in the wrong layout is the usual way to type a name.
     */
    static @NotNull String fold(final @NotNull String s) {
        final String lower = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) >= 0x80) {
                return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "");
            }
        }
        return lower;
    }

    private static @Nullable OutlineLibrary instance;

    /** How many country files are kept parsed: the last one read, and the two before it. */
    static final int FILES_KEPT = 3;

    /** As the index has them: English, with no local names. */
    private final List<Entry> english;
    private final String source;
    /** The entries in the language last asked for; see {@link #localized()}. */
    private volatile @Nullable Localized localized;
    /**
     * Country file to its parsed contents, the least recently read going first. Russia's is tens
     * of megabytes once parsed, and a map of every file somebody has opened would keep all of them.
     */
    private final Map<String, JsonNode> files = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(FILES_KEPT + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, JsonNode> eldest) {
                    return size() > FILES_KEPT;
                }
            });

    private OutlineLibrary(final @NotNull List<Entry> entries, final @NotNull String source) {
        this.english = List.copyOf(entries);
        this.source = source;
    }

    /**
     * The entries in the interface language as it is now, translated and folded the first time
     * it is asked for, and again whenever it has changed since. Translating once, when the
     * library was first read, left it in whatever language that happened in.
     */
    private @NotNull Localized localized() {
        final Locale ui = I18n.locale();
        final String home = homeCountry(ui);
        Localized l = localized;
        if (l == null || !l.ui().equals(ui) || !l.home().equals(home)) {
            final List<Entry> entries = translate(english, ui, home);
            final Map<Entry, Folded> folded = new java.util.IdentityHashMap<>(entries.size());
            for (final Entry e : entries) {
                folded.put(e, Folded.of(e));
            }
            l = new Localized(ui, home, entries, folded);
            localized = l;
        }
        return l;
    }

    /** The machine's country, when the interface is in its language; else none. */
    private static @NotNull String homeCountry(final @NotNull Locale ui) {
        final Locale system = Locale.getDefault(Locale.Category.FORMAT);
        return system.getLanguage().equals(ui.getLanguage()) ? system.getCountry() : "";
    }

    /** How many country files are parsed and kept, for the test of the bound. */
    int filesKept() {
        return files.size();
    }

    /**
     * The library, read on first use.
     * <p>
     * An empty library when the resources are absent, rather than an exception: the outlines are
     * data, a build that ships without them is still a working application, and every caller here
     * already has to cope with "no entry matched".
     */
    public static synchronized @NotNull OutlineLibrary get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    static @NotNull OutlineLibrary load() {
        try (final InputStream in = OutlineLibrary.class.getResourceAsStream(ROOT + "index.json")) {
            if (in == null) {
                log.info("No outline library in this build");
                return new OutlineLibrary(List.of(), "");
            }
            final long started = System.currentTimeMillis();
            final JsonNode root = JSON.readTree(in);
            final List<Entry> entries = new ArrayList<>();
            for (final JsonNode e : root.path("entries")) {
                final JsonNode box = e.path("bbox");
                entries.add(new Entry(
                        e.path("id").asText(),
                        e.path("name").asText(),
                        text(e, "alsoKnownAs"), text(e, "longName"),
                        text(e, "iso"), text(e, "parent"), text(e, "kind"),
                        e.path("level").asInt(),
                        new LonLatBounds(box.get(0).asDouble(), box.get(1).asDouble(),
                                box.get(2).asDouble(), box.get(3).asDouble()),
                        e.path("points").asInt(),
                        e.path("file").asText(),
                        text(e, "key"), null, null));
            }
            log.debug("Outline library: {} entries in {} ms", entries.size(),
                    System.currentTimeMillis() - started);
            return new OutlineLibrary(entries, root.path("source").asText(""));
        } catch (final IOException | RuntimeException ex) {
            log.warn("Could not read the outline library: {}", ex.toString());
            return new OutlineLibrary(List.of(), "");
        }
    }

    /**
     * The names in the interface language, where there is a source for them.
     * <p>
     * The index is English. A continent's name is in the bundle; a country's comes from the JDK,
     * which knows every ISO code's name in every language; a region keeps its English name, except
     * in the country the interface is at home in – a Czech on a Czech machine gets "Jihomoravský"
     * rather than "South Moravian", which is what the index already calls its local name. English
     * gets nothing, because the index is already English.
     *
     * @param home the machine's country when the interface is in its language, else empty
     */
    private static @NotNull List<Entry> translate(final @NotNull List<Entry> english, final @NotNull Locale ui,
                                                  final @NotNull String home) {
        if (ui.getLanguage().equals(Locale.ENGLISH.getLanguage())) {
            return english;
        }
        final List<Entry> entries = new ArrayList<>(english);

        // The countries first, so that a region can be labeled with its country's name
        final Map<String, String> countries = new java.util.HashMap<>();
        for (final Entry e : entries) {
            if (e.level() == COUNTRY && e.iso() != null) {
                final String local = Locale.of("", e.iso()).getDisplayCountry(ui);
                if (!local.isBlank() && !local.equals(e.iso())) {
                    countries.put(e.name(), local);
                    if (e.longName() != null) {
                        countries.putIfAbsent(e.longName(), local);
                    }
                }
            }
        }
        entries.replaceAll(e -> {
            final String found = switch (e.level()) {
                case CONTINENT -> {
                    final String key = "outline.continent." + e.id().substring(e.id().indexOf(':') + 1);
                    yield I18n.bundle().containsKey(key) ? I18n.get(key) : null;
                }
                case COUNTRY -> countries.get(e.name());
                default -> !home.isEmpty() && e.iso() != null && e.iso().startsWith(home + "-")
                        ? e.alsoKnownAs() : null;
            };
            final String local = found == null || found.equals(e.name()) ? null : found;
            final String parent = e.parent() == null ? null : countries.get(e.parent());
            if (local == null && parent == null) {
                return e;
            }
            return new Entry(e.id(), e.name(), e.alsoKnownAs(), e.longName(), e.iso(), e.parent(),
                    e.kind(), e.level(), e.bounds(), e.points(), e.file(), e.key(), local, parent);
        });
        return List.copyOf(entries);
    }

    public @NotNull List<Entry> all() {
        return localized().entries();
    }

    public boolean isEmpty() {
        return english.isEmpty();
    }

    /** What the About dialog says about where these came from. */
    public @NotNull String source() {
        return source;
    }

    /**
     * The entries matching a typed phrase, one group per level, broadest level first.
     * <p>
     * <b>Level by level, not best match first.</b> A single ranked list put Georgia the country,
     * Georgia the state, and a dozen things merely containing the word in one column, and the
     * only way to tell a continent from a province was to read the parent. Grouped, the answer to
     * "america" is two continents, then the countries, then the states, each under a heading.
     * Within a level the best match leads – see {@link Folded#rank}.
     * <p>
     * Substring rather than prefix, because "carolina" should find both of them and nobody types
     * "North" first. An empty phrase is every entry, which is the list somebody browses. Levels
     * with nothing in them are left out.
     *
     * @param perLevel how many of each level to return; {@link Group#total} says how many there were
     */
    public @NotNull List<Group> grouped(final @NotNull String phrase, final int perLevel) {
        final String needle = fold(phrase.trim());
        final java.util.Comparator<String> byName = I18n.collator();
        final Localized l = localized();
        final List<Group> out = new ArrayList<>();
        for (final int level : LEVELS) {
            // Ranked once each: the comparator would otherwise work each rank out again for every
            // comparison
            final List<Entry> matched = l.entries().stream()
                    .filter(e -> e.level() == level)
                    .map(e -> Map.entry(e, l.of(e).rank(needle)))
                    .filter(r -> r.getValue() < Integer.MAX_VALUE)
                    .sorted(Map.Entry.<Entry, Integer>comparingByValue()
                            .thenComparing(r -> r.getKey().shown(), byName))
                    .map(Map.Entry::getKey)
                    .toList();
            if (!matched.isEmpty()) {
                out.add(new Group(level, matched.subList(0, Math.min(perLevel, matched.size())),
                        matched.size()));
            }
        }
        return out;
    }

    /** The same, as one list in the order it is shown – broadest level first. */
    public @NotNull List<Entry> search(final @NotNull String phrase, final int perLevel) {
        return grouped(phrase, perLevel).stream().flatMap(g -> g.entries().stream()).toList();
    }

    /**
     * The one entry pressing Enter should take: the best match at any level.
     * <p>
     * Not simply the first in the list. The list is broadest first, so "georgia" shows the
     * countries before the states – and that is right – but somebody who typed "bavaria" exactly
     * should get Bavaria even if a country happened to contain the letters.
     */
    public @Nullable Entry best(final @NotNull List<Group> groups, final @NotNull String phrase) {
        final String needle = fold(phrase.trim());
        final Localized l = localized();
        Entry best = null;
        int bestRank = Integer.MAX_VALUE;
        for (final Group g : groups) {
            for (final Entry e : g.entries()) {
                final int r = l.of(e).rank(needle);
                if (r < bestRank) {        // strictly better: a tie goes to the broader level
                    best = e;
                    bestRank = r;
                }
            }
        }
        return best;
    }

    public @NotNull java.util.Optional<Entry> find(final @NotNull String id) {
        return all().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /**
     * The outline itself, read from the jar on demand.
     *
     * @throws IOException when the resource is missing or unreadable, which means a broken build
     */
    public @NotNull Geometry geometry(final @NotNull Entry entry) throws IOException {
        final JsonNode file = fileOf(entry.file());
        final JsonNode node = entry.key() == null ? file : file.path(entry.key());
        if (node == null || node.isMissingNode()) {
            throw new IOException("The outline library has no geometry for " + entry.id());
        }
        return ShapeImport.readGeoJson(node, entry.name());
    }

    private @NotNull JsonNode fileOf(final @NotNull String name) throws IOException {
        final JsonNode cached = files.get(name);
        if (cached != null) {
            return cached;
        }
        try (final InputStream in = OutlineLibrary.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IOException("The outline library is missing " + name);
            }
            final JsonNode parsed = JSON.readTree(in);
            files.put(name, parsed);
            return parsed;
        }
    }

    private static @Nullable String text(final @NotNull JsonNode node, final @NotNull String field) {
        final JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
