package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The fade across an area's growth: how much of the map shows at each point of the print, from
 * whole inside the shape the area was grown from ({@link ComposeJob#fadeFrom}) to nothing at the
 * edge of what is printed.
 * <p>
 * <b>A mask, not a treatment of the tiles.</b> The tiles are copied into a PDF, not converted, and
 * a fade worked into their pixels would decode every tile the band crosses. So the PDF lays this
 * over everything the map draws as a soft mask, and the raster multiplies its alpha by it; the
 * tiles stay as they came. On paper, and in a viewer that shows a page over white, what fades
 * out is the map into the paper.
 * <p>
 * <b>The fraction of the way across the band</b>, not a distance divided by the growth: at each
 * point, the distance to the outside of the print over that plus the distance to the fitted
 * shape. Across a straight band it is exactly {@code 1 − d/X}, and it needs no growth and no
 * unit: a growth in meters is a different width on paper at every latitude of a tall print, and
 * a box grown into a bigger box has corners farther out than any buffer reaches – this reaches
 * zero in them all the same.
 * <p>
 * <b>One grid for the whole print.</b> Worked out once, in the unit square, and cut from for
 * every page and every band, so two sheets agree along their seam by construction, and the
 * distance to a fitted edge on another sheet is measured all the same. Built on first use, since
 * a plan is made far more often – for every count the studio shows – than it is exported.
 * <p>
 * Nothing in it comes from the machine or the clock: a fill of its own rather than Java2D's,
 * whose rasterizer is the JDK's to change, and arithmetic that is the same everywhere – an export
 * is the same file every time.
 */
public final class FadeMask {

    /**
     * The grid's cell on paper. Half a millimeter is finer than a fade can be seen to step, and
     * the PDF interpolates between cells besides.
     */
    static final double CELL_MM = 0.5;

    /**
     * The most cells the grid may have, the cell growing past {@link #CELL_MM} to keep within it:
     * two distances of four bytes a cell while it is worked out, so about 32 MB at the most. A
     * five-meter wall comes out at cells of two millimeters, across a band that on such a print
     * is ten centimeters wide or more.
     */
    static final long MAX_CELLS = 4_000_000;

    /** What a cell with no fitted shape anywhere starts from, in squared cells: past any real one. */
    private static final double FAR = 1e20;

    private final Geometry from;
    private final Geometry to;
    private final double mmPerUnit;
    private @Nullable Grid grid;

    private FadeMask(final @NotNull Geometry from, final @NotNull Geometry to, final double mmPerUnit) {
        this.from = from;
        this.to = to;
        this.mmPerUnit = mmPerUnit;
    }

    /**
     * @param from      the fitted shape the fade starts from, in the unit square; null for no fade
     * @param to        what is printed, in the unit square – {@link TilePlan.Projected#printed}
     * @param mmPerUnit the print's scale
     * @return the fade, or null when there is none
     */
    static @Nullable FadeMask of(final @Nullable Geometry from, final @NotNull Geometry to, final double mmPerUnit) {
        return from == null || from.isEmpty() || to.isEmpty() ? null : new FadeMask(from, to, mmPerUnit);
    }

    /** The grid, worked out the first time it is asked for – which takes a while for a big print. */
    public synchronized @NotNull Grid grid() {
        if (grid == null) {
            grid = Grid.of(from, to, mmPerUnit);
        }
        return grid;
    }

    /**
     * The fade as cells of the unit square, one byte each: 255 where the map is whole, 0 where
     * nothing of it shows. Row 0 is the north edge, as in the tile grid and in an image.
     */
    public static final class Grid {
        /** The west edge of the first column, in the unit square. */
        final double x0;
        /** The north edge of the first row. */
        final double y0;
        /** A cell's side, in the unit square. */
        final double cell;
        final int width;
        final int height;
        private final byte[] alpha;

        private Grid(final double x0, final double y0, final double cell, final int width, final int height,
                     final byte @NotNull [] alpha) {
            this.x0 = x0;
            this.y0 = y0;
            this.cell = cell;
            this.width = width;
            this.height = height;
            this.alpha = alpha;
        }

        static @NotNull Grid of(final @NotNull Geometry from, final @NotNull Geometry to, final double mmPerUnit) {
            final Envelope e = to.getEnvelopeInternal();
            double cell = CELL_MM / mmPerUnit;
            final double cells = (e.getWidth() / cell + 2) * (e.getHeight() / cell + 2);
            if (cells > MAX_CELLS) {
                cell *= Math.sqrt(cells / MAX_CELLS);
            }
            // A cell of margin all round, so that the edge of what is printed always has a cell
            // outside it to measure to
            final int width = (int) Math.ceil(e.getWidth() / cell) + 2;
            final int height = (int) Math.ceil(e.getHeight() / cell) + 2;
            final double x0 = e.getMinX() - cell;
            final double y0 = e.getMinY() - cell;

            final boolean[] inside = fill(from, x0, y0, cell, width, height);
            if (!any(inside)) {
                // Fitted to something smaller than a cell: the cell it is in is where it starts
                final Coordinate c = from.getInteriorPoint().getCoordinate();
                final int i = clamp((int) Math.floor((c.x - x0) / cell), width);
                final int j = clamp((int) Math.floor((c.y - y0) / cell), height);
                inside[j * width + i] = true;
            }
            final boolean[] printed = fill(to, x0, y0, cell, width, height);
            final boolean[] outside = new boolean[printed.length];
            for (int k = 0; k < printed.length; k++) {
                outside[k] = !printed[k];
            }
            final float[] toFrom = squaredDistances(inside, width, height);
            final float[] toEdge = squaredDistances(outside, width, height);

            final byte[] alpha = new byte[width * height];
            for (int k = 0; k < alpha.length; k++) {
                final double a;
                if (inside[k]) {
                    a = 1;
                } else if (!printed[k]) {
                    a = 0;
                } else {
                    // From cell centers to the edges, which lie half a cell short of the nearest
                    // cell on their other side
                    final double in = Math.max(0, Math.sqrt(toFrom[k]) - 0.5);
                    final double out = Math.max(0, Math.sqrt(toEdge[k]) - 0.5);
                    a = in + out == 0 ? 1 : out / (in + out);
                }
                alpha[k] = (byte) Math.round(a * 255);
            }
            return new Grid(x0, y0, cell, width, height, alpha);
        }

        /** The west edge of the first column, in the unit square. */
        public double x0() {
            return x0;
        }

        /** The north edge of the first row. */
        public double y0() {
            return y0;
        }

        /** A cell's side, in the unit square. */
        public double cell() {
            return cell;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** The byte of one cell, 0..255. */
        public int at(final int i, final int j) {
            return alpha[j * width + i] & 0xFF;
        }

        /**
         * How much of the map shows at a point of the unit square, 0..1, between the four cells
         * around it; nothing outside the grid.
         */
        double alphaAt(final double u, final double v) {
            final double fx = (u - x0) / cell - 0.5;
            final double fy = (v - y0) / cell - 0.5;
            if (fx < -1 || fy < -1 || fx > width || fy > height) {
                return 0;
            }
            final int i = (int) Math.floor(fx);
            final int j = (int) Math.floor(fy);
            final double tx = fx - i;
            final double ty = fy - j;
            final double top = (1 - tx) * cellOrNothing(i, j) + tx * cellOrNothing(i + 1, j);
            final double bottom = (1 - tx) * cellOrNothing(i, j + 1) + tx * cellOrNothing(i + 1, j + 1);
            return ((1 - ty) * top + ty * bottom) / 255;
        }

        private int cellOrNothing(final int i, final int j) {
            return i < 0 || j < 0 || i >= width || j >= height ? 0 : at(i, j);
        }

        /** The column a point of the unit square is in, which may be off the grid. */
        int column(final double u) {
            return (int) Math.floor((u - x0) / cell);
        }

        /** The row a point of the unit square is in, which may be off the grid. */
        int row(final double v) {
            return (int) Math.floor((v - y0) / cell);
        }

        /**
         * The cells from column {@code i0} and row {@code j0}, {@code w} by {@code h}, row by row;
         * nothing where they are off the grid.
         */
        public byte @NotNull [] crop(final int i0, final int j0, final int w, final int h) {
            final byte[] out = new byte[w * h];
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    out[j * w + i] = (byte) cellOrNothing(i0 + i, j0 + j);
                }
            }
            return out;
        }

        /** Everything the grid says, for a PDF's {@code /ID}. */
        byte @NotNull [] digest() {
            final MessageDigest d = PdfTileImages.sha256();
            d.update(ByteBuffer.allocate(3 * Double.BYTES + 2 * Integer.BYTES)
                    .putDouble(x0).putDouble(y0).putDouble(cell).putInt(width).putInt(height).array());
            d.update(alpha);
            return d.digest();
        }

        @Override
        public @NotNull String toString() {
            return "Grid[" + width + " x " + height + " cells of " + cell + "]";
        }
    }

    private static int clamp(final int i, final int n) {
        return Math.max(0, Math.min(n - 1, i));
    }

    private static boolean any(final boolean @NotNull [] cells) {
        for (final boolean c : cells) {
            if (c) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------
    // The fill: which cells' centers a shape covers, even-odd over every ring
    // -------------------------------------------------------------------------------------------

    /**
     * Every ring's crossings with every row's center line, gathered row by row, then the spans
     * between each pair taken as inside. An edge only visits the rows it crosses, so a surveyed
     * border of 125 000 points costs its own length and the grid's, not their product.
     */
    static boolean @NotNull [] fill(final @NotNull Geometry g, final double x0, final double y0,
                                    final double cell, final int width, final int height) {
        // Counted first, so the crossings go into one array, each row's run after the last's
        final int[] count = new int[height + 1];
        forEachEdge(g, (ax, ay, bx, by) -> {
            final int[] rows = rows(ay, by, y0, cell, height);
            for (int j = rows[0]; j < rows[1]; j++) {
                count[j + 1]++;
            }
        });
        for (int j = 0; j < height; j++) {
            count[j + 1] += count[j];
        }
        final double[] xs = new double[count[height]];
        final int[] next = Arrays.copyOf(count, height);
        forEachEdge(g, (ax, ay, bx, by) -> {
            final int[] rows = rows(ay, by, y0, cell, height);
            for (int j = rows[0]; j < rows[1]; j++) {
                final double yc = y0 + (j + 0.5) * cell;
                xs[next[j]++] = ax + (yc - ay) * (bx - ax) / (by - ay);
            }
        });

        final boolean[] inside = new boolean[width * height];
        for (int j = 0; j < height; j++) {
            Arrays.sort(xs, count[j], count[j + 1]);
            for (int k = count[j]; k + 1 < count[j + 1]; k += 2) {
                // The cells whose centers lie in [xs[k], xs[k + 1])
                final int from = Math.max(0, (int) Math.ceil((xs[k] - x0) / cell - 0.5));
                final int to = Math.min(width, (int) Math.ceil((xs[k + 1] - x0) / cell - 0.5));
                for (int i = from; i < to; i++) {
                    inside[j * width + i] = true;
                }
            }
        }
        return inside;
    }

    /**
     * The rows whose center line an edge crosses, as {@code [first, end)}: those from the one at
     * its lower end to the one before its upper end. Half open, and <b>the row of a vertex is
     * worked out from the vertex alone</b>, by the same arithmetic for both edges that meet there
     * – so it is counted once where the ring passes through and twice or not at all where it
     * turns, and the parity every span depends on is right whatever the rounding does.
     */
    private static int @NotNull [] rows(final double ay, final double by, final double y0, final double cell,
                                        final int height) {
        return new int[]{Math.max(0, rowAt(Math.min(ay, by), y0, cell)),
                Math.min(height, rowAt(Math.max(ay, by), y0, cell))};
    }

    /** The first row whose center is at {@code y} or past it, southward. */
    private static int rowAt(final double y, final double y0, final double cell) {
        return (int) Math.ceil((y - y0) / cell - 0.5);
    }

    private interface EdgeVisitor {
        void edge(double ax, double ay, double bx, double by);
    }

    private static void forEachEdge(final @NotNull Geometry g, final @NotNull EdgeVisitor visitor) {
        for (int n = 0; n < g.getNumGeometries(); n++) {
            if (!(g.getGeometryN(n) instanceof final Polygon p) || p.isEmpty()) {
                continue;
            }
            ring(p.getExteriorRing(), visitor);
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                ring(p.getInteriorRingN(h), visitor);
            }
        }
    }

    private static void ring(final @NotNull LineString ring, final @NotNull EdgeVisitor visitor) {
        final Coordinate[] c = ring.getCoordinates();
        for (int k = 0; k + 1 < c.length; k++) {
            if (c[k].y != c[k + 1].y) {
                visitor.edge(c[k].x, c[k].y, c[k + 1].x, c[k + 1].y);
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // The distances: exact Euclidean, from cell center to cell center, in squared cells
    // -------------------------------------------------------------------------------------------

    /**
     * Each cell's squared distance to the nearest cell that is set – Felzenszwalb and
     * Huttenlocher's transform, a column at a time and then a row at a time, each in time linear
     * in its length.
     */
    static float @NotNull [] squaredDistances(final boolean @NotNull [] set, final int width, final int height) {
        final int n = Math.max(width, height);
        final double[] f = new double[n];
        final double[] d = new double[n];
        final int[] v = new int[n];
        final double[] z = new double[n + 1];
        final float[] out = new float[width * height];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                f[j] = set[j * width + i] ? 0 : FAR;
            }
            transform(f, height, d, v, z);
            for (int j = 0; j < height; j++) {
                out[j * width + i] = (float) d[j];
            }
        }
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                f[i] = out[j * width + i];
            }
            transform(f, width, d, v, z);
            for (int i = 0; i < width; i++) {
                out[j * width + i] = (float) d[i];
            }
        }
        return out;
    }

    /** The one-dimensional transform: the lower envelope of the parabolas rooted at each sample. */
    private static void transform(final double @NotNull [] f, final int n, final double @NotNull [] d,
                                  final int @NotNull [] v, final double @NotNull [] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double s = intersection(f, q, v[k]);
            while (s <= z[k]) {
                k--;
                s = intersection(f, q, v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            final double dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }

    private static double intersection(final double @NotNull [] f, final int q, final int p) {
        return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
    }
}
