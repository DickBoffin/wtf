package net.trdlo.wtf.studio.outline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bundled outlines. These read the real resources, because the thing most likely to be wrong
 * about a shipped data file is the data file.
 */
class OutlineLibraryTest {

    private static final OutlineLibrary LIBRARY = OutlineLibrary.get();

    @Test
    @DisplayName("the library ships, with countries and with the regions inside them")
    void theLibraryIsThere() {
        assertFalse(LIBRARY.isEmpty(), "no outline library in the build");
        assertTrue(LIBRARY.all().size() > 4_000, "expected the admin-1 entries too");
        for (final int level : OutlineLibrary.LEVELS) {
            assertTrue(LIBRARY.all().stream().anyMatch(e -> e.level() == level), "nothing at level " + level);
        }
        assertFalse(LIBRARY.source().isBlank(), "the About dialog has to be able to credit it");
    }

    @Test
    @DisplayName("searching finds a country by name, a region by name, and a country by code")
    void searchingWorks() {
        assertTrue(LIBRARY.search("czechia", 20).stream().anyMatch(e -> e.name().equals("Czechia")));
        assertTrue(LIBRARY.search("bavaria", 20).stream().anyMatch(e -> e.level() == OutlineLibrary.REGION));
        assertTrue(LIBRARY.search("CZ", 50).stream().anyMatch(e -> "CZ".equals(e.iso())));
    }

    @Test
    @DisplayName("an exact name comes first, so typing it and pressing Enter gets it")
    void theBestMatchIsFirst() {
        assertEquals("Georgia", LIBRARY.search("georgia", 10).getFirst().name());
        // Two of them, a country and a state of the United States, and the country wins the tie
        assertEquals(OutlineLibrary.COUNTRY, LIBRARY.search("georgia", 10).getFirst().level());
    }

    @Test
    @DisplayName("the local name finds the entry too, for somebody who knows it by that")
    void localNamesAreSearchable() {
        assertTrue(LIBRARY.search("bayern", 20).stream().anyMatch(e -> e.name().equals("Bavaria")),
                "Bayern should find Bavaria");
    }

    @Test
    @DisplayName("a country's geometry loads, and is where the index says it is")
    void countryGeometryLoads() throws IOException {
        final OutlineLibrary.Entry cz = LIBRARY.search("czechia", 5).getFirst();
        final Geometry g = LIBRARY.geometry(cz);
        assertTrue(g.isValid() || !g.isEmpty(), "Czechia should be a usable polygon");
        assertTrue(g.getNumPoints() > 500, "1:10m Czechia should not be a triangle");

        final var envelope = g.getEnvelopeInternal();
        assertEquals(cz.bounds().west(), envelope.getMinX(), 0.001);
        assertEquals(cz.bounds().north(), envelope.getMaxY(), 0.001);
        assertTrue(envelope.contains(14.42, 50.08), "Prague should be inside Czechia");
    }

    @Test
    @DisplayName("a region's geometry loads out of its country's file")
    void regionGeometryLoads() throws IOException {
        final OutlineLibrary.Entry bavaria = LIBRARY.search("bavaria", 5).stream()
                .filter(e -> e.level() == OutlineLibrary.REGION).findFirst().orElseThrow();
        final Geometry g = LIBRARY.geometry(bavaria);
        assertTrue(g.getNumPoints() > 200);
        assertTrue(g.getEnvelopeInternal().contains(11.58, 48.14), "Munich should be in Bavaria");
    }

    @Test
    @DisplayName("every index entry names a file that exists and a box that makes sense")
    void theIndexIsConsistent() {
        for (final OutlineLibrary.Entry e : LIBRARY.all()) {
            assertNotNull(OutlineLibrary.class.getResource(
                            "/net/trdlo/wtf/studio/outlines/" + e.file()),
                    () -> e.id() + " points at a missing file: " + e.file());
            assertTrue(e.points() > 0, () -> e.id() + " has no points");
            assertTrue(e.bounds().widthDegrees() >= 0 && e.bounds().heightDegrees() >= 0,
                    () -> e.id() + " has an inside-out bounding box");
            assertFalse(e.name().isBlank(), () -> e.id() + " has no name");
        }
    }

    @Test
    @DisplayName("the contested entries are all there, because the choice is the user's")
    void nothingIsOmittedForBeingContested() {
        // The policy of docs/design/subject-outlines.md section 4.2, as a test rather than a
        // paragraph: if a future data refresh or a future maintainer drops one of these, the
        // build says so.
        for (final String name : List.of("Taiwan", "Palestine", "Kosovo", "Western Sahara")) {
            assertTrue(LIBRARY.search(name, 30).stream().anyMatch(e -> e.name().equalsIgnoreCase(name)),
                    () -> "the library no longer offers " + name);
        }
        for (final String region : List.of("Tibet", "Scotland", "Wales", "Crimea", "California")) {
            assertTrue(LIBRARY.search(region, 30).stream().anyMatch(e -> e.name().equalsIgnoreCase(region)),
                    () -> "the library no longer offers " + region);
        }
        // Catalonia is deliberately not on that list: Natural Earth subdivides Spain by province,
        // so Girona and Lleida are there and the community above them is not. It is a gap in the
        // source rather than a choice made here, and the finer geoBoundaries data would close it.
        assertTrue(LIBRARY.search("girona", 10).stream().anyMatch(e -> e.level() == OutlineLibrary.REGION));
    }

    // ------------------------------------------------------------------------------ continents

    private static Geometry continent(final String name) throws IOException {
        return LIBRARY.geometry(LIBRARY.grouped(name, 5).getFirst().entries().stream()
                .filter(e -> e.level() == OutlineLibrary.CONTINENT && e.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no continent " + name)));
    }

    private static boolean covers(final Geometry g, final double lon, final double lat) {
        return g.covers(org.locationtech.jts.geom.GeometryFactory.class.cast(g.getFactory())
                .createPoint(new org.locationtech.jts.geom.Coordinate(lon, lat)));
    }

    @Test
    @DisplayName("the continents are there, and a country's pieces are on the continents they are on")
    void continentsFollowThePiecesNotTheCountry() throws IOException {
        for (final String name : List.of("Africa", "Antarctica", "Asia", "Europe",
                "North America", "Oceania", "South America")) {
            assertNotNull(continent(name));
        }
        final Geometry europe = continent("Europe");
        final Geometry asia = continent("Asia");
        final Geometry southAmerica = continent("South America");

        // France is in Europe; French Guiana is France and is not
        assertTrue(covers(europe, 2.35, 48.86), "Paris");
        assertFalse(covers(europe, -52.33, 4.93), "Cayenne is not in Europe");
        assertTrue(covers(southAmerica, -52.33, 4.93), "Cayenne is in South America");
        // and Russia is two
        assertTrue(covers(europe, 37.62, 55.75), "Moscow");
        assertFalse(covers(europe, 82.92, 55.03), "Novosibirsk is not in Europe");
        assertTrue(covers(asia, 82.92, 55.03), "Novosibirsk is in Asia");
    }

    @Test
    @DisplayName("results come level by level, broadest first, each level in one piece")
    void resultsAreGroupedByLevel() {
        final List<OutlineLibrary.Group> groups = LIBRARY.grouped("america", 50);
        assertEquals(OutlineLibrary.CONTINENT, groups.getFirst().level(), "the continents lead");
        for (int i = 1; i < groups.size(); i++) {
            assertTrue(groups.get(i - 1).level() < groups.get(i).level(), "levels in order, never twice");
        }
        for (final OutlineLibrary.Group g : groups) {
            assertTrue(g.entries().stream().allMatch(e -> e.level() == g.level()));
        }
    }

    @Test
    @DisplayName("an exact name is what Enter takes, whatever level it is on")
    void theBestMatchIsExactAtAnyLevel() {
        final var groups = LIBRARY.grouped("bavaria", 50);
        assertEquals("Bavaria", LIBRARY.best(groups, "bavaria").name());
        final var europe = LIBRARY.grouped("europe", 50);
        assertEquals(OutlineLibrary.CONTINENT, LIBRARY.best(europe, "europe").level());
        // A whole word beats a prefix: the continents, not American Samoa
        final var america = LIBRARY.grouped("america", 50);
        assertEquals(OutlineLibrary.CONTINENT, LIBRARY.best(america, "america").level());
    }

    @Test
    @DisplayName("a country code has to match exactly, not appear somewhere inside another")
    // Every name folded as the search folds it: Médéa contains "de" once its accents are gone
    void codesMatchExactly() {
        final var hits = LIBRARY.search("de", 500);
        assertTrue(hits.stream().anyMatch(e -> "DE".equals(e.iso())), "Germany by its code");
        assertTrue(hits.stream().noneMatch(e -> e.iso() != null && !e.iso().equalsIgnoreCase("de")
                        && !OutlineLibrary.fold(e.name()).contains("de")
                        && (e.alsoKnownAs() == null || !OutlineLibrary.fold(e.alsoKnownAs()).contains("de"))
                        && (e.parent() == null || !OutlineLibrary.fold(e.parent()).contains("de"))
                        && (e.longName() == null || !OutlineLibrary.fold(e.longName()).contains("de"))
                        && (e.localName() == null || !OutlineLibrary.fold(e.localName()).contains("de"))
                        && (e.localParent() == null || !OutlineLibrary.fold(e.localParent()).contains("de"))),
                "nothing found only because its code contains the letters");
    }

    @Test
    @DisplayName("the names follow the interface language, not the one the library was first read in")
    void theLanguageIsNotBakedIn() {
        final java.util.Locale before = net.trdlo.wtf.studio.I18n.locale();
        try {
            net.trdlo.wtf.studio.I18n.setLocale(java.util.Locale.ENGLISH);
            final OutlineLibrary library = OutlineLibrary.load();
            assertEquals("Germany", library.find("ne0:DEU").orElseThrow().shown());

            net.trdlo.wtf.studio.I18n.setLocale(java.util.Locale.forLanguageTag("cs-CZ"));
            assertEquals("Německo", library.find("ne0:DEU").orElseThrow().shown());
            assertEquals("ne0:DEU", library.best(library.grouped("nemecko", 20), "nemecko").id(),
                    "and found by the new name, folded");
        } finally {
            net.trdlo.wtf.studio.I18n.setLocale(before);
        }
    }

    @Test
    @DisplayName("only the last few country files are kept parsed")
    void theFilesAreBounded() throws IOException {
        final OutlineLibrary library = OutlineLibrary.load();
        // The smallest countries, each from a file of its own
        final List<OutlineLibrary.Entry> small = library.all().stream()
                .filter(e -> e.level() == OutlineLibrary.COUNTRY)
                .sorted(java.util.Comparator.comparingInt(OutlineLibrary.Entry::points))
                .filter(distinctFile())
                .limit(OutlineLibrary.FILES_KEPT + 2)
                .toList();
        assertEquals(OutlineLibrary.FILES_KEPT + 2, small.size());
        for (final OutlineLibrary.Entry e : small) {
            assertNotNull(library.geometry(e));
            assertTrue(library.filesKept() <= OutlineLibrary.FILES_KEPT, "after " + e.name());
        }
        assertEquals(OutlineLibrary.FILES_KEPT, library.filesKept());
    }

    private static java.util.function.Predicate<OutlineLibrary.Entry> distinctFile() {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        return e -> seen.add(e.file());
    }
}
