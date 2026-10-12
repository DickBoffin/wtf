package net.trdlo.wtf.studio.steps;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import org.kordamp.ikonli.javafx.FontIcon;
import net.trdlo.wtf.studio.I18n;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The Start page and the five steps down the left edge.
 * <p>
 * Every step is always clickable. The summary line under each name is what makes the rail worth
 * the width it costs: the user can see that step 3 says "915 mm roll, 6 strips" and step 4 says
 * "z13, labels 2.6 mm" without leaving step 1.
 * <p>
 * It starts on the Start page, which is what a window holding a new project shows; opening a
 * project moves it on to step 1.
 */
public final class StepRail extends VBox {

    private final ToggleGroup group = new ToggleGroup();
    private final Map<Step, ToggleButton> buttons = new EnumMap<>(Step.class);
    private final Map<Step, Label> summaries = new EnumMap<>(Step.class);
    private final ObjectProperty<Step> current = new SimpleObjectProperty<>(Step.START);

    public StepRail() {
        getStyleClass().add("step-rail");
        setSpacing(2);
        setPadding(new Insets(10, 8, 10, 8));
        setMinWidth(184);
        setPrefWidth(184);

        for (final Step step : Step.values()) {
            final ToggleButton b = buildButton(step);
            buttons.put(step, b);
            getChildren().add(b);
        }
        buttons.get(Step.START).setSelected(true);

        // A toggle group lets the selected button be clicked off, which would leave no step
        // showing at all. Put it back rather than letting the panel area go blank.
        group.selectedToggleProperty().addListener((o, was, is) -> {
            if (is == null && was != null) {
                was.setSelected(true);
            } else if (is != null) {
                current.set((Step) is.getUserData());
            }
        });
    }

    private @NotNull ToggleButton buildButton(final @NotNull Step step) {
        final Label number = new Label(String.valueOf(step.number()));
        number.getStyleClass().add("step-number");

        final Label title = new Label(I18n.get(step.titleKey()));
        title.getStyleClass().add("step-title");

        final Label summary = new Label();
        summary.getStyleClass().add("step-summary");
        // Wrapped rather than cut to a width: a Czech summary is longer, and an ellipsis hides the
        // part that says what was chosen
        summary.setWrapText(true);
        summaries.put(step, summary);

        final VBox text = new VBox(0, title, summary);
        text.setAlignment(Pos.CENTER_LEFT);

        final FontIcon icon = new FontIcon(step.icon());
        icon.getStyleClass().add("step-icon");

        final HBox content = new HBox(8, number, icon, text);
        content.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        // Its height depends on its width, because the summary wraps. Asked without a width, the
        // button hands its graphic a negative one and the summary breaks after every letter: each
        // step grows to a fifth of the window.
        final ToggleButton b = new ToggleButton() {
            @Override
            public Orientation getContentBias() {
                return Orientation.HORIZONTAL;
            }
        };
        b.setGraphic(content);
        b.setUserData(step);
        b.setToggleGroup(group);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.getStyleClass().add("step-button");
        b.setFocusTraversable(false);
        return b;
    }

    public @NotNull ObjectProperty<Step> currentProperty() {
        return current;
    }

    public @NotNull Step current() {
        return current.get();
    }

    public void select(final @NotNull Step step) {
        buttons.get(step).setSelected(true);
    }

    /** Refreshes every summary line from its panel. Called whenever the model changes. */
    public void refresh(final @NotNull Function<Step, String> summaryOf) {
        for (final Step step : Step.values()) {
            final String s = summaryOf.apply(step);
            summaries.get(step).setText(s);
            summaries.get(step).setVisible(!s.isBlank());
            summaries.get(step).setManaged(!s.isBlank());
        }
    }

    /** A spacer so the rail's background runs the full height rather than stopping at step 5. */
    public void fill() {
        final Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);
    }
}
