package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Every user-visible string, and every number the user types or reads.
 * <p>
 * English and Czech, with the infrastructure there from the first commit because retrofitting it
 * is the expensive way round – see docs/open-questions.md D15. The rules that matter most are not
 * about strings at all:
 * <ul>
 *   <li><b>Parse with the display locale, write with {@link Locale#ROOT}.</b> A user whose system
 *       uses a decimal comma types {@code 5,5} into a millimeter field; {@code Double.parseDouble}
 *       throws on it, and on a German {@code 1.000,5} it silently returns {@code 1.0}. Meanwhile a
 *       number written into a {@code .wtf} with a comma is a file that only opens on the
 *       machine that wrote it. {@code wtf-compose} has no locale at all, by construction.</li>
 *   <li><b>No concatenation.</b> {@link MessageFormat} with numbered arguments, so word order can
 *       change.</li>
 *   <li><b>Design around plurals.</b> Slavic plural rules have three forms, which
 *       {@code MessageFormat} cannot express; "Sheets: 6" sidesteps it and reads perfectly well.</li>
 * </ul>
 */
public final class I18n {

    private static final Logger log = LoggerFactory.getLogger(I18n.class);

    private static final String BUNDLE = "net.trdlo.wtf.studio.messages";

    /**
     * The languages there is a bundle for, English first because it is the base bundle. Adding one
     * is a {@code messages_<tag>.properties} and a line here, so Settings can offer it.
     */
    public static final List<Locale> LANGUAGES = List.of(Locale.ENGLISH, Locale.forLanguageTag("cs"));

    private static Locale locale = Locale.getDefault(Locale.Category.DISPLAY);
    private static ResourceBundle bundle = load(locale);

    private I18n() {
    }

    /**
     * English asks for the base bundle by {@link Locale#ROOT}. Asked for by {@code en}, which has no
     * file of its own, {@code ResourceBundle} falls back to the system's language before the base
     * bundle – so on a Czech machine, choosing English in Settings gave Czech.
     */
    private static @NotNull ResourceBundle load(final @NotNull Locale l) {
        return ResourceBundle.getBundle(BUNDLE,
                l.getLanguage().equals(Locale.ENGLISH.getLanguage()) ? Locale.ROOT : l);
    }

    /** The interface language. Deliberately separate from the map's {@code lang} token. */
    public static void setLocale(final @NotNull Locale l) {
        locale = l;
        bundle = load(l);
        log.debug("Interface language set to {}", l.toLanguageTag());
    }

    /**
     * The language Settings chose, or the system's when it chose none: a language tag, or blank
     * for the system's. Called once at startup, before the first string is read.
     */
    public static void useLanguage(final @Nullable String tag) {
        if (tag != null && !tag.isBlank()) {
            final Locale chosen = Locale.forLanguageTag(tag);
            Locale.setDefault(Locale.Category.DISPLAY, chosen);
            setLocale(chosen);
        }
    }

    /** A language's name in that language, as a list of languages should show it: "Čeština". */
    public static @NotNull String nativeName(final @NotNull Locale l) {
        final String name = l.getDisplayLanguage(l);
        return name.isEmpty() ? l.toLanguageTag() : name.substring(0, 1).toUpperCase(l) + name.substring(1);
    }

    public static @NotNull Locale locale() {
        return locale;
    }

    public static @NotNull ResourceBundle bundle() {
        return bundle;
    }

    /** The string for a key. A missing key shows as {@code !key!} rather than throwing. */
    public static @NotNull String get(final @NotNull String key) {
        try {
            return bundle.getString(key);
        } catch (final MissingResourceException ex) {
            log.warn("No message for key '{}'", key);
            return "!" + key + "!";
        }
    }

    public static @NotNull String format(final @NotNull String key, final Object... args) {
        final MessageFormat f = new MessageFormat(get(key), locale);
        return f.format(args);
    }

    /**
     * For sorting names a reader will look through: Č after C rather than after Z, and case not
     * deciding anything. A new one each time, because a {@code Collator} is not thread-safe.
     */
    public static @NotNull java.util.Comparator<String> collator() {
        final java.text.Collator c = java.text.Collator.getInstance(locale);
        c.setStrength(java.text.Collator.SECONDARY);
        return c::compare;
    }

    // -------------------------------------------------------------------------------------------
    // Numbers
    // -------------------------------------------------------------------------------------------

    /** For display: grouping separators and a decimal mark the reader expects. */
    public static @NotNull String number(final double value, final int decimals) {
        final NumberFormat f = NumberFormat.getNumberInstance(locale);
        f.setMinimumFractionDigits(decimals);
        f.setMaximumFractionDigits(decimals);
        return f.format(value);
    }

    /** Up to {@code decimals}, and no more than it needs: 8.5, 11, 0.004. */
    public static @NotNull String numberUpTo(final double value, final int decimals) {
        final NumberFormat f = NumberFormat.getNumberInstance(locale);
        f.setMinimumFractionDigits(0);
        f.setMaximumFractionDigits(decimals);
        return f.format(value);
    }

    public static @NotNull String integer(final long value) {
        return NumberFormat.getIntegerInstance(locale).format(value);
    }

    /**
     * Parses what the user typed, in their locale. Returns null rather than throwing, because a
     * half-typed number is the normal state of a text field.
     * <p>
     * Where the locale groups digits with a space – a no-break one in Czech, a narrow one in
     * French – any of the three spaces is read as it: "50 000" is what a keyboard types, and
     * nobody can see which of them a field holds.
     */
    public static @Nullable Double parseNumber(final @Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final NumberFormat f = NumberFormat.getNumberInstance(locale);
        final char grouping = DecimalFormatSymbols.getInstance(locale).getGroupingSeparator();
        String typed = text.strip();
        if (Character.isSpaceChar(grouping)) {
            typed = typed.replace(' ', grouping).replace('\u00a0', grouping).replace('\u202f', grouping);
        }
        final ParsePosition pos = new ParsePosition(0);
        final Number n = f.parse(typed, pos);
        if (n == null || pos.getIndex() != typed.length()) {
            return null;
        }
        return n.doubleValue();
    }

    // -------------------------------------------------------------------------------------------
    // Dates
    // -------------------------------------------------------------------------------------------

    /**
     * A moment as the reader writes one, in their own time zone – "Sep 28, 2026, 2:32 PM",
     * "28. 9. 2026 14:32" – through {@link DateTimeFormatter#ofLocalizedDateTime}, as D15 has it.
     */
    public static @NotNull String dateTime(final @NotNull Instant when) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale).format(when.atZone(ZoneId.systemDefault()));
    }

    /** Bytes, for a cache that is measured in gigabytes. */
    public static @NotNull String bytes(final long value) {
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024L * 1024) {
            return number(value / 1024.0, 1) + " kB";
        }
        if (value < 1024L * 1024 * 1024) {
            return number(value / 1_048_576.0, 1) + " MB";
        }
        return number(value / 1_073_741_824.0, 2) + " GB";
    }
}
