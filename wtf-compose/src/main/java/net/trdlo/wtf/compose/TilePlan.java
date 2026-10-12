package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import net.trdlo.wtf.tiles.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which tiles a job needs, per sheet and per region.
 * <p>
 * A tile straddling the subject's outline is planned <b>for both regions</b> and clipped twice. That is correct, and it is the only place the two-region model costs extra tiles:
 * one ring of them along the boundary. Two regions drawn alike are one region, planned once.
 */
public final class TilePlan {

    /** Which side of the cut a run of tiles belongs to. */
    public enum Side { INSIDE, OUTSIDE }

    /** The tiles of one region on one sheet, at the print's zoom. */
    public record RegionTiles(@NotNull Side side, int zoom, @NotNull TileRange range,
                              @NotNull List<TileKey> keys, @NotNull TileEffect effect) {
        public RegionTiles {
            keys = List.copyOf(keys);
        }
    }

    public record SheetPlan(SheetLayout.@NotNull Sheet sheet, @NotNull List<RegionTiles> regions) {
        public SheetPlan {
            regions = List.copyOf(regions);
        }

        public int tileCount() {
            return regions.stream().mapToInt(r -> r.keys().size()).sum();
        }
    }

    /**
     * The job's two shapes in the unit square, projected once for the whole job.
     * <p>
     * The tiles are planned against these and the exporters clip to them, so what was planned and
     * what is printed come from one projection and cannot disagree.
     *
     * @param printed what is printed – see {@link #printedShape}
     * @param subject the cut line, or null for one region
     */
    public record Projected(@NotNull Geometry printed, @Nullable Geometry subject) {

        /**
         * Both shapes as far as a piece of paper reaches: a page, or a raster sheet, and whatever
         * margin the caller put round it.
         * <p>
         * <b>A page needs its own part of an outline, not all of it.</b> A surveyed border is
         * 125 000 points, and a page writes the shapes up to four times – the area's clip, both
         * regions' clips and the stroke – so uncut, every page of a twenty-sheet print would carry
         * the whole border four times over.
         * <p>
         * Inside the box the cut shape and the whole one are the same set of points, so a clip to
         * either shows the same. The cut adds edges of its own along the box, which is why the box
         * is the paper and a margin rather than the paper itself.
         */
        public @NotNull Projected within(final @NotNull PrintRect box) {
            final Envelope e = new Envelope(box.x0(), box.x1(), box.y0(), box.y1());
            return new Projected(cut(printed, e), subject == null ? null : cut(subject, e));
        }

        private static @NotNull Geometry cut(final @NotNull Geometry shape, final @NotNull Envelope box) {
            if (box.covers(shape.getEnvelopeInternal())) {
                return shape;              // all of it is on this paper, as on a print of one sheet
            }
            final Geometry cut = OverlayNGRobust.overlay(shape, Shapes.GF.toGeometry(box), OverlayNG.INTERSECTION);
            // Areas only: where the shape merely touches the box, an overlay returns the line or
            // the point they share as well
            final List<Polygon> parts = new ArrayList<>();
            for (int i = 0; i < cut.getNumGeometries(); i++) {
                if (cut.getGeometryN(i) instanceof final Polygon p && !p.isEmpty()) {
                    parts.add(p);
                }
            }
            return parts.size() == 1 ? parts.getFirst()
                    : shape.getFactory().createMultiPolygon(parts.toArray(new Polygon[0]));
        }
    }

    /**
     * @param projected the shapes the tiles were planned against, for the exporters to clip to
     * @param fade      the fade across the area's growth, for the exporters to lay over the map, or
     *                  null for a hard edge; worked out only when an exporter first asks
     */
    public record Plan(@NotNull SheetLayout layout, @NotNull List<SheetPlan> sheets,
                       @NotNull Projected projected, @Nullable FadeMask fade) {
        public Plan {
            sheets = List.copyOf(sheets);
        }

        /** Every distinct tile the whole job needs; a tile shared by two sheets appears once. */
        public @NotNull Set<TileKey> distinctKeys() {
            final Set<TileKey> all = new LinkedHashSet<>();
            for (final SheetPlan s : sheets) {
                for (final RegionTiles r : s.regions()) {
                    all.addAll(r.keys());
                }
            }
            return all;
        }

        public int placements() {
            return sheets.stream().mapToInt(SheetPlan::tileCount).sum();
        }
    }

    private TilePlan() {
    }

    /** Works out every tile of every sheet. Counting only – nothing is read from disk. */
    public static @NotNull Plan of(final @NotNull ComposeJob job) {
        final SheetLayout layout = job.layout();

        // the geometry of the two regions, in the unit square, projected and prepared once for the
        // whole job; the projection goes on with the plan to the exporters
        final Projected projected = new Projected(printedShape(job, layout),
                job.subject() == null ? null : Shapes.toUnitSquare(job.subject()));
        final PreparedGeometry fill = PreparedGeometryFactory.prepare(projected.printed());
        // Two sides drawn alike are one region, planned once. With the subject in the plan, its
        // ring of tiles belonged to both sides, and an export that clips nothing for a uniform job
        // placed that ring twice. The projection keeps the subject for the stroke.
        final PreparedGeometry subject = projected.subject() == null || job.regions().isUniform() ? null
                : PreparedGeometryFactory.prepare(projected.subject());

        final List<SheetPlan> sheetPlans = new ArrayList<>(layout.count());
        for (final SheetLayout.Sheet sheet : layout.sheets()) {
            final List<RegionTiles> regions = new ArrayList<>(2);
            if (job.regions().inside().drawn()) {
                final RegionTiles t = tilesOf(job, sheet, Side.INSIDE, job.regions().inside(), fill, subject);
                if (!t.keys().isEmpty()) {
                    regions.add(t);
                }
            }
            if (job.regions().outside().drawn()) {
                final RegionTiles t = tilesOf(job, sheet, Side.OUTSIDE, job.regions().outside(), fill, subject);
                if (!t.keys().isEmpty()) {
                    regions.add(t);
                }
            }
            sheetPlans.add(new SheetPlan(sheet, regions));
        }
        return new Plan(layout, sheetPlans, projected, fade(job, layout, projected.printed()));
    }

    /**
     * What a job prints, in the unit square – the shape the plan is made against, for something
     * that shows the print without planning its tiles: the studio's 1:1 preview.
     */
    public static @NotNull Geometry printed(final @NotNull ComposeJob job) {
        return printedShape(job, job.layout());
    }

    /**
     * The job's fade across the growth, or null for a hard edge; not worked out until its grid is
     * asked for.
     *
     * @param printed what the job prints, as {@link #printed} gives it
     */
    public static @Nullable FadeMask fade(final @NotNull ComposeJob job, final @NotNull Geometry printed) {
        return fade(job, job.layout(), printed);
    }

    private static @Nullable FadeMask fade(final @NotNull ComposeJob job, final @NotNull SheetLayout layout,
                                           final @NotNull Geometry printed) {
        return FadeMask.of(job.fadeFrom() == null ? null : Shapes.toUnitSquare(job.fadeFrom()),
                printed, layout.print().mmPerUnit());
    }

    /**
     * Whether a region prints anything in this square: in the printed shape, and on the region's
     * side of the subject.
     */
    private static boolean touches(final @NotNull Geometry square, final @NotNull Side side,
                                   final @NotNull PreparedGeometry fill, final @Nullable PreparedGeometry subject) {
        if (!fill.intersects(square)) {
            return false;
        }
        if (subject == null) {
            return side == Side.INSIDE;
        }
        return side == Side.INSIDE ? subject.intersects(square) : !subject.containsProperly(square);
    }

    /**
     * What is printed, in the unit square: the shape the tiles are chosen against, and – carried
     * on the plan as {@link Projected#printed} – the one both exporters clip an area that is not a
     * rectangle to. One definition, so that the tiles planned and the map on the paper cannot
     * disagree.
     * <p>
     * <b>The spare paper never changes what is printed; it is spent</b> (D34). For a rectangle the
     * spare is more map, so the rectangle grows with the print: the layout grows the print
     * rectangle until the sheets are covered exactly, and were the shape the tiles are chosen
     * against to stay the size it was, the extra band would come out as blank paper – on two A4
     * sheets, a third of the second one.
     * <p>
     * For an outline or a hull the outline stays the edge and the spare is paper, half on each
     * side, so the shape is the area as it stands. Grown with the print, it would cost tiles: the
     * clip keeps the paper right, and every tile in the grown band would be planned and downloaded
     * for nothing.
     * <p>
     * <b>So a rectangle is the layout's print rectangle</b>, whatever grew it: "fill and crop",
     * which takes in more map until the box has the paper's shape, and the spare paper of whole
     * sheets. With the area left as it was, the band fill and crop takes in would have no tiles
     * and print as background – 104 of the 169 tiles under a 4° × 1.4° box printed 300 × 300 mm. And the
     * rectangle is the print's, not the area buffered round, which would reach the paper's corners
     * only by the grace of the diagonal.
     * <p>
     * <b>Unless it is to be printed exactly</b> ({@link ComposeJob#exactArea}): a rectangle drawn
     * by hand is often that rectangle and no more, and then it is its own edge as an outline is,
     * with the spare paper around it and no tiles planned for the band nobody asked for.
     */
    private static @NotNull Geometry printedShape(final @NotNull ComposeJob job,
                                                  final @NotNull SheetLayout layout) {
        if (job.clipsToArea()) {
            return Shapes.toUnitSquare(job.fillShape());
        }
        final PrintRect r = layout.print();
        return square(r.x0(), r.y0(), r.x1(), r.y1());
    }

    private static @NotNull RegionTiles tilesOf(final @NotNull ComposeJob job,
                                                final SheetLayout.@NotNull Sheet sheet,
                                                final @NotNull Side side,
                                                final Regions.@NotNull RegionSpec spec,
                                                final @NotNull PreparedGeometry fill,
                                                final @Nullable PreparedGeometry subject) {
        final int zoom = job.regions().zoom();
        final int n = Mercator.tileCount(zoom);
        final PrintRect slice = sheet.slice();
        final double tileUnit = 1.0 / n;

        // the tiles this sheet covers, from the unit-square slice; ceil-1 on the maximum for the
        // same reason Mercator.range does it – an edge on a boundary must not add a column
        final TileRange range = new TileRange(
                clamp((int) Math.floor(slice.x0() * n), n),
                clamp((int) Math.floor(slice.y0() * n), n),
                clamp((int) Math.ceil(slice.x1() * n) - 1, n),
                clamp((int) Math.ceil(slice.y1() * n) - 1, n));

        // the language decided once, not per tile
        final TileKey origin = job.key(zoom, 0, 0);
        final List<TileKey> keys = new ArrayList<>();
        for (final int[] xy : range) {
            final Geometry onSheet = tilePartOnSheet(xy[0], xy[1], tileUnit, slice);
            if (onSheet == null) {
                continue;         // a neighbor's slice reaches this tile's square; this sheet's does not
            }
            // Which side a tile is on is the whole tile's question, so that "a tile the outline
            // crosses" does not depend on where a sheet is cut; whether this sheet shows any of
            // it is its own part's question, since a tile the page's clip hides would be embedded
            // and counted for nothing
            if (!belongs(xy[0], xy[1], zoom, side, fill, subject)
                    || !touches(onSheet, side, fill, subject)) {
                continue;
            }
            keys.add(origin.at(zoom, xy[0], xy[1]));
        }
        return new RegionTiles(side, zoom, range, keys, spec.effect());
    }

    /**
     * The part of a tile's square that falls on this sheet's own slice, or null when none does.
     * <p>
     * Plain rectangle intersection, not an overlay of the fill or the subject: {@code range} is
     * worked out from the slice already, so a tile in range always reaches it, but two sheets can
     * share a run of tiles along their seam – a wide overlap, or simply a tile the cut runs
     * through – and whether a sheet shows any of such a tile is asked of its own part
     * ({@link #touches}), not the whole square. A tile whose square only touches the slice along an
     * edge, with no real area inside it, belongs to the neighbor whose slice it is really in.
     */
    private static @Nullable Geometry tilePartOnSheet(final int x, final int y, final double tileUnit,
                                                      final @NotNull PrintRect slice) {
        final double x0 = Math.max(x * tileUnit, slice.x0());
        final double y0 = Math.max(y * tileUnit, slice.y0());
        final double x1 = Math.min(x * tileUnit + tileUnit, slice.x1());
        final double y1 = Math.min(y * tileUnit + tileUnit, slice.y1());
        return x1 > x0 && y1 > y0 ? square(x0, y0, x1, y1) : null;
    }

    private static int clamp(final int v, final int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    /**
     * Whether a tile is part of a region.
     * <p>
     * Inside means "within the fill shape and within the subject"; outside means "within the fill
     * shape but not wholly within the subject". A tile straddling the outline satisfies both, which
     * is exactly the ring of tiles that gets clipped twice: without it, a band of background would
     * run along the outline.
     * <p>
     * <b>The fill test is {@code intersects} too.</b> Strict there, it would drop every tile along
     * the print's own edge, since a tile that reaches the edge of a rectangle is not properly inside
     * it – and the edge of the paper would come out blank.
     */
    private static boolean belongs(final int x, final int y, final int zoom, final @NotNull Side side,
                                   final @NotNull PreparedGeometry fill,
                                   final @Nullable PreparedGeometry subject) {
        final Geometry tile = unitTile(zoom, x, y);
        if (!fill.intersects(tile)) {
            return false;
        }
        if (subject == null) {
            return side == Side.INSIDE;
        }
        return side == Side.INSIDE ? subject.intersects(tile) : !subject.containsProperly(tile);
    }

    private static @NotNull Geometry unitTile(final int zoom, final int x, final int y) {
        final double s = 1.0 / Mercator.tileCount(zoom);
        return square(x * s, y * s, x * s + s, y * s + s);
    }

    private static @NotNull Geometry square(final double x0, final double y0, final double x1, final double y1) {
        return Shapes.GF.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(x0, y0),
                new org.locationtech.jts.geom.Coordinate(x1, y0),
                new org.locationtech.jts.geom.Coordinate(x1, y1),
                new org.locationtech.jts.geom.Coordinate(x0, y1),
                new org.locationtech.jts.geom.Coordinate(x0, y0)});
    }

    // -------------------------------------------------------------------------------------------

    /**
     * What the job needs against what the cache holds, reported per region because the two differ.
     * <p>
     * Three ways, not two. A tile the provider has said does not exist – sea, or past the edge of
     * a national provider's coverage – is <b>empty</b>: neither cached nor missing, because there
     * is nothing to fetch and the background is the map there. Counting it as missing refuses a
     * coastal print and marks its sea as holes.
     * <p>
     * {@code wanted} is every distinct tile the plan needs. It is what goes to the
     * downloader, and not {@code missing} alone: the downloader refuses a job that comes to nothing,
     * and a print whose last missing tiles are all sea has not come to nothing.
     */
    public record Inventory(@NotNull List<Entry> byRegion, @NotNull List<TileKey> missing,
                            @NotNull List<TileKey> wanted) {

        public record Entry(@NotNull Side side, int zoom, int needed, int cached, int empty, int missing) {
        }

        public int needed() {
            return byRegion.stream().mapToInt(Entry::needed).sum();
        }

        public int cached() {
            return byRegion.stream().mapToInt(Entry::cached).sum();
        }

        public int empty() {
            return byRegion.stream().mapToInt(Entry::empty).sum();
        }

        public boolean isComplete() {
            return missing.isEmpty();
        }

        /**
         * True when nothing here is real: every tile this needs is known not to exist, and none is
         * cached. Distinct from {@link #isComplete()} being false, which means something is still to
         * fetch – a coastal print, real tiles beside known sea, is complete and never this. This is
         * the plan lying wholly outside the provider's coverage, or asking the wrong provider, theme
         * or place, and exporting it would write nothing but the background, silently – so it is
         * refused under every missing-tile policy ({@code Composer.refuseNothingThere}).
         */
        public boolean nothingThere() {
            return missing.isEmpty() && cached() == 0 && empty() > 0;
        }

        public @NotNull String describe() {
            final StringBuilder sb = new StringBuilder();
            for (final Entry e : byRegion) {
                sb.append(String.format(Locale.ROOT,
                        "  %-7s z%-2d needed %,7d  cached %,7d  empty %,7d  missing %,7d%n",
                        e.side().name().toLowerCase(Locale.ROOT), e.zoom(), e.needed(), e.cached(),
                        e.empty(), e.missing()));
            }
            sb.append(String.format(Locale.ROOT,
                    "  total       needed %,7d  cached %,7d  empty %,7d  missing %,7d",
                    needed(), cached(), empty(), missing.size()));
            return sb.toString();
        }
    }

    /** Counts what is on disk, per region; a tile both regions draw counts with the inside. */
    public static @NotNull Inventory inventory(final @NotNull Plan plan, final @NotNull TileSource tiles) {
        final List<TileKey> missing = new ArrayList<>();
        final java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        final Set<TileKey> seen = new LinkedHashSet<>();

        for (final SheetPlan sp : plan.sheets()) {
            for (final RegionTiles rt : sp.regions()) {
                count(rt.keys(), counts.computeIfAbsent(rt.side() + "/" + rt.zoom(), k -> new int[3]),
                        seen, missing, tiles);
            }
        }
        final List<Inventory.Entry> entries = new ArrayList<>();
        counts.forEach((bucket, c) -> {
            final String[] parts = bucket.split("/");
            entries.add(new Inventory.Entry(Side.valueOf(parts[0]), Integer.parseInt(parts[1]),
                    c[0] + c[1] + c[2], c[0], c[1], c[2]));
        });
        return new Inventory(entries, missing, List.copyOf(seen));
    }

    /** Cached, empty or missing, into {@code c} in that order. */
    private static void count(final @NotNull List<TileKey> keys, final int @NotNull [] c,
                              final @NotNull Set<TileKey> seen, final @NotNull List<TileKey> missing,
                              final @NotNull TileSource tiles) {
        for (final TileKey key : keys) {
            if (!seen.add(key)) {
                continue;             // a tile shared by two sheets is counted once
            }
            if (tiles.isCached(key)) {
                c[0]++;
            } else if (tiles.isKnownEmpty(key)) {
                c[1]++;               // the provider says there is no map here; that is an answer
            } else {
                c[2]++;
                missing.add(key);
            }
        }
    }
}
