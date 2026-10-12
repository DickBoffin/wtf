package net.trdlo.wtf.studio.steps;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.PrintRect;
import net.trdlo.wtf.compose.PrintSize;
import net.trdlo.wtf.compose.SheetLayout;
import net.trdlo.wtf.studio.Forms;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.project.ProjectModel;

import java.util.List;
import java.util.Locale;

/**
 * Step 3: how big the print is, and what it is printed on.
 * <p>
 * The brief asks for the medium here and the zoom at step 4 and expects the DPI to fall out –
 * which it only does if the physical size is known, and the brief never says where that comes
 * from. So the size is an input – in millimeters four ways, in sheets, or as a DPI that the zoom
 * then turns into a size; see docs/design/compose-module.md §3.
 */
public final class MediumPanel extends StepPanel {

    private final Label layoutSummary = new Label();
    /** "2 across × 3 down, 420 mm wide" – the count that was asked for and the one that follows. */
    private final Label gridLabel = new Label();
    /** How tall a place name comes out at the DPI, and how big the print is at the zoom chosen so far. */
    private final Label dpiLabel = new Label();
    private final Label scaleLabel = new Label();
    private final Label diagram = new Label();

    public MediumPanel(final @NotNull ProjectModel model) {
        super(model);
    }

    @Override
    public @NotNull Step step() {
        return Step.MEDIUM;
    }

    @Override
    protected @NotNull Node build() {
        // --- how big -------------------------------------------------------------------------
        final ComboBox<PrintSize.Mode> mode =
                Forms.enumCombo(model.printModeProperty(), PrintSize.Mode.values(), "medium.size");

        final net.trdlo.wtf.studio.Units u = net.trdlo.wtf.studio.Units.current();
        final LengthText widthText = new LengthText(model.printWidthMmProperty(), u, 0);
        final LengthText heightText = new LengthText(model.printHeightMmProperty(), u, 0);
        final TextField width = lengthField(widthText);
        final TextField height = lengthField(heightText);
        final Node widthRow = Forms.lengthRow("medium.width", width);
        final Node heightRow = Forms.lengthRow("medium.height", height);
        // FIT_WIDTH has no height to ask for, and an enabled field that changes nothing is a lie
        live(model.printModeProperty(), m -> {
            final boolean needsWidth = m != PrintSize.Mode.FIT_HEIGHT;
            final boolean needsHeight = m != PrintSize.Mode.FIT_WIDTH;
            widthRow.setDisable(!needsWidth);
            heightRow.setDisable(!needsHeight);
            showTheOtherSide(model, widthText, heightText);
        });
        model.printModeProperty().addListener((o, was, is) -> takeTheOtherSide(was, is, widthText, heightText));
        // The disabled field does not keep whatever number was last typed into it, which is a
        // number the print is not going to be. It shows the one the other side works out, so
        // switching which side is fixed leaves the same print rather than a different one.
        live(model.revisionProperty(), r -> showTheOtherSide(model, widthText, heightText));

        // --- what it is printed on --------------------------------------------------------------
        final ToggleGroup kind = new ToggleGroup();
        // Sheets first, and the default: everybody has a printer that takes them, and a roll is
        // the deliberate choice of somebody who has one.
        final RadioButton sheet = new RadioButton(I18n.get("medium.kind.sheet"));
        final RadioButton roll = new RadioButton(I18n.get("medium.kind.roll"));
        sheet.setToggleGroup(kind);
        roll.setToggleGroup(kind);
        live(model.mediumKindProperty(), k -> {
            if (k == ProjectModel.MediumKind.ROLL) {
                roll.setSelected(true);
            } else {
                sheet.setSelected(true);
            }
        });
        kind.selectedToggleProperty().addListener((o, a, b) -> model.mediumKindProperty().set(
                b == roll ? ProjectModel.MediumKind.ROLL : ProjectModel.MediumKind.SHEET));

        final ComboBox<Double> rollWidth = new ComboBox<>();
        rollWidth.setEditable(true);
        // 914 and 915 mm are both "36 in": one entry for each width the list would show. A 915 mm
        // roll from a file still keeps its width, since the text shown for it is never read back.
        final java.util.Map<String, Double> widths = new java.util.LinkedHashMap<>();
        Medium.ROLL_WIDTHS.forEach(w -> widths.putIfAbsent(rollText(u, w), w));
        rollWidth.getItems().setAll(widths.values());
        rollWidth.setConverter(new RollWidthText(model.rollWidthMmProperty(), u));
        // The model's width whenever it changes, so an open or an undo shows its own and not the
        // one the step was built with
        live(model.rollWidthMmProperty(), w -> rollWidth.setValue(w.doubleValue()));
        rollWidth.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                model.rollWidthMmProperty().set(b);
            }
        });
        rollWidth.setMaxWidth(Double.MAX_VALUE);

        final ComboBox<Medium.Axis> axis =
                Forms.enumCombo(model.stripAxisProperty(), Medium.Axis.values(), "medium.axis");
        axis.setTooltip(new Tooltip(I18n.get("medium.axis.tip")));

        final ComboBox<Medium.Paper> paper = new ComboBox<>();
        paper.getItems().setAll(Medium.PAPER_SIZES);
        paper.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final Medium.Paper p) {
                // One decimal where the standard has one: Letter is 215.9 mm, and a label that
                // rounds it to 216 says a size the paper is not.
                return p == null ? "" : I18n.format("medium.paper.item", p.name(),
                        u.size(p.widthMm(), p.heightMm(), wholeMm(p.widthMm()) && wholeMm(p.heightMm()) ? 0 : 1));
            }

            @Override
            public Medium.Paper fromString(final String s) {
                return null;
            }
        });
        paper.setMaxWidth(Double.MAX_VALUE);
        paper.valueProperty().bindBidirectional(model.paperProperty());

        final ComboBox<Medium.Orientation> orientation =
                Forms.enumCombo(model.orientationProperty(), Medium.Orientation.values(),
                        "medium.orientation");

        final VBox rollBox = new VBox(8, Forms.lengthRow("medium.rollWidth", rollWidth),
                Forms.row("medium.axis", axis));
        final VBox sheetBox = new VBox(8, Forms.row("medium.paper", paper),
                Forms.row("medium.orientation", orientation));
        live(model.mediumKindProperty(), k -> {
            final boolean isRoll = k == ProjectModel.MediumKind.ROLL;
            rollBox.setVisible(isRoll);
            rollBox.setManaged(isRoll);
            sheetBox.setVisible(!isRoll);
            sheetBox.setManaged(!isRoll);
        });

        // --- overlap ------------------------------------------------------------------------
        final TextField overlap = lengthField(model.overlapMmProperty(), 0);

        layoutSummary.getStyleClass().add("step-summary");
        layoutSummary.setWrapText(true);
        layoutSummary.setMaxWidth(290);
        diagram.getStyleClass().add("sheet-diagram");
        live(model.revisionProperty(), r -> updateSummary());

        // --- or how many sheets of it -----------------------------------------------------------
        final ToggleGroup sizeBy = new ToggleGroup();
        final RadioButton bySheets = new RadioButton(I18n.get("medium.by.sheets"));
        final RadioButton byMillimeters = new RadioButton(I18n.get("medium.by.millimeters"));
        final RadioButton byDpi = new RadioButton(I18n.get("medium.by.dpi"));
        final RadioButton byScale = new RadioButton(I18n.get("medium.by.scale"));
        bySheets.setToggleGroup(sizeBy);
        byMillimeters.setToggleGroup(sizeBy);
        byDpi.setToggleGroup(sizeBy);
        byScale.setToggleGroup(sizeBy);
        live(model.sizeByProperty(), by -> (switch (by) {
            case SHEETS -> bySheets;
            case DPI -> byDpi;
            case SCALE -> byScale;
            case MILLIMETERS -> byMillimeters;
        }).setSelected(true));
        sizeBy.selectedToggleProperty().addListener((o, a, b) -> model.sizeByProperty().set(
                b == bySheets ? ProjectModel.SizeBy.SHEETS
                        : b == byDpi ? ProjectModel.SizeBy.DPI
                        : b == byScale ? ProjectModel.SizeBy.SCALE : ProjectModel.SizeBy.MILLIMETERS));

        // Any count the engine takes, which is any: a file of 120 sheets across opens with 120. A
        // count below one prints one sheet, and is shown as one.
        final Spinner<Integer> count = new Spinner<>(1, Integer.MAX_VALUE,
                Math.max(1, model.sheetCountProperty().get()));
        // Four digits and the arrows: the axis beside it needs the rest of the row, and "na výšku"
        // is the longer of its words
        count.setPrefWidth(84);
        count.setMinWidth(Region.USE_PREF_SIZE);
        // What the spinner shows of the project is not written back to it: a count it shows
        // otherwise – one for a zero – would become the project's, quietly while a file opens
        final boolean[] following = {false};
        count.getValueFactory().valueProperty().addListener((o, a, b) -> {
            if (!following[0]) {
                model.sheetCountProperty().set(b);
            }
        });
        model.sheetCountProperty().addListener((o, a, b) -> {
            following[0] = true;
            try {
                count.getValueFactory().setValue(b.intValue());
            } finally {
                following[0] = false;
            }
        });

        final ComboBox<Boolean> axisOfCount = new ComboBox<>();
        axisOfCount.getItems().setAll(Boolean.TRUE, Boolean.FALSE);
        axisOfCount.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final Boolean across) {
                return I18n.get(Boolean.FALSE.equals(across) ? "medium.count.down" : "medium.count.across");
            }

            @Override
            public Boolean fromString(final String s) {
                return Boolean.TRUE;
            }
        });
        // The axis the print is counted along, which the model works out: a roll is cut one way
        // only, so counting the other way is not a thing that can be asked, and the combo shows the
        // strips' axis without writing it over the one chosen for sheets
        live(model.revisionProperty(), r -> axisOfCount.setValue(model.countsAcross()));
        axisOfCount.valueProperty().addListener((o, a, b) -> {
            if (b != null && model.mediumKindProperty().get() == ProjectModel.MediumKind.SHEET) {
                model.countAcrossProperty().set(b);
            }
        });
        live(model.mediumKindProperty(), k -> axisOfCount.setDisable(k == ProjectModel.MediumKind.ROLL));

        final HBox countRow = new HBox(6, count, axisOfCount);
        countRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(axisOfCount, Priority.ALWAYS);
        axisOfCount.setMaxWidth(Double.MAX_VALUE);
        // Never narrower than its word: the row is as wide as the column, and what gives way is
        // the space, not "na výšku"
        axisOfCount.setMinWidth(Region.USE_PREF_SIZE);

        // What the count works out to, and what the other axis comes to. Both read-only: they are
        // answers, and the one thing worse than not showing them is letting somebody type into
        // them and wonder why nothing happened.
        gridLabel.getStyleClass().add("step-summary");
        gridLabel.setWrapText(true);
        gridLabel.setMaxWidth(290);

        final VBox sizeInMillimeters = new VBox(8, Forms.row("medium.size", mode, "medium.size.help"),
                widthRow, heightRow, hint("medium.size.hint"));
        final VBox sizeInSheets = new VBox(8, Forms.row("medium.count", countRow, "medium.count.help"),
                gridLabel);
        final VBox sizeAtScale = new VBox(8, Forms.row("medium.scale", scaleCombo()), scaleLabel);
        scaleLabel.getStyleClass().add("step-summary");
        scaleLabel.setWrapText(true);
        scaleLabel.setMaxWidth(290);
        final VBox sizeAtDpi = new VBox(8, Forms.row("medium.dpi", dpiCombo()), dpiLabel);
        dpiLabel.getStyleClass().add("step-summary");
        dpiLabel.setWrapText(true);
        dpiLabel.setMaxWidth(290);
        live(model.sizeByProperty(), by -> {
            show(sizeInMillimeters, by == ProjectModel.SizeBy.MILLIMETERS);
            show(sizeInSheets, by == ProjectModel.SizeBy.SHEETS);
            show(sizeAtScale, by == ProjectModel.SizeBy.SCALE);
            show(sizeAtDpi, by == ProjectModel.SizeBy.DPI);
        });

        // A print on one sheet has no seam: nothing to overlap, and no line to warn of. The
        // printer's white edge stays, since the outermost sheets keep it free round the print.
        final Node overlapRow = Forms.lengthRow("medium.overlap", overlap, "medium.overlap.help");
        final Node overlapHint = hint("medium.overlap.hint");
        final Node seam = seamHint();
        live(model.revisionProperty(), r -> {
            final boolean seams = hasSeams(model);
            for (final Node n : new Node[]{overlapRow, overlapHint, seam}) {
                show(n, seams);
            }
        });

        final VBox box = column();
        box.getChildren().addAll(
                Forms.heading("medium.title"),
                hint("medium.intro"),
                // The paper first: somebody knows what their printer takes, and the size follows
                // from it. Asking how big before asking on what made the second question a
                // correction of the first.
                new HBox(12, sheet, roll),
                rollBox, sheetBox,
                gap(),
                // The common first, the technical last
                byMillimeters, bySheets, byScale, byDpi,
                sizeInMillimeters, sizeInSheets, sizeAtScale, sizeAtDpi,
                gap(),
                overlapRow,
                overlapHint,
                Forms.lengthRow("medium.edge", edgeField(), "medium.edge.help"),
                hint("medium.edge.hint"),
                seam,
                gap(),
                layoutSummary,
                diagram);
        return box;
    }

    /**
     * Fills the field the mode has taken away with the size that side is going to come out.
     * <p>
     * It is the answer to "and how tall will that be", which is the next question anybody asks of
     * "fit a width", and it is the number that makes the two modes agree: type it into the other
     * side, switch which one is fixed, and the print does not change. It is shown, not written:
     * the model keeps the length that side had.
     */
    static void showTheOtherSide(final @NotNull ProjectModel model, final @NotNull LengthText width,
                                 final @NotNull LengthText height) {
        final PrintRect print = model.printRect();
        if (print == null) {
            return;
        }
        final PrintSize.Mode mode = model.printModeProperty().get();
        if (mode == PrintSize.Mode.FIT_WIDTH) {
            height.show(print.heightMm());
        } else if (mode == PrintSize.Mode.FIT_HEIGHT) {
            width.show(print.widthMm());
        }
    }

    /**
     * When the mode sets a side the previous mode worked out, that side becomes the length it was
     * shown as, so switching which side is fixed leaves the same print. Undo and opening a file
     * write the mode before the lengths, so what they bring back is what stays.
     */
    static void takeTheOtherSide(final PrintSize.@NotNull Mode was, final PrintSize.@NotNull Mode is,
                                 final @NotNull LengthText width, final @NotNull LengthText height) {
        if (was == PrintSize.Mode.FIT_HEIGHT && is != PrintSize.Mode.FIT_HEIGHT) {
            width.adopt();
        }
        if (was == PrintSize.Mode.FIT_WIDTH && is != PrintSize.Mode.FIT_WIDTH) {
            height.adopt();
        }
    }

    /**
     * The DPI: the common ones in the list, any other typed. Bound as the roll width is, so an open
     * or an undo shows the model's own.
     */
    private @NotNull ComboBox<Integer> dpiCombo() {
        final ComboBox<Integer> dpi = new ComboBox<>();
        dpi.setEditable(true);
        dpi.getItems().setAll(DpiText.COMMON);
        dpi.setConverter(new DpiText(model.dpiProperty()));
        dpi.setTooltip(new Tooltip(I18n.get("medium.dpi.tip")));
        live(model.dpiProperty(), v -> dpi.setValue(v.intValue()));
        dpi.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                model.dpiProperty().set(b);
            }
        });
        dpi.setMaxWidth(Double.MAX_VALUE);
        return dpi;
    }

    /**
     * The scale: the common ones in the list, any other typed as two numbers and a colon. Bound as
     * the DPI is, so an open or an undo shows the model's own.
     */
    private @NotNull ComboBox<Integer> scaleCombo() {
        final ComboBox<Integer> scale = new ComboBox<>();
        scale.setEditable(true);
        scale.getItems().setAll(ScaleText.COMMON);
        scale.setConverter(new ScaleText(model.mapScaleProperty()));
        scale.setTooltip(new Tooltip(I18n.get("medium.scale.tip")));
        live(model.mapScaleProperty(), v -> scale.setValue(v.intValue()));
        scale.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                model.mapScaleProperty().set(b);
            }
        });
        scale.setMaxWidth(Double.MAX_VALUE);
        return scale;
    }

    /**
     * Whether the print has a seam, so that the overlap and the line about it mean something: more
     * than one sheet, or no layout yet to say otherwise.
     */
    static boolean hasSeams(final @NotNull ProjectModel model) {
        final SheetLayout layout = model.layout();
        return layout == null || layout.count() > 1;
    }

    private static void show(final @NotNull Node node, final boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /**
     * What the DPI decides now and what it leaves to the zoom. One tile pixel is one printed dot, so
     * a place name is as tall at every zoom, and that can be said before the zoom is chosen; the
     * size it cannot, and is said for the zoom chosen so far.
     */
    private void updateDpiLabel() {
        final int dpi = model.dpiProperty().get();
        final net.trdlo.wtf.studio.Units u = net.trdlo.wtf.studio.Units.current();
        final String label = u.length(PrintRect.labelHeightMmAt(dpi), 1);
        final PrintRect print = model.printRect();
        dpiLabel.setText(print == null
                ? I18n.format("medium.dpi.label", I18n.integer(dpi), label)
                : I18n.format("medium.dpi.result", I18n.integer(dpi), label,
                DetailPanel.zoomText(model.zoomProperty().get(), model.printScale()),
                u.size(print.widthMm(), print.heightMm(), 0)));
    }

    /**
     * What the scale comes to: the size, which it and the area decide, and the DPI and the place
     * names, which the zoom chosen so far decides.
     */
    private void updateScaleLabel() {
        final PrintRect print = model.printRect();
        if (print == null || model.sizeByProperty().get() != ProjectModel.SizeBy.SCALE) {
            scaleLabel.setText(I18n.get("medium.scale.label"));
            return;
        }
        final net.trdlo.wtf.studio.Units u = net.trdlo.wtf.studio.Units.current();
        final int zoom = model.zoomProperty().get();
        final double dpi = print.dpi(zoom, model.printScale());
        scaleLabel.setText(I18n.format("medium.scale.result",
                I18n.format("medium.scale.ratio", I18n.integer(model.mapScaleProperty().get())),
                u.size(print.widthMm(), print.heightMm(), 0),
                DetailPanel.zoomText(zoom, model.printScale()), I18n.integer(Math.round(dpi)),
                u.length(PrintRect.labelHeightMmAt(dpi), 1)));
    }

    private void updateSummary() {
        updateDpiLabel();
        updateScaleLabel();
        final SheetLayout layout = model.layout();
        if (layout == null) {
            final ProjectModel.Built b = model.build();
            layoutSummary.setText(b.problem() == null ? I18n.get("medium.noLayout") : b.problem());
            diagram.setText("");
            return;
        }
        final PrintRect print = layout.print();
        final net.trdlo.wtf.studio.Units u = net.trdlo.wtf.studio.Units.current();
        layoutSummary.setText(I18n.format("medium.result",
                u.size(print.widthMm(), print.heightMm(), 0),
                layout.count(), layout.columns(), layout.rows(),
                I18n.integer(Math.round(print.mapScale()))));
        gridLabel.setText(I18n.format("medium.count.result",
                layout.columns(), layout.rows(), u.size(print.widthMm(), print.heightMm(), 0)));
        if (model.mediumKindProperty().get() == ProjectModel.MediumKind.ROLL) {
            // Meters or feet, because a roll is sold in them and nobody pictures 5 415 mm.
            layoutSummary.setText(layoutSummary.getText() + " "
                    + I18n.format("medium.result.roll", u.longLength(layout.mediaLengthMm()),
                    rollText(u, model.rollWidthMmProperty().get()) + " " + u.symbol()));
        }
        // Where the paper whole sheets leave over went: into more map, or left white
        final Spare spare = spare(model);
        final PrintRect asked = model.printRect();
        if (spare != Spare.NONE && asked != null) {
            layoutSummary.setText(layoutSummary.getText() + System.lineSeparator() + I18n.format(
                    spare == Spare.MAP ? "medium.result.grown" : "medium.result.blank",
                    u.size(asked.widthMm(), asked.heightMm(), 0)));
        }
        diagram.setText(gridOf(layout));
    }

    /** What whole sheets do with the paper the print asked for leaves over. */
    enum Spare {
        /** Nothing is left over worth a word: the sheets hold the print asked for. */
        NONE,
        /** It is more map round the print – a rectangle, not printed exactly. */
        MAP,
        /** It stays white – an outline, a hull, or a rectangle printed exactly. */
        PAPER
    }

    /**
     * What happens to the paper left over, said so that a result of "Print 420 × 297 mm" under a
     * width of 210 is not a riddle. Less than a millimeter on each side is not worth saying.
     */
    static @NotNull Spare spare(final @NotNull ProjectModel model) {
        final ProjectModel.Built built = model.build();
        final SheetLayout layout = model.layout();
        if (!built.ok() || layout == null) {
            return Spare.NONE;
        }
        final PrintRect asked = built.job().print();
        final Medium medium = built.job().medium();
        final double edge = built.job().marks().edgeMm();
        // The sheets' rectangle is the print grown by the white edge, once each side along the
        // axes paper is cut in, and by whatever whole sheets leave over
        final double acrossLeft = layout.print().widthMm() - asked.widthMm() - (medium.acrossMm() > 0 ? 2 * edge : 0);
        final double downLeft = layout.print().heightMm() - asked.heightMm() - (medium.downMm() > 0 ? 2 * edge : 0);
        if (acrossLeft < 1 && downLeft < 1) {
            return Spare.NONE;
        }
        return built.job().clipsToArea() ? Spare.PAPER : Spare.MAP;
    }

    /** A small ASCII plan of the sheets, which is what the layout file beside the export holds too. */
    private static @NotNull String gridOf(final @NotNull SheetLayout layout) {
        if (layout.count() > 60) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (int r = 0; r < layout.rows(); r++) {
            for (int c = 0; c < layout.columns(); c++) {
                final int row = r, col = c;
                layout.sheets().stream()
                        .filter(s -> s.row() == row && s.column() == col)
                        .findFirst()
                        .ifPresent(s -> sb.append(String.format(Locale.ROOT, "[%2d]", s.index())));
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    @Override
    public @NotNull String summary() {
        final SheetLayout layout = model.layout();
        if (layout == null) {
            return "";
        }
        return I18n.format("medium.summary",
                model.mediumKindProperty().get() == ProjectModel.MediumKind.ROLL
                        ? rollText(net.trdlo.wtf.studio.Units.current(), model.rollWidthMmProperty().get())
                                + " " + net.trdlo.wtf.studio.Units.current().symbol()
                        : model.paperProperty().get().name(),
                layout.count());
    }

    static @NotNull List<Medium.Paper> papers() {
        return Medium.PAPER_SIZES;
    }

    /** Whether a paper size is whole millimeters – A4 is, Letter's 215.9 is not. */
    private static boolean wholeMm(final double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6;
    }

    /** A roll's width as the list shows it: whole millimeters, or inches to a tenth. */
    private static @NotNull String rollText(final net.trdlo.wtf.studio.@NotNull Units u, final double mm) {
        return RollWidthText.text(u, mm);
    }

    /**
     * The printer's white edge, beside the overlap it is checked against.
     * <p>
     * The project's, because it decides the layout: the outermost sheets keep it free round the
     * print, so an outline never runs to where the printer puts no ink. A new project starts with
     * the edge last used on this machine, as it starts on the paper last used.
     */
    private @NotNull TextField edgeField() {
        return lengthField(model.edgeMmProperty(), 1);
    }

    /**
     * What this overlap does with the printer's white edge, said beside the number.
     * <p>
     * Both sheets' white edges have to disappear at a seam – the covering one's is cut off, the
     * covered one's is hidden – and what is left between them is the strip the sheet numbers go in.
     * Too little overlap and a white line runs down the wall; a little more and the numbers have no
     * room. Both are easy to get wrong without the printer's edge in front of you, so here it is.
     */
    private @NotNull Label seamHint() {
        final Label label = new Label();
        label.getStyleClass().add("step-hint");
        label.setWrapText(true);
        label.setMaxWidth(290);
        live(model.revisionProperty(), r -> {
            final ProjectModel.Printing p = model.printing();
            final double o = model.overlapMmProperty().get();
            final double e = model.edgeMmProperty().get();
            final double strip = o - 2 * e - 2 * net.trdlo.wtf.compose.SeamMarks.GAP_MM;
            final boolean text = p.marks();
            final net.trdlo.wtf.studio.Units u = net.trdlo.wtf.studio.Units.current();
            final String edge = u.length(e, 1);
            if (o < net.trdlo.wtf.compose.SeamMarks.overlapToCover(e)) {
                label.setText(I18n.format("medium.seam.white", edge,
                        u.length(net.trdlo.wtf.compose.SeamMarks.overlapToCover(e), 0)));
            } else if (text && strip < net.trdlo.wtf.compose.SeamMarks.TEXT_STRIP_MM) {
                label.setText(I18n.format("medium.seam.tight", edge,
                        u.length(Math.ceil(net.trdlo.wtf.compose.SeamMarks.overlapForText(e)), 0)));
            } else {
                label.setText(I18n.format("medium.seam.ok", edge, u.length(strip, 1)));
            }
        });
        return label;
    }
}
