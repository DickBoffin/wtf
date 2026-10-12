package net.trdlo.wtf.studio;

import net.trdlo.wtf.compose.ComposeReason;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.tiles.DownloadReason;
import net.trdlo.wtf.tiles.ProviderReason;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileDownloader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What the engine refused, in the interface language.
 * <p>
 * The engine says it in English, for the command line; a {@link Refusal} carries its reason and
 * its values beside that, and this says the reason from the bundle, as {@link ProgressText} says
 * the engine's phrases. The switches have no default, so a reason added to the engine without a
 * sentence here does not compile. Anything that is not a refusal is shown as the engine said it.
 */
public final class EngineText {

    private EngineText() {
    }

    /** What went wrong, for a label or a dialog: a refusal translated, anything else its message. */
    public static @NotNull String of(final @NotNull Throwable ex) {
        return switch (ex) {
            case Refusal r -> say(r);
            case TileDownloader.BulkDownloadRefusedException b -> b.provider().quotaNote() == null
                    ? I18n.format("download.refused.body", b.provider().displayName(),
                    I18n.integer(b.tiles()), I18n.integer(TileDownloader.BROWSE_LIMIT))
                    : I18n.format("download.refused.bodyWithNote", b.provider().displayName(),
                    I18n.integer(b.tiles()), I18n.integer(TileDownloader.BROWSE_LIMIT), b.provider().quotaNote());
            default -> ex.getMessage() == null ? ex.toString() : ex.getMessage();
        };
    }

    public static @NotNull String say(final @NotNull Refusal r) {
        final String key = key(r.reason());
        if (key == null) {
            return r.getMessage();
        }
        final Object[] args = r.args().stream().map(EngineText::arg).toArray();
        // A length on paper is millimeters to the engine and whatever unit the window uses here
        for (final int[] length : lengths(r.reason())) {
            if (args[length[0]] instanceof final Number mm) {
                args[length[0]] = Units.current().length(mm.doubleValue(), length[1]);
            }
        }
        return I18n.format(key, args);
    }

    /**
     * The values of a refusal that are lengths on paper, each with the decimals it would have in
     * millimeters. Their sentences in the bundle take the unit with the value; the engine's English
     * writes "mm" after it.
     */
    static int @NotNull [] @NotNull [] lengths(final Refusal.@NotNull Reason reason) {
        if (!(reason instanceof final ComposeReason c)) {
            return new int[0][];
        }
        return switch (c) {
            case PRINT_SIZE_NOT_POSITIVE -> new int[][]{{0, 0}, {1, 0}};
            case ROLL_WIDTH_NOT_POSITIVE -> new int[][]{{0, 0}};
            case OVERLAP_TOO_WIDE -> new int[][]{{0, 1}, {1, 1}};
            case EDGE_NEGATIVE -> new int[][]{{0, 1}};
            default -> new int[0][];
        };
    }

    /** The shape's name, for the one sentence said of either; numbers are the bundle's to format. */
    private static @Nullable Object arg(final @Nullable Object a) {
        return a instanceof final Shapes.Role role ? switch (role) {
            case AREA -> I18n.get("engine.role.area");
            case SUBJECT -> I18n.get("engine.role.subject");
        } : a;
    }

    /** The bundle key a reason is said by, or null for a reason from a module this does not know. */
    static @Nullable String key(final Refusal.@NotNull Reason reason) {
        return switch (reason) {
            case ProviderReason p -> key(p);
            case ComposeReason c -> key(c);
            case DownloadReason d -> key(d);
            default -> null;
        };
    }

    private static @NotNull String key(final @NotNull DownloadReason d) {
        return switch (d) {
            case REJECTED_WITHOUT_KEY -> "engine.download.rejectedWithoutKey";
        };
    }

    private static @NotNull String key(final @NotNull ProviderReason p) {
        return switch (p) {
            case ID_UNFIT -> "engine.provider.idUnfit";
            case HEADER_NAME_MISSING -> "engine.provider.headerNameMissing";
            case HEADER_VALUE_WITHOUT_KEY -> "engine.provider.headerValueWithoutKey";
            case NAME_MISSING -> "engine.provider.nameMissing";
            case ATTRIBUTION_MISSING -> "engine.provider.attributionMissing";
            case NO_MAPS -> "engine.provider.noMaps";
            case MAP_ID_UNFIT -> "engine.provider.mapIdUnfit";
            case MAP_ID_TWICE -> "engine.provider.mapIdTwice";
            case ADDRESS_NOT_HTTP -> "engine.provider.addressNotHttp";
            case ADDRESS_WITHOUT_TOKEN -> "engine.provider.addressWithoutToken";
            case ADDRESS_UNKNOWN_TOKEN -> "engine.provider.addressUnknownToken";
            case ADDRESS_WITHOUT_KEY -> "engine.provider.addressWithoutKey";
            case ZOOM_OUT_OF_RANGE -> "engine.provider.zoomOutOfRange";
            case MAP_ZOOM_OUT_OF_RANGE -> "engine.provider.mapZoomOutOfRange";
            case SUBDOMAIN_BLANK -> "engine.provider.subdomainBlank";
            case ZOOMS_INVERTED -> "engine.provider.zoomsInverted";
            case LANGUAGE_UNFIT -> "engine.provider.languageUnfit";
        };
    }

    private static @NotNull String key(final @NotNull ComposeReason c) {
        return switch (c) {
            case NOT_A_PROJECT -> "engine.compose.notAProject";
            case NOT_A_PROJECT_SAYS -> "engine.compose.notAProjectSays";
            case FIELD_MISSING -> "engine.compose.fieldMissing";
            case REFINE_TWO_SOURCES -> "engine.compose.refineTwoSources";
            case POLYGON_NULL -> "engine.compose.polygonNull";
            case RING_NULL -> "engine.compose.ringNull";
            case COORDINATE_INCOMPLETE -> "engine.compose.coordinateIncomplete";
            case NO_POLYGONS -> "engine.compose.noPolygons";
            case RING_TOO_SHORT -> "engine.compose.ringTooShort";
            case SHAPE_EMPTY -> "engine.compose.shapeEmpty";
            case SHAPE_NOT_AN_AREA -> "engine.compose.shapeNotAnArea";
            case SHAPE_LEAVES_WORLD -> "engine.compose.shapeLeavesWorld";
            case SHAPE_CROSSES_ANTIMERIDIAN -> "engine.compose.shapeCrossesAntimeridian";
            case SHAPE_UNREPAIRABLE -> "engine.compose.shapeUnrepairable";
            case REFINING_EMPTIED -> "engine.compose.refiningEmptied";
            case IMPORT_KIND_UNKNOWN -> "engine.compose.importKindUnknown";
            case IMPORT_TOO_FEW_COORDINATES -> "engine.compose.importTooFewCoordinates";
            case IMPORT_NO_POLYGON -> "engine.compose.importNoPolygon";
            case IMPORT_NO_KML_POLYGON -> "engine.compose.importNoKmlPolygon";
            case NOTHING_TO_PRINT -> "engine.compose.nothingToPrint";
            case NO_MAP_PRINTED -> "engine.compose.noMapPrinted";
            case SIZE_NEEDS_WIDTH -> "engine.compose.sizeNeedsWidth";
            case SIZE_NEEDS_HEIGHT -> "engine.compose.sizeNeedsHeight";
            case SIZE_NEEDS_BOX -> "engine.compose.sizeNeedsBox";
            case PROJECTS_TO_NOTHING -> "engine.compose.projectsToNothing";
            case NO_RETINA -> "engine.compose.noRetina";
            case ZOOM_OUT_OF_RANGE -> "engine.compose.zoomOutOfRange";
            case PRINT_SIZE_NOT_POSITIVE -> "engine.compose.printSizeNotPositive";
            case ROLL_WIDTH_NOT_POSITIVE -> "engine.compose.rollWidthNotPositive";
            case SHEET_SIZE_NOT_POSITIVE -> "engine.compose.sheetSizeNotPositive";
            case OVERLAP_TOO_WIDE -> "engine.compose.overlapTooWide";
            case EDGE_NEGATIVE -> "engine.compose.edgeNegative";
            case STROKE_NOT_POSITIVE -> "engine.compose.strokeNotPositive";
            case EFFECT_OUT_OF_RANGE -> "engine.compose.effectOutOfRange";
            case RASTER_TOO_LARGE -> "engine.compose.rasterTooLarge";
            case TIFF_TOO_LARGE -> "engine.compose.tiffTooLarge";
            case TILE_UNREADABLE -> "engine.compose.tileUnreadable";
        };
    }
}
