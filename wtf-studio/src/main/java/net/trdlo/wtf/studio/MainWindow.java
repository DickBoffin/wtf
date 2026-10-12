package net.trdlo.wtf.studio;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import net.trdlo.wtf.compose.SheetLayout;
import net.trdlo.wtf.studio.map.MapTreatments;
import net.trdlo.wtf.studio.map.ProjectOverlay;
import net.trdlo.wtf.studio.map.TileCanvas;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.studio.steps.*;
import net.trdlo.wtf.tiles.*;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The main window: the rail of the Start page and the five steps, a toolbar, the map and a status
 * bar.
 * <p>
 * No MenuBar – everything fits, as the brief hoped. The rail down the left is a <b>rail, not a
 * wizard</b>: every step is reachable at any time, because the answer to "how big is the print"
 * changes which zoom is readable and being sent back through three modal pages to find that out
 * is how a person stops using an application.
 * <p>
 * The window is the shell: the rail, the panels, the toolbar and the status bar, and the dialogs.
 * Where the map comes from is {@link MapSource}'s, downloading what the view lacks is
 * {@link ViewDownload}'s, the project as a file is {@link ProjectFiles}'s, the copy of its unsaved
 * work is {@link RecoveryWatch}'s, and what the unattended capture runs seed is {@link CaptureSeeds}'.
 */
public final class MainWindow {

    private final Stage stage;
    private final Prefs prefs;

    private final ProjectModel model = new ProjectModel();
    {
        // The outline itself, grown by 50 km, is too slow for the FX thread on a detailed border
        model.deriveInBackground(javafx.application.Platform::runLater);
    }
    private final TileCanvas map = new TileCanvas();
    private final StepRail rail = new StepRail();
    private final Map<Step, StepPanel> panels = new EnumMap<>(Step.class);
    private final ScrollPane panelHost = new ScrollPane();

    private final Label statusLeft = new Label();
    private final Label statusMiddle = new Label();
    private final Label statusRight = new Label();

    /** Between facts on one line. A run of spaces is not a separator, it is a gap. */
    private static final String SEPARATOR = "  ·  ";

    /** Where the map comes from; see {@link MapSource}. */
    private final MapSource source;
    /** Downloading what the view is missing; see {@link ViewDownload}. */
    private final ViewDownload download;
    /** The project as a file; see {@link ProjectFiles}. */
    private final ProjectFiles files;
    /** What the unattended capture runs seed; see {@link CaptureSeeds}. */
    private final CaptureSeeds seeds;
    /** The copy of this window's unsaved work; see {@link RecoveryWatch}. None in a capture run. */
    private @Nullable RecoveryWatch recovery;

    public MainWindow(final @NotNull Stage stage, final @NotNull Prefs prefs,
                      final @NotNull ProviderCatalog catalog, final @NotNull TileCache cache) {
        this.stage = stage;
        this.prefs = prefs;
        this.source = new MapSource(prefs, catalog, cache, model, map, this::viewChanged);
        this.download = new ViewDownload(stage, prefs, model, map, source, this::openProviders);
        this.files = new ProjectFiles(stage, prefs, model, map, rail, source);
        this.seeds = new CaptureSeeds(model, map, rail, panels);
        // Undo puts providers back as the catalog defines them now, and not one it no longer shows
        model.setProviderLookup(source::lookup);
    }

    public @NotNull Scene build() {
        model.setRememberedOutputDir(prefs.getPath(Prefs.LAST_EXPORT_DIR));
        applyPrinting();
        files.restorePaper();
        final StepContext ctx = new StepContext(stage, map, prefs, source::catalog, source::cache,
                () -> files.saveProject(false), () -> files.saveProject(true), this::openProviders);
        panels.put(Step.START, new StartPanel(model, files::openExample, () -> rail.select(Step.SUBJECT)));
        panels.put(Step.SUBJECT, new SubjectPanel(model, ctx));
        panels.put(Step.AREA, new AreaPanel(model, ctx));
        panels.put(Step.MEDIUM, new MediumPanel(model));
        panels.put(Step.DETAIL, new DetailPanel(model, ctx));
        panels.put(Step.EXPORT, new ExportPanel(model, ctx));

        map.addOverlay(new ProjectOverlay(model, rail::current));

        panelHost.setFitToWidth(true);
        panelHost.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        panelHost.getStyleClass().add("panel-host");
        panelHost.setMinWidth(330);
        panelHost.setPrefWidth(330);

        rail.fill();
        rail.currentProperty().addListener((o, was, is) -> showStep(was, is));
        // The window holds a new project until one is opened, and a new project is the Start page's
        showStep(null, Step.START);

        final BorderPane root = new BorderPane();
        root.setTop(buildToolbar());
        root.setLeft(new HBox(rail, new Separator(javafx.geometry.Orientation.VERTICAL), panelHost));
        root.setCenter(map);
        root.setBottom(buildStatusBar());

        download.watchView();
        MapTreatments.watch(model, shown -> {
            map.setTileEffect(shown.base());
            map.setRegion(shown.region(), shown.regionEffect(), shown.drawInside(), shown.drawOutside());
        });

        download.armAutoDownload();
        source.restore();
        wireStatus();
        // One listener for the whole rail; the panels each keep their own live labels.
        model.revisionProperty().addListener((o, a, b) -> {
            rail.refresh(step -> panels.get(step).summary());
            map.requestPaint();
            files.updateTitle();
        });
        model.dirtyProperty().addListener((o, a, b) -> files.updateTitle());
        // And on the file: opening a project settles whether it is saved before the file is named,
        // so the title would otherwise go on naming the one before
        model.fileProperty().addListener((o, a, b) -> files.updateTitle());
        rail.refresh(step -> panels.get(step).summary());
        files.updateTitle();

        final Scene scene = new Scene(root, 1480, 900);
        installAccelerators(scene);
        endStepsAtInput(scene);
        Platform.runLater(map::requestFocus);
        return scene;
    }

    /**
     * Where one step of undo ends and the next begins: at the next press of a mouse button or a key.
     * <p>
     * So a step is whatever a person did in between – a handle dragged and let go, a slider dragged,
     * a spinner held, a country picked – together with everything the model and the panels did in
     * answer to it. Filters, so the step closes before the press does anything, and on the scene,
     * which every press in the window passes through.
     * <p>
     * A key is a step of its own, so typing is a run of them, and a run merges into one step until
     * it ends: when the focus moves, and when a mouse button is let go. The release is handled
     * afterwards, not in the filter, so that what the release itself does – a button's action, a
     * slider settling on its notch, a toolbar Undo taking the focus out of a field that then tidies
     * its number – still belongs to the run the press began. Late, it can only merge more; it never
     * splits a step.
     */
    private void endStepsAtInput(final @NotNull Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> model.history().closeStep());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> model.history().closeStep());
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> Platform.runLater(model.history()::endRun));
        // The field that loses the focus has already tidied its number by the time this runs, so
        // the tidying joins the typing it tidies before the run ends
        scene.focusOwnerProperty().addListener((o, was, is) -> {
            model.history().closeStep();
            model.history().endRun();
        });
    }

    /**
     * Back one step, from the button or the keyboard.
     * <p>
     * The map takes the focus first. A field that still has it goes on showing what was typed into
     * it, because it does not rewrite itself under the cursor – the value undone would stay on
     * screen, ready to be written back by the next key. Losing the focus makes it show the model.
     */
    private void undo() {
        map.requestFocus();
        model.history().undo();
    }

    /** Forward one step; the focus goes to the map for the same reason as in {@link #undo}. */
    private void redo() {
        map.requestFocus();
        model.history().redo();
    }

    private void showStep(final @Nullable Step was, final @NotNull Step is) {
        if (was != null) {
            panels.get(was).hidden();
        }
        final StepPanel panel = panels.get(is);
        panelHost.setContent(panel.node());
        panel.shown();
        map.requestPaint();
    }

    // -------------------------------------------------------------------------------------------
    // Toolbar
    // -------------------------------------------------------------------------------------------

    private @NotNull ToolBar buildToolbar() {
        source.installPickers();

        final Button newProject = iconButton(FontAwesomeSolid.FILE, I18n.get("project.new"),
                files::newProject);
        final Button open = iconButton(FontAwesomeSolid.FOLDER_OPEN, I18n.get("project.open"),
                files::openProject);
        final Button save = iconButton(FontAwesomeSolid.SAVE, I18n.get("project.save"),
                () -> files.saveProject(false));
        final Button saveAs = new Button(null, saveAsIcon());
        saveAs.setTooltip(new Tooltip(I18n.get("project.saveAs")));
        saveAs.setOnAction(e -> files.saveProject(true));
        saveAs.setFocusTraversable(false);
        final Button undo = iconButton(FontAwesomeSolid.UNDO, I18n.get("toolbar.undo"), this::undo);
        final Button redo = iconButton(FontAwesomeSolid.REDO, I18n.get("toolbar.redo"), this::redo);
        // With nothing to take back they are visibly not available, as Frame is below, rather than
        // buttons that do nothing
        model.history().canUndoProperty().addListener((o, a, can) -> undo.setDisable(!can));
        model.history().canRedoProperty().addListener((o, a, can) -> redo.setDisable(!can));
        undo.setDisable(!model.history().canUndoProperty().get());
        redo.setDisable(!model.history().canRedoProperty().get());
        final Button frame = iconButton(FontAwesomeSolid.EXPAND, I18n.get("toolbar.frame"), () -> {
            new ProjectOverlay(model, rail::current).frame(map.camera());
            map.requestPaint();
        });
        // Nothing drawn, nothing to show the whole of. An icon-only button that silently does
        // nothing is worse than one that is visibly not available yet.
        final Runnable frameable = () -> frame.setDisable(
                model.areaProperty().get() == null && model.subjectProperty().get() == null);
        model.areaProperty().addListener((o, a, b) -> frameable.run());
        model.subjectProperty().addListener((o, a, b) -> frameable.run());
        frameable.run();

        final Button settings = iconButton(FontAwesomeSolid.COG, I18n.get("toolbar.settings"),
                this::openSettings);
        final Button providers = iconButton(FontAwesomeSolid.KEY, I18n.get("toolbar.providers"),
                () -> openProviders(null));
        final Button about = iconButton(FontAwesomeSolid.INFO_CIRCLE, I18n.get("toolbar.about"),
                this::openAbout);

        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        return new ToolBar(
                newProject, open, save, saveAs,
                new Separator(),
                undo, redo,
                new Separator(),
                new Label(I18n.get("toolbar.provider")), source.providerBox(),
                new Label(I18n.get("toolbar.theme")), source.themeBox(),
                source.langLabel(), source.langBox(),
                new Separator(), frame,
                spacer, providers, settings, about);
    }

    private void installAccelerators(final @NotNull Scene scene) {
        final Map<KeyCombination, Runnable> keys = scene.getAccelerators();
        keys.put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), files::newProject);
        keys.put(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN), files::openProject);
        keys.put(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), () -> files.saveProject(false));
        keys.put(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN), () -> files.saveProject(true));
        // A text field with the focus keeps these for its own text: a scene's accelerators only get
        // the keys nothing else has taken. Not Ctrl+Shift+Z as well – on Windows a field takes
        // Ctrl+Y as its redo and not that, so the two would redo different things from one field.
        keys.put(new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN), this::undo);
        keys.put(new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN), this::redo);
        // 0..5 jump to the Start page or a step, which is the whole argument for a rail over a wizard
        for (final Step step : Step.values()) {
            keys.put(new KeyCodeCombination(KeyCode.valueOf("DIGIT" + step.number()),
                    KeyCombination.ALT_DOWN), () -> rail.select(step));
        }
    }

    /**
     * The diskette with a pen on its corner: the same save, under a name still to be written.
     * Font Awesome has no glyph of its own for it, and a second plain diskette would be a guess.
     */
    private static @NotNull Node saveAsIcon() {
        final FontIcon pen = new FontIcon(FontAwesomeSolid.PEN);
        pen.getStyleClass().add("save-as-pen");
        final StackPane both = new StackPane(new FontIcon(FontAwesomeSolid.SAVE), pen);
        StackPane.setAlignment(pen, Pos.BOTTOM_RIGHT);
        return both;
    }

    private @NotNull Button iconButton(final @NotNull FontAwesomeSolid icon, final @NotNull String tip,
                                       final @NotNull Runnable action) {
        final Button b = new Button(null, new FontIcon(icon));
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        return b;
    }

    // -------------------------------------------------------------------------------------------
    // Status bar
    // -------------------------------------------------------------------------------------------

    private @NotNull HBox buildStatusBar() {
        download.installProgress();
        statusMiddle.getStyleClass().add("status-print");
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final HBox bar = new HBox(10, statusLeft, new Separator(javafx.geometry.Orientation.VERTICAL),
                statusMiddle, spacer, download.progressLabel(), download.progressBar(), statusRight);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 10, 4, 10));
        return bar;
    }

    private void wireStatus() {
        final Runnable update = () -> {
            final double lon = map.cursorLonProperty().get();
            final double lat = map.cursorLatProperty().get();
            final StringBuilder sb = new StringBuilder();
            // Both, not just the longitude: the two are set one after the other, so the listener
            // on the first of them runs while the second is still the previous value – which on
            // the very first mouse move is the NaN it started as, and "14.4213 NaN" reached the
            // status bar for a frame.
            if (!Double.isNaN(lon) && !Double.isNaN(lat)) {
                sb.append(I18n.format("status.position", I18n.number(lon, 4), I18n.number(lat, 4)));
                sb.append(SEPARATOR);
            }
            sb.append(I18n.format("status.zoom", map.camera().tileZoom()));
            sb.append(SEPARATOR).append(resolution(map.camera().groundResolution()));
            statusLeft.setText(sb.toString());
        };
        map.cursorLonProperty().addListener((o, a, b) -> update.run());
        map.cursorLatProperty().addListener((o, a, b) -> update.run());
        map.camera().pixelsPerUnitProperty().addListener((o, a, b) -> update.run());
        // And on the vertical origin, because the ground resolution is taken at the middle of the
        // view: panning from Prague to Svalbard halves it without touching the zoom, and the label
        // would otherwise go on saying what it said before the pan.
        map.camera().originYProperty().addListener((o, a, b) -> update.run());
        // One property carrying both counts, so the pair is always consistent and the label is
        // still updated when the number of missing tiles happens to stay put at zero.
        map.statsProperty().addListener((o, a, s) ->
                statusRight.setText(s.visible() == 0 ? "" : I18n.format("status.cache", s.cachedPercent())));
        statusRight.setText(I18n.format("status.cache", map.statsProperty().get().cachedPercent()));
        // The print, wherever you are in the steps: the one number a person keeps checking
        model.revisionProperty().addListener((o, a, b) -> {
            final SheetLayout layout = model.layout();
            statusMiddle.setText(layout == null ? I18n.get("status.noPrint")
                    : I18n.format("status.print",
                    Units.current().size(layout.print().widthMm(), layout.print().heightMm(), 0),
                    layout.count(), I18n.integer(Math.round(layout.print().mapScale()))));
        });
        statusMiddle.setText(I18n.get("status.noPrint"));
        update.run();
    }

    /**
     * A pixel is anything from a few centimeters to a few thousand kilometers of ground, so the
     * unit follows the number rather than the other way round. The switch is at a kilometer,
     * which is where the meters stop being a quantity anybody pictures.
     */
    static @NotNull String resolution(final double metersPerPixel) {
        if (Units.current() == Units.INCHES) {
            // Feet, and miles from a mile on, as a place that measures paper in inches measures ground
            final double feet = metersPerPixel / 0.3048;
            return feet >= 5_280
                    ? I18n.format("status.resolutionMi", I18n.number(feet / 5_280, 2))
                    : I18n.format("status.resolutionFt", I18n.number(feet, 2));
        }
        return metersPerPixel >= 1_000
                ? I18n.format("status.resolutionKm", I18n.number(metersPerPixel / 1_000, 2))
                : I18n.format("status.resolution", I18n.number(metersPerPixel, 2));
    }

    // -------------------------------------------------------------------------------------------

    private void openSettings() {
        new SettingsDialog(stage, prefs, source.cache()).showAndWait();
        source.rebuild();
        applyPrinting();
    }

    /** The seam marks, from the settings into the model that builds the job. */
    private void applyPrinting() {
        model.setPrinting(PrintingPrefs.load(prefs));
    }

    private void openProviders(final @Nullable Provider select) {
        final ProvidersDialog dialog =
                new ProvidersDialog(stage, prefs, source.catalog(), source.cache(),
                        select == null ? model.providerProperty().get() : select);
        dialog.showAndWait();
        if (dialog.hasChanges()) {
            source.reloadProviders();
        }
        if (dialog.forgotEmpty()) {
            if (map.imageCache() != null) {
                map.imageCache().invalidate();      // what was drawn as sea is "not cached" again
            }
            exportPanel().invalidateCount();        // and the export's count of it is a lie too
        }
        download.consider();
        map.requestPaint();
    }

    /** The map's source was looked at again, so what the view needs may have changed. */
    private void viewChanged() {
        download.consider();
    }

    private void openAbout() {
        AboutDialog.show(stage, source.catalog(), prefs);
    }

    public @NotNull ProjectModel model() {
        return model;
    }

    public @NotNull StepRail rail() {
        return rail;
    }

    /** What the unattended capture runs seed; for {@link WtfApp} and nothing else. */
    @NotNull CaptureSeeds seeds() {
        return seeds;
    }

    /** Step 5, which is where a download or an export runs; the close request asks it what is. */
    @NotNull ExportPanel exportPanel() {
        return (ExportPanel) panels.get(Step.EXPORT);
    }

    /** Stops the download of the view and waits for the tile in hand; see {@link Closing}. */
    void stopViewDownload() {
        download.stopAndWait();
    }

    /** Whether the project may be thrown away: asks when it is not saved; see {@link ProjectFiles}. */
    boolean confirmDiscard() {
        return files.confirmDiscard();
    }

    /** {@link #confirmDiscard()} for a hand-over: the question names the file that would open. */
    boolean confirmDiscard(final @NotNull Path opening) {
        return files.confirmDiscard(opening);
    }

    /**
     * Opens a project without asking which: the one chosen in the dialog, or the one the
     * system handed WTF at start, when a {@code .wtf} file was opened from a file manager.
     *
     * @return whether it opened
     */
    boolean openProjectFile(final @NotNull Path file) {
        return files.openProjectFile(file);
    }

    /** Opens an example as its card on the Start page does: for the capture runs that photograph one. */
    void openExample(final net.trdlo.wtf.studio.project.@NotNull Example example) {
        files.openExample(example);
    }

    /**
     * Offers the recovery copies the start found, then keeps a copy of this window's own unsaved work
     * – in that order, so that a copy opened here is the window's own from the first.
     */
    void keepRecoveryCopies(final @NotNull Recovery copies, final @NotNull List<Recovery.Orphan> found) {
        files.offerRecovery(found);
        recovery = RecoveryWatch.forWindow(model, copies);
        recovery.start();
    }

    /** The close went ahead, its question answered: the copy goes with what it held. */
    void discardRecoveryCopy() {
        if (recovery != null) {
            recovery.discard();
        }
    }

    /** The window is going: see {@link RecoveryWatch#stop}. */
    void stopRecoveryCopies() {
        if (recovery != null) {
            recovery.stop();
            recovery = null;
        }
    }

    public void saveState() {
        // Windows.decorate keeps the bounds when the window is hidden, but a run that ends in
        // Platform.exit – the batch runs, a failed start – hides nothing before stop()
        prefs.saveWindow(stage, "main");
        source.rememberView();
        files.rememberForNextStart();
        prefs.flush();
        map.dispose();
    }

    /** Color used by the CSS-less parts of the canvas; kept here so the two agree. */
    public static final Color CANVAS_BACKGROUND = Color.web("#1b1e23");
}
