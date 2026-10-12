package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.Refusal;
import org.jetbrains.annotations.NotNull;

/**
 * Why a print, a shape or a project file is refused – the checks a person can run into, as opposed
 * to the ones only a bug can reach, which say what they say in English. {@link java.text.MessageFormat}
 * patterns, so an apostrophe is doubled and a brace quoted; a shape is named by its
 * {@link Shapes.Role}.
 *
 * @see Refusal
 */
public enum ComposeReason implements Refusal.Reason {

    // --- the project file: {0} is always the file's name

    NOT_A_PROJECT("{0} is not a WTF project."),
    /** {1} the format it names instead */
    NOT_A_PROJECT_SAYS("{0} is not a WTF project: it says its format is \"{1}\"."),
    /** {1} the field, as the file spells it: "print", "export.formats" */
    FIELD_MISSING("{0} has no \"{1}\", which every project has."),
    /** A refine block naming a Custom shape and a fit both: the area grows from one or the other */
    REFINE_TWO_SOURCES("{0} says its area grows both from a Custom shape and from a fit of the subject."
            + " It grows from one: \"refine\" has \"custom\" or \"simplify\", not both."),

    // --- a shape the file holds: {0} is its role

    POLYGON_NULL("The {0} has a polygon written as null"),
    RING_NULL("The {0} has a ring written as null"),
    COORDINATE_INCOMPLETE("Every coordinate of the {0} needs a longitude and a latitude"),
    NO_POLYGONS("The {0} has no polygons"),
    /** {1} how many points the ring has, as the file writes them */
    RING_TOO_SHORT("The {0} has a ring of {1} points; a ring needs at least three"),

    // --- any shape: {0} is its role

    SHAPE_EMPTY("The {0} is empty"),
    /** {1} what it is instead, in JTS's word for it: "LineString" */
    SHAPE_NOT_AN_AREA("The {0} must be an area, not a {1}"),
    /** {1} and {2} its westmost and eastmost longitude */
    SHAPE_LEAVES_WORLD("The {0} leaves the world: longitude {1}..{2}"),
    /** {1} and {2} the longitudes of the two neighboring points more than half the world apart (D22) */
    SHAPE_CROSSES_ANTIMERIDIAN("The {0} crosses the antimeridian between longitudes {1} and {2}: neighboring"
            + " points more than half the world apart are joined the short way, across ±180°. The map ends"
            + " there, and WTF does not print across it – the print would be two, one at each end of the map."),
    SHAPE_UNREPAIRABLE("The {0} is self-intersecting and could not be repaired"),
    /** {0} the growth, in the unit it was given in */
    REFINING_EMPTIED("Refining emptied the shape – a negative buffer of {0} is too large"),

    // --- a shape imported from a file: {0} is the file's name

    IMPORT_KIND_UNKNOWN("Cannot tell what kind of file this is: {0}. Use .csv, .geojson or .kml."),
    IMPORT_TOO_FEW_COORDINATES("{0} has fewer than three coordinates"),
    IMPORT_NO_POLYGON("{0} holds no Polygon or MultiPolygon"),
    IMPORT_NO_KML_POLYGON("{0} holds no <Polygon>"),

    // --- the print and its paper

    /** A project saved before it had an area: the paper and the map chosen, nothing drawn yet */
    NOTHING_TO_PRINT("The project has no area yet, so there is nothing to print"),
    /**
     * A plan of no tiles at all: neither side drawn, or only the inside of a subject that misses
     * the area. Not {@code NothingThereException}, which is a plan whose every tile does not exist
     */
    NO_MAP_PRINTED("The print has no map in it: neither the map inside the subject nor the map outside it"
            + " is printed where the area is"),
    SIZE_NEEDS_WIDTH("A print that fits a width needs the width"),
    SIZE_NEEDS_HEIGHT("A print that fits a height needs the height"),
    SIZE_NEEDS_BOX("A print that fills or fits inside a box needs both its sides"),
    PROJECTS_TO_NOTHING("The shape projects to an empty rectangle"),
    /** {0} the provider's name, {1} the map's id */
    NO_RETINA("The map {0} / {1} has no @2x tiles"),
    /** {0} the provider's name, {1} the map's id, {2} and {3} its lowest and highest zoom, {4} the print's */
    ZOOM_OUT_OF_RANGE("The map {0} / {1} has zooms {2} to {3}, not {4}"),
    /** {0} and {1} the print's width and height in millimeters */
    PRINT_SIZE_NOT_POSITIVE("Print size must be positive: {0} × {1} mm"),
    /** {0} the width in millimeters */
    ROLL_WIDTH_NOT_POSITIVE("Roll width must be positive: {0} mm"),
    SHEET_SIZE_NOT_POSITIVE("Sheet size must be positive"),
    /** {0} the overlap and {1} the sheet's length along it, in millimeters */
    OVERLAP_TOO_WIDE("An overlap of {0,number,0.0} mm on a {1,number,0.0} mm sheet has no solution"),
    /** {0} the edge in millimeters */
    EDGE_NEGATIVE("The printer''s unprinted edge cannot be negative: {0} mm"),
    STROKE_NOT_POSITIVE("Stroke width must be positive"),
    /** {0} saturation, {1} lightness, {2} contrast */
    EFFECT_OUT_OF_RANGE("Effect out of range: saturation {0}, lightness {1}, contrast {2}"),

    // --- a raster that cannot be written: {0} is the sheet's number

    /** {1} and {2} the width and height in pixels, {3} their product in gigapixels */
    RASTER_TOO_LARGE("Sheet {0} would be {1} × {2} px ({3,number,0.00} Gpx), past what a Java raster can "
            + "index. Use more sheets, a lower zoom, or a PDF."),
    /** {1} the size uncompressed, in gigabytes */
    TIFF_TOO_LARGE("Sheet {0} would be about {1,number,0.0} GB as TIFF, past the 4 GB a classic TIFF can "
            + "address, and this build has no BigTIFF writer. Use PNG, a PDF, more sheets or a lower zoom."),

    // --- a tile no decoder can read: {0} is its key, as every tile error names it

    /** {1} its format, as its bytes say: {@code WEBP} (see {@code TileSamples.decode}) */
    TILE_UNREADABLE("WTF cannot read tile {0} (format {1}), so it cannot be printed. Choose another map.");

    private final String english;

    ComposeReason(final @NotNull String english) {
        this.english = english;
    }

    @Override
    public @NotNull String english() {
        return english;
    }
}
