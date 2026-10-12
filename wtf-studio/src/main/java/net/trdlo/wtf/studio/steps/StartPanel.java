package net.trdlo.wtf.studio.steps;

import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.studio.Forms;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import net.trdlo.wtf.studio.project.Example;
import net.trdlo.wtf.studio.project.ProjectModel;

import java.util.List;
import java.util.function.Consumer;

/**
 * Step 0, the Start page: what WTF makes, what each of the five steps decides, and four examples
 * to start from.
 * <p>
 * What a window holding a new project shows – at a start that opens none, and after New – and what
 * the rail's Start and Alt+0 come back to. An example opens as a new project with every step filled
 * in ({@link Example}), and the window moves on to its first step, as Start with step 1 does.
 */
public final class StartPanel extends StepPanel {

    /**
     * The wallpaper's size and the roll it is printed on, as its card says them, in the lengths
     * chosen for paper; {@code ExampleProjectsTest} holds them to its file.
     */
    public static final double WALLPAPER_WIDTH_MM = 5_000;
    public static final double WALLPAPER_HEIGHT_MM = 2_500;
    public static final double WALLPAPER_ROLL_MM = 914;

    /** The steps the page names, in the rail's order. */
    private static final List<Step> STEPS = List.of(Step.SUBJECT, Step.AREA, Step.MEDIUM, Step.DETAIL, Step.EXPORT);

    private final Consumer<Example> open;
    private final Runnable begin;

    /**
     * @param open  opens an example, after the question about unsaved changes
     * @param begin goes to step 1
     */
    public StartPanel(final @NotNull ProjectModel model, final @NotNull Consumer<Example> open,
                      final @NotNull Runnable begin) {
        super(model);
        this.open = open;
        this.begin = begin;
    }

    @Override
    public @NotNull Step step() {
        return Step.START;
    }

    @Override
    protected @NotNull Node build() {
        // Longer than any step's question, and longer still in Czech: wrapped, not cut to an ellipsis
        final Label title = Forms.heading("start.title");
        title.setWrapText(true);
        final Label examples = new Label(I18n.get("start.examples"));
        examples.getStyleClass().add("step-label");

        final VBox box = column();
        box.getChildren().addAll(
                title,
                hint("start.intro"),
                steps(),
                hint("start.map"),
                gap(),
                examples,
                hint("start.examples.hint"));
        for (final Example example : Example.values()) {
            box.getChildren().add(card(example));
        }
        final Button first = new Button(I18n.get("start.begin"));
        first.getStyleClass().add("accent");
        first.setMaxWidth(Double.MAX_VALUE);
        // As the slot buttons are: a press does not take the keys from the map
        first.setFocusTraversable(false);
        first.setOnAction(e -> begin.run());
        box.getChildren().addAll(gap(), first);
        return box;
    }

    /** The five steps, each by its number and name on the rail, and what it decides. */
    private static @NotNull Node steps() {
        final GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(5);
        final ColumnConstraints number = new ColumnConstraints();
        number.setMinWidth(Region.USE_PREF_SIZE);
        final ColumnConstraints name = new ColumnConstraints();
        name.setMinWidth(Region.USE_PREF_SIZE);
        final ColumnConstraints what = new ColumnConstraints();
        what.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(number, name, what);
        for (int row = 0; row < STEPS.size(); row++) {
            final Step step = STEPS.get(row);
            final Label n = new Label(String.valueOf(step.number()));
            n.getStyleClass().add("step-number");
            final Label title = new Label(I18n.get(step.titleKey()));
            title.getStyleClass().add("start-step-name");
            final Label decides = new Label(what(step));
            decides.getStyleClass().add("step-hint");
            decides.setWrapText(true);
            for (final Node node : List.of(n, title, decides)) {
                GridPane.setValignment(node, VPos.TOP);
            }
            grid.addRow(row, n, title, decides);
        }
        return grid;
    }

    /** What a step decides, in a few words. */
    static @NotNull String what(final @NotNull Step step) {
        return switch (step) {
            case START -> "";
            case SUBJECT -> I18n.get("start.step.1");
            case AREA -> I18n.get("start.step.2");
            case MEDIUM -> I18n.get("start.step.3");
            case DETAIL -> I18n.get("start.step.4");
            case EXPORT -> I18n.get("start.step.5");
        };
    }

    /** An example's button, and what it comes out as under it. */
    private @NotNull Node card(final @NotNull Example example) {
        final Button b = new Button(example.title());
        b.getStyleClass().add("example-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setFocusTraversable(false);
        b.setOnAction(e -> open.accept(example));
        final Label body = new Label(body(example));
        body.getStyleClass().add("step-hint");
        body.setWrapText(true);
        return new VBox(4, b, body);
    }

    /** What an example comes out as, in paper and meters – or feet, where lengths on paper are inches. */
    public static @NotNull String body(final @NotNull Example example) {
        final Units u = Units.current();
        return switch (example) {
            case HIKE -> I18n.get("example.hike.body");
            case POSTER -> I18n.get("example.poster.body");
            case WALLPAPER -> I18n.format("example.wallpaper.body", unbroken(u.longLength(WALLPAPER_WIDTH_MM)),
                    unbroken(u.longLength(WALLPAPER_HEIGHT_MM)),
                    unbroken(RollWidthText.text(u, WALLPAPER_ROLL_MM) + " " + u.symbol()));
            case CITY -> I18n.get("example.city.body");
        };
    }

    /** A length the wrapping line does not break between its number and its unit. */
    private static @NotNull String unbroken(final @NotNull String length) {
        return length.replace(' ', ' ');
    }

    /** Nothing on the rail: the page is the same whatever the project. */
    @Override
    public @NotNull String summary() {
        return "";
    }
}
