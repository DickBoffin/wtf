package net.trdlo.wtf.studio.steps;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.ShapeImport;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Dialogs;
import net.trdlo.wtf.studio.Forms;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Modal;
import net.trdlo.wtf.studio.Prefs;
import net.trdlo.wtf.studio.map.EditTool;
import net.trdlo.wtf.studio.map.ShapeTool;
import net.trdlo.wtf.studio.outline.OutlineLibrary;
import net.trdlo.wtf.studio.outline.OutlinePicker;
import net.trdlo.wtf.studio.project.ProjectModel;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * One shape, and every way of getting one: draw it, pick it out of the library, import it, throw
 * it away.
 * <p>
 * <b>Editing is not one of them.</b> It is not a way of getting a shape, it is what the step does:
 * the slot's shape has handles for as long as its step is on screen, and none at all outside it.
 * There is no button for it: a button would have to be found and pressed before a corner could be
 * moved, and would leave the subject draggable in the area step, which is a shape being edited in
 * a step that is not about it.
 * <p>
 * The subject step has one of these and the area step has one, for its Custom shape. Both gather
 * parts, because either is often several things – Germany and Slovakia, Europe and an island drawn
 * beside it – and both have the whole editor. They differ in what the print does with the shape:
 * the subject is the cut line, used exactly as given, and the Custom shape is what the area grows
 * from. A place, one click, is the subject's – a town, a summit, with the area grown around it in
 * the next step – and each slot keeps its own colors, so that a Custom shape does not read as a
 * second subject.
 */
public final class ShapeSlot {

    private final ProjectModel model;
    private final StepContext ctx;
    private final ShapeTool.Slot slot;
    private final String titleKey;
    /** The gray line under the caption, or null for none. */
    private final @Nullable String hintKey;
    /** The text behind the caption's "?". */
    private final String helpKey;
    private final ObjectProperty<Geometry> target;
    private final StringProperty origin;
    /** Where the live tool writes "drag a rectangle"; owned by the panel, shared by its slots. */
    private final Label hint;

    private final ToggleGroup tools = new ToggleGroup();
    /** Whatever arrives from a drawing tool. */
    private final Consumer<Geometry> accept = g -> add(g, "drawn");
    /** Whether this slot's step is the one on screen; see {@link #activate}. */
    private boolean active;

    public ShapeSlot(final @NotNull ProjectModel model, final @NotNull StepContext ctx,
                     final ShapeTool.@NotNull Slot slot, final @NotNull String titleKey,
                     final @Nullable String hintKey, final @NotNull String helpKey,
                     final @NotNull ObjectProperty<Geometry> target,
                     final @NotNull StringProperty origin, final @NotNull Label hint) {
        this.model = model;
        this.ctx = ctx;
        this.slot = slot;
        this.titleKey = titleKey;
        this.hintKey = hintKey;
        this.helpKey = helpKey;
        this.target = target;
        this.origin = origin;
        this.hint = hint;
    }

    /** Caption, what it is, what you can do, what you have – in that order, for every slot. */
    public @NotNull Node build() {
        final Label title = new Label(I18n.get(titleKey));
        title.getStyleClass().add("step-label");

        final Label state = new Label();
        state.getStyleClass().add("step-summary");
        state.setWrapText(true);
        state.setMaxWidth(290);
        final Tooltip where = new Tooltip();
        final Runnable describe = () -> {
            state.setText(describe(target.get(), origin.get()));
            final String bounds = bounds(target.get());
            where.setText(bounds);
            state.setTooltip(bounds.isEmpty() ? null : where);
        };
        StepPanel.live(target, g -> describe.run());
        origin.addListener((o, a, b) -> describe.run());

        // Most shapes come from the library, so it comes first and says what it does in words, on
        // a line of its own. Importing has a word too; drawing is what the icons show.
        final Button library = wordButton(FontAwesomeSolid.GLOBE_EUROPE, "area.tool.library", null,
                this::fromLibrary);
        // Set apart from the drawing tools by a little room rather than a separator, which the
        // row in step 1 has no width for once Import has its word
        final Button importing = wordButton(FontAwesomeSolid.FILE_IMPORT, "area.import", "area.tool.import",
                this::fromFile);
        FlowPane.setMargin(importing, new Insets(0, 0, 0, 6));
        // A FlowPane, not an HBox: the panel is 330 pixels wide, so in a row the last control would
        // be off the edge of a narrow panel – present, reachable by tab, and invisible. They wrap.
        final FlowPane buttons = new FlowPane(4, 4,
                toolButton(FontAwesomeSolid.VECTOR_SQUARE, "area.tool.rectangle",
                        () -> new ShapeTool.RectangleTool(slot, accept, this::clearTool)),
                toolButton(FontAwesomeSolid.DRAW_POLYGON, "area.tool.polygon",
                        () -> new ShapeTool.PolygonTool(slot, accept, this::clearTool)),
                importing,
                iconButton(FontAwesomeSolid.TRASH_ALT, "area.tool.clear", () -> {
                    target.set(null);
                    origin.set(null);
                }));
        // A place is the subject's: a town, a summit, with the area grown around it in the next
        // step. The area grows from a shape somebody gives it, or from that subject.
        if (slot == ShapeTool.Slot.SUBJECT) {
            buttons.getChildren().add(2, toolButton(FontAwesomeSolid.MAP_PIN, "area.tool.point",
                    () -> new ShapeTool.PointTool(slot, accept, this::clearTool)));
        }
        // The shape can be replaced while the step is open – a library pick, an import, a part
        // taken out. The editor follows it by itself; only what the hint says about it changes
        // here.
        //
        // Not by installing the editor again: installing a tool asks the map for the keyboard
        // focus – so every digit typed into "Grow by" would re-derive the area, reinstall the
        // editor, and take the field's focus away.
        StepPanel.live(target, g -> {
            if (active && tools.getSelectedToggle() == null) {
                showEditHint();
            }
        });
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPrefWrapLength(286);

        final VBox box = new VBox(6, Forms.helped(title, helpKey));
        if (hintKey != null) {
            box.getChildren().add(StepPanel.hint(hintKey));
        }
        box.getChildren().addAll(library, buttons, state);
        VBox.setMargin(box, new Insets(2, 0, 0, 0));
        return box;
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Adds a shape to the slot rather than replacing what is in it.
     * <p>
     * <b>A shape is often several things.</b> Germany and Slovakia; a country with a rectangle
     * over the part that matters; an imported file with an island drawn in by hand; Europe and the
     * valley beside it. If each arrival threw away the last, the only way to say two things would be
     * to prepare one file outside the application – and the bin is here for when you mean to start
     * again, and Control and a right-click for one part.
     * <p>
     * The parts stay parts. They are made one shape on the way into the engine, which is the last
     * moment at which two overlapping ones could count a tile twice – and for a Custom shape, when
     * the area is grown from them.
     */
    void add(final @NotNull Geometry shape, final @NotNull String how) {
        try {
            final Geometry combined = Shapes.withPart(target.get(), shape, role());
            target.set(combined);
            origin.set(describeOrigin(origin.get(), how, combined));
        } catch (final RuntimeException ex) {
            Dialogs.error(ctx.stage(), ex);
        }
    }

    private Shapes.@NotNull Role role() {
        return slot == ShapeTool.Slot.AREA ? Shapes.Role.AREA : Shapes.Role.SUBJECT;
    }

    /** "Czechia, from the outline library + 1 more" – what the slot holds, not how it got there. */
    private static @NotNull String describeOrigin(final @Nullable String existing,
                                                  final @NotNull String how,
                                                  final @NotNull Geometry combined) {
        if (existing == null || existing.isBlank()) {
            return how;
        }
        // A token, not a sentence: it goes into the project file, and a file written in Czech
        // should not greet an English reader with Czech. originLabel() makes it words.
        return stripCount(existing) + PARTS + combined.getNumGeometries();
    }

    /** Between an origin and the number of parts it has grown to: {@code drawn + 3}. */
    private static final String PARTS = " + ";

    /**
     * The origin without the number of parts after it: the line under the shape counts its parts
     * itself. Only a number is a count – a file may have " + " in its name.
     */
    private static @NotNull String stripCount(final @NotNull String origin) {
        final int plus = origin.lastIndexOf(PARTS);
        return plus >= 0 && origin.substring(plus + PARTS.length()).matches("\\d+")
                ? origin.substring(0, plus) : origin;
    }

    /**
     * This slot's step is the one on screen, so its shape is the one with handles on it.
     * <p>
     * Called from the panel's {@code shown()} – the area step's while the Custom shape is the one
     * the area grows from. Steps that hold no shape override nothing, so arriving at one leaves the
     * map with no tool at all – which is the point.
     */
    public void activate() {
        active = true;
        installEditTool();
    }

    /** And the step is over: no handles, no half-drawn polygon, no tool. */
    public void deactivate() {
        active = false;
        final Toggle selected = tools.getSelectedToggle();
        if (selected != null) {
            selected.setSelected(false);
        }
        ctx.map().setTool(null);
        hint.setText("");
    }

    /**
     * Puts this slot's editor on the map, if this slot's step is the one showing.
     * <p>
     * The {@code active} guard is what makes this safe to call from everywhere it is called from –
     * the target changing, a drawing tool finishing, a toggle clearing – without any of them
     * having to know whether the step is still open.
     */
    private void installEditTool() {
        if (!active) {
            return;
        }
        final EditTool t = new EditTool(slot, EditTool.Mode.FULL, target, accept,
                () -> ctx.map().requestPaint());
        t.setSubjectColor(() -> model.boundaryRgbProperty().get());
        ctx.map().setTool(t);
        showEditHint();
    }

    /**
     * What the hint says while the step's editor is the tool.
     * <p>
     * Nothing to edit, nothing to say about editing it. The hint is shared with the panel, and
     * instructions for moving a vertex that is not there read as a fault.
     */
    private void showEditHint() {
        final Geometry g = target.get();
        hint.setText(EditTool.isEditable(g) ? I18n.get(EditTool.hintKeyFor(g)) : "");
    }

    private @NotNull ToggleButton toolButton(final @NotNull FontAwesomeSolid icon,
                                             final @NotNull String tipKey,
                                             final java.util.function.@NotNull Supplier<ShapeTool> make) {
        final ToggleButton b = new ToggleButton(null, new FontIcon(icon));
        b.setTooltip(new Tooltip(I18n.get(tipKey)));
        b.setToggleGroup(tools);
        b.setFocusTraversable(false);
        b.selectedProperty().addListener((o, a, selected) -> {
            if (selected) {
                final ShapeTool t = make.get();
                ctx.map().setTool(t);
                hint.setText(I18n.get(t.hintKey()));
            } else if (ctx.map().tool() != null && tools.getSelectedToggle() == null) {
                installEditTool();
            }
        });
        return b;
    }

    private @NotNull Button iconButton(final @NotNull FontAwesomeSolid icon, final @NotNull String tipKey,
                                       final @NotNull Runnable action) {
        final Button b = new Button(null, new FontIcon(icon));
        b.setTooltip(new Tooltip(I18n.get(tipKey)));
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        return b;
    }

    /** A button that says what it does, with an icon before the words and a tooltip if there is more to say. */
    private static @NotNull Button wordButton(final @NotNull FontAwesomeSolid icon, final @NotNull String key,
                                              final @Nullable String tipKey, final @NotNull Runnable action) {
        final Button b = new Button(I18n.get(key), new FontIcon(icon));
        if (tipKey != null) {
            b.setTooltip(new Tooltip(I18n.get(tipKey)));
        }
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        // Never shortened to an ellipsis: the words are the point of it
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    /**
     * The tool has produced its shape; hand the map back to the step's editor.
     * <p>
     * Not to nothing: drawing is an excursion, not a one-way door. {@code ShapeTool.finish} calls
     * {@code onFinished} before {@code onDone}, so by the time this runs the new shape is already
     * in the property and the handles land on it without a second click.
     */
    void clearTool() {
        final Toggle selected = tools.getSelectedToggle();
        if (selected != null) {
            selected.setSelected(false);
        }
        installEditTool();
    }

    /**
     * A country or region out of the bundled library, which is where most subjects come from.
     * <p>
     * The origin records the entry's id rather than the word "library", so that reopening the
     * project can still say what the shape is, and so the attribution can name the source.
     */
    private void fromLibrary() {
        OutlinePicker.show(ctx.stage()).ifPresent(chosen -> {
            add(chosen.geometry(), "library:" + chosen.entry().id() + ":" + chosen.entry().name());
            ctx.map().camera().zoomTo(Shapes.boundsOf(target.get()), 0.08);
            ctx.map().requestPaint();
            warnIfCoarse(chosen.geometry());
        });
    }

    private void fromFile() {
        final FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("area.import.title"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("area.import.shapes"), ShapeImport.extensions()),
                new FileChooser.ExtensionFilter(I18n.get("area.import.all"), List.of("*.*")));
        startIn(chooser);
        final java.io.File chosen = Modal.openFile(chooser, ctx.stage());
        if (chosen == null) {
            return;
        }
        final Path path = chosen.toPath();
        if (path.toAbsolutePath().getParent() != null) {
            ctx.prefs().putPath(Prefs.LAST_IMPORT_DIR, path.toAbsolutePath().getParent());
        }
        try {
            final Geometry g = ShapeImport.read(path);
            add(g, path.getFileName().toString());
            ctx.map().camera().zoomTo(Shapes.boundsOf(target.get()), 0.12);
            ctx.map().requestPaint();
            warnIfCoarse(g);
        } catch (final Exception ex) {
            Dialogs.error(ctx.stage(), ex);
        }
    }

    /**
     * Where the file picker opens: the folder a shape was last imported from, else the project's
     * own folder, else the one last exported to.
     * <p>
     * Shapes are kept in a folder of their own more often than not, so the last import wins; a
     * first import starts beside the project, which is the other place somebody puts one.
     */
    private void startIn(final @NotNull FileChooser chooser) {
        final Path project = model.fileProperty().get() == null ? null
                : model.fileProperty().get().toAbsolutePath().getParent();
        for (final Path dir : java.util.Arrays.asList(ctx.prefs().getPath(Prefs.LAST_IMPORT_DIR), project,
                ctx.prefs().getPath(Prefs.LAST_PROJECT_DIR), ctx.prefs().getPath(Prefs.LAST_EXPORT_DIR))) {
            if (dir != null && java.nio.file.Files.isDirectory(dir)) {
                chooser.setInitialDirectory(dir.toFile());
                return;
            }
        }
    }

    /**
     * A 34-point country outline draws a 34-sided polygon on a five-meter wall however fine the
     * tiles are. Better to say so when it arrives than to discover it on paper.
     */
    private void warnIfCoarse(final @NotNull Geometry g) {
        final var print = model.printRect();
        if (print == null) {
            return;
        }
        final Shapes.Coarseness c = Shapes.coarseness(g, print.mmPerUnit());
        if (c != null && c.isVisiblyAngular()) {
            hint.setText(I18n.format("area.coarse",
                    net.trdlo.wtf.studio.Units.current().length(c.medianMm(), 1)));
        }
    }

    // -------------------------------------------------------------------------------------------

    /**
     * What the slot holds, in a person's terms: where it came from and how big it is on the ground,
     * "Czechia, from the outline library · 490 × 280 km", and how many parts it has when it has more
     * than one. A place has no size, so it is only where it came from.
     */
    static @NotNull String describe(final @Nullable Geometry g, final @Nullable String origin) {
        if (g == null || g.isEmpty()) {
            return I18n.get("area.slot.empty");
        }
        final var b = Shapes.boundsOf(g);
        final String from = origin == null || origin.isBlank() ? null : originLabel(stripCount(origin), false);
        final String size = g.getDimension() == 0 ? null : AreaPanel.ground(b);
        String line = from == null ? size : size == null ? from : I18n.format("shape.summary", from, size);
        if (line == null) {
            line = I18n.get("area.origin.drawn");
        }
        return g.getNumGeometries() > 1
                ? I18n.format("shape.summary", line, I18n.format("shape.parts", g.getNumGeometries()))
                : line;
    }

    /** The rail's word for it: where it came from, a library entry by its name alone. */
    static @NotNull String summary(final @Nullable Geometry g, final @Nullable String origin) {
        if (g == null || g.isEmpty()) {
            return I18n.get("subject.summary.none");
        }
        final String from = origin == null || origin.isBlank() ? I18n.get("area.origin.drawn")
                : originLabel(stripCount(origin), true);
        return g.getNumGeometries() > 1
                ? I18n.format("shape.summary", from, I18n.format("shape.parts", g.getNumGeometries()))
                : from;
    }

    /** Its corners in degrees, for the tooltip of the line that describes it; empty for nothing. */
    static @NotNull String bounds(final @Nullable Geometry g) {
        if (g == null || g.isEmpty()) {
            return "";
        }
        final var b = Shapes.boundsOf(g);
        return I18n.format("area.slot.bounds",
                I18n.number(b.west(), 3), I18n.number(b.south(), 3),
                I18n.number(b.east(), 3), I18n.number(b.north(), 3));
    }

    /** The origins with a word of their own, in {@code area.origin.*}. */
    private static final java.util.Set<String> ORIGINS = java.util.Set.of("drawn", "subject");

    /**
     * What the origin says to a reader.
     * <p>
     * A library outline records {@code library:<id>:<name>}, so that a reopened project can still
     * name what it holds and the attribution can name where it came from. The user sees the name:
     * "Czechia, from the outline library", or with {@code nameOnly}, as the rail says it, "Czechia".
     */
    private static @NotNull String originLabel(final @NotNull String origin, final boolean nameOnly) {
        // The origins the application writes itself; anything else is a file name, shown as it is
        if (ORIGINS.contains(origin)) {
            return I18n.get("area.origin." + origin);
        }
        if (!origin.startsWith("library:")) {
            return origin;
        }
        // The id has a colon of its own – ne0:CZE – so the name starts after the second one
        final String rest = origin.substring("library:".length());
        final int name = rest.indexOf(':', rest.indexOf(':') + 1);
        // Named as the library names it now, in the interface language; the file keeps English
        final String shown = name < 0 ? rest : OutlineLibrary.get().find(rest.substring(0, name))
                .map(OutlineLibrary.Entry::shown).orElse(rest.substring(name + 1));
        return nameOnly ? shown : I18n.format("outline.chosen", shown);
    }
}
