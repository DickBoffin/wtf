package net.trdlo.wtf.studio.steps;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Forms;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.map.EditTool;
import net.trdlo.wtf.studio.map.ShapeTool;
import net.trdlo.wtf.studio.project.ProjectModel;

/**
 * Step 2: how much world goes around it.
 * <p>
 * <b>A shape, grown, and two ways of arriving at the shape.</b> Normally it is worked out rather
 * than set: the subject is known exactly, and the area is a consequence of it – its bounding box,
 * its convex hull, or its own outline – plus room around the edge. A wall is a rectangle, so the
 * bounding box is usually the answer. Or it is a Custom shape, any shape and as many parts as it
 * takes – Europe with Svalbard taken out – grown by the same room and faded across it the same way,
 * which is why Room around it and the fade sit below both answers rather than under one.
 * <p>
 * Fit then grow, in that order, because the order changes the result: the bounding box of a grown
 * outline is not the grown bounding box. It happens to make no difference for the convex hull –
 * growing by a distance is a Minkowski sum with a disc, and that commutes with taking the hull –
 * but it does for the other two, and one order has to be the order.
 * <p>
 * <b>Touching it is what takes it over.</b> No button hands the worked-out box to the drawing
 * tools; the fit has handles whenever it is a rectangle, and moving one is the whole gesture. The
 * answer above moves to Custom as it happens, the box as it was let go its shape and the growth
 * kept, because from that point the subject no longer decides it – and that is a thing to show
 * rather than to hide. Under the other two fits the area follows the subject's own edge and has no
 * corners to take hold of, so the way to change it is to change the subject, or to choose Custom.
 */
public final class AreaPanel extends StepPanel {

    private final StepContext ctx;
    private final Label result = new Label();
    private final Label hint = new Label();
    private final Label handles = new Label();
    /** Kept so the step can hand it the map when it opens, and take it back when it closes. */
    private @Nullable ShapeSlot slot;
    /** Whether the step is on screen, so a change of source can put the other editor on the map. */
    private boolean open;
    /** The editor on the fit, while the subject decides the area and the step is open; else null. */
    private @Nullable EditTool fitEditor;
    /** The step's column, whose scene says which control has the keys when the editor changes. */
    private @Nullable VBox column;

    public AreaPanel(final @NotNull ProjectModel model, final @NotNull StepContext ctx) {
        super(model);
        this.ctx = ctx;
    }

    @Override
    public @NotNull Step step() {
        return Step.AREA;
    }

    @Override
    protected @NotNull Node build() {
        // --- where the area grows from ----------------------------------------------------------
        final ToggleGroup source = new ToggleGroup();
        final RadioButton fromSubject = new RadioButton(I18n.get("area.source.subject"));
        final RadioButton customRadio = new RadioButton(I18n.get("area.source.custom"));
        fromSubject.setToggleGroup(source);
        customRadio.setToggleGroup(source);
        live(model.areaSourceProperty(), s ->
                (s == ProjectModel.AreaSource.FROM_SUBJECT ? fromSubject : customRadio).setSelected(true));
        source.selectedToggleProperty().addListener((o, a, b) -> model.areaSourceProperty().set(
                b == fromSubject ? ProjectModel.AreaSource.FROM_SUBJECT : ProjectModel.AreaSource.CUSTOM));

        hint.getStyleClass().add("step-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(290);

        slot = new ShapeSlot(model, ctx, ShapeTool.Slot.AREA, "area.slot", "area.slot.hint", "area.slot.help",
                model.customProperty(), model.customOriginProperty(), hint);
        final Node slotNode = slot.build();

        // --- the fit, the subject's answer -------------------------------------------------------
        final ComboBox<Shapes.Simplify> fit =
                Forms.enumCombo(model.simplifyProperty(), Shapes.Simplify.values(), "area.fit");

        // Whether the fit can be taken over by dragging it, said where the dragging would happen.
        // Silent when there is nothing yet: the line below already says to pick a subject.
        handles.getStyleClass().add("step-hint");
        handles.setWrapText(true);
        handles.setMaxWidth(290);
        live(model.revisionProperty(), r -> updateHandles());

        // --- and the growth, both answers' --------------------------------------------------------
        final TextField grow = new TextField();
        grow.setPrefColumnCount(7);
        growInto(grow);
        // "km" or "mi" rather than the word, which the row has no width for
        final Label unit = new Label(I18n.get("area.unit." + Grow.current().name()));
        unit.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        final HBox growRow = new HBox(6, grow, unit);
        growRow.setAlignment(Pos.CENTER_LEFT);

        // The fade is about the growth, so it sits under it, and there is nothing to fade across
        // until something has been grown
        final CheckBox fade = new CheckBox(I18n.get("area.fade"));
        fade.setWrapText(true);
        fade.selectedProperty().bindBidirectional(model.areaFadeProperty());
        fade.disableProperty().bind(model.bufferValueProperty().lessThanOrEqualTo(0));

        result.getStyleClass().add("step-summary");
        result.setWrapText(true);
        result.setMaxWidth(290);
        live(model.revisionProperty(), r -> updateResult());
        live(model.areaPendingProperty(), r -> updateResult());

        // Each radio owns what follows it, and nothing of it shows under the other: the fit is how
        // the subject becomes the shape the area grows from, and the slot is the shape itself.
        // Both are grown and faded alike, so those two sit below both.
        final VBox fitBlock = indented(Forms.row("area.fit", fit, "area.fit.help", INDENT), handles);
        final VBox customBlock = indented(slotNode);
        live(model.areaSourceProperty(), which -> {
            final boolean custom = which == ProjectModel.AreaSource.CUSTOM;
            customBlock.setVisible(custom);
            customBlock.setManaged(custom);
            fitBlock.setVisible(!custom);
            fitBlock.setManaged(!custom);
            switchEditor();
        });
        // A fit becomes a shape of its own, a new box, or none as the subject goes: the hint says
        // what its editor can do with the one there now
        model.areaFitProperty().addListener((o, was, is) -> {
            if (fitEditor != null) {
                showFitHint();
            }
        });

        // A rectangle grows with the print, so that the spare paper is more map; somebody who
        // drew one may have meant exactly that one. Under both answers, because a subject's
        // bounding box is a rectangle too, and only while the area is one: an outline or a hull
        // is its own edge already, and the box would change nothing.
        final CheckBox exact = new CheckBox(I18n.get("area.exact"));
        exact.setWrapText(true);
        exact.selectedProperty().bindBidirectional(model.areaExactProperty());
        final Node exactHint = hint("area.exact.hint");
        live(model.revisionProperty(), r -> {
            final Geometry area = model.areaProperty().get();
            final boolean box = area != null && !area.isEmpty() && Shapes.isRectangle(area);
            for (final Node n : new Node[]{exact, exactHint}) {
                n.setVisible(box);
                n.setManaged(box);
            }
        });

        final VBox box = column();
        box.getChildren().addAll(
                Forms.heading("area.title"),
                hint("area.intro"),
                fromSubject, fitBlock,
                customRadio, customBlock,
                Forms.row("area.grow", growRow, "area.grow.help"),
                fade, hint("area.fade.hint"),
                exact, exactHint,
                gap(),
                result,
                hint);
        column = box;
        return box;
    }

    /**
     * The shape the area grows from is editable here, and only here.
     * <p>
     * Under both answers: the Custom shape with the whole editor, and under From the subject the
     * fit, when it is a box – it has corners too, and dragging one is how it becomes Custom.
     */
    @Override
    public void shown() {
        open = true;
        installEditor();
    }

    @Override
    public void hidden() {
        open = false;
        fitEditor = null;
        if (slot != null) {
            slot.deactivate();          // no handles, no half-drawn shape, no tool
        }
    }

    /** How far a block is indented under the radio button it belongs to. */
    private static final double INDENT = 22;

    /** A block that belongs to the radio button above it, and is indented to say so. */
    private static @NotNull VBox indented(final @NotNull Node... children) {
        final VBox box = new VBox(8, children);
        box.setPadding(new javafx.geometry.Insets(6, 0, 2, INDENT));
        return box;
    }

    /**
     * Puts the editor of the shape the area grows from on the map, while the step is open: the
     * Custom slot's, or one on the fit that keeps a box a box.
     */
    private void installEditor() {
        if (!open || slot == null) {
            return;
        }
        if (model.areaSourceProperty().get() == ProjectModel.AreaSource.CUSTOM) {
            fitEditor = null;
            slot.activate();
            return;
        }
        slot.deactivate();
        final EditTool t = new EditTool(ShapeTool.Slot.AREA, EditTool.Mode.RECTANGLE, model.areaFitProperty(),
                g -> { }, () -> ctx.map().requestPaint());
        fitEditor = t;
        ctx.map().setTool(t);
        showFitHint();
    }

    /**
     * The other answer's editor, for a source changed while the step is open – by a radio, a corner
     * dragged or an undo – without taking the keys from wherever they were: installing a tool asks
     * the map for the focus, and the radio somebody is arrowing through would lose it.
     */
    private void switchEditor() {
        if (!open) {
            return;
        }
        final javafx.scene.Scene scene = column == null ? null : column.getScene();
        final Node focused = scene == null ? null : scene.getFocusOwner();
        installEditor();
        if (focused != null) {
            focused.requestFocus();
        }
    }

    /** What the hint says while the fit's editor is the tool: how to drag a box, or nothing. */
    private void showFitHint() {
        final Geometry g = model.areaFitProperty().get();
        hint.setText(EditTool.isEditable(g, EditTool.Mode.RECTANGLE) ? I18n.get(EditTool.hintKeyFor(g)) : "");
    }

    private void updateHandles() {
        final Geometry fit = model.areaFitProperty().get();
        final boolean nothingYet = fit == null || fit.isEmpty();
        final String text = nothingYet ? ""
                : Shapes.isRectangle(fit) ? I18n.get("area.handles.yes") : I18n.get("area.handles.none");
        handles.setText(text);
        handles.setManaged(!text.isEmpty());
        handles.setVisible(!text.isEmpty());
    }

    private void updateResult() {
        if (model.areaPendingProperty().get()) {
            result.setText(I18n.get("area.working"));
            return;
        }
        final boolean custom = model.areaSourceProperty().get() == ProjectModel.AreaSource.CUSTOM;
        final Geometry area = model.areaProperty().get();
        if (area == null) {
            result.setText(I18n.get(custom ? "area.noArea" : "area.noSubject"));
            return;
        }
        // What it grows from, and what that comes to: the same line for both answers
        final Geometry base = custom ? model.customProperty().get() : model.subjectProperty().get();
        if (base == null) {
            result.setText("");
            return;
        }
        // Without room around it the two are the same, and saying both says nothing
        result.setText(model.bufferValueProperty().get() == 0
                ? I18n.format("area.result.bare", ground(Shapes.boundsOf(area)))
                : I18n.format("area.result", ground(Shapes.boundsOf(base)), ground(Shapes.boundsOf(area))));
    }

    /**
     * The unit Room around it is typed in: kilometers, or miles where lengths are in inches – the
     * units a person gives a distance on the ground in, "50 km" rather than "50 000 m".
     * <p>
     * Only the field and the rail know about them. The model and the file keep meters – a project
     * grown by 50 km in Prague is grown by 31.07 miles in Denver.
     */
    enum Grow {
        KILOMETERS(1000),
        MILES(1609.344);

        final double factor;

        Grow(final double factor) {
            this.factor = factor;
        }

        /** The field's unit, as Settings set the lengths. */
        static @NotNull Grow current() {
            return net.trdlo.wtf.studio.Units.current() == net.trdlo.wtf.studio.Units.INCHES ? MILES : KILOMETERS;
        }

        @NotNull String shown(final double meters) {
            // As many decimals as the number needs, down to a meter or so: 50 km, 0.25 km, 31.07 mi
            return I18n.numberUpTo(meters / factor, this == KILOMETERS ? 3 : 2);
        }
    }

    /**
     * The Room around it field, kept in step with the model in both directions.
     * <p>
     * A write the field makes is not echoed back into it while it has the focus, or a decimal
     * point typed mid-number would be tidied away under the cursor.
     */
    private void growInto(final @NotNull TextField field) {
        final Grow unit = Grow.current();
        final Runnable show = () -> field.setText(unit.shown(model.bufferValueProperty().get()));
        field.textProperty().addListener((o, a, b) -> {
            final Double v = I18n.parseNumber(b);
            field.pseudoClassStateChanged(BAD_NUMBER, v == null && !b.isBlank());
            if (v != null && field.isFocused()) {
                model.bufferValueProperty().set(v * unit.factor);
            }
        });
        field.focusedProperty().addListener((o, was, is) -> {
            if (!is) {
                show.run();
                field.pseudoClassStateChanged(BAD_NUMBER, false);
            }
        });
        live(model.bufferValueProperty(), v -> {
            if (!field.isFocused()) {
                show.run();
            }
        });
    }

    /** Kilometers across at the middle latitude – the number a person can picture. */
    private static double widthKm(final net.trdlo.wtf.tiles.@NotNull LonLatBounds b) {
        final double midLat = (b.north() + b.south()) / 2;
        return (b.east() - b.west()) * Shapes.METERS_PER_DEGREE_LATITUDE
                * Math.cos(Math.toRadians(midLat)) / 1000;
    }

    private static double heightKm(final net.trdlo.wtf.tiles.@NotNull LonLatBounds b) {
        return (b.north() - b.south()) * Shapes.METERS_PER_DEGREE_LATITUDE / 1000;
    }

    /** "486.3 × 278.7 km", or the same in miles: a shape's size on the ground, as a person says it. */
    static @NotNull String ground(final net.trdlo.wtf.tiles.@NotNull LonLatBounds b) {
        final net.trdlo.wtf.studio.Units u = net.trdlo.wtf.studio.Units.current();
        return I18n.format("area.ground", u.groundNumber(widthKm(b) * 1000), u.groundNumber(heightKm(b) * 1000),
                u.groundSymbol());
    }

    @Override
    public @NotNull String summary() {
        final Geometry area = model.areaProperty().get();
        if (area == null) {
            return "";
        }
        final String how = model.fades() ? I18n.format("area.summary.faded", how()) : how();
        return model.areaExactProperty().get() && Shapes.isRectangle(area)
                ? I18n.format("area.summary.exact", how) : how;
    }

    /** How the area was arrived at: the Custom shape or the fit, and the growth either way. */
    private @NotNull String how() {
        final String from = model.areaSourceProperty().get() == ProjectModel.AreaSource.CUSTOM
                ? I18n.get("area.source.custom")
                : I18n.get("area.fit." + model.simplifyProperty().get().name());
        final double grow = model.bufferValueProperty().get();
        if (grow == 0) {
            return from;
        }
        // The unit's own string says where its number goes
        final Grow g = Grow.current();
        return I18n.format("area.summary.grown", from, I18n.format("area.unit.short." + g.name(), g.shown(grow)));
    }
}
