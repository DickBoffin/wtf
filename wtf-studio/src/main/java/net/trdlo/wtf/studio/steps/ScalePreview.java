package net.trdlo.wtf.studio.steps;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.compose.PrintRect;
import net.trdlo.wtf.studio.AppInfo;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Prefs;
import net.trdlo.wtf.studio.map.Camera;
import net.trdlo.wtf.studio.map.MapTreatments;
import net.trdlo.wtf.studio.map.PrintedLine;
import net.trdlo.wtf.studio.map.PrintedPaper;
import net.trdlo.wtf.studio.map.TileCanvas;
import net.trdlo.wtf.studio.map.TileImageCache;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileDownloader;
import net.trdlo.wtf.tiles.Provider;


/**
 * The print at 1:1, with a ruler.
 * <p>
 * A DPI is an abstraction until you see a place name at the size it will actually be. This window
 * shows a patch of the print at life size on the monitor, so an overshot zoom is caught in the
 * one second it takes to look at it rather than after six strips have been printed. It shows the
 * print as the steps before it have set it: the area's edge with white paper past it, the fade
 * across the growth, both regions' treatments and the line along the subject.
 * <p>
 * <b>The monitor's reported DPI is a guess</b> – manufacturers lie, and a scaled desktop lies
 * again – so it is seeded from the screen and then calibrated against a bank card, which is
 * 85.60 x 53.98 mm by ISO/IEC 7810 ID-1 and is therefore the one precisely-known rectangle
 * everybody already owns. The calibration is remembered per machine.
 */
public final class ScalePreview {

    /** ISO/IEC 7810 ID-1, the size of every payment card. */
    private static final double CARD_WIDTH_MM = 85.60;
    private static final double CARD_HEIGHT_MM = 53.98;

    private static final Color RULER_BG = Color.web("#11141a", 0.82);
    private static final Color RULER_LINE = Color.web("#f8f9fa", 0.85);
    private static final Color RULER_TEXT = Color.web("#f8f9fa");
    private static final Color CARD_LINE = Color.web("#ffd43b");

    private final StepContext ctx;
    private final ProjectModel model;

    private final Stage stage = new Stage();
    private final TileCanvas canvas = new TileCanvas();
    /** The paper past the area's edge and under its fade; see {@link PrintedPaper}. */
    private final PrintedPaper paper = new PrintedPaper(canvas::requestPaint);
    private final Label caption = new Label();
    private final Slider dpiSlider = new Slider(60, 400, 96);
    /**
     * Calibrating or looking.
     * <p>
     * There is no zooming in this window – the print is at life size, and the tiles are the zoom
     * the print will use. The only thing the scale may change is the screen's own DPI, which is
     * calibration, so the slider, the wheel and the card all belong to this one switch and none of
     * them is there when it is off.
     */
    private final ToggleButton calibrating = new ToggleButton();

    /** Over whatever cache is the window's now; see {@link FollowingDownloader}. */
    private final FollowingDownloader downloader;

    private double screenDpi;
    private boolean built;

    public ScalePreview(final @NotNull StepContext ctx, final @NotNull ProjectModel model) {
        this.ctx = ctx;
        this.model = model;
        this.screenDpi = ctx.prefs().getDouble(Prefs.SCREEN_DPI, guessScreenDpi());
        this.downloader = new FollowingDownloader(cache -> new TileDownloader(cache,
                ctx.prefs().get(Prefs.DOWNLOAD_THREADS),
                AppInfo.get().userAgent()));
    }

    /** What the platform thinks, which is the right starting point and the wrong final answer. */
    private static double guessScreenDpi() {
        final double reported = Screen.getPrimary().getDpi();
        return reported > 20 && reported < 800 ? reported : 96;
    }

    public void show() {
        if (!built) {
            build();
            built = true;
        }
        apply();
        stage.show();
        stage.toFront();
    }

    public void hide() {
        stage.hide();
    }

    /**
     * One downloader for as long as the cache is the same one.
     * <p>
     * Not closed when the window hides: a decode already in flight would fail with it, and what
     * it holds is an idle {@code HttpClient}. The window is opened and closed all afternoon while
     * a zoom is chosen, and building a client each time to throw it away is the expensive half of
     * a request. It is replaced only when the cache is – Settings moved the folder – so the
     * preview never fetches into a folder the rest of the window has left.
     */
    private @NotNull TileDownloader downloader(final @NotNull TileCache cache) {
        return downloader.over(cache);
    }

    /** For the unattended capture, which is the only way to look at this without opening it. */
    public @NotNull javafx.scene.Scene scene() {
        return stage.getScene();
    }

    /** Also for the capture: how many of this window's tiles are still being read or fetched. */
    public int pendingDecodes() {
        final net.trdlo.wtf.studio.map.TileImageCache images = canvas.imageCache();
        return images == null ? 0 : images.pending();
    }

    /** Also for the capture: the state worth a second picture is the one being calibrated. */
    public void setCalibrating(final boolean on) {
        calibrating.setSelected(on);
    }

    // -------------------------------------------------------------------------------------------

    private void build() {
        canvas.setZoomLocked(true);      // the whole point is that it stays 1:1
        // The paper first: the line along the subject is drawn over it, as the print draws it, and
        // the ruler and the card over everything
        canvas.addOverlay(paper);
        // The line the print draws along the subject, under the ruler and the card
        canvas.addOverlay(new PrintedLine(model, () -> screenDpi / 25.4));
        canvas.addOverlay(this::drawRuler);
        // The treatments, as the map shows them: both regions, each its own way, and one that is
        // not printed white. This window exists to answer "what will that look like on paper",
        // and numbers in a panel nobody sees the result of until the PDF is open answer nothing.
        // The card does the work – a ColorAdjust on each tile layer – so it costs nothing per
        // frame. It is a preview, not the exporter, which applies the same numbers to the palette
        // of each tile, exactly.
        MapTreatments.watch(model, shown -> {
            canvas.setTileEffect(shown.base());
            canvas.setRegion(shown.region(), shown.regionEffect(), shown.drawInside(), shown.drawOutside());
        });
        canvas.setPrefSize(900, 620);

        dpiSlider.setValue(screenDpi);
        dpiSlider.setMajorTickUnit(50);
        dpiSlider.setShowTickMarks(true);
        HBox.setHgrow(dpiSlider, Priority.ALWAYS);
        dpiSlider.valueProperty().addListener((o, a, b) -> {
            screenDpi = b.doubleValue();
            ctx.prefs().putDouble(Prefs.SCREEN_DPI, screenDpi);
            apply();
        });

        calibrating.setText(I18n.get("preview.calibrate"));
        calibrating.setTooltip(new Tooltip(I18n.format("preview.calibrate.tip",
                cardSize())));
        calibrating.selectedProperty().addListener((o, a, on) -> {
            dpiSlider.setVisible(on);
            dpiSlider.setManaged(on);
            canvas.requestPaint();
        });
        dpiSlider.setVisible(false);
        dpiSlider.setManaged(false);

        // The wheel is the fine adjustment, and only while calibrating: a tenth of a DPI a notch,
        // which is the difference between a card that is nearly right and one that is right.
        canvas.setOnScroll(e -> {
            if (!calibrating.isSelected()) {
                return;
            }
            dpiSlider.setValue(dpiSlider.getValue() + Math.signum(e.getDeltaY()) * 0.5);
            e.consume();
        });

        caption.getStyleClass().add("step-summary");
        caption.setWrapText(true);

        final Label dpiLabel = new Label();
        dpiLabel.setMinWidth(112);
        dpiSlider.valueProperty().addListener((o, a, b) ->
                dpiLabel.setText(I18n.format("preview.screenDpi", I18n.number(b.doubleValue(), 0))));
        dpiLabel.setText(I18n.format("preview.screenDpi", I18n.number(screenDpi, 0)));

        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // No Close button: the window has the one its frame came with, and a second one is a
        // control that has to be explained for no gain.
        dpiLabel.visibleProperty().bind(calibrating.selectedProperty());
        dpiLabel.managedProperty().bind(calibrating.selectedProperty());
        final HBox bar = new HBox(10, calibrating, dpiLabel, dpiSlider, spacer);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));

        final BorderPane root = new BorderPane();
        root.setTop(bar);
        root.setCenter(canvas);
        final HBox bottom = new HBox(caption);
        bottom.setPadding(new Insets(6, 12, 8, 12));
        root.setBottom(bottom);

        stage.setScene(new Scene(root, 940, 720));
        stage.setTitle(I18n.get("preview.title"));
        stage.initOwner(ctx.stage());
        stage.initModality(Modality.NONE);   // you want to change the zoom while looking at it
        // Its own taskbar button, so its own icons; and its bounds kept when it is hidden
        net.trdlo.wtf.studio.Windows.decorate(stage, ctx.prefs(), "preview");
        stage.setOnHidden(e -> {
            // Its decoders and tiles go with the window; shown again, it starts from a new source
            canvas.dispose();
            lastSource = null;
        });

        // The caption says how much of the print is in view, which is a question of the width:
        // asked before the window was laid out, it said nothing was. The centering needs no
        // listener of its own – a middle asked for before the canvas has both its sides is kept by
        // the camera until it has them.
        canvas.widthProperty().addListener((o, a, b) -> {
            if (stage.isShowing()) {
                apply();
            }
        });

        // Redraw when the zoom or the print changes, so the window is never showing a stale scale
        model.revisionProperty().addListener((o, a, b) -> {
            if (stage.isShowing()) {
                apply();
            }
        });
    }

    /**
     * Sets the camera so one millimeter of print is one millimeter of glass.
     * <p>
     * {@code mmPerUnit} is millimeters of paper per unit of the Mercator unit square, so
     * {@code pixelsPerUnit = mmPerUnit * screenPixelsPerMm} puts the print on the screen at life
     * size. No tile arithmetic is involved, which is why this holds at any zoom.
     */
    /** What the canvas's tiles were last asked for: cache, provider, map, key and language. */
    private java.util.@Nullable List<Object> lastSource;

    private void apply() {
        paper.update(model.build().job());
        final PrintRect print = model.printRect();
        final Provider p = model.providerProperty().get();
        final Provider.Theme t = model.themeProperty().get();
        if (print == null || p == null || t == null) {
            caption.setText(I18n.get("preview.nothing"));
            return;
        }
        final double pxPerMm = screenDpi / 25.4;
        // Downloading, unlike the map behind it. This window is usually the first thing to look
        // at the subject at the zoom the print will use, so its tiles are usually the ones nobody
        // has fetched yet – and it shows a patch of paper at screen resolution, which is a few
        // dozen tiles, not a wall's worth. A preview of placeholders answers nothing.
        //
        // Only when something that decides the tiles has changed. A new source is a new, empty
        // image cache, and this ran on every step of the calibration slider and on every change
        // to the project – so the tiles went blank and loaded again each time, the same tiles.
        final String apiKey = ctx.prefs().apiKey(p.id());
        final TileCache cache = ctx.tileCache();     // asked once, so the source and its downloader agree
        final int scale = model.printScale();
        final java.util.List<Object> source = java.util.Arrays.asList(cache, p, t.id(), apiKey,
                model.mapLangProperty().get(), scale);
        if (!source.equals(lastSource)) {
            canvas.setSource(p, t.id(), model.mapLangProperty().get(), scale,
                    CachedTileSource.downloading(cache, downloader(cache), p, apiKey),
                    TileImageCache.PREVIEW_BUDGET);
            lastSource = source;
        }
        canvas.camera().pixelsPerUnitProperty().set(print.mmPerUnit() * pxPerMm);
        canvas.camera().centerOn((print.x0() + print.x1()) / 2, (print.y0() + print.y1()) / 2);
        // The zoom the print will use, not the one this window's scale rounds to. Calibrating the
        // screen changes how big a tile pixel is here; it must not change which tiles they are.
        canvas.setFixedTileZoom(model.zoomProperty().get());
        canvas.requestPaint();

        final int zoom = model.zoomProperty().get();
        caption.setText(I18n.format("preview.caption",
                I18n.number(print.dpi(zoom, scale), 0), DetailPanel.zoomText(zoom, scale),
                net.trdlo.wtf.studio.Units.current().length(print.labelHeightMm(zoom), 2),
                net.trdlo.wtf.studio.Units.current().length(canvas.getWidth() / pxPerMm, 0)));
    }


    // -------------------------------------------------------------------------------------------

    /** The card as its standard gives it: 85.60 × 53.98 mm, or 3.37 × 2.125 in. */
    private static @NotNull String cardSize() {
        final net.trdlo.wtf.studio.Units u = net.trdlo.wtf.studio.Units.current();
        return u == net.trdlo.wtf.studio.Units.INCHES
                ? I18n.numberUpTo(u.fromMm(CARD_WIDTH_MM), 3) + " × " + I18n.numberUpTo(u.fromMm(CARD_HEIGHT_MM), 3) + " in"
                : u.size(CARD_WIDTH_MM, CARD_HEIGHT_MM, 2);
    }

    /** Centimeters or inches along the top and down the left, plus the calibration card. */
    private void drawRuler(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                           final double width, final double height) {
        final double pxPerMm = screenDpi / 25.4;
        final double thickness = 22;

        g.setFill(RULER_BG);
        g.fillRect(0, 0, width, thickness);
        g.fillRect(0, 0, thickness, height);

        g.setFont(Font.font(9));
        g.setStroke(RULER_LINE);
        g.setFill(RULER_TEXT);
        g.setLineWidth(1);

        // Centimeters in millimeter ticks, or inches in eighths: a tick, and every how many ticks
        // the half mark and the numbered one come
        final boolean inches = net.trdlo.wtf.studio.Units.current() == net.trdlo.wtf.studio.Units.INCHES;
        final double tickMm = inches ? 25.4 / 8 : 1;
        final int major = inches ? 8 : 10, half = inches ? 4 : 5;
        for (int t = 0; t * tickMm * pxPerMm < width; t++) {
            final double x = Math.floor(thickness + t * tickMm * pxPerMm) + 0.5;
            if (x > width) {
                break;
            }
            final double len = t % major == 0 ? 10 : t % half == 0 ? 6 : 3;
            g.strokeLine(x, thickness - len, x, thickness);
            if (t % major == 0 && t > 0) {
                g.fillText(String.valueOf(t / major), x + 2, 9);
            }
        }
        for (int t = 0; t * tickMm * pxPerMm < height; t++) {
            final double y = Math.floor(thickness + t * tickMm * pxPerMm) + 0.5;
            if (y > height) {
                break;
            }
            final double len = t % major == 0 ? 10 : t % half == 0 ? 6 : 3;
            g.strokeLine(thickness - len, y, thickness, y);
            if (t % major == 0 && t > 0) {
                g.fillText(String.valueOf(t / major), 2, y - 2);
            }
        }
        g.setFill(RULER_TEXT);
        g.setFont(Font.font(9));
        g.fillText(inches ? "in" : "cm", 4, 10);

        if (calibrating.isSelected()) {
            drawCard(g, pxPerMm, width, height);
        }
    }

    /**
     * The calibration rectangle. Hold a real card against it and move the slider until they
     * match; from then on the preview is life size on this monitor.
     */
    private void drawCard(final @NotNull GraphicsContext g, final double pxPerMm,
                          final double width, final double height) {
        final double w = CARD_WIDTH_MM * pxPerMm;
        final double h = CARD_HEIGHT_MM * pxPerMm;
        // In the middle, not tucked into a corner: it is the thing to be doing while the
        // calibration is on, and a card somebody has to go looking for is a calibration nobody
        // finishes. It is also easier to hold a real card against.
        final double x = (width - w) / 2;
        final double y = (height - h) / 2;
        if (x < 30 || y < 30) {
            return;
        }
        g.setFill(RULER_BG);
        g.fillRect(x, y, w, h);
        g.setStroke(CARD_LINE);
        g.setLineWidth(1.5);
        g.setLineDashes(5, 4);
        g.strokeRect(x + 0.5, y + 0.5, w, h);
        g.setLineDashes((double[]) null);

        g.setFill(CARD_LINE);
        g.setFont(Font.font(11));
        g.fillText(I18n.get("preview.card.label"), x + 8, y + 18);
        g.setFont(Font.font(9));
        g.fillText(I18n.format("preview.card.size", cardSize()), x + 8, y + 32);
    }
}
