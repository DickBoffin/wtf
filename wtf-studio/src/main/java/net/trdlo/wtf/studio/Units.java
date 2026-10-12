package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Lengths on paper, in millimeters or inches.
 * <p>
 * The model, the project file and the engine are millimeters and stay millimeters; this is only
 * how a length is shown and typed. Inches get two more decimals than millimeters, because an inch
 * is twenty-five of them: a 0.1 mm boundary is 0.004 in, and rounding it to 0.0 would say there is
 * none.
 * <p>
 * The default follows the machine – inches where they are the everyday unit, millimeters
 * everywhere else – and Settings can choose either. Read once at startup, like the language: a
 * window whose fields change unit under the cursor is a window nobody trusts.
 */
public enum Units {
    MILLIMETERS("mm", 1.0, 0),
    INCHES("in", 25.4, 2);

    /** Where inches are the everyday unit: the United States, Liberia, Myanmar. */
    private static final Set<String> INCH_COUNTRIES = Set.of("US", "LR", "MM");

    private static Units current = fromLocale(Locale.getDefault(Locale.Category.FORMAT));

    private final String symbol;
    private final double mmPer;
    private final int extraDecimals;

    Units(final @NotNull String symbol, final double mmPer, final int extraDecimals) {
        this.symbol = symbol;
        this.mmPer = mmPer;
        this.extraDecimals = extraDecimals;
    }

    public static @NotNull Units current() {
        return current;
    }

    /** The unit Settings chose – {@code mm}, {@code inch} – or the machine's when it chose none. */
    public static void use(final @Nullable String chosen) {
        if ("inch".equals(chosen)) {
            current = INCHES;
        } else if ("mm".equals(chosen)) {
            current = MILLIMETERS;
        }
    }

    static @NotNull Units fromLocale(final @NotNull Locale l) {
        return INCH_COUNTRIES.contains(l.getCountry()) ? INCHES : MILLIMETERS;
    }

    public @NotNull String symbol() {
        return symbol;
    }

    /** The key Settings stores: {@code mm} or {@code inch}. */
    public @NotNull String key() {
        return this == INCHES ? "inch" : "mm";
    }

    public double fromMm(final double mm) {
        return mm / mmPer;
    }

    public double toMm(final double value) {
        return value * mmPer;
    }

    /** How many decimals a value shown with {@code mmDecimals} in millimeters needs in this unit. */
    public int decimals(final int mmDecimals) {
        return mmDecimals + extraDecimals;
    }

    /**
     * "297" or "11.69": the number, in this unit, without the symbol.
     * <p>
     * Inches drop the zeros they do not need – Letter is 8.5 × 11, not 8.50 × 11.00 – because
     * their extra decimals are there for the small lengths, not to pad the round ones.
     */
    public @NotNull String number(final double mm, final int mmDecimals) {
        return this == INCHES ? I18n.numberUpTo(fromMm(mm), decimals(mmDecimals))
                : I18n.number(fromMm(mm), decimals(mmDecimals));
    }

    /** "297 × 210 mm", or "11.69 × 8.27 in". */
    public @NotNull String size(final double widthMm, final double heightMm, final int mmDecimals) {
        return number(widthMm, mmDecimals) + " × " + number(heightMm, mmDecimals) + " " + symbol;
    }

    /** "297 mm" or "11.69 in". */
    public @NotNull String length(final double mm, final int mmDecimals) {
        return number(mm, mmDecimals) + " " + symbol;
    }

    /** A distance on the ground, across a print's area: "486.3 km", or "302.2 mi". */
    public @NotNull String groundNumber(final double meters) {
        return I18n.number(this == INCHES ? meters / 1609.344 : meters / 1000, 1);
    }

    /** "km" or "mi", for the ground as {@link #groundNumber} measures it. */
    public @NotNull String groundSymbol() {
        return this == INCHES ? "mi" : "km";
    }

    /** One distance on the ground: "50.0 km", or "31.1 mi". */
    public @NotNull String ground(final double meters) {
        return groundNumber(meters) + " " + groundSymbol();
    }

    /** A length off a roll: "12.3 m", or "40.4 ft". */
    public @NotNull String longLength(final double mm) {
        return this == INCHES ? I18n.number(mm / 304.8, 1) + " ft" : I18n.number(mm / 1000, 1) + " m";
    }
}
