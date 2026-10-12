package net.trdlo.wtf.studio.steps;

import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.project.ProjectModel;

import java.util.function.Consumer;

/**
 * One step's controls.
 * <p>
 * A panel only ever writes to the {@link ProjectModel} and reads back from it. It never calls
 * another panel, and never holds a value of its own that the model does not also hold – otherwise
 * loading a project would fill the model and leave five panels showing the previous one.
 */
public abstract class StepPanel {

    protected final ProjectModel model;

    private @Nullable Node built;

    protected StepPanel(final @NotNull ProjectModel model) {
        this.model = model;
    }

    public abstract @NotNull Step step();

    protected abstract @NotNull Node build();

    /** What the rail shows under the step's name: the current answer, in a few words. */
    public abstract @NotNull String summary();

    public final @NotNull Node node() {
        if (built == null) {
            built = build();
        }
        return built;
    }

    /** Called when the panel becomes visible, for anything too expensive to keep live. */
    public void shown() {
    }

    /** Called when it stops being visible – a half-drawn polygon should not survive a step change. */
    public void hidden() {
    }

    // -------------------------------------------------------------------------------------------
    // The panel's frame. Every panel is a column of labeled rows; this is that column, and the
    // rows and pickers in it are Forms'.
    // -------------------------------------------------------------------------------------------

    protected static @NotNull VBox column() {
        final VBox box = new VBox(10);
        box.setPadding(new Insets(14, 16, 16, 16));
        box.getStyleClass().add("step-panel");
        return box;
    }

    /** A gray explanatory paragraph. Wraps, because it is prose and the panel is narrow. */
    static @NotNull Node hint(final @NotNull String key) {
        final Text t = new Text(I18n.get(key));
        t.getStyleClass().add("step-hint");
        final TextFlow flow = new TextFlow(t);
        flow.setMaxWidth(290);
        return flow;
    }

    protected static @NotNull Separator gap() {
        final Separator s = new Separator();
        s.setPadding(new Insets(4, 0, 4, 0));
        return s;
    }

    /**
     * A length on paper: millimeters in the model, the chosen unit in the field.
     * <p>
     * What is typed is parsed in the display locale, with the conversion at the edge – so nothing
     * behind it ever sees an inch, and a value typed in inches is stored as the millimeters it is.
     * Only what is typed is written: a length shown in inches is rounded, and the text the code
     * puts there is never read back ({@link LengthText}).
     */
    protected static @NotNull TextField lengthField(final javafx.beans.property.@NotNull DoubleProperty mm,
                                                    final int mmDecimals) {
        return lengthField(new LengthText(mm, net.trdlo.wtf.studio.Units.current(), mmDecimals));
    }

    /** A length field over a text the panel also shows lengths in of its own. */
    static @NotNull TextField lengthField(final @NotNull LengthText length) {
        final TextField f = new TextField();
        f.setPrefColumnCount(7);
        f.textProperty().bindBidirectional(length.text());
        length.focused().bind(f.focusedProperty());
        f.textProperty().addListener((o, a, b) ->
                f.pseudoClassStateChanged(BAD_NUMBER, I18n.parseNumber(b) == null && !b.isBlank()));
        f.focusedProperty().addListener((o, was, is) -> {
            if (!is) {
                f.pseudoClassStateChanged(BAD_NUMBER, false);
            }
        });
        return f;
    }

    protected static final javafx.css.PseudoClass BAD_NUMBER =
            javafx.css.PseudoClass.getPseudoClass("bad-number");

    /** Runs the consumer now and on every change, which is what every "keep this label live" wants. */
    static <T> void live(final @NotNull ObservableValue<T> value,
                                   final @NotNull Consumer<T> apply) {
        apply.accept(value.getValue());
        value.addListener((o, a, b) -> apply.accept(b));
    }
}
