package net.trdlo.wtf.studio;

import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.MissingTilePolicy;
import net.trdlo.wtf.compose.PrintSize;
import net.trdlo.wtf.compose.ScaleMark;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderStore;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The interface strings, checked for the mistakes that only a reader notices, and against the
 * code for keys that are missing or unused.
 */
class MessagesTest {

    private static final String BUNDLE = "/net/trdlo/wtf/studio/messages.properties";

    /** Every translation beside the English, by language tag. */
    private static final List<String> TRANSLATIONS = List.of("cs");

    private static Map<String, String> load() throws IOException {
        return load(BUNDLE);
    }

    private static Map<String, String> load(final @NotNull String bundle) throws IOException {
        final Properties p = new Properties();
        try (final InputStream in = MessagesTest.class.getResourceAsStream(bundle)) {
            assertNotNull(in, () -> bundle + " is not on the test classpath");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        final Map<String, String> out = new TreeMap<>();
        p.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        assertFalse(out.isEmpty());
        return out;
    }

    private static @NotNull String translation(final @NotNull String tag) {
        return BUNDLE.replace(".properties", "_" + tag + ".properties");
    }

    /**
     * The English and every translation, each key prefixed with its language, for the checks that
     * are about typing rather than about English: a swallowed continuation is as visible in Czech.
     */
    private static Map<String, String> everyBundle() throws IOException {
        final Map<String, String> out = new TreeMap<>();
        load().forEach((k, v) -> out.put("en:" + k, v));
        for (final String tag : TRANSLATIONS) {
            load(translation(tag)).forEach((k, v) -> out.put(tag + ":" + k, v));
        }
        return out;
    }

    /**
     * A translation says everything the English says and nothing else. A key it lacks falls back
     * to English in the middle of a Czech window; a key only it has is a leftover nobody reads.
     */
    @Test
    void everyTranslationHasExactlyTheEnglishKeys() throws IOException {
        final Map<String, String> english = load();
        for (final String tag : TRANSLATIONS) {
            final Map<String, String> other = load(translation(tag));
            final List<String> missing = english.keySet().stream().filter(k -> !other.containsKey(k)).toList();
            final List<String> extra = other.keySet().stream().filter(k -> !english.containsKey(k)).toList();
            assertTrue(missing.isEmpty(), () -> tag + " lacks: " + missing);
            assertTrue(extra.isEmpty(), () -> tag + " has keys the English does not: " + extra);
        }
    }

    /**
     * And fills the same placeholders. Word order may change, so {1} may come first; but a
     * placeholder the translation drops is a number the reader never sees.
     */
    @Test
    void everyTranslationUsesTheSamePlaceholders() throws IOException {
        final Pattern placeholder = Pattern.compile("\\{(\\d+)");
        final Map<String, String> english = load();
        final List<String> bad = new ArrayList<>();
        for (final String tag : TRANSLATIONS) {
            load(translation(tag)).forEach((key, value) -> {
                final String en = english.get(key);
                if (en != null && !placeholders(placeholder, en).equals(placeholders(placeholder, value))) {
                    bad.add(tag + ":" + key);
                }
            });
        }
        assertTrue(bad.isEmpty(), () -> "placeholders differ from the English: " + bad);
    }

    private static @NotNull java.util.Set<String> placeholders(final @NotNull Pattern p, final @NotNull String v) {
        final java.util.Set<String> out = new java.util.TreeSet<>();
        final Matcher m = p.matcher(v);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void noValueHasARunOfSpaces() throws IOException {
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((key, value) -> {
            if (Pattern.compile("\\S {2,}\\S").matcher(value).find()) {
                bad.add(key + " = " + value);
            }
        });
        assertTrue(bad.isEmpty(),
                () -> "a run of spaces is not a separator, it is a gap:\n  " + String.join("\n  ", bad));
    }

    @Test
    void noValueHasALiteralEscape() throws IOException {
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((key, value) -> {
            // A real newline is fine; the two characters backslash and n are a swallowed
            // continuation, and they reach the screen exactly as written.
            if (value.contains("\\n") || value.contains("\\t")) {
                bad.add(key);
            }
        });
        assertTrue(bad.isEmpty(), () -> "literal escapes left in: " + bad);
    }

    @Test
    void noValueIsEmptyOrUntrimmed() throws IOException {
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((key, value) -> {
            if (value.isBlank() || !value.equals(value.strip())) {
                bad.add(key);
            }
        });
        assertTrue(bad.isEmpty(), () -> "blank or untrimmed: " + bad);
    }

    /**
     * A hyphen joins words; an en dash separates clauses. Getting this wrong is invisible until
     * somebody who cares reads the dialog, and then it is the only thing they can see.
     */
    @Test
    void clauseBreaksUseAnEnDashRatherThanAHyphen() throws IOException {
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((key, value) -> {
            if (!hyphensAsDashes(value).isEmpty()) {
                bad.add(key);
            }
        });
        assertTrue(bad.isEmpty(),
                () -> "these separate clauses with a hyphen; use – : " + bad);
    }

    /**
     * A hyphen with white space on each side and something beyond the white space on each side: a
     * dash typed as a hyphen, whether the white space is a space or a line break. A hyphen in a
     * word, a flag, a negative number or an arrow touches something on one side at least.
     */
    private static final Pattern HYPHEN_AS_DASH = Pattern.compile("(?<=\\S)\\s+-(?=\\s+\\S)");

    /** The places in this text where a hyphen does a dash's job. */
    private static @NotNull List<Integer> hyphensAsDashes(final @NotNull String text) {
        final List<Integer> at = new ArrayList<>();
        final Matcher m = HYPHEN_AS_DASH.matcher(text);
        while (m.find()) {
            at.add(m.end() - 1);
        }
        return at;
    }

    /**
     * A length is written by {@link Units}, number and unit together, so that it is inches when
     * inches are chosen (D32). A value that writes "mm" or "km" after a placeholder says a metric
     * unit under inches – except the few that are shown only under millimeters, each beside its
     * imperial twin.
     */
    @Test
    void noLengthIsWrittenInAMetricUnit() throws IOException {
        final java.util.Set<String> metricOnly = java.util.Set.of(
                "status.resolution", "status.resolutionKm",     // status.resolutionFt, .resolutionMi
                "area.unit.short.KILOMETERS");                   // offered only under millimeters
        final Pattern metric = Pattern.compile("\\{\\d[^}]*}[\\s\\u00a0]?(mm|cm|m|km)(?!\\p{L})");
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((tagged, value) -> {
            if (metric.matcher(value).find() && !metricOnly.contains(tagged.substring(tagged.indexOf(':') + 1))) {
                bad.add(tagged + " = " + value);
            }
        });
        assertTrue(bad.isEmpty(), () -> "a metric unit written after a number:\n  " + String.join("\n  ", bad));
    }

    /**
     * A count takes a label, not a plural: "Sheets: 6" (D15). "format(s)" is the English way of
     * not choosing, and Czech has three forms to not choose between, so neither language says it.
     */
    @Test
    void noCountHedgesItsPlural() throws IOException {
        final Pattern hedged = Pattern.compile("\\p{L}\\((s|es)\\)", Pattern.CASE_INSENSITIVE);
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((key, value) -> {
            if (hedged.matcher(value).find()) {
                bad.add(key + " = " + value);
            }
        });
        assertTrue(bad.isEmpty(), () -> "a plural in parentheses; write \"Sheets: {0}\":\n  " + String.join("\n  ", bad));
    }

    /** Dimensions take a multiplication sign. "5000 x 3000" is arithmetic written as a typo. */
    @Test
    void dimensionsUseAMultiplicationSign() throws IOException {
        final Pattern letterEx = Pattern.compile("[\\d}] x [\\d{]");
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((key, value) -> {
            if (letterEx.matcher(value).find()) {
                bad.add(key);
            }
        });
        assertTrue(bad.isEmpty(), () -> "use × between dimensions: " + bad);
    }

    /**
     * Every {@code {0}} has to be filled by somebody. A message whose placeholders are not
     * contiguous from zero is a message that will throw or print a brace at the user.
     */
    @Test
    void placeholdersAreContiguousFromZero() throws IOException {
        final Pattern placeholder = Pattern.compile("\\{(\\d+)");
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((key, value) -> {
            int highest = -1;
            final Matcher m = placeholder.matcher(value);
            final boolean[] seen = new boolean[10];
            while (m.find()) {
                final int index = Integer.parseInt(m.group(1));
                if (index < 10) {
                    seen[index] = true;
                }
                highest = Math.max(highest, index);
            }
            for (int i = 0; i <= highest && i < 10; i++) {
                if (!seen[i]) {
                    bad.add(key + " skips {" + i + "}");
                }
            }
        });
        assertTrue(bad.isEmpty(), () -> String.join(", ", bad));
    }

    /**
     * And every one of them has to survive MessageFormat, which is fussy about lone quotes. With
     * numbers, which is what a {@code {0,number,0.0}} takes and what a plain {@code {0}} prints too.
     */
    @Test
    void everyMessageFormats() throws IOException {
        final Object[] args = {1, 2, 3, 4, 5, 6, 7};
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((key, value) -> {
            try {
                new MessageFormat(value, java.util.Locale.ENGLISH).format(args);
            } catch (final RuntimeException ex) {
                bad.add(key + ": " + ex.getMessage());
            }
        });
        assertTrue(bad.isEmpty(), () -> String.join("\n  ", bad));
    }

    /**
     * An apostrophe is MessageFormat's quote. In a message that goes through it, a lone one is not
     * printed: it quotes the text after it, and a placeholder in that text is printed as a brace.
     * "arm's length" came out as "arms length" that way, and nothing looked for the missing letter.
     * <p>
     * So they are counted. The reader is meant to see one apostrophe for every doubled one; a
     * lone one may only quote a brace that is meant literally, as in {@code '{key}'}. The formatted
     * text has to hold exactly as many as that – and a message that does not go through
     * MessageFormat, because it has no placeholder and the code only reads it, prints a doubled
     * one doubled.
     */
    @Test
    void everyApostropheReachesTheReader() throws IOException {
        final Code code = Code.read();
        final Map<String, String> formatted = new TreeMap<>();
        for (final String key : code.formattedKeys()) {
            formatted.put(key, "");
        }
        // "area.unit.short." + g.name(): every key of the family is formatted
        for (final String key : load().keySet()) {
            if (code.formattedStems().stream().anyMatch(key::startsWith)) {
                formatted.put(key, "");
            }
        }
        // Every refusal is formatted, with arguments or without
        EngineTextTest.reasons().forEach(r -> formatted.put(EngineText.key(r), ""));
        final List<String> bad = new ArrayList<>();
        everyBundle().forEach((tagged, value) -> {
            final String key = tagged.substring(tagged.indexOf(':') + 1);
            if (!PLACEHOLDER.matcher(value).find() && !formatted.containsKey(key)) {
                if (value.contains("''")) {
                    bad.add(tagged + " is read as it stands, so its '' is printed twice");
                }
                return;
            }
            final int meant = apostrophesMeant(value, tagged, bad);
            final String out = new MessageFormat(value, java.util.Locale.ENGLISH)
                    .format(new Object[]{1, 2, 3, 4, 5, 6, 7});
            final long shown = out.chars().filter(c -> c == '\'').count();
            if (shown != meant) {
                bad.add(tagged + " shows " + shown + " apostrophes of " + meant + ": " + out);
            }
        });
        assertTrue(bad.isEmpty(), () -> "apostrophes lost or doubled:\n  " + String.join("\n  ", bad));
    }

    /**
     * The places the code hands {@code I18n.format} a key in a variable, and a pattern for where
     * the keys it is handed are written out. The apostrophe check has to know those keys are
     * formatted: a key with no placeholder that goes through MessageFormat loses a lone apostrophe,
     * and one read as it stands prints a doubled one doubled.
     */
    private static final Map<String, Pattern> INDIRECTIONS = Map.of(
            "ProgressText.java: p.key()", Pattern.compile("Phrase\\.of\\([^,()]+,\\s*\"([^\"]+)\"\\)"),
            "Forms.java: labelKey", Pattern.compile("lengthRow\\(\\s*\"([^\"]+)\""),
            // Every refusal's key, which the check takes from EngineTextTest.reasons() instead
            "EngineText.java: key", Pattern.compile("(?!)"));

    /**
     * Every key formatted through a variable is one the apostrophe check follows. A new helper
     * that formats the key it is given has to be taught here, or its keys are checked as keys that
     * are read as they stand; and one that went is struck, so the list says what the code does.
     */
    @Test
    void everyKeyFormattedThroughAVariableIsFollowed() throws IOException {
        final Code code = Code.read();
        assertEquals(new java.util.TreeSet<>(INDIRECTIONS.keySet()), code.indirections(),
                "I18n.format handed a key in a variable: teach INDIRECTIONS where its keys are written out");
        assertTrue(code.formattedKeys().containsAll(List.of("progress.downloading", "medium.overlap")),
                () -> "the keys passed along are among the formatted ones: " + code.formattedKeys());
        assertTrue(code.formattedKeys().containsAll(List.of("project.refused.size.across",
                "project.refused.size.down")), "both sides of a ?:");
        assertTrue(code.formattedStems().contains("area.unit.short."), "a stem and a value put after it");
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d");

    /**
     * How many apostrophes a MessageFormat pattern is meant to print, noting each lone one that
     * quotes something other than a literal brace.
     */
    private static int apostrophesMeant(final @NotNull String pattern, final @NotNull String key,
                                        final @NotNull List<String> bad) {
        int meant = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) != '\'') {
                continue;
            }
            if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                meant++;
                i++;
                continue;
            }
            final int close = pattern.indexOf('\'', i + 1);
            final String quoted = pattern.substring(i + 1, close < 0 ? pattern.length() : close);
            if (!(quoted.contains("{") || quoted.contains("}")) || PLACEHOLDER.matcher(quoted).find()) {
                bad.add(key + " has a lone apostrophe that swallows \"" + quoted + "\"");
            }
            i = close < 0 ? pattern.length() : close;
        }
        return meant;
    }

    /**
     * American spelling. One locale's worth of English is a decision, not a preference: a dialog that spells a word
     * one way in one sentence and the other way in the next reads as two people arguing. The keys
     * are read too, since each is written out in the code as well.
     */
    @Test
    void spellingIsAmerican() throws IOException {
        final List<String> bad = new ArrayList<>();
        load().forEach((key, value) -> {
            misspellings(key).forEach(found -> bad.add(key + " (the key): " + found));
            misspellings(value).forEach(found -> bad.add(key + ": " + found));
        });
        assertTrue(bad.isEmpty(),
                () -> "American spelling, in every key and every string:\n  " + String.join("\n  ", bad));
    }

    /**
     * The pairs that actually occur in mapping and printing text: a stem spelled the way the check
     * forbids, and the American one. Each is written in two pieces, so that no search and replace
     * over the repository can correct it into agreeing with everything. Add to it when a word gets
     * through.
     */
    private static final String[][] SPELLINGS = {
            {"col" + "our", "color"}, {"neighb" + "our", "neighbor"},
            {"behavi" + "our", "behavior"}, {"cen" + "tre", "center"},
            {"me" + "tre", "meter"}, {"gr" + "ey", "gray"},
            {"lic" + "ence", "license"}, {"catalog" + "ue", "catalog"},
            {"recogn" + "is", "recogniz"}, {"hon" + "our", "honor"},
            {"model" + "l", "model"}, {"normal" + "is", "normaliz"},
            {"label" + "l", "label"}, {"cancel" + "l(?!ation)", "cancel"},
            {"defen" + "ce", "defense"},
    };

    /** Each stem, found wherever it is; the word it is part of is widened from there. */
    private static final List<Pattern> SPELLED = Arrays.stream(SPELLINGS)
            .map(pair -> Pattern.compile(pair[0], Pattern.CASE_INSENSITIVE))
            .toList();

    /**
     * Every word spelled the other way, each with the American word to write instead. A stem is
     * found only as one word, its letters after the first all of one case: {@code labelLength} is
     * two words run together, neither of which is spelled wrong.
     */
    private static @NotNull List<String> misspellings(final @NotNull String value) {
        final List<String> found = new ArrayList<>();
        for (int p = 0; p < SPELLINGS.length; p++) {
            final Matcher m = SPELLED.get(p).matcher(value);
            while (m.find()) {
                final String stem = m.group();
                final String tail = stem.substring(1);
                if (!tail.equals(tail.toLowerCase(Locale.ROOT)) && !stem.equals(stem.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                int from = m.start();
                while (from > 0 && isWordCharacter(value.charAt(from - 1))) {
                    from--;
                }
                int to = m.end();
                while (to < value.length() && isWordCharacter(value.charAt(to))) {
                    to++;
                }
                found.add(value.substring(from, to) + " → " + value.substring(from, m.start())
                        + american(stem, SPELLINGS[p][1], value.substring(m.end(), to)));
            }
        }
        return found;
    }

    /** What a regular expression's {@code \w} takes. */
    private static boolean isWordCharacter(final char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '_');
    }

    /**
     * The American stem in the case of the one found, and the rest of the word after it. An e that
     * the other spelling's stem shares with a d after it comes back as the ending's, as in
     * {@code centered}.
     */
    private static @NotNull String american(final @NotNull String found, final @NotNull String stem,
                                            final @NotNull String rest) {
        final boolean upper = found.equals(found.toUpperCase(Locale.ROOT));
        final String cased = upper ? stem.toUpperCase(Locale.ROOT)
                : Character.isUpperCase(found.charAt(0)) ? Character.toUpperCase(stem.charAt(0)) + stem.substring(1)
                : stem;
        final boolean sharedE = found.toLowerCase(Locale.ROOT).endsWith("e") && !stem.endsWith("e")
                && rest.toLowerCase(Locale.ROOT).startsWith("d");
        return cased + (sharedE ? (upper ? "E" : "e") : "") + rest;
    }

    // -------------------------------------------------------------------------------------------
    // The bundle against the code
    // -------------------------------------------------------------------------------------------

    /**
     * Strings in the code that are shaped like a key and start the way keys do, but are the names
     * of preferences – each of them beside the step it belongs to, which is why they look alike.
     */
    private static final java.util.Set<String> PREFERENCES = java.util.Set.of(
            "export.lastDir", "project.lastDir", "project.last", "providers.divider", "update.lastCheck",
            "download.threads");

    /** A file's name is not a key either: "splash.png". */
    private static final Pattern FILE_NAME = Pattern.compile(".*\\.(png|css|json|properties|txt|md)");

    private static final Pattern KEY_SHAPED = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-zA-Z0-9_-]+)+\\.?");

    /**
     * The interface's own source, read for the strings in it. A key is written out where it is
     * used, as a literal, so it can be found; a family of them is a literal stem and a value put
     * after it – an enum constant's name, a level, a continent.
     */
    private record Code(@NotNull java.util.Set<String> literals, @NotNull java.util.Set<String> formattedKeys,
                        @NotNull java.util.Set<String> formattedStems, @NotNull java.util.Set<String> indirections,
                        @NotNull java.util.Set<String> readKeys, @NotNull java.util.Set<String> properties) {

        private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
        private static final String FORMAT_CALL = "I18n.format(";
        private static final Pattern READ = Pattern.compile("I18n\\.get\\(\\s*\"([^\"]+)\"\\s*\\)");
        /** The names read out of app.properties and the system's properties. */
        private static final Pattern PROPERTY = Pattern.compile("getProperty\\(\\s*\"([^\"]+)\"");

        static @NotNull Code read() throws IOException {
            final java.util.Set<String> literals = new java.util.TreeSet<>();
            final java.util.Set<String> formatted = new java.util.TreeSet<>();
            final java.util.Set<String> stems = new java.util.TreeSet<>();
            final java.util.Set<String> indirections = new java.util.TreeSet<>();
            final java.util.Set<String> readKeys = new java.util.TreeSet<>();
            final java.util.Set<String> properties = new java.util.TreeSet<>();
            final java.nio.file.Path root = java.nio.file.Path.of("src", "main", "java");
            assertTrue(java.nio.file.Files.isDirectory(root), () -> root.toAbsolutePath() + " is not where the tests run");
            try (final var files = java.nio.file.Files.walk(root)) {
                for (final java.nio.file.Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    final String source = java.nio.file.Files.readString(p);
                    collect(LITERAL, source, literals);
                    formatted(source, p.getFileName().toString(), formatted, stems, indirections);
                    for (final Pattern passedAlong : INDIRECTIONS.values()) {
                        collect(passedAlong, source, formatted);
                    }
                    collect(READ, source, readKeys);
                    collect(PROPERTY, source, properties);
                }
            }
            return new Code(literals, formatted, stems, indirections, readKeys, properties);
        }

        /**
         * What each {@code I18n.format} call is handed as its key: the literals in its first
         * argument – one, or one on each side of a {@code ?:} – a literal stem ending in a dot with
         * a value put after it, or no literal at all, which is a key passed along in a variable
         * and noted as {@code File.java: expression}.
         */
        private static void formatted(final @NotNull String source, final @NotNull String file,
                                      final java.util.@NotNull Set<String> keys,
                                      final java.util.@NotNull Set<String> stems,
                                      final java.util.@NotNull Set<String> indirections) {
            for (int at = source.indexOf(FORMAT_CALL); at >= 0; at = source.indexOf(FORMAT_CALL, at + 1)) {
                final String argument = firstArgument(source, at + FORMAT_CALL.length()).strip();
                boolean named = false;
                final Matcher m = LITERAL.matcher(argument);
                while (m.find()) {
                    final String literal = m.group(1);
                    if (literal.isEmpty() || literal.startsWith(".")) {
                        continue;                                   // a suffix, not a key
                    }
                    (literal.endsWith(".") ? stems : keys).add(literal);
                    named = true;
                }
                if (!named) {
                    indirections.add(file + ": " + argument);
                }
            }
        }

        /** The text of a call's first argument, up to the comma or parenthesis that ends it. */
        private static @NotNull String firstArgument(final @NotNull String source, final int from) {
            int depth = 0;
            for (int i = from; i < source.length(); i++) {
                final char c = source.charAt(i);
                if (c == '"' || c == '\'') {
                    for (i++; i < source.length() && source.charAt(i) != c; i++) {
                        if (source.charAt(i) == '\\') {
                            i++;
                        }
                    }
                } else if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                    depth--;
                } else if ((c == ',' || c == ')') && depth == 0) {
                    return source.substring(from, i);
                }
            }
            return source.substring(from);
        }

        private static void collect(final @NotNull Pattern p, final @NotNull String source,
                                    final java.util.@NotNull Set<String> into) {
            final Matcher m = p.matcher(source);
            while (m.find()) {
                into.add(m.group(1));
            }
        }

        /** Whether the code can arrive at this key, written out or as one of a family. */
        boolean names(final @NotNull String key) {
            if (literals.contains(key)) {
                return true;
            }
            for (final String l : literals) {
                if (l.endsWith(".") && key.startsWith(l) && key.length() > l.length()) {
                    return true;                                    // "area.fit." + fit.name()
                }
                if (key.startsWith(l + ".") && key.substring(l.length() + 1).matches("[A-Z][A-Z0-9_]*")) {
                    return true;                                    // enumCombo(..., "medium.axis")
                }
            }
            return false;
        }
    }

    /**
     * Every key the code writes out has to be there, or the window shows !key! where a sentence
     * goes. Asked of every key-shaped string in a namespace the bundle uses, not just of those
     * handed straight to I18n: a key passed to a helper that labels a row is a key all the same.
     */
    @Test
    void everyKeyTheCodeNamesIsThere() throws IOException {
        final Map<String, String> messages = load();
        final java.util.Set<String> namespaces = new java.util.TreeSet<>();
        messages.keySet().forEach(k -> namespaces.add(k.substring(0, k.indexOf('.') < 0 ? k.length() : k.indexOf('.'))));
        final Code code = Code.read();
        final List<String> missing = new ArrayList<>();
        for (final String l : code.literals()) {
            if (!KEY_SHAPED.matcher(l).matches() || !namespaces.contains(l.substring(0, l.indexOf('.')))
                    || PREFERENCES.contains(l) || code.properties().contains(l) || FILE_NAME.matcher(l).matches()) {
                continue;
            }
            final String stem = l.endsWith(".") ? l : l + ".";
            if (!messages.containsKey(l) && messages.keySet().stream().noneMatch(k -> k.startsWith(stem))) {
                missing.add(l);
            }
        }
        for (final String k : code.formattedKeys()) {
            if (!messages.containsKey(k)) {
                missing.add(k);
            }
        }
        for (final String k : code.readKeys()) {
            if (!messages.containsKey(k)) {
                missing.add(k);
            }
        }
        assertTrue(missing.isEmpty(), () -> "the code names keys the bundle does not have: " + missing);
    }

    /**
     * And every key in the bundle is one the code can arrive at. A key nobody reads is a sentence
     * that was translated for nothing and will be translated again, and ten had collected.
     */
    @Test
    void everyKeyIsOneTheCodeUses() throws IOException {
        final Code code = Code.read();
        final List<String> dead = load().keySet().stream().filter(k -> !code.names(k)).toList();
        assertTrue(dead.isEmpty(), () -> "keys nothing in the code can reach: " + dead);
    }

    /**
     * The families of keys the code builds from an enum constant's name, each with its enum. The
     * scan above sees only the stem, so a constant added without a key, or a key left behind by a
     * constant that went, passes it; walking the enum does not.
     */
    private static final Map<String, Enum<?>[]> FAMILIES = Map.ofEntries(
            Map.entry("area.fit", Shapes.Simplify.values()),
            Map.entry("area.unit", growUnits()),
            Map.entry("area.unit.short", growUnits()),
            Map.entry("editor.auth", Provider.AuthKind.values()),
            Map.entry("export.format", ComposeJob.OutputFormat.values()),
            Map.entry("export.format.tip", ComposeJob.OutputFormat.values()),
            Map.entry("export.grouping.short", ComposeJob.OutputGrouping.values()),
            Map.entry("export.missing", MissingTilePolicy.values()),
            Map.entry("export.missing.hint", MissingTilePolicy.values()),
            Map.entry("export.scale.mark", ScaleMark.values()),
            Map.entry("medium.axis", Medium.Axis.values()),
            Map.entry("medium.orientation", Medium.Orientation.values()),
            Map.entry("medium.size", PrintSize.Mode.values()),
            Map.entry("providers.origin", ProviderStore.Origin.values()));

    /** The Room around it field's units, an enum the area step keeps to its own package. */
    private static Enum<?> @NotNull [] growUnits() {
        try {
            return (Enum<?>[]) Class.forName("net.trdlo.wtf.studio.steps.AreaPanel$Grow").getEnumConstants();
        } catch (final ClassNotFoundException ex) {
            throw new IllegalStateException("the area step's units have moved", ex);
        }
    }

    /** A key that ends in a constant's name: {@code medium.axis.VERTICAL}. */
    private static final Pattern CONSTANT_KEY = Pattern.compile("(.+)\\.([A-Z][A-Z0-9_]*)");

    /** Each family has exactly one key for each constant of its enum, in every language. */
    @Test
    void everyEnumFamilyHasAKeyForEachConstantAndNoOther() throws IOException {
        final List<String> bad = new ArrayList<>();
        final List<String> bundles = new ArrayList<>(List.of(BUNDLE));
        TRANSLATIONS.forEach(tag -> bundles.add(translation(tag)));
        for (final String bundle : bundles) {
            final java.util.Set<String> keys = load(bundle).keySet();
            FAMILIES.forEach((stem, constants) -> {
                final java.util.Set<String> wanted = new java.util.TreeSet<>();
                for (final Enum<?> c : constants) {
                    wanted.add(stem + "." + c.name());
                }
                final java.util.Set<String> there = new java.util.TreeSet<>();
                for (final String k : keys) {
                    final Matcher m = CONSTANT_KEY.matcher(k);
                    if (m.matches() && m.group(1).equals(stem)) {
                        there.add(k);
                    }
                }
                wanted.stream().filter(k -> !there.contains(k)).forEach(k -> bad.add(bundle + " lacks " + k));
                there.stream().filter(k -> !wanted.contains(k)).forEach(k -> bad.add(bundle + " has " + k
                        + ", which no constant of " + constants.getClass().getComponentType().getSimpleName()
                        + " names"));
            });
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n  ", bad));
    }

    /**
     * And every key that ends in a constant's name is in a family above, and every stem the
     * studio's enum picker is given: a new family is walked from its first key.
     */
    @Test
    void everyKeyNamedForAConstantIsInAFamily() throws IOException {
        final List<String> bad = new ArrayList<>();
        for (final String k : load().keySet()) {
            final Matcher m = CONSTANT_KEY.matcher(k);
            if (m.matches() && !FAMILIES.containsKey(m.group(1))) {
                bad.add(k);
            }
        }
        final Pattern picker = Pattern.compile("enumCombo\\([^;]*?,\\s*\"([^\"]+)\"\\s*\\)");
        final java.util.Set<String> stems = new java.util.TreeSet<>();
        try (final var files = java.nio.file.Files.walk(java.nio.file.Path.of("src", "main", "java"))) {
            for (final java.nio.file.Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                final Matcher m = picker.matcher(java.nio.file.Files.readString(p));
                while (m.find()) {
                    stems.add(m.group(1));
                }
            }
        }
        assertTrue(stems.size() >= 6, () -> "the scan finds the enum pickers: " + stems);
        stems.stream().filter(s -> !FAMILIES.containsKey(s)).forEach(s -> bad.add("enumCombo(..., \"" + s + "\")"));
        assertTrue(bad.isEmpty(), () -> "keys named for constants of an enum FAMILIES does not walk: " + bad);
    }

    /** A key the code reads without formatting prints its placeholders as they stand. */
    @Test
    void noKeyThatIsReadAsItStandsHasAPlaceholder() throws IOException {
        final Map<String, String> messages = load();
        final List<String> bad = Code.read().readKeys().stream()
                .filter(k -> messages.containsKey(k) && PLACEHOLDER.matcher(messages.get(k)).find())
                .toList();
        assertTrue(bad.isEmpty(), () -> "read with I18n.get, so their braces reach the screen: " + bad);
    }

    /** The scan has to be finding the code, or the two tests above pass by reading nothing. */
    @Test
    void theScanReadsTheCode() throws IOException {
        final Code code = Code.read();
        assertTrue(code.names("step.subject"), "a key written out");
        assertTrue(code.names("medium.axis.VERTICAL"), "a stem and an enum constant");
        assertTrue(code.names("area.fit.CONVEX_HULL"), "a stem ending in a dot");
        assertTrue(code.names("subject.help"), "a key handed to a helper");
        assertFalse(code.names("step.nothing"), "and nothing else");
        assertTrue(code.formattedKeys().size() > 50 && code.readKeys().size() > 150,
                () -> code.formattedKeys().size() + " formatted, " + code.readKeys().size() + " read");
    }
}
