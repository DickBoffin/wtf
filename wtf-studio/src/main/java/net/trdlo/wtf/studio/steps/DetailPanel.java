package net.trdlo.wtf.studio.steps;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.compose.PrintRect;
import net.trdlo.wtf.compose.TileEffect;
import net.trdlo.wtf.studio.Forms;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.Mercator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Step 4: how much detail, and how each region is colored.
 * <p>
 * The table is the point of this panel, and the column that matters is the last one. In native
 * mode the DPI and the zoom are the same choice, so <b>raising the DPI shrinks every label</b>:
 * Bohemia across 5.4 m gives 2.65 mm place names at z13 and 1.32 mm at z14, and reading stops
 * working somewhere around 1.5 mm. That is the easiest way to ruin a wall-sized print, and the
 * only defense is to put the number on screen before the paper is bought.
 * See docs/design/compose-module.md §3.
 */
public final class DetailPanel extends StepPanel {

    /**
     * A row of the scale table: a zoom, at the usual scale or from its {@code @2x} tiles, and the
     * print's width beside the DPI, for a print sized at a DPI.
     */
    private record ZoomRow(int zoom, int scale, double dpi, double widthMm, double labelMm, long tiles) {

        /** "13", or "13 ×2" for its {@code @2x} tiles. */
        @NotNull String zoomText() {
            return DetailPanel.zoomText(zoom, scale);
        }

        /** Below this a place name is not readable at arm's length, whatever the printer does. */
        boolean isUnreadable() {
            return labelMm < 1.5;
        }

        boolean isComfortable() {
            return labelMm >= 2.0;
        }
    }

    private final StepContext ctx;
    private final TableView<ZoomRow> table = new TableView<>();
    private final Label verdict = new Label();
    /**
     * The DPI at each zoom – or, for a print sized at a DPI, which is the same at every zoom, the
     * width each zoom makes of it.
     */
    private final TableColumn<ZoomRow, Double> second = new TableColumn<>();
    /** The second column's heading, which names the DPI or the width and explains either. */
    private final Label secondHeader = new Label();
    private boolean secondIsWidth;
    /** The step's two sentences: how the zoom works, which a print sized at a DPI changes. */
    private final Label intro = new Label();
    private ScalePreview preview;

    public DetailPanel(final @NotNull ProjectModel model, final @NotNull StepContext ctx) {
        super(model);
        this.ctx = ctx;
    }

    @Override
    public @NotNull Step step() {
        return Step.DETAIL;
    }

    @Override
    protected @NotNull Node build() {
        buildTable();

        verdict.getStyleClass().add("step-summary");
        verdict.setWrapText(true);
        verdict.setMaxWidth(290);

        intro.getStyleClass().add("step-hint");
        intro.setWrapText(true);
        intro.setMaxWidth(290);
        live(model.sizeByProperty(), by -> intro.setText(I18n.get(
                by == ProjectModel.SizeBy.DPI ? "detail.intro.dpi" : "detail.intro")));

        final VBox box = column();
        box.getChildren().addAll(
                Forms.heading("detail.title"),
                intro,
                table,
                verdict,
                gap());

        final VBox insideBox = region("detail.inside", model.insideEffectProperty(), model.insideDrawnProperty());
        box.getChildren().add(insideBox);

        // The outside region only exists when there is a subject shape to divide it from the
        // inside; showing two identical panels with no cut between them invites the question
        // "which one is this?" and has no answer.
        final VBox outside = region("detail.outside", model.outsideEffectProperty(), model.outsideDrawnProperty());

        // The opt-in, under the heading that draws the outside at all. Everything below it is the
        // exception, and until somebody asks for it the outside is treated as the inside is.
        final CheckBox drawsOutside = regionTitle(outside);
        final CheckBox differs = new CheckBox(I18n.get("detail.outside.differs"));
        differs.setTooltip(new Tooltip(I18n.get("detail.outside.differs.tip")));
        differs.selectedProperty().bindBidirectional(model.outsideDiffersProperty());
        // And while the inside is not drawn, the outside differs by force (ProjectModel), so the
        // box is ticked and there is nothing to choose
        differs.disableProperty().bind(drawsOutside.selectedProperty().not()
                .or(model.insideDrawnProperty().not()));
        outside.getChildren().add(1, differs);
        // The treatment opens with the box: until somebody asks for it there is nothing to set, and
        // a copy of the controls grayed out says only that there could be
        final Node treatment = regionBody(outside);
        treatment.visibleProperty().bind(drawsOutside.selectedProperty().and(differs.selectedProperty()));
        treatment.managedProperty().bind(treatment.visibleProperty());

        final VBox boundary = boundaryBox();
        // Last, because the preview shows what everything above it sets: the zoom, both
        // treatments and the line along the outline
        box.getChildren().addAll(outside, boundary, gap(),
                Forms.button("detail.preview", this::openPreview), hint("detail.preview.hint"));
        live(model.subjectProperty(), s -> {
            final boolean has = s != null;
            outside.setVisible(has);
            outside.setManaged(has);
            boundary.setVisible(has);
            boundary.setManaged(has);
            // Without a subject there is one region, and heading it "inside the subject" names
            // a thing that is not there.
            regionTitle(insideBox).setText(I18n.get(has ? "detail.inside" : "detail.whole"));
        });

        live(model.revisionProperty(), r -> refreshTable());
        return box;
    }

    // -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void buildTable() {
        final TableColumn<ZoomRow, String> zoom = new TableColumn<>();
        heading(zoom, new Label(I18n.get("detail.col.zoom")), "detail.col.zoom.tip");
        zoom.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().zoomText()));
        zoom.setPrefWidth(60);

        secondHeader.setText(I18n.get("detail.col.dpi"));
        heading(second, secondHeader, "detail.col.dpi.tip");
        second.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                secondIsWidth ? c.getValue().widthMm() : c.getValue().dpi()));
        second.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(final Double v, final boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? ""
                        : secondIsWidth ? net.trdlo.wtf.studio.Units.current().number(v, 0) : I18n.number(v, 0));
            }
        });
        second.setPrefWidth(56);

        // Not the map scale: that is fixed by the print's size and the ground it covers, so it
        // is the same on every row and a column of thirteen identical numbers teaches nobody
        // anything. The tile count is what changes with the zoom, and it is the download bill.
        final TableColumn<ZoomRow, Long> tiles = new TableColumn<>();
        heading(tiles, new Label(I18n.get("detail.col.tiles")), "detail.col.tiles.tip");
        tiles.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().tiles()));
        tiles.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(final Long v, final boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? "" : I18n.integer(v));
            }
        });
        tiles.setPrefWidth(80);

        final TableColumn<ZoomRow, Double> label = new TableColumn<>();
        heading(label, new Label(I18n.format("detail.col.label", net.trdlo.wtf.studio.Units.current().symbol())),
                "detail.col.label.tip");
        label.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().labelMm()));
        label.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(final Double v, final boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setText("");
                    setStyle("");
                    return;
                }
                setText(net.trdlo.wtf.studio.Units.current().number(v, 2));
                // The one piece of color in the panel, on the one number people misjudge
                setStyle(v < 1.5 ? "-fx-text-fill: #ff8787;"
                        : v >= 2.0 ? "-fx-text-fill: #8ce99a;" : "");
            }
        });
        label.setPrefWidth(86);

        table.getColumns().setAll(zoom, second, tiles, label);
        table.getStyleClass().add("zoom-table");
        // A fixed row height so the table ends on a row boundary rather than slicing one in half
        table.setFixedCellSize(26);
        table.setPrefHeight(26 * 7 + 28);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(I18n.get("detail.table.empty")));
        table.setTooltip(new Tooltip(I18n.get("detail.retina.tip")));
        // Clicking a row is the fastest way to say "that one": its zoom and its scale, in one step
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                model.zoomProperty().set(b.zoom());
                model.scaleProperty().set(b.scale());
            }
        });
    }

    /**
     * A column's heading as a label with a tooltip, since a column has none of its own, and the
     * column never narrower than its heading: "Přiblížení" ending in an ellipsis is the word the
     * table is about.
     */
    private static void heading(final @NotNull TableColumn<ZoomRow, ?> column, final @NotNull Label text,
                                final @NotNull String tipKey) {
        text.setTooltip(new Tooltip(I18n.get(tipKey)));
        text.getStyleClass().add("zoom-heading");
        text.setMinWidth(Region.USE_PREF_SIZE);
        column.setText(null);
        column.setGraphic(text);
        column.minWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> text.prefWidth(-1) + 12, text.textProperty(), text.fontProperty()));
    }

    private void refreshTable() {
        final PrintRect print = model.printRect();
        if (print == null) {
            table.setItems(FXCollections.emptyObservableList());
            verdict.setText(I18n.get("detail.noPrint"));
            return;
        }
        secondIsWidth = model.sizeByProperty().get() == ProjectModel.SizeBy.DPI;
        secondHeader.setText(secondIsWidth
                ? I18n.format("detail.col.width", net.trdlo.wtf.studio.Units.current().symbol())
                : I18n.get("detail.col.dpi"));
        secondHeader.getTooltip().setText(I18n.get(secondIsWidth ? "detail.col.width.tip" : "detail.col.dpi.tip"));
        final boolean retina = offersRetina();
        final int chosenScale = model.printScale();
        final List<ZoomRow> rows = new ArrayList<>();
        for (final int z : model.availableZooms()) {
            // Counting is by the print's bounding box, not the plan: the plan runs a geometry
            // predicate per tile and this table redraws on every keystroke in step 3.
            final long n = Mercator.tileCount(z);
            final long across = Math.max(1, Math.round(print.unitWidth() * n));
            final long down = Math.max(1, Math.round(print.unitHeight() * n));
            // A @2x tile has its zoom's names and ground, in twice the pixels: the DPI of the zoom
            // below, the label of this one, and this one's tile count
            for (final int s : retina ? new int[]{1, 2} : new int[]{1}) {
                // What this zoom would make of a print sized at a DPI: twice as wide a zoom up
                final double width = print.widthMm() * Math.pow(2, z - model.zoomProperty().get())
                        * s / chosenScale;
                rows.add(new ZoomRow(z, s, print.dpi(z, s), width, print.labelHeightMm(z), across * down));
            }
        }
        table.setItems(FXCollections.observableArrayList(rows));

        final int chosen = model.zoomProperty().get();
        // Was this already the chosen row before the table was rebuilt? A row is a record, so the
        // selection survives a rebuild that leaves the selected row's values as they were; and it
        // is asked of the zoom, since the selected row need not be the chosen one any more.
        final ZoomRow previous = table.getSelectionModel().getSelectedItem();
        final boolean unchanged = previous != null && previous.zoom() == chosen && previous.scale() == chosenScale;
        rows.stream().filter(r -> r.zoom() == chosen && r.scale() == chosenScale).findFirst()
                .ifPresent(r -> {
                    table.getSelectionModel().select(r);
                    // Only when the choice came from somewhere else – an undo, a project being
                    // opened. Scrolling a row the user has just clicked on puts it at the
                    // top under their cursor, which reads as the table jumping for no reason.
                    if (!unchanged) {
                        table.scrollTo(r);
                    }
                    // At a DPI no zoom changes a label's height, so the way out is the DPI
                    verdict.setText(r.isUnreadable()
                            ? I18n.format(secondIsWidth ? "detail.verdict.unreadable.dpi" : "detail.verdict.unreadable",
                            labelLength(r.labelMm()))
                            : r.isComfortable()
                            ? I18n.format("detail.verdict.good", labelLength(r.labelMm()),
                            I18n.number(r.dpi(), 0))
                            : I18n.format("detail.verdict.marginal", labelLength(r.labelMm())));
                });
    }

    // -------------------------------------------------------------------------------------------

    /**
     * One region's controls, the same for inside and outside – they are symmetric. The heading is
     * the checkbox that draws the region, and what is under it is the region's treatment, which
     * means something only while the region is drawn. The three sliders are shown for a custom
     * treatment only: under <i>Unchanged</i> and <i>Faded</i> they would sit at values nobody chose.
     */
    private @NotNull VBox region(final @NotNull String titleKey, final @NotNull ObjectProperty<TileEffect> effect,
                                 final @NotNull BooleanProperty drawn) {
        final CheckBox title = new CheckBox(I18n.get(titleKey));
        title.selectedProperty().bindBidirectional(drawn);
        title.setTooltip(new Tooltip(I18n.get("detail.drawn.tip")));

        final ComboBox<String> preset = new ComboBox<>();
        preset.getItems().setAll(I18n.get("detail.effect.none"), I18n.get("detail.effect.faded"),
                I18n.get("detail.effect.custom"));
        preset.setMaxWidth(Double.MAX_VALUE);

        // Custom is a choice of its own and not only an effect that is neither of the others: it is
        // picked before a slider has moved, and it stays while a slider passes through None
        final BooleanProperty custom = new SimpleBooleanProperty(!isPreset(effect.get()));
        final BooleanProperty sliding = new SimpleBooleanProperty();
        final Supplier<String> shown = () -> custom.get() ? I18n.get("detail.effect.custom") : nameOf(effect.get());
        preset.setValue(shown.get());
        preset.valueProperty().addListener((o, a, b) -> {
            if (b == null) {
                return;
            }
            if (b.equals(I18n.get("detail.effect.none"))) {
                custom.set(false);
                effect.set(TileEffect.NONE);
            } else if (b.equals(I18n.get("detail.effect.faded"))) {
                custom.set(false);
                effect.set(TileEffect.FADED);
            } else {
                custom.set(true);
            }
        });
        effect.addListener((o, a, b) -> {
            if (!isPreset(b)) {
                custom.set(true);
            } else if (!sliding.get()) {
                custom.set(false);
            }
            preset.setValue(shown.get());
        });

        final Slider saturation = effectSlider(0, 1, effect, sliding, TileEffect::saturation,
                (e, v) -> new TileEffect(v, e.lightness(), e.contrast()));
        final Slider lightness = effectSlider(-1, 1, effect, sliding, TileEffect::lightness,
                (e, v) -> new TileEffect(e.saturation(), v, e.contrast()));
        final Slider contrast = effectSlider(-1, 1, effect, sliding, TileEffect::contrast,
                (e, v) -> new TileEffect(e.saturation(), e.lightness(), v));
        final Node[] sliderRows = {Forms.row("detail.saturation", saturation),
                Forms.row("detail.lightness", lightness), Forms.row("detail.contrast", contrast)};
        for (final Node row : sliderRows) {
            row.visibleProperty().bind(custom);
            row.managedProperty().bind(custom);
        }

        final VBox treatment = new VBox(6, Forms.row("detail.effect", preset));
        treatment.getChildren().addAll(sliderRows);
        treatment.disableProperty().bind(title.selectedProperty().not());
        final VBox box = new VBox(6, title, treatment);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** The heading of a region box, which is its first child by construction. */
    private static @NotNull CheckBox regionTitle(final @NotNull VBox box) {
        return (CheckBox) box.getChildren().getFirst();
    }

    /** What is under the heading: the treatment, which is the last child by construction. */
    private static @NotNull Node regionBody(final @NotNull VBox box) {
        return box.getChildren().getLast();
    }

    /** Whether a treatment is one of the two that have a name of their own. */
    private static boolean isPreset(final @NotNull TileEffect e) {
        return e.isIdentity() || e.equals(TileEffect.FADED);
    }

    private static @NotNull String nameOf(final @NotNull TileEffect e) {
        return e.isIdentity() ? I18n.get("detail.effect.none")
                : e.equals(TileEffect.FADED) ? I18n.get("detail.effect.faded")
                : I18n.get("detail.effect.custom");
    }

    private interface EffectWith {
        @NotNull TileEffect apply(@NotNull TileEffect e, double v);
    }

    private static @NotNull Slider effectSlider(final double min, final double max,
                                                final @NotNull ObjectProperty<TileEffect> effect,
                                                final @NotNull BooleanProperty sliding,
                                                final java.util.function.@NotNull ToDoubleFunction<TileEffect> get,
                                                final @NotNull EffectWith with) {
        final Slider s = new Slider(min, max, get.applyAsDouble(effect.get()));
        s.setBlockIncrement(0.05);
        s.setMajorTickUnit(max - min);
        HBox.setHgrow(s, Priority.ALWAYS);
        s.valueProperty().addListener((o, a, b) -> {
            final TileEffect next = with.apply(effect.get(), round(b.doubleValue()));
            if (!next.equals(effect.get())) {
                sliding.set(true);
                try {
                    effect.set(next);
                } finally {
                    sliding.set(false);
                }
            }
        });
        effect.addListener((o, a, b) -> {
            final double v = get.applyAsDouble(b);
            if (Math.abs(v - s.getValue()) > 1e-9) {
                s.setValue(v);
            }
        });
        return s;
    }

    /** Two decimals: a saturation of 0.8300000000000001 in a project file helps nobody. */
    private static double round(final double v) {
        return Math.round(v * 100) / 100.0;
    }

    private @NotNull VBox boundaryBox() {
        final CheckBox on = new CheckBox(I18n.get("detail.boundary"));
        on.selectedProperty().bindBidirectional(model.boundaryStrokedProperty());

        final TextField width = lengthField(model.boundaryWidthMmProperty(), 2);
        final net.trdlo.wtf.studio.ColorField color =
                new net.trdlo.wtf.studio.ColorField(rgb(model.boundaryRgbProperty().get()));
        color.valueProperty().addListener((o, a, b) -> model.boundaryRgbProperty().set(packed(b)));
        model.boundaryRgbProperty().addListener((o, a, b) -> color.setValue(rgb(b.intValue())));

        final VBox inner = new VBox(8, Forms.lengthRow("detail.boundary.width", width),
                Forms.row("detail.boundary.color", color));
        inner.visibleProperty().bind(on.selectedProperty());
        inner.managedProperty().bind(on.selectedProperty());

        return new VBox(6, on, inner);
    }

    static @NotNull Color rgb(final int packed) {
        return Color.rgb((packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF);
    }

    static int packed(final @NotNull Color c) {
        return ((int) Math.round(c.getRed() * 255) << 16)
                | ((int) Math.round(c.getGreen() * 255) << 8)
                | (int) Math.round(c.getBlue() * 255);
    }

    // -------------------------------------------------------------------------------------------

    private void openPreview() {
        openPreviewWindow();
    }

    /** The preview, opened and handed back, so an unattended run can photograph it. */
    public @NotNull ScalePreview openPreviewWindow() {
        if (preview == null) {
            preview = new ScalePreview(ctx, model);
        }
        preview.show();
        return preview;
    }

    @Override
    public void hidden() {
        if (preview != null) {
            preview.hide();
        }
    }

    @Override
    public @NotNull String summary() {
        final PrintRect print = model.printRect();
        if (print == null) {
            return "";
        }
        final int z = model.zoomProperty().get();
        return I18n.format("detail.summary", zoomText(z, model.printScale()),
                I18n.number(print.dpi(z, model.printScale()), 0),
                net.trdlo.wtf.studio.Units.current().length(print.labelHeightMm(z), 1));
    }

    /** "13", or "13 ×2" for the zoom's {@code @2x} tiles: the table's and the summary's word for it. */
    static @NotNull String zoomText(final int zoom, final int scale) {
        return scale == 1 ? Integer.toString(zoom) : I18n.format("detail.zoom.retina", zoom);
    }

    /**
     * Whether the map has {@code @2x} tiles: its address says where they go, and it has not answered
     * one at the usual size before.
     */
    private boolean offersRetina() {
        final net.trdlo.wtf.tiles.Provider p = model.providerProperty().get();
        final net.trdlo.wtf.tiles.Provider.Theme t = model.themeProperty().get();
        return p != null && t != null && p.offersRetina(t) && !ctx.tileCache().knowsNoRetina(p.id(), t.id());
    }

    /** A place name's height on paper, as the verdicts say it: "1.20 mm", "0.047 in". */
    private static @NotNull String labelLength(final double mm) {
        return net.trdlo.wtf.studio.Units.current().length(mm, 2);
    }
}
