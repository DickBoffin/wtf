import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.operation.overlayng.UnaryUnionNG;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns Natural Earth's 1:10m admin-0 and admin-1 GeoJSON into the outline library that ships
 * inside the application. Run by hand when the data is refreshed, never by the build.
 * <p>
 * <b>Why a tool and not a dependency.</b> The published files are 13 MB and 41 MB, and nine tenths
 * of that is prose: Natural Earth carries a hundred and sixty attribute columns per feature, in
 * several languages, and coordinates at fifteen decimal places - a resolution of one Angstrom, on
 * a dataset whose vertices are a kilometer apart. What the application needs is the geometry, six
 * fields, and a bounding box. This strips it to that.
 * <p>
 * <b>Four levels</b>, broadest first, and the number is what the picker groups by:
 * <ol start="0">
 *   <li>continents, dissolved from the map subunits on each one;</li>
 *   <li>countries;</li>
 *   <li>the parts a country is made of where it has more than one - Scotland, Corsica, Alaska;</li>
 *   <li>states, provinces and regions.</li>
 * </ol>
 * <p>
 * <b>What it writes</b>, under {@code wtf-studio/src/main/resources/net/trdlo/wtf/studio/outlines}:
 * <ul>
 *   <li>{@code c/<slug>.json} - one continent's geometry.</li>
 *   <li>{@code index.json} - every entry's name, code, level, parent, bounding box and vertex
 *       count, and nothing else. Small enough to read at startup to fill a list.</li>
 *   <li>{@code a0/<A3>.json} - one country's geometry.</li>
 *   <li>{@code a1/<A3>.json} - every subdivision of that country, keyed by id. Picking Bavaria
 *       reads Germany's file and nothing else; the library is never loaded whole.</li>
 * </ul>
 * <p>
 * <b>Coordinates are rounded to five decimals</b>, which is a meter at the equator. The source's
 * own vertex spacing is about a kilometer, so this throws away nothing that was ever there, and
 * it is what turns 54 MB into something an installer can carry.
 * <p>
 * <b>The same data gives the same files, byte for byte.</b> The library is kept in git (D35), so
 * nothing in it says when it was made, and every number is a plain decimal - {@code 180}, never
 * {@code 1.8E+2}. A run over unchanged Natural Earth data leaves nothing to commit.
 * <p>
 * Run it from the repository root, with the project's Jackson and JTS on the classpath:
 * <pre>
 * curl -sL -o admin0.geojson https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson
 * curl -sL -o admin1.geojson https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson
 * curl -sL -o subunits.geojson https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_map_subunits.geojson
 * mvn -q -pl wtf-compose dependency:build-classpath -Dmdep.outputFile=cp.txt
 * java -Xmx3g -cp "$(cat wtf-compose/cp.txt)" tools/outlines/OutlineImport.java &lt;dir-with-the-three-files&gt;
 * </pre>
 */
public final class OutlineImport {

    /** A meter at the equator, on data whose vertices are a kilometer apart. */
    private static final int DECIMALS = 5;

    /** Natural Earth 1:10m, public domain. The one fact the About dialog has to be able to say. */
    private static final String SOURCE = "Natural Earth 1:10m (public domain)";

    /** Plain decimals, because {@link #round} strips trailing zeros and 180 stripped is 1.8E+2. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private static final int CONTINENT = 0, COUNTRY = 1, PART = 2, REGION = 3;

    /** The grid the coordinates are rounded to, as JTS sees it - so the union snaps to it too. */
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(Math.pow(10, DECIMALS)));

    /**
     * Natural Earth's word for the islands that belong to no continent - Reunion, the Azores,
     * Saint Helena. It is a real grouping and not a continent, and a print of "the open ocean"
     * would be a print of fourteen specks.
     */
    private static final String NOT_A_CONTINENT = "Seven seas (open ocean)";

    public static void main(final String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: OutlineImport <directory holding admin0.geojson and admin1.geojson>");
            System.exit(2);
        }
        final Path source = Path.of(args[0]);
        final Path out = args.length > 1 ? Path.of(args[1])
                : Path.of("wtf-studio/src/main/resources/net/trdlo/wtf/studio/outlines");
        Files.createDirectories(out.resolve("a0"));
        Files.createDirectories(out.resolve("a1"));
        Files.createDirectories(out.resolve("c"));

        final List<ObjectNode> index = new ArrayList<>();
        index.addAll(countries(JSON.readTree(Files.readAllBytes(source.resolve("admin0.geojson"))), out));
        final Path subunits = source.resolve("subunits.geojson");
        if (!Files.exists(subunits)) {
            // The continents are made from it, so it is not optional
            System.err.println("subunits.geojson is missing - the continents are built from it");
            System.exit(2);
        }
        final JsonNode subunitFeatures = JSON.readTree(Files.readAllBytes(subunits));
        index.addAll(continents(subunitFeatures, out));
        index.addAll(subunits(subunitFeatures, out));
        index.addAll(subdivisions(JSON.readTree(Files.readAllBytes(source.resolve("admin1.geojson"))), out));

        // Sorted by name, so the list the application shows is in order without sorting it again
        index.sort(Comparator.comparing((ObjectNode n) -> n.path("level").asInt())
                .thenComparing(n -> n.path("name").asText().toLowerCase(Locale.ROOT)));

        final ObjectNode root = JSON.createObjectNode();
        root.put("source", SOURCE);
        root.set("entries", JSON.createArrayNode().addAll(index));
        Files.writeString(out.resolve("index.json"), JSON.writeValueAsString(root), StandardCharsets.UTF_8);

        System.out.printf(Locale.ROOT, "%,d entries, index %,d bytes%n",
                index.size(), Files.size(out.resolve("index.json")));
        long bytes = 0;
        try (var walk = Files.walk(out)) {
            for (final Path f : walk.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(f);
            }
        }
        System.out.printf(Locale.ROOT, "library %,d bytes on disk%n", bytes);
    }

    // -------------------------------------------------------------------------------------------

    private static List<ObjectNode> countries(final JsonNode fc, final Path out) throws IOException {
        final List<ObjectNode> index = new ArrayList<>();
        for (final JsonNode feature : fc.path("features")) {
            final JsonNode p = feature.path("properties");
            final String a3 = text(p, "ADM0_A3");
            if (a3.isEmpty()) {
                continue;
            }
            final ArrayNode geometry = geometry(feature.path("geometry"));
            if (geometry == null) {
                continue;
            }
            final ObjectNode geo = JSON.createObjectNode();
            geo.put("type", feature.path("geometry").path("type").asText());
            geo.set("coordinates", geometry);
            Files.writeString(out.resolve("a0").resolve(a3 + ".json"), JSON.writeValueAsString(geo),
                    StandardCharsets.UTF_8);

            final ObjectNode entry = entry("ne0:" + a3, text(p, "NAME"), COUNTRY, geometry);
            putIfPresent(entry, "longName", text(p, "NAME_LONG"));
            putIfPresent(entry, "iso", text(p, "ISO_A2"));
            entry.put("file", "a0/" + a3 + ".json");
            // Everything Natural Earth is unsure about, kept and labeled rather than dropped:
            // a disputed or indeterminate boundary is exactly the one somebody wants to print.
            putIfPresent(entry, "kind", text(p, "TYPE"));
            index.add(entry);
        }
        return index;
    }

    /**
     * The continents, each the union of the map subunits Natural Earth places on it.
     * <p>
     * <b>From the subunits, not from the countries.</b> A country is not on one continent: France
     * is metropolitan France and French Guiana and Mayotte, Spain has Ceuta and the Canaries, and
     * Russia is two. Natural Earth's country table gives each country a single continent, so
     * building Europe from it would carry French Guiana into Europe and all of Siberia with it.
     * The subunit table splits exactly those countries, and gives each piece its own continent -
     * so Europe is European Russia and metropolitan France, and South America has French Guiana.
     * <p>
     * The union dissolves the borders between neighbors, which Natural Earth draws as shared
     * edges, so what is left is the coastline and nothing inside it. It runs on the same
     * five-decimal grid the coordinates are rounded to, so two borders that met before rounding
     * still meet after it.
     */
    private static List<ObjectNode> continents(final JsonNode fc, final Path out) throws IOException {
        final Map<String, List<Geometry>> parts = new TreeMap<>();
        for (final JsonNode feature : fc.path("features")) {
            final String continent = text(feature.path("properties"), "CONTINENT");
            if (continent.isEmpty() || NOT_A_CONTINENT.equals(continent)) {
                continue;
            }
            final ArrayNode geometry = geometry(feature.path("geometry"));
            if (geometry == null) {
                continue;
            }
            parts.computeIfAbsent(continent, k -> new ArrayList<>())
                    .add(toJts(feature.path("geometry").path("type").asText(), geometry));
        }
        final List<ObjectNode> index = new ArrayList<>();
        for (final Map.Entry<String, List<Geometry>> e : parts.entrySet()) {
            final Geometry union = UnaryUnionNG.union(GF.buildGeometry(e.getValue()), GF.getPrecisionModel());
            final ArrayNode coordinates = toGeoJson(union);
            final String slug = e.getKey().toLowerCase(Locale.ROOT).replace(' ', '-');

            final ObjectNode geo = JSON.createObjectNode();
            geo.put("type", "MultiPolygon");
            geo.set("coordinates", coordinates);
            Files.writeString(out.resolve("c").resolve(slug + ".json"), JSON.writeValueAsString(geo),
                    StandardCharsets.UTF_8);

            final ObjectNode entry = entry("nec:" + slug, e.getKey(), CONTINENT, coordinates);
            entry.put("kind", "Continent");
            entry.put("file", "c/" + slug + ".json");
            index.add(entry);
            System.out.printf(Locale.ROOT, "%s: %,d pieces into %,d polygons%n",
                    e.getKey(), e.getValue().size(), union.getNumGeometries());
        }
        return index;
    }

    /** A GeoJSON Polygon or MultiPolygon, as JTS. */
    private static Geometry toJts(final String type, final JsonNode coordinates) {
        final List<Polygon> out = new ArrayList<>();
        if ("Polygon".equals(type)) {
            out.add(polygon(coordinates));
        } else {
            coordinates.forEach(c -> out.add(polygon(c)));
        }
        return GF.createMultiPolygon(out.toArray(new Polygon[0]));
    }

    private static Polygon polygon(final JsonNode rings) {
        final LinearRing shell = ring(rings.get(0));
        final LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring(rings.get(i));
        }
        return GF.createPolygon(shell, holes);
    }

    private static LinearRing ring(final JsonNode points) {
        final List<Coordinate> cs = new ArrayList<>(points.size());
        points.forEach(pt -> cs.add(new Coordinate(pt.get(0).asDouble(), pt.get(1).asDouble())));
        if (!cs.getFirst().equals2D(cs.getLast())) {
            cs.add(new Coordinate(cs.getFirst()));
        }
        return GF.createLinearRing(cs.toArray(new Coordinate[0]));
    }

    /** Back to GeoJSON MultiPolygon coordinates, on the same rounding as everything else. */
    private static ArrayNode toGeoJson(final Geometry g) {
        final ArrayNode polygons = JSON.createArrayNode();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (!(g.getGeometryN(i) instanceof final Polygon p) || p.isEmpty()) {
                continue;
            }
            final ArrayNode rings = JSON.createArrayNode();
            rings.add(pairs(p.getExteriorRing().getCoordinates()));
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                rings.add(pairs(p.getInteriorRingN(h).getCoordinates()));
            }
            polygons.add(rings);
        }
        return polygons;
    }

    private static ArrayNode pairs(final Coordinate[] cs) {
        final ArrayNode ring = JSON.createArrayNode();
        for (final Coordinate c : cs) {
            final ArrayNode pt = JSON.createArrayNode();
            pt.add(round(c.x));
            pt.add(round(c.y));
            ring.add(pt);
        }
        return ring;
    }

    /**
     * The pieces a country is made of where it is made of more than one: Scotland, Wales,
     * England, Corsica.
     * <p>
     * They are neither a country nor a province, and without them the library cannot offer
     * Scotland at all - Natural Earth records it as a property of the thirty-two council areas
     * rather than as a feature. Only countries with more than one subunit contribute: for the
     * other two hundred the subunit is the country, and a second identical entry helps nobody.
     */
    private static List<ObjectNode> subunits(final JsonNode fc, final Path out) throws IOException {
        final Map<String, Integer> perCountry = new TreeMap<>();
        for (final JsonNode feature : fc.path("features")) {
            final String a3 = text(feature.path("properties"), "ADM0_A3");
            perCountry.merge(a3, 1, Integer::sum);
        }
        final List<ObjectNode> index = new ArrayList<>();
        for (final JsonNode feature : fc.path("features")) {
            final JsonNode p = feature.path("properties");
            final String a3 = text(p, "ADM0_A3");
            final String su = text(p, "SU_A3");
            final String name = text(p, "NAME");
            if (su.isEmpty() || name.isEmpty() || perCountry.getOrDefault(a3, 0) < 2) {
                continue;
            }
            final ArrayNode geometry = geometry(feature.path("geometry"));
            if (geometry == null) {
                continue;
            }
            final ObjectNode geo = JSON.createObjectNode();
            geo.put("type", feature.path("geometry").path("type").asText());
            geo.set("coordinates", geometry);
            Files.writeString(out.resolve("a0").resolve("su-" + su + ".json"),
                    JSON.writeValueAsString(geo), StandardCharsets.UTF_8);

            final ObjectNode entry = entry("nes:" + su, name, PART, geometry);
            putIfPresent(entry, "longName", text(p, "NAME_LONG"));
            putIfPresent(entry, "parent", text(p, "ADMIN"));
            putIfPresent(entry, "kind", text(p, "TYPE"));
            entry.put("file", "a0/su-" + su + ".json");
            index.add(entry);
        }
        return index;
    }

    private static List<ObjectNode> subdivisions(final JsonNode fc, final Path out) throws IOException {
        // Grouped by country first, so each country's file is written once
        final Map<String, Map<String, ObjectNode>> byCountry = new TreeMap<>();
        final List<ObjectNode> index = new ArrayList<>();

        for (final JsonNode feature : fc.path("features")) {
            final JsonNode p = feature.path("properties");
            final String a3 = text(p, "adm0_a3");
            final String code = text(p, "adm1_code");
            if (a3.isEmpty() || code.isEmpty()) {
                continue;
            }
            final ArrayNode geometry = geometry(feature.path("geometry"));
            if (geometry == null) {
                continue;
            }
            final ObjectNode geo = JSON.createObjectNode();
            geo.put("type", feature.path("geometry").path("type").asText());
            geo.set("coordinates", geometry);
            byCountry.computeIfAbsent(a3, k -> new LinkedHashMap<>()).put(code, geo);

            // name_en where there is one, because the list is English; the local name is kept as
            // an alternative so that searching for "Bayern" finds Bavaria.
            final String english = text(p, "name_en");
            final String local = text(p, "name");
            if (english.isEmpty() && local.isEmpty()) {
                // Seven of them, mostly Antarctic. An entry nobody can find by name and nobody
                // can recognize in a list is not an entry; the country above it still covers it.
                continue;
            }
            final ObjectNode entry = entry("ne1:" + code,
                    english.isEmpty() ? local : english, REGION, geometry);
            if (!english.isEmpty() && !local.isEmpty() && !english.equals(local)) {
                entry.put("alsoKnownAs", local);
            }
            putIfPresent(entry, "iso", text(p, "iso_3166_2"));
            putIfPresent(entry, "parent", text(p, "admin"));
            putIfPresent(entry, "kind", text(p, "type_en"));
            entry.put("file", "a1/" + a3 + ".json");
            entry.put("key", code);
            index.add(entry);
        }

        for (final Map.Entry<String, Map<String, ObjectNode>> e : byCountry.entrySet()) {
            final ObjectNode file = JSON.createObjectNode();
            e.getValue().forEach(file::set);
            Files.writeString(out.resolve("a1").resolve(e.getKey() + ".json"),
                    JSON.writeValueAsString(file), StandardCharsets.UTF_8);
        }
        return index;
    }

    // -------------------------------------------------------------------------------------------

    private static ObjectNode entry(final String id, final String name, final int level,
                                    final ArrayNode coordinates) {
        final ObjectNode entry = JSON.createObjectNode();
        entry.put("id", id);
        entry.put("name", name);
        entry.put("level", level);

        final double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        final int[] points = {0};
        measure(coordinates, box, points);
        final ArrayNode bbox = JSON.createArrayNode();
        for (final double v : box) {
            bbox.add(round(v));
        }
        entry.set("bbox", bbox);
        // What the coarseness check needs to warn before the paper is bought, without opening
        // the geometry file: see docs/design/subject-outlines.md section 2.
        entry.put("points", points[0]);
        return entry;
    }

    /** Walks the nested coordinate arrays, whatever the geometry's nesting depth happens to be. */
    private static void measure(final JsonNode node, final double[] box, final int[] points) {
        if (node.isArray() && !node.isEmpty() && node.get(0).isNumber()) {
            final double lon = node.get(0).asDouble();
            final double lat = node.get(1).asDouble();
            box[0] = Math.min(box[0], lon);
            box[1] = Math.min(box[1], lat);
            box[2] = Math.max(box[2], lon);
            box[3] = Math.max(box[3], lat);
            points[0]++;
            return;
        }
        node.forEach(child -> measure(child, box, points));
    }

    /**
     * The coordinates, rounded and with consecutive duplicates dropped.
     * <p>
     * Rounding creates duplicates - two vertices a centimeter apart become one point twice - and a
     * ring with a repeated vertex is still valid but says nothing, so they go. The closing vertex
     * of a ring is left alone: dropping that one would make it not a ring.
     */
    private static ArrayNode geometry(final JsonNode geometry) {
        final JsonNode coordinates = geometry.path("coordinates");
        if (coordinates.isMissingNode() || !coordinates.isArray()) {
            return null;
        }
        return (ArrayNode) thin(coordinates);
    }

    private static JsonNode thin(final JsonNode node) {
        if (node.isArray() && !node.isEmpty() && node.get(0).isNumber()) {
            final ArrayNode point = JSON.createArrayNode();
            point.add(round(node.get(0).asDouble()));
            point.add(round(node.get(1).asDouble()));
            return point;
        }
        final ArrayNode out = JSON.createArrayNode();
        JsonNode previous = null;
        for (final JsonNode child : node) {
            final JsonNode thinned = thin(child);
            if (previous != null && thinned.isArray() && !thinned.isEmpty()
                    && thinned.get(0).isNumber() && thinned.equals(previous)) {
                continue;                       // the same point twice, after rounding
            }
            out.add(thinned);
            previous = thinned;
        }
        return out;
    }

    private static BigDecimal round(final double v) {
        return BigDecimal.valueOf(v).setScale(DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText().trim();
    }

    private static void putIfPresent(final ObjectNode node, final String field, final String value) {
        if (!value.isEmpty() && !"-99".equals(value) && !"-1".equals(value)) {
            node.put(field, value);
        }
    }
}
