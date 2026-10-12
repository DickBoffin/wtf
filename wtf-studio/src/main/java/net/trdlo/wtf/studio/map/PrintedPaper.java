package net.trdlo.wtf.studio.map;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.FadeMask;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.PrintRect;
import net.trdlo.wtf.compose.TilePlan;
import net.trdlo.wtf.studio.BackgroundWork;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;

import java.util.concurrent.Executor;

/**
 * The paper of the print, over the map: white past the edge of what is printed, and the map going
 * into that white across the growth when the print fades.
 * <p>
 * For the 1:1 preview, which shows the print rather than the map. Both shapes are the export's own
 * – {@link TilePlan#printed} and {@link TilePlan#fade} – so the preview cannot stop at one edge and
 * the paper at another. The fade is drawn as white laid over the map at one minus the fade's alpha,
 * which is what a map faded to nothing over white paper looks like.
 * <p>
 * The fade's grid is a distance transform over the whole print, which for a big one takes a while,
 * so it is worked out off the FX thread, and only when what decides it changes; until then the
 * edge is hard.
 */
public final class PrintedPaper implements TileCanvas.Overlay {

    private static final Color PAPER = Color.WHITE;

    private final Runnable repaint;
    private final Executor worker;
    private final Executor fx;
    private final ScreenShape onScreen = ScreenShape.inUnitSquare();

    /** What {@link #printed} and {@link #fade} were worked out from; see {@link Key}. */
    private @Nullable Key key;
    private @Nullable Geometry printed;
    private FadeMask.@Nullable Grid fade;

    /** The cells under the view as an image, and which cells they are. */
    private @Nullable WritableImage crop;
    private FadeMask.@Nullable Grid cropOf;
    private int cropI0, cropJ0, cropW, cropH;

    /** @param repaint the canvas's repaint, for a fade that has finished working out */
    public PrintedPaper(final @NotNull Runnable repaint) {
        this(repaint, work -> {
            final Thread t = new Thread(work, "fade-preview");
            t.setDaemon(true);
            t.start();
        }, javafx.application.Platform::runLater);
    }

    /** With the fade worked out and landed where a test says, which is on its own thread. */
    PrintedPaper(final @NotNull Runnable repaint, final @NotNull Executor worker, final @NotNull Executor fx) {
        this.repaint = repaint;
        this.worker = worker;
        this.fx = fx;
    }

    /**
     * What decides the printed shape and the fade.
     * <p>
     * The shapes by identity: the model hands every job the same copy of a shape until the shape
     * changes, and comparing a surveyed border point by point at every keystroke in the export
     * name is the cost this exists to avoid. The sheets too, since a rectangle grown to cover
     * them is printed to their edge.
     */
    private record Key(@NotNull Geometry fill, @Nullable Geometry fadeFrom, boolean clips,
                       @NotNull PrintRect print, @NotNull Medium medium,
                       double overlapMm, double edgeMm) {

        static @NotNull Key of(final @NotNull ComposeJob job) {
            return new Key(job.fillShape(), job.fadeFrom(), job.clipsToArea(), job.print(),
                    job.medium(), job.overlapMm(), job.marks().edgeMm());
        }

        boolean same(final @Nullable Key other) {
            return other != null && fill == other.fill && fadeFrom == other.fadeFrom && clips == other.clips
                    && print.equals(other.print) && medium.equals(other.medium)
                    && Double.compare(overlapMm, other.overlapMm) == 0 && Double.compare(edgeMm, other.edgeMm) == 0;
        }
    }

    /** The print to show, or null for none: nothing of the paper is drawn then. */
    public void update(final @Nullable ComposeJob job) {
        if (job == null) {
            key = null;
            printed = null;
            fade = null;
            return;
        }
        final Key k = Key.of(job);
        if (k.same(key)) {
            return;
        }
        key = k;
        fade = null;
        try {
            printed = TilePlan.printed(job);
        } catch (final RuntimeException ex) {
            printed = null;     // an impossible overlap; the export panel says so
            return;
        }
        final FadeMask mask = TilePlan.fade(job, printed);
        if (mask == null) {
            return;
        }
        worker.execute(() -> BackgroundWork.land(mask::grid, (grid, failure) -> fx.execute(() -> {
            if (failure != null) {
                org.slf4j.LoggerFactory.getLogger(PrintedPaper.class).warn("Could not work out the fade", failure);
            } else if (key == k) {
                // Landed for the print still shown, not one replaced while it was being worked out
                fade = grid;
                repaint.run();
            }
        })));
    }

    @Override
    public void draw(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                     final double width, final double height) {
        final Geometry printed = this.printed;
        if (printed == null) {
            return;
        }
        if (fade != null) {
            drawFade(g, camera, fade, width, height);
        }
        // The canvas less what is printed: the rings run the other way round from this rectangle
        // (ScreenShape), so the non-zero rule leaves the shape a hole
        g.setFill(PAPER);
        g.beginPath();
        g.moveTo(0, 0);
        g.lineTo(width, 0);
        g.lineTo(width, height);
        g.lineTo(0, height);
        g.closePath();
        onScreen.append(g, printed, camera);
        g.fill();
    }

    /**
     * The cells under the view, and one past it on every side, stretched over the map with
     * smoothing: between cell centers the image interpolates as the PDF and the raster do.
     */
    private void drawFade(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                          final FadeMask.@NotNull Grid grid, final double width, final double height) {
        final double cell = grid.cell();
        final int i0 = (int) Math.floor((camera.toUnitX(0) - grid.x0()) / cell) - 1;
        final int j0 = (int) Math.floor((camera.toUnitY(0) - grid.y0()) / cell) - 1;
        final int i1 = (int) Math.ceil((camera.toUnitX(width) - grid.x0()) / cell) + 1;
        final int j1 = (int) Math.ceil((camera.toUnitY(height) - grid.y0()) / cell) + 1;
        final int w = i1 - i0, h = j1 - j0;
        if (w <= 0 || h <= 0 || (long) w * h > MAX_CROP_CELLS) {
            return;
        }
        final WritableImage img = crop(grid, i0, j0, w, h);
        final double x = camera.toScreenX(grid.x0() + i0 * cell);
        final double y = camera.toScreenY(grid.y0() + j0 * cell);
        final double side = cell * camera.pixelsPerUnit();
        g.setImageSmoothing(true);
        g.drawImage(img, x, y, w * side, h * side);
    }

    /**
     * More cells than a view at 1:1 can hold, at half a millimeter each; past it the view is not
     * at 1:1, and an image of millions of cells for it is a stall, not a preview.
     */
    private static final long MAX_CROP_CELLS = 4_000_000;

    private @NotNull WritableImage crop(final FadeMask.@NotNull Grid grid,
                                        final int i0, final int j0, final int w, final int h) {
        if (crop != null && cropOf == grid && cropI0 == i0 && cropJ0 == j0 && cropW == w && cropH == h) {
            return crop;
        }
        final int[] argb = paperOver(grid.crop(i0, j0, w, h));
        final WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        crop = img;
        cropOf = grid;
        cropI0 = i0;
        cropJ0 = j0;
        cropW = w;
        cropH = h;
        return img;
    }

    /** White, as opaque as the map is faded: none where it is whole, all where nothing of it shows. */
    static int @NotNull [] paperOver(final byte @NotNull [] alpha) {
        final int[] argb = new int[alpha.length];
        for (int k = 0; k < alpha.length; k++) {
            argb[k] = (255 - (alpha[k] & 0xFF)) << 24 | 0xFFFFFF;
        }
        return argb;
    }
}
