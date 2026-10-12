package net.trdlo.wtf.studio.map;

import javafx.beans.property.*;
import javafx.geometry.Point2D;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.TileRange;

/**
 * The viewport: two numbers and a scale.
 * <p>
 * The camera holds the unit-square coordinate at the canvas's top-left and the number of screen
 * pixels one whole unit spans. Everything else – which zoom's tiles to fetch, where a tile lands,
 * what the cursor is pointing at – falls out of those three.
 * <p>
 * Zooming is continuous; tile selection is quantized. Tiles come from {@code round(fractionalZoom)}
 * and are drawn at a scale that therefore stays within [0.71, 1.41], so the map is never a blur
 * and never a mosaic of enormous squares.
 */
public final class Camera {

    /** One tile for the whole world. Zoomed this far out the world is smaller than the canvas. */
    public static final double MIN_PIXELS_PER_UNIT = 256;

    private final DoubleProperty originX = new SimpleDoubleProperty(0);
    private final DoubleProperty originY = new SimpleDoubleProperty(0);
    private final DoubleProperty pixelsPerUnit = new SimpleDoubleProperty(1024);

    private final DoubleProperty viewWidth = new SimpleDoubleProperty(1);
    private final DoubleProperty viewHeight = new SimpleDoubleProperty(1);

    /**
     * The middle a view not sized both ways is to have once it is; null while the view is sized,
     * and once anything but a centering has moved it.
     * <p>
     * A side one pixel long has no middle worth keeping, and a Region is given its width and then
     * its height, as two changes. A middle asked for before the height came sat half a pixel from
     * the top, and the height's arrival kept the corner, so the 1:1 preview opened with the
     * print's middle on its top edge; and a map made shorter than its toolbar and status bar lost
     * its middle the same way as it grew back. Kept here instead: what {@link #centerOn} asks for
     * meanwhile, or the middle a view had when it lost a side, which each {@link #setViewSize}
     * centers on, up to the one that makes both sides real. A restore, a pan or a zoom lets it go,
     * since the view is then where that put it.
     */
    private @Nullable Point2D keptMiddle;

    public DoubleProperty originXProperty() {
        return originX;
    }

    public DoubleProperty originYProperty() {
        return originY;
    }

    public DoubleProperty pixelsPerUnitProperty() {
        return pixelsPerUnit;
    }

    public double pixelsPerUnit() {
        return pixelsPerUnit.get();
    }

    /**
     * The canvas changed size, and whatever was in the middle of it stays in the middle.
     * <p>
     * Keeping the origin instead – which is what setting the two sizes and clamping does by
     * itself – pins the top left corner, so a window maximized to the right and downwards shows
     * its old content in the corner and new ground along two edges, exactly as if it had been
     * panned. Nobody resizes a window in order to pan it.
     * <p>
     * A view not sized both ways has no middle of its own, so the middle kept through it is
     * {@code keptMiddle} – the one a centering asked for meanwhile, or the one the view had when
     * it lost a side. With none, the corner stays where a restore, a pan or a zoom put it.
     */
    public void setViewSize(final double width, final double height) {
        final Point2D middle = isSized()
                ? new Point2D(toUnitX(viewWidth.get() / 2), toUnitY(viewHeight.get() / 2))
                : keptMiddle;

        viewWidth.set(Math.max(1, width));
        viewHeight.set(Math.max(1, height));

        if (middle != null) {
            place(middle.getX(), middle.getY());
        } else {
            clamp();
        }
        keptMiddle = isSized() ? null : middle;        // until both sides are real
    }

    /** Both sides longer than the one pixel a view starts at, and a canvas not laid out has. */
    private boolean isSized() {
        return viewWidth.get() > 1 && viewHeight.get() > 1;
    }

    public double viewWidth() {
        return viewWidth.get();
    }

    public double viewHeight() {
        return viewHeight.get();
    }

    // -------------------------------------------------------------------------------------------
    // Zoom
    // -------------------------------------------------------------------------------------------

    /** Continuous. {@code log2(pixelsPerUnit / 256)}. */
    public double fractionalZoom() {
        return Math.log(pixelsPerUnit.get() / Mercator.TILE_SIZE) / Math.log(2);
    }

    /** Which zoom's tiles to draw. */
    public int tileZoom() {
        return Math.max(0, Math.min(22, (int) Math.round(fractionalZoom())));
    }

    /** What a tile of {@link #tileZoom()} is scaled by on screen; always within [0.71, 1.41]. */
    public double tileScale() {
        return Math.pow(2, fractionalZoom() - tileZoom());
    }

    /** Keeps the corner, and so moves the middle: a {@code keptMiddle} goes, as with a pan. */
    public void setFractionalZoom(final double z) {
        keptMiddle = null;
        pixelsPerUnit.set(Mercator.TILE_SIZE * Math.pow(2, z));
        clamp();
    }

    /** Zooms about a point on the canvas, so whatever is under the cursor stays under it. */
    public void zoomAt(final double screenX, final double screenY, final double zoomDelta) {
        final double unitX = toUnitX(screenX);
        final double unitY = toUnitY(screenY);
        setFractionalZoom(fractionalZoom() + zoomDelta);
        originX.set(unitX - screenX / pixelsPerUnit.get());
        originY.set(unitY - screenY / pixelsPerUnit.get());
        clamp();
    }

    public void panByPixels(final double dx, final double dy) {
        keptMiddle = null;
        originX.set(originX.get() - dx / pixelsPerUnit.get());
        originY.set(originY.get() - dy / pixelsPerUnit.get());
        clamp();
    }

    /** Frames a geographic rectangle with a little air around it. */
    public void zoomTo(final @NotNull LonLatBounds bounds, final double marginFraction) {
        final double x0 = Mercator.lonToUnitX(bounds.west());
        final double x1 = Mercator.lonToUnitX(bounds.east());
        final double y0 = Mercator.latToUnitY(bounds.north());
        final double y1 = Mercator.latToUnitY(bounds.south());
        final double w = Math.max(1e-9, x1 - x0);
        final double h = Math.max(1e-9, y1 - y0);
        final double margin = 1 + Math.max(0, marginFraction);

        pixelsPerUnit.set(Math.min(viewWidth.get() / (w * margin), viewHeight.get() / (h * margin)));
        centerOn((x0 + x1) / 2, (y0 + y1) / 2);
    }

    /**
     * Puts a point of the unit square in the middle of the view – and, while the view is not sized
     * both ways, keeps it there as it gets its sides ({@code keptMiddle}).
     */
    public void centerOn(final double unitX, final double unitY) {
        keptMiddle = isSized() ? null : new Point2D(unitX, unitY);
        place(unitX, unitY);
    }

    /** The origin that puts a point in the middle of the view as it is now, clamped. */
    private void place(final double unitX, final double unitY) {
        originX.set(unitX - viewWidth.get() / 2 / pixelsPerUnit.get());
        originY.set(unitY - viewHeight.get() / 2 / pixelsPerUnit.get());
        clamp();
    }

    /**
     * Puts the camera back where it was, origin and scale together.
     * <p>
     * The three are restored before the single clamp, so a view saved on a wide monitor and
     * reopened on a narrow one is corrected once, as a whole, rather than being dragged into
     * range one property at a time.
     */
    public void restore(final double unitX, final double unitY, final double pixels) {
        keptMiddle = null;
        originX.set(unitX);
        originY.set(unitY);
        pixelsPerUnit.set(pixels);
        clamp();
    }

    public void centerOn(final double lon, final double lat, final double fractionalZoom) {
        setFractionalZoom(fractionalZoom);
        centerOn(Mercator.lonToUnitX(lon), Mercator.latToUnitY(lat));
    }

    /**
     * Keeps the world on screen, on both axes: the view never scrolls past an edge of the map,
     * and when the world is smaller than the canvas – which it is at the last zoom or two – it is
     * centered on that axis rather than left lying against a corner.
     * <p>
     * The two axes are decided separately, because at most zooms one of them fits and the other
     * does not. Longitude is not left free to run off both edges, though a map refusing to show the
     * Atlantic in the middle is annoying: what that buys is the ability to pan the world away
     * entirely and be left looking at the background, and it is the one thing every other map
     * application prevents.
     */
    private void clamp() {
        pixelsPerUnit.set(Math.max(MIN_PIXELS_PER_UNIT, pixelsPerUnit.get()));
        originX.set(clampAxis(originX.get(), viewWidth.get()));
        originY.set(clampAxis(originY.get(), viewHeight.get()));
    }

    /** Centered when the world is the smaller of the two, otherwise held inside {@code [0, 1]}. */
    private double clampAxis(final double origin, final double viewSize) {
        final double unitsVisible = viewSize / pixelsPerUnit.get();
        return unitsVisible >= 1
                ? (1 - unitsVisible) / 2
                : Math.max(0, Math.min(1 - unitsVisible, origin));
    }

    // -------------------------------------------------------------------------------------------
    // Screen and world
    // -------------------------------------------------------------------------------------------

    public double toUnitX(final double screenX) {
        return originX.get() + screenX / pixelsPerUnit.get();
    }

    public double toUnitY(final double screenY) {
        return originY.get() + screenY / pixelsPerUnit.get();
    }

    public double toScreenX(final double unitX) {
        return (unitX - originX.get()) * pixelsPerUnit.get();
    }

    public double toScreenY(final double unitY) {
        return (unitY - originY.get()) * pixelsPerUnit.get();
    }

    /**
     * Clamped to the antimeridian, like the latitude and the view itself. Wrapped, a rectangle
     * dragged past the east edge of a zoomed-out world would come back as one that started in the
     * Pacific and ran west.
     */
    public double lonAt(final double screenX) {
        return Mercator.unitXToLon(clampUnit(toUnitX(screenX)));
    }

    public double latAt(final double screenY) {
        return Mercator.unitYToLat(clampUnit(toUnitY(screenY)));
    }

    private static double clampUnit(final double u) {
        return Math.max(0, Math.min(1, u));
    }

    /**
     * Meters of ground per screen pixel, at the middle of the view.
     * <p>
     * The whole circumference over the pixels the whole world spans, which is what
     * {@link #pixelsPerUnit} is. Dividing {@link Mercator#EQUATOR_RESOLUTION} – the meters per
     * pixel of a zoom 0 <b>tile</b> – by the same number instead was a factor of 256 out, and it
     * reported a view of Prague at zoom 8 as 1.53 m/px when a pixel there is 393 m of ground.
     * <p>
     * One number for both directions is not a simplification: Web Mercator is conformal, so at
     * any one point the scale along the meridian and along the parallel are equal. What it is not
     * is constant over the map – it falls off as {@code cos(latitude)} – which is why this is
     * measured at the middle of the view and changes as you pan north.
     */
    public double groundResolution() {
        return Mercator.EQUATORIAL_CIRCUMFERENCE
                * Math.cos(Math.toRadians(latAt(viewHeight.get() / 2))) / pixelsPerUnit.get();
    }

    /** The geographic rectangle on screen. */
    public @NotNull LonLatBounds visibleBounds() {
        final double west = Mercator.unitXToLon(Math.max(0, Math.min(1, toUnitX(0))));
        final double east = Mercator.unitXToLon(Math.max(0, Math.min(1, toUnitX(viewWidth.get()))));
        final double north = latAt(0);
        final double south = latAt(viewHeight.get());
        return new LonLatBounds(Math.min(west, east), Math.min(south, north),
                Math.max(west, east), Math.max(south, north));
    }

    /** The tiles the view covers at {@link #tileZoom()}, plus a margin so panning is not blank. */
    public @NotNull TileRange visibleTiles(final int marginTiles) {
        return visibleTiles(marginTiles, tileZoom());
    }

    /**
     * The same, at a zoom somebody else chose.
     * <p>
     * For the 1:1 preview, which draws the zoom the print will use rather than the one the
     * current scale happens to round to – the whole point of that window is to show what a chosen
     * zoom looks like on paper, and tiles that changed zoom as it was calibrated would show
     * something else.
     */
    public @NotNull TileRange visibleTiles(final int marginTiles, final int zoom) {
        final int n = Mercator.tileCount(zoom);
        final int minX = (int) Math.floor(toUnitX(0) * n) - marginTiles;
        final int maxX = (int) Math.floor(toUnitX(viewWidth.get()) * n) + marginTiles;
        final int minY = (int) Math.floor(toUnitY(0) * n) - marginTiles;
        final int maxY = (int) Math.floor(toUnitY(viewHeight.get()) * n) + marginTiles;
        return new TileRange(minX, minY, maxX, maxY).clampedTo(zoom);
    }
}
