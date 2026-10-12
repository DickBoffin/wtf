package net.trdlo.wtf.studio.steps;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import org.jetbrains.annotations.NotNull;

/**
 * The text of a length field and what of it reaches the model – everything a
 * {@link StepPanel#lengthField} does but draw itself, so it can be tested without the toolkit.
 * <p>
 * Only what a person types is parsed. The text the code puts there – the model's value after an
 * open or an undo, the value tidied when the focus leaves, the side of the print its mode does not
 * fix – is shown and never read back: under inches 210 mm is shown as 8.27 in, and reading that
 * back would write 210.058 mm to a project nobody touched.
 */
final class LengthText {

    private final DoubleProperty mm;
    private final Units units;
    private final int mmDecimals;
    private final StringProperty text = new SimpleStringProperty(this, "text");
    private final BooleanProperty focused = new SimpleBooleanProperty(this, "focused");
    /** True while {@link #show} sets the text, so the change it makes is not taken for typing. */
    private boolean showing;
    /** The length the text stands for, to the last digit – which a length shown in inches is not. */
    private double standsForMm;

    LengthText(final @NotNull DoubleProperty mm, final @NotNull Units units, final int mmDecimals) {
        this.mm = mm;
        this.units = units;
        this.mmDecimals = mmDecimals;
        show(mm.get());
        text.addListener((o, a, b) -> {
            if (!showing) {
                typed(b);
            }
        });
        // Not while the field has the focus, or a decimal point typed mid-number is normalized
        // away under the cursor
        mm.addListener((o, a, b) -> {
            if (!focused.get()) {
                show(b.doubleValue());
            }
        });
        // Re-rendered on the way out, so "5000" becomes "5 000" and a half-typed value is tidied
        focused.addListener((o, was, is) -> {
            if (!is) {
                show(mm.get());
            }
        });
    }

    /** The field's text, bound both ways to the {@code TextField}'s. */
    @NotNull StringProperty text() {
        return text;
    }

    /** Whether the field has the focus, bound to the {@code TextField}'s. */
    @NotNull BooleanProperty focused() {
        return focused;
    }

    /** Shows a length without writing it: the model's own, or one worked out beside it. */
    void show(final double lengthMm) {
        standsForMm = lengthMm;
        showing = true;
        try {
            text.set(units.number(lengthMm, mmDecimals));
        } finally {
            showing = false;
        }
    }

    /**
     * Writes the length shown to the model – for when a side that was worked out becomes a side
     * that is set, and the print should not change on the way. The length, not the text: the text
     * is rounded.
     */
    void adopt() {
        mm.set(standsForMm);
    }

    private void typed(final String s) {
        final Double v = I18n.parseNumber(s);
        if (v != null) {
            standsForMm = units.toMm(v);
            mm.set(standsForMm);
        }
    }
}
