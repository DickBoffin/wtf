package net.trdlo.wtf.studio.map;

import javafx.animation.AnimationTimer;
import javafx.beans.property.*;
import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.tiles.*;

/**
 * The map: one {@link Canvas} painted by hand.
 * <p>
 * Not a scene graph of {@code ImageView}s – at zoom 15 a 4K window shows about 200 tiles, the
 * overlays are drawn shapes rather than nodes, and moving a thousand nodes per drag is a layout
 * storm. One canvas, one repaint, driven by an {@link AnimationTimer} that only actually paints
 * when something changed.
 * <p>
 * The canvas is the three canvases, the order a frame is painted in, and what the rest of the
 * window reads off the map. The tiles and how they are painted are {@link TileLayer}'s, the mouse
 * and the keys are {@link CanvasInput}'s, and the view it opens on is {@link OpeningView}'s.
 */
public final class TileCanvas extends Region {

    private final Canvas canvas = new Canvas();
    /**
     * The overlays live on their own canvas, above the tiles.
     * <p>
     * So that an effect can be put on the map without putting it on the things drawn over the
     * map. The 1:1 preview shows the treatment – the color, the lightness, the contrast – by
     * setting a filter on the tile layer, and a faded ruler would be a ruler nobody can read.
     */
    private final Canvas overlayCanvas = new Canvas();

    /**
     * The same tiles again, clipped to one region and filtered differently.
     * <p>
     * A print with a subject has two treatments, and "faded outside, full color inside" is a
     * thing somebody adjusts by eye – it has to be visible on the map, not only in the export.
     * One effect per node is all a scene graph offers, so the second region is a second copy of
     * the tiles with its own effect and a geometric clip. It is drawn only when the two
     * treatments actually differ, which is the unusual case.
     */
    private final Canvas regionCanvas = new Canvas();
    private final Camera camera = new Camera();
    /** The tiles, where they come from, and a frame of them painted; see {@link TileLayer}. */
    private final TileLayer layer = new TileLayer(camera, this::requestPaint);
    /** The view the map opens on; see {@link OpeningView}. */
    private final OpeningView opening = new OpeningView(camera);
    /** The mouse and the keys, and the tool that hears them first; see {@link CanvasInput}. */
    private final CanvasInput input = new CanvasInput(this, camera, layer);

    /**
     * What the next frame has to paint: the tiles and everything over them, or only the overlays.
     * A handle lighting up under the pointer is the second, which spares repainting two hundred
     * tiles.
     */
    private boolean tilesDirty = true;
    private boolean overlaysDirty;

    /** Paints when something changed; stopped by {@link #dispose}. */
    private final AnimationTimer frames = new AnimationTimer() {
        @Override
        public void handle(final long now) {
            if (tilesDirty) {
                tilesDirty = overlaysDirty = false;
                paint();
            } else if (overlaysDirty) {
                overlaysDirty = false;
                overlayCanvas.getGraphicsContext2D().clearRect(0, 0, overlayCanvas.getWidth(),
                        overlayCanvas.getHeight());
                drawOverlays(canvas.getWidth(), canvas.getHeight());
            }
        }
    };

    /**
     * Something drawn over the tiles: the shapes, the sheet rectangles, the tile grid, a ruler.
     * Drawn in the order they were added, after every tile, so nothing can be covered by a tile
     * that happens to decode late.
     */
    public interface Overlay {
        void draw(@NotNull GraphicsContext g, @NotNull Camera camera, double width, double height);
    }

    private final java.util.List<Overlay> overlays = new java.util.ArrayList<>();

    /** Set from the canvas so the toolbar and status bar can show it without recomputing. */
    private final ReadOnlyIntegerWrapper visibleTiles = new ReadOnlyIntegerWrapper();
    private final ReadOnlyIntegerWrapper missingTiles = new ReadOnlyIntegerWrapper();

    /**
     * Both counts as one value.
     * <p>
     * Two separate properties cannot be read together without a race: {@code paint()} sets one
     * before the other, and a listener on the first sees last frame's second. Listening to the
     * second instead fixes that but goes silent when its value never changes – which is exactly
     * what the cache percentage would do the moment the count stops moving off zero. One
     * property carrying both is the only version with neither failure.
     */
    public record Stats(int visible, int missing) {
        public int cachedPercent() {
            return visible == 0 ? 100 : (int) Math.round(100.0 * (visible - missing) / visible);
        }
    }

    private final ReadOnlyObjectWrapper<Stats> stats = new ReadOnlyObjectWrapper<>(new Stats(0, 0));

    public TileCanvas() {
        getChildren().addAll(canvas, regionCanvas, overlayCanvas);
        regionCanvas.setMouseTransparent(true);
        overlayCanvas.setMouseTransparent(true);
        setFocusTraversable(true);
        canvas.setFocusTraversable(false);

        widthProperty().addListener((o, a, b) -> resized());
        heightProperty().addListener((o, a, b) -> resized());

        opening.install();

        input.install();
        frames.start();
    }

    /**
     * Stops painting and lets the decoders go, for a canvas whose window has closed.
     * <p>
     * The frame timer holds the canvas for as long as it runs, and the image cache holds its
     * threads and its decoded tiles: a 1:1 preview opened and closed ten times left ten of each.
     * Given a source again, the canvas paints again.
     */
    public void dispose() {
        frames.stop();
        layer.dispose();
    }

    private void resized() {
        canvas.setWidth(Math.max(1, Math.round(getWidth())));
        canvas.setHeight(Math.max(1, Math.round(getHeight())));
        overlayCanvas.setWidth(canvas.getWidth());
        overlayCanvas.setHeight(canvas.getHeight());
        regionCanvas.setWidth(canvas.getWidth());
        regionCanvas.setHeight(canvas.getHeight());
        // Keeping the middle moves the origin, and a window being laid out is not somebody taking
        // the view – or a change of height alone would let go of the opening view before the
        // window had settled at its final size
        opening.layingOut(() -> camera.setViewSize(canvas.getWidth(), canvas.getHeight()));
        if (getWidth() > 1) {
            opening.apply();
        }
        requestPaint();
    }

    /**
     * Sets the opening view, which needs the view size, and keeps setting it until somebody
     * touches the map.
     * <p>
     * Fitting divides by the view size, and a view chosen while the window is still being built
     * divides by one pixel – so the world is fitted to nothing. Centering would too, and put the
     * place at the left edge instead of the middle: at street zoom half a screen out, and on a
     * world view half the planet. So the camera keeps a middle asked for until the view has both
     * its sides ({@link Camera#setViewSize}).
     * <p>
     * Running it once on the first size is not enough either: a window is laid out several times
     * on the way up, and the first size the canvas is given is not the one it settles at. So the
     * opening view is re-applied on every resize, and dropped the moment the camera is moved by
     * anything else – a pan, a zoom, a project being opened. From then on the view is the user's.
     */
    public void openOn(final @NotNull Runnable action) {
        opening.open(action);
        if (getWidth() > 1) {
            opening.apply();
        }
        requestPaint();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        regionCanvas.relocate(0, 0);
        overlayCanvas.relocate(0, 0);
    }

    /**
     * A filter over the tiles, and only over the tiles: the overlays stay as they are drawn.
     * <p>
     * Null removes it. Used by the 1:1 preview to show a region's treatment at once; the exporter
     * applies the same numbers to each tile's palette, which is exact rather than approximate,
     * and this is the cheap picture of what that will do.
     */
    public void setTileEffect(final javafx.scene.effect.@Nullable Effect effect) {
        canvas.setEffect(effect);
    }

    /**
     * A region of the map drawn a second time, with its own filter.
     * <p>
     * Null for either argument means one treatment for the whole map, which costs nothing extra;
     * with both, the tiles inside {@code shape} are painted again on top and the filter applies to
     * them alone. The shape is in degrees, because that is what the model holds and what stays
     * true while the map is panned.
     */
    public void setRegion(final @Nullable Geometry shape,
                          final javafx.scene.effect.@Nullable Effect effect,
                          final boolean drawInside, final boolean drawOutside) {
        layer.setRegion(shape, drawInside, drawOutside);
        regionCanvas.setEffect(effect);
        requestPaint();
    }

    public @NotNull Camera camera() {
        return camera;
    }

    /** Everything again next frame: the tiles, the region and the overlays. */
    public void requestPaint() {
        tilesDirty = true;
    }

    /** Only what is drawn over the tiles: a hover, a handle, a tool's half-drawn shape. */
    public void requestOverlayPaint() {
        overlaysDirty = true;
    }

    /**
     * Points the canvas at a provider and theme; the decoded images start again from nothing.
     *
     * @param lang the map language, which a theme not lettered in one ignores
     */
    public void setSource(final @NotNull Provider provider, final @NotNull String themeId,
                          final @Nullable String lang,
                          final @NotNull TileSource tiles, final long budgetBytes) {
        setSource(provider, themeId, lang, 1, tiles, budgetBytes);
    }

    /**
     * The same, drawing the theme's {@code @2x} tiles for a scale of 2 – which the 1:1 preview does,
     * showing the print as it will be; the map browses at the usual scale.
     */
    public void setSource(final @NotNull Provider provider, final @NotNull String themeId,
                          final @Nullable String lang, final int scale,
                          final @NotNull TileSource tiles, final long budgetBytes) {
        layer.setSource(provider, themeId, lang, scale, tiles, budgetBytes);
        frames.start();     // after a dispose; a running timer is left as it is
        requestPaint();
    }

    public @Nullable TileImageCache imageCache() {
        return layer.imageCache();
    }

    public void addOverlay(final @NotNull Overlay overlay) {
        overlays.add(overlay);
        requestPaint();
    }

    /**
     * Draws this zoom's tiles whatever the scale is, or the scale's own zoom when null.
     * <p>
     * The 1:1 preview is the case: it exists to show what the chosen zoom looks like at life
     * size, and the tiles it drew changed zoom as the screen was calibrated – so the one thing it
     * was for was the one thing it did not do. Tiles are still drawn at the scale the camera says,
     * so they come out bigger or smaller than 256 pixels, which is exactly the point.
     */
    public void setFixedTileZoom(final @Nullable Integer zoom) {
        if (layer.setFixedTileZoom(zoom)) {
            requestPaint();
        }
    }

    /**
     * Where on the canvas the fixed zoom is drawn when the view is too big to draw all of it at
     * that zoom, or null when it is drawn everywhere or there is no fixed zoom. Elsewhere the
     * tiles are the scale's own zoom.
     */
    public @Nullable Rectangle2D fixedZoomPatch() {
        final TileRange patch = layer.patch();
        final Integer zoom = layer.fixedTileZoom();
        if (patch.isEmpty() || zoom == null) {
            return null;
        }
        final int n = Mercator.tileCount(zoom);
        final double x0 = camera.toScreenX((double) patch.minX() / n);
        final double y0 = camera.toScreenY((double) patch.minY() / n);
        final double x1 = camera.toScreenX((double) (patch.maxX() + 1) / n);
        final double y1 = camera.toScreenY((double) (patch.maxY() + 1) / n);
        return new Rectangle2D(x0, y0, x1 - x0, y1 - y0);
    }

    public void setZoomLocked(final boolean locked) {
        input.setZoomLocked(locked);
    }

    /**
     * Installs a drawing tool, or null to go back to panning.
     * <p>
     * A tool takes the primary button; the middle button keeps panning whatever is installed, so a
     * half-drawn polygon never has to be abandoned just to see the next hill.
     */
    public void setTool(final @Nullable ShapeTool tool) {
        input.setTool(tool);
    }

    public @Nullable ShapeTool tool() {
        return input.tool();
    }

    public ReadOnlyIntegerProperty visibleTilesProperty() {
        return visibleTiles.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty missingTilesProperty() {
        return missingTiles.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<Stats> statsProperty() {
        return stats.getReadOnlyProperty();
    }

    public ReadOnlyDoubleProperty cursorLonProperty() {
        return input.cursorLonProperty();
    }

    public ReadOnlyDoubleProperty cursorLatProperty() {
        return input.cursorLatProperty();
    }

    public ReadOnlyObjectProperty<TileKey> cursorTileProperty() {
        return input.cursorTileProperty();
    }

    // -------------------------------------------------------------------------------------------
    // Painting
    // -------------------------------------------------------------------------------------------

    private static final Color BACKGROUND = Color.web("#1b1e23");

    private void paint() {
        final GraphicsContext g = canvas.getGraphicsContext2D();
        final double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);
        overlayCanvas.getGraphicsContext2D().clearRect(0, 0, w, h);

        if (!layer.hasSource()) {
            drawOverlays(w, h);
            return;
        }
        final int[] counts = layer.paint(g, regionCanvas.getGraphicsContext2D(), w, h);
        drawOverlays(w, h);
        final int visible = counts[0], missing = counts[1];
        visibleTiles.set(visible);
        missingTiles.set(missing);
        // One value, set last, and only when it actually changed – so a listener always sees a
        // consistent pair and is not woken sixty times a second to be told the same thing.
        if (stats.get().visible() != visible || stats.get().missing() != missing) {
            stats.set(new Stats(visible, missing));
        }
    }

    private void drawOverlays(final double w, final double h) {
        final GraphicsContext g = overlayCanvas.getGraphicsContext2D();
        for (final Overlay overlay : overlays) {
            g.save();
            try {
                overlay.draw(g, camera, w, h);
            } catch (final RuntimeException ex) {
                // An overlay that throws must not take the map down with it; the next frame tries
                // again and the exception is on the log for whoever wrote the overlay.
                org.slf4j.LoggerFactory.getLogger(TileCanvas.class).warn("Overlay failed", ex);
            } finally {
                g.restore();
            }
        }
        final ShapeTool tool = input.tool();
        if (tool != null) {
            g.save();
            tool.draw(g, camera);
            g.restore();
        }
    }

    /** Every tile the view needs right now, for the view's download. */
    public @NotNull java.util.List<TileKey> visibleKeys() {
        return layer.visibleKeys();
    }
}
