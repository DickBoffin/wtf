package net.trdlo.wtf.compose;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.Refusal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code .wtf} project: one JSON object describing a print, never any imagery.
 * <p>
 * Everything derivable is absent – no DPI, no sheet layout, no tile list – because storing it would
 * mean storing something that can disagree with the rest of the file. See
 * docs/design/project-file.md.
 * <p>
 * The numbers and switches every project has are boxed in these records, never primitive: one that
 * a file leaves out arrives as null, which {@link #read} refuses by name, instead of as a zero that
 * nobody chose – a zoom of zero, no overlap, no credit on the print.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WtfFile(
        @NotNull String format,
        @Nullable String title,
        @Nullable String notes,
        @Nullable String saved,
        @NotNull Source source,
        /*
         * What gets printed, and the one answer every reader takes as given: the subject fitted
         * and grown, or a Custom shape grown – a box, or a real country outline with islands and
         * holes, which is why this is shaped like the subject. Null in a project that has none
         * yet – the paper, the map and the zoom chosen, nothing drawn – which the editor opens as
         * it was left and nothing can print.
         */
        @Nullable MultiRing area,
        @Nullable MultiRing subject,
        @NotNull Refine refine,
        @NotNull RegionsJson regions,
        @NotNull PrintJson print,
        @NotNull MediumJson medium,
        @NotNull ExportJson export) {

    public static final String FORMAT = "wtf";

    /** @param lang the map language, which a file may leave out: see {@link #langFor} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(@NotNull String provider, @NotNull String theme, @Nullable String lang) {

        /**
         * The map language the file means with this provider: the one it names, or – where it names
         * none – the provider's own (D40), the first its definition lists or English. The command
         * line and the editor both ask this, so that a file prints the same wherever it is opened,
         * whatever the system's language or the one last used with the provider.
         */
        public @NotNull String langFor(final @NotNull Provider p) {
            return lang != null ? lang : p.defaultLanguage();
        }
    }

    /**
     * Polygons, each an array of rings, the first the shell and the rest holes, each ring an array
     * of {@code [lon, lat]} pairs – so four levels of nesting, which is what a real country outline
     * with islands and enclaves actually needs.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultiRing(@Nullable String kind, @Nullable String source,
                            @NotNull List<List<List<List<Double>>>> polygons) { }

    /**
     * How the editor arrived at the area: worked out from the subject with a fit, or grown from a
     * Custom shape, and by how much either way – or neither, and the area stands as given.
     * <p>
     * <b>Provenance, not instructions.</b> The {@code area} above is the shape that gets printed and
     * every reader takes it as given. This records how the editor arrived at it, so that the editor
     * can work it out again and can check on open that it still would ({@link #checkArea}). No
     * reader applies it to the area: the area is already its result, and growing a grown area grows
     * it twice.
     * <p>
     * <b>Except the fade.</b> It is about the growth, so it lives beside it, and it is the one field
     * here a reader acts on: the edge it starts from is the shape before the growth – the subject's
     * fit, or the Custom shape made one – worked out again with {@link Shapes#fitOf}, as
     * {@link #checkArea} works out the whole area again. The area itself is still taken as given.
     * <p>
     * <b>One of the two, or neither.</b> A file with both a Custom shape and a fit is refused as it
     * is read. With neither, nothing was worked out and the area stands as given, which the editor
     * opens as a Custom area of that shape, grown by nothing – the line a refusal of a file that
     * does not reproduce its area says to delete.
     *
     * @param custom      the shape the area was grown from, drawn, picked or imported rather than
     *                    worked out from the subject; its {@code source} says where it came from
     * @param simplify    the fit the area was worked out from the subject with
     * @param bufferValue the growth, in meters on the ground; negative shrinks. Required beside
     *                    either of the two
     * @param fade        true when the map fades out across the growth, from the shape the area
     *                    grows from to the edge of the area ({@link ComposeJob#fadeFrom}); left out
     *                    otherwise. Nothing fades under a growth of zero or less, which has no band
     *                    to fade across
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Refine(@Nullable MultiRing custom, @Nullable Shapes.Simplify simplify,
                         @Nullable Double bufferValue, @Nullable Boolean fade) {

        /** Nothing worked out: the area, if there is one, stands as given. */
        public static final Refine NONE = new Refine(null, null, null, null);

        /** Whether the map fades: asked for, and a growth to fade across. */
        public boolean fades() {
            return Boolean.TRUE.equals(fade) && growth() > 0;
        }

        /** The growth, in meters on the ground; nothing where the file names none. */
        public double growth() {
            return bufferValue == null ? 0 : bufferValue;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EffectJson(@NotNull Double saturation, @NotNull Double lightness, @NotNull Double contrast) {
        public static @NotNull EffectJson of(final @NotNull TileEffect e) {
            return new EffectJson(e.saturation(), e.lightness(), e.contrast());
        }

        public @NotNull TileEffect toEffect() {
            return new TileEffect(saturation, lightness, contrast);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegionJson(@NotNull Boolean drawn, @NotNull EffectJson effect) { }

    /**
     * The subject's outline: its color, and the line the print draws along it.
     *
     * @param drawn whether the print strokes the outline. The block is there whenever there is a
     *              subject, stroked or not, because its color is the subject's on the map as well,
     *              and a project saved with the stroke off keeps it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StrokeJson(@NotNull Boolean drawn, @NotNull Double widthMm, @NotNull String color) { }

    /**
     * @param zoom  the zoom of the whole print, both regions alike
     * @param scale 2 for the zoom's {@code @2x} tiles; left out for the usual ones
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegionsJson(@NotNull Integer zoom, @Nullable Integer scale, @NotNull RegionJson inside,
                              @NotNull RegionJson outside, @Nullable StrokeJson boundaryStroke) { }

    /**
     * How big the print is.
     *
     * @param sizeMode how the size is stated, with the two lengths beside it: the size every reader
     *                 prints, as it stands. When the print was asked for in sheets, at a DPI or at a
     *                 scale, it is what those come to, so a reader that knows nothing of them prints
     *                 the same print.
     * @param setAside what the size step holds for a size given in millimeters, while the print is
     *                 sized in sheets, at a DPI or at a scale instead – kept so that going back to millimeters
     *                 finds it, and read by the editor only. Null when the print is sized in
     *                 millimeters, because then it is the size above.
     * @param dpi      the DPI the print was asked for at, the width above being what the area comes
     *                 to at it from tiles of the print's zoom ({@link PrintSize#atDpi}); read by the
     *                 editor only, and null otherwise
     * @param mapScale the denominator of the scale the print was asked for at, 50000 for 1:50 000,
     *                 the width above being what the area comes to at it ({@link PrintSize#atScale});
     *                 read by the editor only, and null otherwise
     * @param exactArea true when a rectangular area is printed to its edge and the spare paper stays
     *                 paper ({@link ComposeJob#exactArea}); left out otherwise
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrintJson(@NotNull PrintSize.Mode sizeMode, @Nullable Double widthMm, @Nullable Double heightMm,
                            @Nullable String backgroundColor, @Nullable SizeJson setAside,
                            @Nullable Integer dpi, @Nullable Integer mapScale,
                            @Nullable Boolean exactArea) { }

    /** A size as the size step states it: the mode, and the lengths it asks for, absent when unused. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SizeJson(@NotNull PrintSize.Mode sizeMode, @Nullable Double widthMm, @Nullable Double heightMm) {
        public static @NotNull SizeJson of(final @NotNull PrintSize s) {
            return new SizeJson(s.mode(), s.widthOrNull(), s.heightOrNull());
        }
    }

    /**
     * @param edgeMm       how much the printer leaves white at the edge of the paper, which the
     *                     outermost sheets keep free round the print. The project's, not the
     *                     machine's: it decides the layout, and the file has to mean one print
     * @param sheetsAcross how many sheets the print was asked for across, when it was asked for in
     *                     sheets rather than in millimeters; null otherwise
     * @param sheetsDown   the same, down. At most one of the two is ever set: a count on one axis
     *                     and the area's own shape decide the other.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MediumJson(@NotNull Kind kind, @Nullable Double rollWidthMm, @Nullable Medium.Axis stripAxis,
                             @Nullable Double sheetWidthMm, @Nullable Double sheetHeightMm,
                             @Nullable Medium.Orientation orientation, @NotNull Double overlapMm,
                             @NotNull Double edgeMm,
                             @Nullable Integer sheetsAcross, @Nullable Integer sheetsDown) {

        /** A roll cut into strips, or sheets tiled both ways. */
        public enum Kind { ROLL, SHEET }
    }

    /**
     * @param credit whether the provider's credit is printed in the print's bottom-right corner
     * @param scale  how the map scale is printed in the print's bottom-left corner; null for not at all
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportJson(@NotNull List<ComposeJob.OutputFormat> formats,
                             @NotNull ComposeJob.OutputGrouping grouping,
                             @Nullable String outputDir, @NotNull String baseName,
                             @Nullable MissingTilePolicy missingTiles, @NotNull Boolean credit,
                             @Nullable ScaleMark scale) { }

    // -------------------------------------------------------------------------------------------

    private static @NotNull ObjectMapper mapper() {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                // A mode is its name. Without this "sizeMode": 2 – and even "2" – is the third one.
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Reads a project, or refuses it and says why.
     *
     * @throws IOException              when the file cannot be read, is not JSON, or holds a value
     *                                  that its field does not take, such as a size mode this build
     *                                  does not know – Jackson's own exception, which names the field
     * @throws Refusal                  when it is refused: JSON of some other kind, or a project with
     *                                  something missing that every project has. Its reason names
     *                                  what is wrong, in English here and from the bundle in a window.
     */
    public static @NotNull WtfFile read(final @NotNull Path file) throws IOException {
        return read(Files.readAllBytes(file), String.valueOf(file.getFileName()));
    }

    /**
     * {@link #read(Path)} of a project that is not a file of its own – one of the examples the
     * studio bundles – named in a refusal by {@code name}, as a file is by its file name.
     */
    public static @NotNull WtfFile read(final byte @NotNull [] json, final @NotNull String name) throws IOException {
        final WtfFile f = mapper().readValue(json, WtfFile.class);
        if (!FORMAT.equals(f.format())) {
            throw f.format() == null
                    ? new Refusal(ComposeReason.NOT_A_PROJECT, name)
                    : new Refusal(ComposeReason.NOT_A_PROJECT_SAYS, name, f.format());
        }
        // After the format: a JSON file of another kind is not a project with its blocks missing
        f.requireComplete(name);
        return f;
    }

    /**
     * Refuses a file that leaves out something no project is without, naming it – rather than letting
     * it through to fail on the missing value later, in whatever reads it first, with nothing to say
     * what was missing, or to be read as a zero or an "off" that nobody chose.
     * <p>
     * Not what has a default that means something: a map language (the provider's own, D40), a
     * background, the way a strip runs or a sheet is turned, what to do about a missing tile. Those
     * may be left out.
     *
     * @param name the file's name, as the refusal says it
     */
    private void requireComplete(final @NotNull String name) {
        final Source s = need(name, source, "source");
        need(name, s.provider(), "source.provider");
        need(name, s.theme(), "source.theme");
        if (area != null) {
            need(name, area.polygons() == null || area.polygons().isEmpty() ? null : area, "area.polygons");
        }
        if (subject != null) {
            need(name, subject.polygons(), "subject.polygons");
        }

        final Refine r = need(name, refine, "refine");
        if (r.custom() != null && r.simplify() != null) {
            throw new Refusal(ComposeReason.REFINE_TWO_SOURCES, name);
        }
        if (r.custom() != null) {
            need(name, r.custom().polygons() == null || r.custom().polygons().isEmpty() ? null : r.custom(),
                    "refine.custom.polygons");
        }
        if (r.custom() != null || r.simplify() != null) {
            need(name, r.bufferValue(), "refine.bufferValue");
        }

        final RegionsJson rs = need(name, regions, "regions");
        need(name, rs.zoom(), "regions.zoom");
        region(name, rs.inside(), "regions.inside");
        region(name, rs.outside(), "regions.outside");
        if (rs.boundaryStroke() != null) {
            need(name, rs.boundaryStroke().drawn(), "regions.boundaryStroke.drawn");
            need(name, rs.boundaryStroke().widthMm(), "regions.boundaryStroke.widthMm");
            need(name, rs.boundaryStroke().color(), "regions.boundaryStroke.color");
        }

        final PrintJson p = need(name, print, "print");
        need(name, p.sizeMode(), "print.sizeMode");
        if (p.setAside() != null) {
            need(name, p.setAside().sizeMode(), "print.setAside.sizeMode");
        }

        final MediumJson m = need(name, medium, "medium");
        if (need(name, m.kind(), "medium.kind") == MediumJson.Kind.ROLL) {
            need(name, m.rollWidthMm(), "medium.rollWidthMm");
        } else {
            need(name, m.sheetWidthMm(), "medium.sheetWidthMm");
            need(name, m.sheetHeightMm(), "medium.sheetHeightMm");
        }
        need(name, m.overlapMm(), "medium.overlapMm");
        need(name, m.edgeMm(), "medium.edgeMm");

        final ExportJson e = need(name, export, "export");
        need(name, e.formats() == null || e.formats().stream().anyMatch(Objects::isNull) ? null : e,
                "export.formats");
        need(name, e.grouping(), "export.grouping");
        need(name, e.baseName(), "export.baseName");
        need(name, e.credit(), "export.credit");
    }

    /** One region's block: whether it is drawn, and the three values of its treatment. */
    private static void region(final @NotNull String file, final @Nullable RegionJson given,
                               final @NotNull String where) {
        final RegionJson r = need(file, given, where);
        need(file, r.drawn(), where + ".drawn");
        final EffectJson e = need(file, r.effect(), where + ".effect");
        need(file, e.saturation(), where + ".effect.saturation");
        need(file, e.lightness(), where + ".effect.lightness");
        need(file, e.contrast(), where + ".effect.contrast");
    }

    private static <T> @NotNull T need(final @NotNull String file, final @Nullable T value,
                                       final @NotNull String what) {
        if (value == null) {
            throw new Refusal(ComposeReason.FIELD_MISSING, file, what);
        }
        return value;
    }

    /**
     * Writes the project, all of it or none of it.
     * <p>
     * Into {@code <name>.part} first, and over the file only once that is complete – the way the
     * exports write theirs. Writing straight into the file would truncate it first, so a crash, a
     * full disk or a killed process in the middle would leave neither the old project nor the new
     * one.
     * <p>
     * <b>On the disk before it replaces the file.</b> The system may put the rename on the disk
     * before the bytes it names, and a power cut between the two would leave a file of nothing where
     * the project had been – the old one replaced, the new one never written. So the part is forced
     * to the disk first: for a save, and for the studio's recovery copy of unsaved work, which is
     * written this way to outlive exactly that.
     */
    public void write(final @NotNull Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        final Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            mapper().writeValue(part.toFile(), this);
            try (FileChannel written = FileChannel.open(part, StandardOpenOption.WRITE)) {
                written.force(true);
            }
        } catch (final IOException | RuntimeException ex) {
            Files.deleteIfExists(part);
            throw ex;
        }
        OutputFiles.replace(part, file);
    }

    // -------------------------------------------------------------------------------------------

    /** Turns the stored description into a job the {@link Composer} can run. */
    public @NotNull ComposeJob toJob(final @NotNull ProviderCatalog catalog, final @NotNull Path defaultOutput) {
        final Provider provider = catalog.require(source.provider());
        provider.requireTheme(source.theme());

        final Geometry stated = areaGeometry();
        if (stated == null) {
            throw new Refusal(ComposeReason.NOTHING_TO_PRINT);
        }
        final Geometry areaShape = Shapes.validate(stated, Shapes.Role.AREA);
        final Geometry subjectShape = subjectGeometry() == null ? null
                : Shapes.validate(subjectGeometry(), Shapes.Role.SUBJECT);

        // The area is the printed area, full stop. The refine block says how it was reached and
        // is not applied here: the area is already its result.
        final Geometry fill = areaShape;
        // Except for the edge a fade starts from, which the area does not say: see Refine
        final Geometry fadeFrom = refine.fades() ? grownFrom(subjectShape) : null;

        // The size as it stands. A count of sheets was turned into this when the file was written,
        // and print.setAside is the editor's alone.
        final PrintSize size = new PrintSize(print.sizeMode(),
                print.widthMm() == null ? 0 : print.widthMm(),
                print.heightMm() == null ? 0 : print.heightMm());
        final PrintRect rect = size.apply(Shapes.boundsOf(fill));

        final Medium med = medium.kind() == MediumJson.Kind.ROLL
                ? new Medium.Roll(require(medium.rollWidthMm(), "rollWidthMm"),
                medium.stripAxis() == null ? Medium.Axis.VERTICAL : medium.stripAxis())
                : new Medium.CutSheet(require(medium.sheetWidthMm(), "sheetWidthMm"),
                require(medium.sheetHeightMm(), "sheetHeightMm"),
                medium.orientation() == null ? Medium.Orientation.PORTRAIT : medium.orientation());

        final Set<ComposeJob.OutputFormat> formats = EnumSet.noneOf(ComposeJob.OutputFormat.class);
        formats.addAll(export.formats());

        return new ComposeJob(fill, subjectShape, provider, source.theme(), source.langFor(provider),
                toRegions(), rect, med, medium.overlapMm(), Boolean.TRUE.equals(print.exactArea()),
                fadeFrom, parseColor(print.backgroundColor(), 0xFFFFFF),
                export.missingTiles() == null ? MissingTilePolicy.REFUSE : export.missingTiles(),
                formats, export.grouping(),
                export.outputDir() == null ? defaultOutput : Path.of(export.outputDir()),
                export.baseName(),
                // The file carries the credit and the printer's white edge, which decides the
                // layout; the seam marks are the printing machine's business and live in its
                // settings, so the command line prints none of them. A label switched on after
                // all is in English and names the file's title.
                new Marks(medium.edgeMm(), false, false, false, export.credit(), export.scale(),
                        title == null ? "" : title, Marks.today()),
                // And the layout and the attribution beside the print are in English: the command
                // line has no language
                FileWords.ENGLISH);
    }

    // -------------------------------------------------------------------------------------------
    // Geometry in and out, so the GUI and the CLI cannot disagree about what a ring means
    // -------------------------------------------------------------------------------------------

    /** What gets printed, as the file states it; null when the project has no area yet. */
    public @Nullable Geometry areaGeometry() {
        return area == null ? null : toMultiPolygon(area.polygons(), Shapes.Role.AREA);
    }

    public @Nullable Geometry subjectGeometry() {
        return subject == null || subject.polygons().isEmpty() ? null
                : toMultiPolygon(subject.polygons(), Shapes.Role.SUBJECT);
    }

    /**
     * The Custom shape the area was grown from, as the file states it; null when the file names
     * none. Checked as it is read, as the area is: it is the area's shape before the growth.
     */
    public @Nullable Geometry customGeometry() {
        return refine == null || refine.custom() == null ? null
                : toMultiPolygon(refine.custom().polygons(), Shapes.Role.AREA);
    }

    /**
     * The shape the area grows from, before the growth: the Custom shape made one, or the subject's
     * fit – the edge a fade across the growth starts from. Null when the file names neither, or a
     * fit and no subject to take it of.
     *
     * @param subjectShape the subject as the job has it
     */
    private @Nullable Geometry grownFrom(final @Nullable Geometry subjectShape) {
        final Geometry custom = customGeometry();
        if (custom != null) {
            return Shapes.fitOf(custom, Shapes.Simplify.NONE);
        }
        return refine.simplify() != null && subjectShape != null
                ? Shapes.fitOf(subjectShape, refine.simplify()) : null;
    }

    /**
     * Whether a file that says how its area was worked out still works out that way.
     *
     * @param claimed         false when the file names neither a Custom shape nor a fit with a
     *                        subject to take it of, in which case nothing was checked and the other
     *                        fields mean nothing
     * @param deviationMeters how far apart the stored area and the recomputed one are at their
     *                        worst, on the ground
     */
    public record AreaCheck(boolean claimed, boolean reproduces, double deviationMeters,
                            @Nullable LonLatBounds stored, @Nullable LonLatBounds recomputed) {

        public static @NotNull AreaCheck notClaimed() {
            return new AreaCheck(false, true, 0, null, null);
        }
    }

    /**
     * Works the area out again – from the Custom shape, or from the subject with the fit – and asks
     * whether it comes out the same.
     * <p>
     * Only the editor acts on the answer. It is the only thing that will ever rewrite the file, so
     * it is the only thing that has to know the provenance is still true; a reader that just
     * prints has the area right there and nothing to gain by doubting it.
     */
    public @NotNull AreaCheck checkArea() {
        // Every shape read, and refused if it has to be, whichever of them the check needs
        final Geometry subjectShape = subjectGeometry();
        final Geometry custom = customGeometry();
        final Geometry stated = areaGeometry();
        final Geometry base = custom != null ? custom : refine.simplify() != null ? subjectShape : null;
        // No area stated, nothing claimed about one
        if (base == null || stated == null) {
            return AreaCheck.notClaimed();
        }
        final Geometry stored = Shapes.validate(stated, Shapes.Role.AREA);
        final Geometry again = Shapes.areaFrom(base,
                custom != null ? Shapes.Simplify.NONE : refine.simplify(), refine.growth());
        return new AreaCheck(true, Shapes.reproduces(stored, again),
                Shapes.deviationMeters(stored, again),
                Shapes.boundsOf(stored), Shapes.boundsOf(again));
    }

    /** Every polygon, shell first then holes – the four levels a real outline needs. */
    public static @NotNull MultiRing multiRingOf(final @NotNull Geometry g, final @Nullable String kind,
                                                 final @Nullable String origin) {
        final List<List<List<List<Double>>>> polygons = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (!(g.getGeometryN(i) instanceof final Polygon p)) {
                continue;
            }
            final List<List<List<Double>>> rings = new ArrayList<>(1 + p.getNumInteriorRing());
            rings.add(pairsOf(p.getExteriorRing()));
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                rings.add(pairsOf(p.getInteriorRingN(h)));
            }
            polygons.add(rings);
        }
        if (polygons.isEmpty()) {
            throw new IllegalArgumentException("Not an area: " + g.getGeometryType());
        }
        return new MultiRing(kind, origin, polygons);
    }

    private static @NotNull List<List<Double>> pairsOf(final @NotNull LineString ring) {
        final Coordinate[] cs = ring.getCoordinates();
        final List<List<Double>> out = new ArrayList<>(cs.length);
        for (final Coordinate c : cs) {
            out.add(List.of(c.x, c.y));
        }
        return out;
    }

    private @NotNull Regions toRegions() {
        return new Regions(regions.zoom(), regions.scale() == null ? 1 : regions.scale(),
                new Regions.RegionSpec(regions.inside().effect().toEffect(), regions.inside().drawn()),
                new Regions.RegionSpec(regions.outside().effect().toEffect(), regions.outside().drawn()),
                // The block keeps the subject's color while the stroke is off; only a drawn one is printed
                regions.boundaryStroke() == null || !regions.boundaryStroke().drawn() ? null
                        : new Regions.BoundaryStroke(regions.boundaryStroke().widthMm(),
                        parseColor(regions.boundaryStroke().color(), 0xFFA94D)));
    }

    private static double require(final @Nullable Double v, final @NotNull String what) {
        if (v == null) {
            throw new IllegalArgumentException("medium." + what + " is required");
        }
        return v;
    }

    static int parseColor(final @Nullable String s, final int fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16);
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * The polygons of a shape the file holds, refusing – with a message, not a null pointer – one
     * that has a polygon, a ring or a coordinate written as null, and one that leaves the world or
     * crosses the antimeridian ({@link Shapes#checkExtent}).
     * <p>
     * <b>Checked as it is read, by every reader alike</b>, as D22 has it. The editor opening a
     * project whose area grows from a Custom shape checks its subject too, rather than taking it in
     * unchecked for the export to refuse, as the command line refuses the file outright.
     *
     * @param what which of the two it is, for the message
     */
    private static @NotNull Geometry toMultiPolygon(final @NotNull List<List<List<List<Double>>>> polygons,
                                                    final Shapes.@NotNull Role what) {
        final List<Polygon> out = new ArrayList<>(polygons.size());
        for (final List<List<List<Double>>> poly : polygons) {
            if (poly == null) {
                throw new Refusal(ComposeReason.POLYGON_NULL, what);
            }
            if (poly.isEmpty()) {
                continue;
            }
            final LinearRing shell = ring(poly.getFirst(), what);
            final LinearRing[] holes = new LinearRing[poly.size() - 1];
            for (int i = 1; i < poly.size(); i++) {
                holes[i - 1] = ring(poly.get(i), what);
            }
            out.add(Shapes.GF.createPolygon(shell, holes));
        }
        if (out.isEmpty()) {
            throw new Refusal(ComposeReason.NO_POLYGONS, what);
        }
        final Geometry shape = out.size() == 1 ? out.getFirst()
                : Shapes.GF.createMultiPolygon(out.toArray(new Polygon[0]));
        Shapes.checkExtent(shape, what);
        return shape;
    }

    /**
     * One ring, closed, refused by name when it is too short to be one. JTS would refuse it too,
     * but in its own English – "Invalid number of points in LinearRing" – which no bundle says.
     * Three points make a ring; a file may write the first point again at the end or not.
     */
    private static @NotNull LinearRing ring(final @Nullable List<List<Double>> pairs, final Shapes.@NotNull Role what) {
        final Coordinate[] given = toCoordinates(pairs, what);
        final Coordinate[] cs = closed(given);
        if (cs.length < 4) {
            throw new Refusal(ComposeReason.RING_TOO_SHORT, what, given.length);
        }
        return Shapes.GF.createLinearRing(cs);
    }

    private static @NotNull Coordinate @NotNull [] toCoordinates(final @Nullable List<List<Double>> pairs,
                                                                 final Shapes.@NotNull Role what) {
        if (pairs == null) {
            throw new Refusal(ComposeReason.RING_NULL, what);
        }
        final Coordinate[] cs = new Coordinate[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            final List<Double> p = pairs.get(i);
            if (p == null || p.size() < 2 || p.get(0) == null || p.get(1) == null) {
                throw new Refusal(ComposeReason.COORDINATE_INCOMPLETE, what);
            }
            cs[i] = new Coordinate(p.get(0), p.get(1));
        }
        return cs;
    }

    private static @NotNull Coordinate @NotNull [] closed(final @NotNull Coordinate @NotNull [] cs) {
        if (cs.length >= 2 && !cs[0].equals2D(cs[cs.length - 1])) {
            final Coordinate[] out = new Coordinate[cs.length + 1];
            System.arraycopy(cs, 0, out, 0, cs.length);
            out[cs.length] = new Coordinate(cs[0]);
            return out;
        }
        return cs;
    }

    // -------------------------------------------------------------------------------------------

    /**
     * A minimal project, for the CLI's {@code sample} command and for tests. It names no map
     * language, so a map lettered in one is in its provider's own ({@link Source#langFor}) – the
     * language {@code wtf-tiles} downloads it in without {@code --lang}.
     */
    public static @NotNull WtfFile sample(final @NotNull String provider, final @NotNull String theme,
                                               final double west, final double south,
                                               final double east, final double north,
                                               final int zoom, final double widthMm) {
        final List<List<Double>> ring = List.of(
                List.of(west, north), List.of(east, north), List.of(east, south),
                List.of(west, south), List.of(west, north));
        return new WtfFile(FORMAT, "Sample", null, Instant.now().toString(),
                new Source(provider, theme, null),
                // The sample's area is a rectangle somebody stated, not one worked out from a
                // subject it does not have, so nothing is worked out: it stands as given.
                new MultiRing("rectangle", null, List.of(List.of(ring))), null,
                Refine.NONE,
                new RegionsJson(zoom, null,
                        new RegionJson(true, EffectJson.of(TileEffect.NONE)),
                        new RegionJson(true, EffectJson.of(TileEffect.NONE)), null),
                new PrintJson(PrintSize.Mode.FIT_WIDTH, widthMm, null, "#FFFFFF", null, null, null, null),
                new MediumJson(MediumJson.Kind.ROLL, 915.0, Medium.Axis.VERTICAL, null, null, null, 15.0,
                        Marks.DEFAULT_EDGE_MM, null, null),
                new ExportJson(List.of(ComposeJob.OutputFormat.PDF),
                        ComposeJob.OutputGrouping.PER_SHEET, null, "sample",
                        MissingTilePolicy.REFUSE, true, null));
    }
}
