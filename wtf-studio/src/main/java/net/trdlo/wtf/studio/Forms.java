package net.trdlo.wtf.studio;

import javafx.beans.property.Property;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kordamp.ikonli.fontawesome5.FontAwesomeRegular;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The studio's form vocabulary: labeled rows, pickers and buttons, for a step panel and a dialog
 * alike.
 * <p>
 * Static, and holding nothing: a dialog need not be a step panel to get a labeled row. Three
 * shapes of row, because the studio has three: a label beside its control in a narrow column
 * ({@link #row}), a label over its control ({@link #labeled}), and a two-column grid whose hints
 * sit under the control ({@link #grid}).
 */
public final class Forms {

    private Forms() {
    }

    /** A section's title: a step's, or a heading inside a form. */
    public static @NotNull Label heading(final @NotNull String key) {
        final Label l = new Label(I18n.get(key));
        l.getStyleClass().add("step-heading");
        return l;
    }

    /** A label beside its control, the label a column of its own so a column of rows lines up. */
    public static @NotNull Node row(final @NotNull String labelKey, final @NotNull Node control) {
        return rowWithText(I18n.get(labelKey), control, null, 0);
    }

    /** The same, with a {@link #help} mark after the label. */
    public static @NotNull Node row(final @NotNull String labelKey, final @NotNull Node control,
                                    final @NotNull String helpKey) {
        return rowWithText(I18n.get(labelKey), control, helpKey, 0);
    }

    /**
     * The same, in a block indented by {@code indent} under the choice it belongs to: the label
     * column is that much narrower, so the control still starts on the line every other row's does
     * and keeps the width its words need.
     */
    public static @NotNull Node row(final @NotNull String labelKey, final @NotNull Node control,
                                    final @NotNull String helpKey, final double indent) {
        return rowWithText(I18n.get(labelKey), control, helpKey, indent);
    }

    /** A row for a length: the label says the unit, "Width (mm)" or "Width (in)". */
    public static @NotNull Node lengthRow(final @NotNull String labelKey, final @NotNull Node control) {
        return rowWithText(I18n.format(labelKey, Units.current().symbol()), control, null, 0);
    }

    /** The same, with a {@link #help} mark after the label. */
    public static @NotNull Node lengthRow(final @NotNull String labelKey, final @NotNull Node control,
                                          final @NotNull String helpKey) {
        return rowWithText(I18n.format(labelKey, Units.current().symbol()), control, helpKey, 0);
    }

    private static @NotNull Node rowWithText(final @NotNull String text, final @NotNull Node control,
                                             final @Nullable String helpKey, final double indent) {
        // A column, so the controls line up – and a label longer than it wraps rather than ending
        // in an ellipsis: "Když dlaždice chybí" is what Czech makes of "If a tile is missing"
        final Region column;
        if (helpKey == null) {
            final Label l = new Label(text);
            l.getStyleClass().add("step-label");
            l.setWrapText(true);
            column = l;
        } else {
            // The mark is the label's last word, in the label's column, and wraps with it: after
            // the control it would take its width from a picker whose words need all of it
            final Text words = new Text(text + " ");
            words.getStyleClass().add("step-label");
            column = new TextFlow(words, help(helpKey));
        }
        column.setMinWidth(LABEL_WIDTH - indent);
        column.setPrefWidth(LABEL_WIDTH - indent);
        column.setMaxWidth(LABEL_WIDTH - indent);
        final HBox box = new HBox(8, column, control);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(control, Priority.ALWAYS);
        return box;
    }

    /** The width of a row's label, the column a step's controls line up on. */
    private static final double LABEL_WIDTH = 96;

    /** A label over its control, for a form wide enough that the label need not share its line. */
    public static @NotNull VBox labeled(final @NotNull String labelKey, final @NotNull Node control) {
        return new VBox(8, new Label(I18n.get(labelKey)), control);
    }

    /**
     * A two-column form: labels on the left, controls taking the rest. Filled by {@link #section}
     * and {@link #field}, each of which takes the row to start at and gives back the next one.
     */
    public static @NotNull GridPane grid(final double labelWidth) {
        final GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        final ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(labelWidth);
        labels.setPrefWidth(labelWidth);
        labels.setHgrow(Priority.NEVER);
        final ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    /** A heading across both columns of a {@link #grid}, with room above it unless it is first. */
    public static int section(final @NotNull GridPane grid, final int row, final @NotNull String key) {
        final Label heading = heading(key);
        GridPane.setMargin(heading, new Insets(row == 0 ? 0 : 10, 0, 2, 0));
        grid.add(heading, 0, row, 2, 1);
        return row + 1;
    }

    /** A labeled control in a {@link #grid}, as wide as its column, and a hint under it if any. */
    public static int field(final @NotNull GridPane grid, final int row, final @NotNull String labelKey,
                            final @NotNull Node control, final @Nullable String hintKey) {
        final Label label = new Label(I18n.get(labelKey));
        label.getStyleClass().add("step-label");
        GridPane.setValignment(label, VPos.TOP);
        grid.add(label, 0, row);
        grid.add(control, 1, row);
        if (control instanceof final Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        if (hintKey == null) {
            return row + 1;
        }
        grid.add(hint(hintKey), 1, row + 1);
        return row + 2;
    }

    /** A gray line of explanation that wraps to whatever width it is given. */
    public static @NotNull Label hint(final @NotNull String key) {
        final Label l = new Label(I18n.get(key));
        l.getStyleClass().add("step-hint");
        l.setWrapText(true);
        return l;
    }

    /**
     * A small muted question mark with the text of {@code key} behind it: what a control does, for
     * whoever wants to know more than its label and the gray line under it say.
     * <p>
     * The tooltip opens at once and stays while the pointer is on the mark, since it is the whole
     * point of the mark. It also opens on a click or on Space, so a touchpad that cannot hover and a
     * keyboard reach it, and it wraps at the width of a step panel.
     */
    public static @NotNull Button help(final @NotNull String key) {
        final FontIcon icon = new FontIcon(FontAwesomeRegular.QUESTION_CIRCLE);
        icon.getStyleClass().add("help-icon-glyph");
        final Button b = new Button(null, icon);
        b.getStyleClass().addAll("help-icon", "flat");
        b.setMinWidth(Region.USE_PREF_SIZE);
        final Tooltip tip = new Tooltip(I18n.get(key));
        tip.setWrapText(true);
        tip.setMaxWidth(HELP_WIDTH);
        tip.setShowDelay(Duration.ZERO);
        tip.setShowDuration(Duration.INDEFINITE);
        tip.setHideDelay(Duration.millis(200));
        b.setTooltip(tip);
        b.setOnAction(e -> {
            if (tip.isShowing()) {
                return;
            }
            final Bounds at = b.localToScreen(b.getBoundsInLocal());
            if (at != null) {
                tip.show(b, at.getMinX(), at.getMaxY() + 2);
            }
        });
        b.focusedProperty().addListener((o, was, is) -> {
            if (!is) {
                tip.hide();
            }
        });
        return b;
    }

    /** How wide the text behind a {@link #help} mark runs before it wraps: a step panel's width. */
    private static final double HELP_WIDTH = 300;

    /** A checkbox or a caption with its {@link #help} mark after it. */
    public static @NotNull HBox helped(final @NotNull Node node, final @NotNull String key) {
        final HBox box = new HBox(2, node, help(key));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** A picker bound to a property, each item named by what {@code name} makes of its text. */
    public static <T> @NotNull ComboBox<T> combo(final @NotNull Property<T> p, final @NotNull List<T> items,
                                                 final @NotNull UnaryOperator<String> name) {
        final ComboBox<T> box = new ComboBox<>();
        box.getItems().setAll(items);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(final T value) {
                return value == null ? "" : name.apply(String.valueOf(value));
            }

            @Override
            public T fromString(final String s) {
                return null;
            }
        });
        box.setMaxWidth(Double.MAX_VALUE);
        box.valueProperty().bindBidirectional(p);
        return box;
    }

    /** An enum picker bound to a property, its labels {@code <prefix>.<CONSTANT>} in the bundle. */
    public static <E extends Enum<E>> @NotNull ComboBox<E> enumCombo(final @NotNull Property<E> p,
                                                                     final @NotNull E @NotNull [] values,
                                                                     final @NotNull String prefix) {
        final ComboBox<E> box = enumCombo(values, prefix);
        box.valueProperty().bindBidirectional(p);
        return box;
    }

    /** An enum picker with nothing behind it but its own value, for a form that reads it on OK. */
    public static <E extends Enum<E>> @NotNull ComboBox<E> enumCombo(final @NotNull E @NotNull [] values,
                                                                     final @NotNull String prefix) {
        final ComboBox<E> box = new ComboBox<>();
        box.getItems().setAll(values);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(final E value) {
                return value == null ? "" : I18n.get(prefix + "." + value.name());
            }

            @Override
            public E fromString(final String s) {
                return null;
            }
        });
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /** A button that stays out of the tab order and never shrinks its own text to an ellipsis. */
    public static @NotNull Button button(final @NotNull String key, final @NotNull Runnable action) {
        final Button b = new Button(I18n.get(key));
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        // A Labeled will shrink to an ellipsis before it lets its row overflow, which turns
        // "Choose..." into "C..." next to a text field given all the growth. The
        // button keeps what it computed for itself; the field beside it is what gives way.
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }
}
