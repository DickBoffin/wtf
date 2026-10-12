package net.trdlo.wtf.compose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Refusal;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shapes from files: CSV, GeoJSON and KML.
 * <p>
 * Lives in {@code wtf-compose} rather than in the window so the CLI and the tests can use it, and
 * so importing a country outline is not a thing only the GUI can do.
 * <p>
 * Shapefile is deliberately absent: it is three files in a trench coat and needs a dependency, and
 * every source worth using offers GeoJSON – see docs/design/subject-outlines.md §4.
 */
public final class ShapeImport {

    private ShapeImport() {
    }

    /**
     * Picks the reader from the extension.
     *
     * @throws IOException              when the file cannot be read, or is not what its extension says
     * @throws IllegalArgumentException when it is refused: a kind of file this does not read, or one
     *                                  that holds no shape to take – the message says which, for a person
     */
    public static @NotNull Geometry read(final @NotNull Path file) throws IOException {
        final String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return readCsv(file);
        }
        if (name.endsWith(".geojson") || name.endsWith(".json")) {
            return readGeoJson(file);
        }
        if (name.endsWith(".kml")) {
            return readKml(file);
        }
        throw new Refusal(ComposeReason.IMPORT_KIND_UNKNOWN, String.valueOf(file.getFileName()));
    }

    public static @NotNull List<String> extensions() {
        return List.of("*.geojson", "*.json", "*.kml", "*.csv", "*.txt");
    }

    // -------------------------------------------------------------------------------------------

    /** {@code lon,lat} per line – the format of the CSVs that produced the original cache. */
    public static @NotNull Geometry readCsv(final @NotNull Path file) throws IOException {
        final List<Coordinate> coords = new ArrayList<>();
        for (final String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final String[] parts = line.split("[,;\\s]+");
            if (parts.length < 2) {
                continue;
            }
            try {
                // Locale.ROOT on purpose: a file is not a text field, and a decimal comma here
                // would be indistinguishable from the separator anyway
                coords.add(new Coordinate(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])));
            } catch (final NumberFormatException ignored) {
                // a header line, most likely
            }
        }
        if (coords.size() < 3) {
            throw new Refusal(ComposeReason.IMPORT_TOO_FEW_COORDINATES, String.valueOf(file.getFileName()));
        }
        return Shapes.polygon(coords.toArray(new Coordinate[0]));
    }

    /**
     * The first polygonal feature, with every part and every hole.
     * <p>
     * A real country outline is a MultiPolygon with holes – Greece and Croatia are many parts,
     * South Africa has a hole in it – so nothing here flattens to a single ring.
     */
    public static @NotNull Geometry readGeoJson(final @NotNull Path file) throws IOException {
        final JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(file));
        final List<Polygon> polygons = new ArrayList<>();
        collectGeoJson(root, polygons);
        if (polygons.isEmpty()) {
            throw new Refusal(ComposeReason.IMPORT_NO_POLYGON, String.valueOf(file.getFileName()));
        }
        return polygons.size() == 1 ? polygons.getFirst()
                : Shapes.GF.createMultiPolygon(polygons.toArray(new Polygon[0]));
    }

    /**
     * The polygons of a GeoJSON node that is already in memory.
     * <p>
     * For the bundled outline library, whose entries are geometry objects inside a file of them
     * rather than files of their own: the caller picks the node, this turns it into geometry.
     */
    public static @NotNull Geometry readGeoJson(final @NotNull JsonNode node, final @NotNull String what) {
        final List<Polygon> polygons = new ArrayList<>();
        collectGeoJson(node, polygons);
        if (polygons.isEmpty()) {
            throw new Refusal(ComposeReason.IMPORT_NO_POLYGON, what);
        }
        return polygons.size() == 1 ? polygons.getFirst()
                : Shapes.GF.createMultiPolygon(polygons.toArray(new Polygon[0]));
    }

    private static void collectGeoJson(final @Nullable JsonNode node, final @NotNull List<Polygon> out) {
        if (node == null || !node.isObject() && !node.isArray()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectGeoJson(child, out));
            return;
        }
        final String type = node.path("type").asText("");
        switch (type) {
            case "FeatureCollection" -> collectGeoJson(node.get("features"), out);
            case "Feature" -> collectGeoJson(node.get("geometry"), out);
            case "GeometryCollection" -> collectGeoJson(node.get("geometries"), out);
            case "Polygon" -> {
                final Polygon p = polygonOf(node.get("coordinates"));
                if (p != null) {
                    out.add(p);
                }
            }
            case "MultiPolygon" -> {
                final JsonNode coords = node.get("coordinates");
                if (coords != null) {
                    coords.forEach(poly -> {
                        final Polygon p = polygonOf(poly);
                        if (p != null) {
                            out.add(p);
                        }
                    });
                }
            }
            default -> { }
        }
    }

    private static @Nullable Polygon polygonOf(final @Nullable JsonNode rings) {
        if (rings == null || !rings.isArray() || rings.isEmpty()) {
            return null;
        }
        final LinearRing shell = ringOf(rings.get(0));
        if (shell == null) {
            return null;
        }
        final List<LinearRing> holes = new ArrayList<>();
        for (int i = 1; i < rings.size(); i++) {
            final LinearRing hole = ringOf(rings.get(i));
            if (hole != null) {
                holes.add(hole);
            }
        }
        return Shapes.GF.createPolygon(shell, holes.toArray(new LinearRing[0]));
    }

    private static @Nullable LinearRing ringOf(final @Nullable JsonNode ring) {
        if (ring == null || !ring.isArray() || ring.size() < 3) {
            return null;
        }
        final List<Coordinate> cs = new ArrayList<>(ring.size());
        ring.forEach(pair -> {
            if (pair.isArray() && pair.size() >= 2) {
                cs.add(new Coordinate(pair.get(0).asDouble(), pair.get(1).asDouble()));
            }
        });
        return cs.size() < 3 ? null : Shapes.GF.createLinearRing(close(cs));
    }

    /** Every {@code <Polygon>} in the document, outer boundaries and inner holes. */
    public static @NotNull Geometry readKml(final @NotNull Path file) throws IOException {
        final List<Polygon> polygons = new ArrayList<>();
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // a KML file is untrusted input; no external entities, no DTDs
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            final Document doc = factory.newDocumentBuilder().parse(file.toFile());

            final NodeList nodes = doc.getElementsByTagName("Polygon");
            for (int i = 0; i < nodes.getLength(); i++) {
                final Polygon p = kmlPolygon((Element) nodes.item(i));
                if (p != null) {
                    polygons.add(p);
                }
            }
        } catch (final IOException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IOException("Could not read " + file.getFileName() + ": " + ex.getMessage(), ex);
        }
        // Outside the try, which would take a refusal for a file it could not parse
        if (polygons.isEmpty()) {
            throw new Refusal(ComposeReason.IMPORT_NO_KML_POLYGON, String.valueOf(file.getFileName()));
        }
        return polygons.size() == 1 ? polygons.getFirst()
                : Shapes.GF.createMultiPolygon(polygons.toArray(new Polygon[0]));
    }

    private static @Nullable Polygon kmlPolygon(final @NotNull Element polygon) {
        final LinearRing shell = kmlRing(polygon, "outerBoundaryIs");
        if (shell == null) {
            return null;
        }
        final List<LinearRing> holes = new ArrayList<>();
        final NodeList inner = polygon.getElementsByTagName("innerBoundaryIs");
        for (int i = 0; i < inner.getLength(); i++) {
            final LinearRing hole = coordinatesOf((Element) inner.item(i));
            if (hole != null) {
                holes.add(hole);
            }
        }
        return Shapes.GF.createPolygon(shell, holes.toArray(new LinearRing[0]));
    }

    private static @Nullable LinearRing kmlRing(final @NotNull Element polygon, final @NotNull String tag) {
        final NodeList outer = polygon.getElementsByTagName(tag);
        return outer.getLength() == 0 ? null : coordinatesOf((Element) outer.item(0));
    }

    /** KML coordinates are {@code lon,lat[,alt]} separated by whitespace. */
    private static @Nullable LinearRing coordinatesOf(final @NotNull Element boundary) {
        final NodeList list = boundary.getElementsByTagName("coordinates");
        if (list.getLength() == 0) {
            return null;
        }
        final Node text = list.item(0).getFirstChild();
        if (text == null) {
            return null;
        }
        final List<Coordinate> cs = new ArrayList<>();
        for (final String token : text.getNodeValue().trim().split("\\s+")) {
            final String[] parts = token.split(",");
            if (parts.length < 2) {
                continue;
            }
            try {
                cs.add(new Coordinate(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])));
            } catch (final NumberFormatException ignored) {
                // not a coordinate
            }
        }
        return cs.size() < 3 ? null : Shapes.GF.createLinearRing(close(cs));
    }

    private static @NotNull Coordinate @NotNull [] close(final @NotNull List<Coordinate> cs) {
        if (!cs.getFirst().equals2D(cs.getLast())) {
            cs.add(new Coordinate(cs.getFirst()));
        }
        return cs.toArray(new Coordinate[0]);
    }
}
