package net.trdlo.wtf.studio.map;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.tiles.*;

/**
 * The map's tiles: where they come from, which zoom of them is drawn, and one frame of them
 * painted – the base layer, the region's copy of it, the stand-ins for tiles still on their way,
 * and the count of what is on screen and what is missing.
 * <p>
 * The canvas it belongs to, {@link TileCanvas}, owns the three canvases and the order a frame is
 * painted in; this draws onto two of them.
 */
final class TileLayer {

    private final Camera camera;
    /** The canvas's repaint, for a tile that has finished decoding. */
    private final Runnable repaint;

    private @Nullable Geometry regionShape;
    /**
     * Whether each side of {@link #regionShape} is printed at all.
     * <p>
     * A region that is not printed is blank paper, and the map says so by painting it white –
     * which is what makes a stencil something a person can look at while choosing it, rather than
     * something they find out about in the PDF.
     */
    private boolean drawInside = true;
    private boolean drawOutside = true;

    private @Nullable TileImageCache images;
    private @Nullable Provider provider;
    private @Nullable String themeId;
    /** A tile of the map at zoom 0, which every other key is made from so that it carries the language. */
    private @Nullable TileKey origin;

    /** A zoom to draw instead of the one the scale implies; see {@link #setFixedTileZoom}. */
    private @Nullable Integer fixedTileZoom;

    /**
     * More tiles than any screen can show, and the point past which drawing them is a hang.
     * <p>
     * A 4K canvas holds about 130 tiles at their own size; a thousand is generous for every
     * honest case and small enough that the worst frame is still a frame.
     */
    private static final long MAX_TILES_PER_FRAME = 1_000;

    private static final Color PLACEHOLDER_FILL = Color.web("#2a2f37");
    private static final Color PLACEHOLDER_LINE = Color.web("#3a414c");
    private static final Color PLACEHOLDER_TEXT = Color.web("#6c7689");

    TileLayer(final @NotNull Camera camera, final @NotNull Runnable repaint) {
        this.camera = camera;
        this.repaint = repaint;
    }

    /** See {@link TileCanvas#setSource}; the canvas starts painting again. */
    void setSource(final @NotNull Provider provider, final @NotNull String themeId,
                   final @Nullable String lang, final int scale,
                   final @NotNull TileSource tiles, final long budgetBytes) {
        if (images != null) {
            images.shutdown();
        }
        this.provider = provider;
        this.themeId = themeId;
        // Every tile asked for is a neighbor, parent or child of this one, and keeps its scale
        this.origin = provider.key(themeId, lang, 0, 0, 0).atScale(scale);
        this.images = new TileImageCache(tiles, budgetBytes, repaint, scale);
    }

    /** Lets the decoders and the decoded tiles go; see {@link TileCanvas#dispose}. */
    void dispose() {
        if (images != null) {
            images.shutdown();
            images = null;
        }
    }

    @Nullable TileImageCache imageCache() {
        return images;
    }

    /** See {@link TileCanvas#setRegion}; the effect is on the canvas's own region canvas. */
    void setRegion(final @Nullable Geometry shape, final boolean drawInside, final boolean drawOutside) {
        regionShape = shape;
        this.drawInside = drawInside;
        this.drawOutside = drawOutside;
    }

    /**
     * See {@link TileCanvas#setFixedTileZoom}.
     *
     * @return whether it changed, which is when a repaint is worth asking for
     */
    boolean setFixedTileZoom(final @Nullable Integer zoom) {
        if (java.util.Objects.equals(fixedTileZoom, zoom)) {
            return false;
        }
        fixedTileZoom = zoom;
        return true;
    }

    /** The tile at zoom 0 every key is made from, or null while there is no source. */
    @Nullable TileKey origin() {
        return origin;
    }

    /** Whether there is anything to paint: without a source a frame is the background and the overlays. */
    boolean hasSource() {
        return images != null && provider != null && themeId != null;
    }

    /**
     * One frame of the tiles, for a layer that {@link #hasSource has a source}: the base layer onto
     * the tile canvas, and the region's own copy of them onto the region canvas.
     *
     * @return {@code {visible, missing}}
     */
    int @NotNull [] paint(final @NotNull GraphicsContext g, final @NotNull GraphicsContext region,
                          final double w, final double h) {
        final int zoom = drawZoom();
        final TileRange range = camera.visibleTiles(1, zoom);
        // Drawn with a ring of one tile past the edges, so a pan never shows a blank strip before
        // the next tile arrives – but counted without it. The download fetches only what is on
        // screen, so a counted ring's tiles would be "missing" forever: the status bar stuck at
        // 70 % over a view that is complete, and "fetching 12 tiles" waiting for a fetch that has
        // nothing to do.
        final TileRange onScreen = camera.visibleTiles(0, zoom);
        java.util.Objects.requireNonNull(images).ensureRoomFor(range.count());
        final int n = Mercator.tileCount(zoom);
        final double tilePx = camera.pixelsPerUnit() / n;

        // The base layer. With a region in play it is the ground outside it, so it is clipped
        // away from the inside when the inside is not drawn at all – otherwise the tiles under
        // the blank half would show through it.
        // Read once: the checks below and the clips after them must be about the same shape
        final Geometry regionShape = this.regionShape;
        final boolean split = regionShape != null && !regionShape.isEmpty();
        final int[] counts;
        if (split && !drawOutside) {
            // Counted but not painted: the view still needs those tiles to say how much of what
            // is on screen is cached, and the view's download is about the view rather than
            // about the print. The ground is paper there, as the print leaves it.
            counts = countTiles(zoom, onScreen);
            g.save();
            clipOutside(g, regionShape, w, h);
            g.setFill(PAPER);
            g.fillRect(0, 0, w, h);
            g.restore();
        } else if (split && !drawInside) {
            g.save();
            clipOutside(g, regionShape, w, h);
            counts = drawTiles(g, zoom, n, tilePx, range, onScreen);
            g.restore();
        } else {
            counts = drawTiles(g, zoom, n, tilePx, range, onScreen);
        }

        // The inside, over it, clipped to its own shape and filtered its own way. Only the tiles
        // are drawn twice, and only while the two halves are actually being treated differently.
        // An inside that is not drawn is paper, over the hole the base layer leaves for it.
        region.clearRect(0, 0, w, h);
        if (split) {
            region.save();
            clipTo(region, regionShape);
            if (drawInside) {
                drawTiles(region, zoom, n, tilePx, range, onScreen);
            } else {
                region.setFill(PAPER);
                region.fillRect(0, 0, w, h);
            }
            region.restore();
        }
        return counts;
    }

    /** What a region that is not drawn prints as: the paper, white. */
    private static final Color PAPER = Color.WHITE;

    /**
     * One pass over the visible tiles.
     *
     * @return {@code {visible, missing}} – counted the same on every pass, and only the first
     *         pass's answer is kept, because the same tiles drawn twice are not twice as many
     */
    /** The same walk without the drawing, for a half of the map that is not printed. */
    private int @NotNull [] countTiles(final int zoom, final @NotNull TileRange onScreen) {
        final TileImageCache images = java.util.Objects.requireNonNull(this.images);
        final TileKey origin = java.util.Objects.requireNonNull(this.origin);
        int visible = 0, missing = 0;
        for (final int[] xy : onScreen) {
            visible++;
            final TileKey key = origin.at(zoom, xy[0], xy[1]);
            if (images.get(key) == null
                    && images.absenceOf(key) == TileImageCache.Absence.NOT_CACHED) {
                missing++;
            }
        }
        return new int[]{visible, missing};
    }

    private int @NotNull [] drawTiles(final @NotNull GraphicsContext g, final int zoom, final int n,
                                      final double tilePx, final @NotNull TileRange range,
                                      final @NotNull TileRange onScreen) {
        final TileImageCache images = java.util.Objects.requireNonNull(this.images);
        final TileKey origin = java.util.Objects.requireNonNull(this.origin);
        int visible = 0, missing = 0;
        for (final int[] xy : range) {
            final int tx = xy[0], ty = xy[1];
            final boolean counted = onScreen.contains(tx, ty);
            if (counted) {
                visible++;
            }
            final double x = camera.toScreenX((double) tx / n);
            final double y = camera.toScreenY((double) ty / n);

            final TileKey key = origin.at(zoom, tx, ty);
            final Image img = images.get(key);
            if (img != null) {
                drawTile(g, img, x, y, tilePx);
                continue;
            }
            // Three states, not two. A tile still decoding is late, not missing. A tile the
            // provider says does not exist – open sea, or past a national provider's edge – is
            // the map, so it is left as background and never counted or marked. Only a tile that
            // was looked for and was not there is a hole.
            final TileImageCache.Absence absence = images.absenceOf(key);
            if (absence == TileImageCache.Absence.DOES_NOT_EXIST) {
                continue;
            }
            if (absence == TileImageCache.Absence.NOT_CACHED && counted) {
                missing++;
            }
            if (!drawSubstitute(g, key, x, y, tilePx)) {
                drawPlaceholder(g, x, y, tilePx);
            }
        }
        return new int[]{visible, missing};
    }

    /**
     * Clips what follows to a shape in degrees, holes and all.
     * <p>
     * Every ring goes into one path before the clip, so a polygon with a hole in it clips to the
     * ring minus the hole – its rings run opposite ways ({@link ScreenShape}), and a subject with a
     * lake in it is not a special case.
     */
    private void clipTo(final @NotNull GraphicsContext g, final @NotNull Geometry wgs84) {
        g.beginPath();
        appendRings(g, wgs84);
        g.clip();
    }

    /**
     * The opposite clip: everything on screen except the shape.
     * <p>
     * A path of the whole canvas followed by the shape's own rings leaves the shape as a hole. The
     * canvas clips by the non-zero rule whatever its fill rule says, so that holds because the
     * shells run the other way round from this rectangle ({@link ScreenShape}), and a subject's
     * rings wound as the rectangle – which a file may well give – are turned. No geometry has to
     * be subtracted to say it.
     */
    private void clipOutside(final @NotNull GraphicsContext g, final @NotNull Geometry wgs84,
                             final double w, final double h) {
        g.beginPath();
        g.moveTo(0, 0);
        g.lineTo(w, 0);
        g.lineTo(w, h);
        g.lineTo(0, h);
        g.closePath();
        appendRings(g, wgs84);
        g.clip();
    }

    /**
     * The region's rings, simplified to the zoom and cut to the view – see {@link ScreenShape}.
     * The same instance serves both canvases, which clip to the same shape in the same frame.
     */
    private void appendRings(final @NotNull GraphicsContext g, final @NotNull Geometry wgs84) {
        regionOnScreen.append(g, wgs84, camera);
    }

    private final ScreenShape regionOnScreen = new ScreenShape();

    private static void drawTile(final @NotNull GraphicsContext g, final @NotNull Image img,
                                 final double x, final double y, final double size) {
        // +1 so neighboring tiles never show a hairline of background between them
        g.drawImage(Mipmaps.forWidth(img, size * renderScale(g)),
                Math.floor(x), Math.floor(y), Math.ceil(size) + 1, Math.ceil(size) + 1);
    }

    /** Screen pixels per canvas unit: 1.5 on a Windows display at 150 %. */
    private static double renderScale(final @NotNull GraphicsContext g) {
        final javafx.scene.Scene scene = g.getCanvas().getScene();
        final javafx.stage.Window window = scene == null ? null : scene.getWindow();
        return window == null ? 1 : window.getRenderScaleX();
    }

    /**
     * Something to draw instead of a hole: a coarser tile stretched up, or finer tiles shrunk
     * down.
     * <p>
     * <b>Both directions matter.</b> Zooming in, the parent is nearly always in hand, because the
     * user just came through it. Zooming out it never is – what is in hand are the four children
     * – and with only the parent fallback the whole map would go flat gray for as long as the
     * coarser tiles take to decode: a flash on every first visit to a zoom level, and again after
     * the settings dialog, because reopening the source throws the decoded tiles away.
     */
    private boolean drawSubstitute(final @NotNull GraphicsContext g, final @NotNull TileKey key,
                                   final double x, final double y, final double size) {
        return drawParent(g, key, x, y, size) || drawChildren(g, key, x, y, size);
    }

    /** The four tiles one zoom finer, each into its quarter. Partial cover beats no cover. */
    private boolean drawChildren(final @NotNull GraphicsContext g, final @NotNull TileKey key,
                                 final double x, final double y, final double size) {
        if (images == null || key.zoom() >= 22) {
            return false;
        }
        final double half = size / 2;
        boolean any = false;
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                final Image child = images.peek(key.at(
                        key.zoom() + 1, key.x() * 2 + dx, key.y() * 2 + dy));
                if (child == null) {
                    continue;
                }
                g.drawImage(Mipmaps.forWidth(child, half * renderScale(g)), Math.floor(x + dx * half), Math.floor(y + dy * half),
                        Math.ceil(half) + 1, Math.ceil(half) + 1);
                any = true;
            }
        }
        return any;
    }

    private boolean drawParent(final @NotNull GraphicsContext g, final @NotNull TileKey key,
                               final double x, final double y, final double size) {
        if (images == null || key.zoom() == 0) {
            return false;
        }
        for (int up = 1; up <= 4 && key.zoom() - up >= 0; up++) {
            final int factor = 1 << up;
            final TileKey parent = key.at(
                    key.zoom() - up, key.x() / factor, key.y() / factor);
            final Image img = images.peek(parent);
            if (img == null) {
                continue;
            }
            final double part = img.getWidth() / factor;
            g.drawImage(img, (key.x() % factor) * part, (key.y() % factor) * part, part, part,
                    Math.floor(x), Math.floor(y), Math.ceil(size) + 1, Math.ceil(size) + 1);
            return true;
        }
        return false;
    }

    /**
     * A tile that is not cached. Flat, quiet, and <b>no text per tile</b>: a screen of two hundred
     * tiles each saying "not in local cache" is unreadable. The status bar gives the share of the
     * view that is cached.
     */
    private static void drawPlaceholder(final @NotNull GraphicsContext g, final double x, final double y,
                                        final double size) {
        g.setFill(PLACEHOLDER_FILL);
        g.fillRect(x, y, size, size);
        g.setStroke(PLACEHOLDER_LINE);
        g.setLineWidth(1);
        g.strokeRect(x + 0.5, y + 0.5, size - 1, size - 1);
        // No text. Fifteen copies of the same sentence is noise, and two hundred is unreadable –
        // the status bar says it once, as a share. A small mark is enough to show
        // that the tile is absent rather than merely empty.
        if (size >= 96) {
            final double r = Math.min(9, size / 12);
            g.setStroke(PLACEHOLDER_TEXT);
            g.setLineWidth(1.4);
            g.strokeOval(x + size / 2 - r, y + size / 2 - r, r * 2, r * 2);
        }
    }

    /** See {@link TileCanvas#visibleKeys}. */
    @NotNull java.util.List<TileKey> visibleKeys() {
        final java.util.List<TileKey> keys = new java.util.ArrayList<>();
        final TileKey origin = this.origin;
        if (origin == null) {
            return keys;
        }
        // The zoom that is drawn, not the one the scale rounds to: in the 1:1 preview they differ,
        // and fetching the other one fetched tiles nobody was looking at.
        final int zoom = drawZoom();
        for (final int[] xy : camera.visibleTiles(0, zoom)) {
            keys.add(origin.at(zoom, xy[0], xy[1]));
        }
        return keys;
    }

    /**
     * The zoom the tiles are drawn at: the fixed one if there is one and it is not absurd for this
     * view, else the one the scale implies.
     * <p>
     * The fixed zoom is a request, not an order. A zoom far finer than the scale means tiles
     * smaller than a pixel and a range of millions of them, and painting every one hangs the
     * window with nothing to show for it but a busy FX thread. One place decides, so the tiles
     * that are drawn, counted and fetched are always the same tiles.
     */
    int drawZoom() {
        if (fixedTileZoom != null && camera.visibleTiles(1, fixedTileZoom).count() <= MAX_TILES_PER_FRAME) {
            return fixedTileZoom;
        }
        return camera.tileZoom();
    }
}
