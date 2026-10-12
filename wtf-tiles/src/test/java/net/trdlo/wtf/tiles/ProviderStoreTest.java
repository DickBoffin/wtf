package net.trdlo.wtf.tiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adding, changing, hiding and removing providers, without a window.
 * <p>
 * The rules that matter are all here rather than in the dialog, so that a file written by hand or
 * by an older build meets the same checks as one written by a click.
 */
class ProviderStoreTest {

    private static ProviderStore store(final Path dir) {
        return new ProviderStore(dir.resolve("providers.json"));
    }

    private static Provider simple(final String id, final String name) {
        return ProviderStore.blank(id, name);
    }

    // -------------------------------------------------------------------------------------------
    // Origin: what may be done to an entry
    // -------------------------------------------------------------------------------------------

    @Test
    void aShippedProviderIsBuiltInUntilItIsOverridden(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        assertEquals(ProviderStore.Origin.BUILT_IN, s.originOf("mapy-com"));

        s.put(ProviderStore.copyUnder(ProviderCatalog.builtIn().require("mapy-com"),
                "mapy-com", "Mapy, mine"));
        assertEquals(ProviderStore.Origin.OVERRIDDEN, s.originOf("mapy-com"));

        assertTrue(s.remove("mapy-com"));
        assertEquals(ProviderStore.Origin.BUILT_IN, s.originOf("mapy-com"),
                "removing the override restores the shipped definition");
    }

    @Test
    void anAddedProviderIsTheUsersOwn(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        s.put(simple("my-wmts", "My WMTS"));
        assertEquals(ProviderStore.Origin.USER, s.originOf("my-wmts"));
        assertFalse(s.isBuiltIn("my-wmts"));
    }

    @Test
    void removingSomethingThatIsNotThereIsNotAnError(@TempDir final Path dir) throws IOException {
        assertFalse(store(dir).remove("nothing-like-this"));
    }

    // -------------------------------------------------------------------------------------------
    // Round trip
    // -------------------------------------------------------------------------------------------

    @Test
    void whatIsWrittenIsWhatTheCatalogThenLoads(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        s.put(simple("my-wmts", "My WMTS"));

        final ProviderCatalog catalog = ProviderCatalog.load(s.file());
        assertTrue(catalog.find("my-wmts").isPresent());
        assertEquals("My WMTS", catalog.require("my-wmts").displayName());
        assertTrue(catalog.size() > 1, "the built-ins are still there");
    }

    @Test
    void anOverrideReplacesTheShippedOneEntirely(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        final Provider original = ProviderCatalog.builtIn().require("mapy-com");
        s.put(ProviderStore.copyUnder(original, "mapy-com", "Renamed"));

        final Provider loaded = ProviderCatalog.load(s.file()).require("mapy-com");
        assertEquals("Renamed", loaded.displayName());
        assertEquals(original.urlTemplate(), loaded.urlTemplate(), "the rest came along");
        assertEquals("mapy-com.svg", loaded.logo(), "the logo its credit is printed with among it");
    }

    @Test
    void survivesReloadingFromDisk(@TempDir final Path dir) throws IOException {
        store(dir).put(simple("my-wmts", "My WMTS"));
        final ProviderStore reopened = store(dir);
        assertEquals(1, reopened.userProviders().size());
        assertEquals("my-wmts", reopened.userProviders().getFirst().id());
    }

    // -------------------------------------------------------------------------------------------
    // Hiding
    // -------------------------------------------------------------------------------------------

    @Test
    void hidingKeepsTheProviderResolvable(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        s.setHidden("osm", true);

        final ProviderCatalog catalog = ProviderCatalog.load(s.file());
        assertTrue(catalog.isHidden("osm"));
        assertTrue(catalog.find("osm").isPresent(),
                "a project or a cache folder may still name it; hidden is not deleted");
        assertTrue(catalog.visible().stream().noneMatch(p -> p.id().equals("osm")));
        assertTrue(catalog.all().stream().anyMatch(p -> p.id().equals("osm")));
    }

    @Test
    void unhidingPutsItBack(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        s.setHidden("osm", true);
        s.setHidden("osm", false);
        assertFalse(ProviderCatalog.load(s.file()).isHidden("osm"));
    }

    @Test
    void hidingAndAddingCoexistInOneFile(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        s.put(simple("my-wmts", "My WMTS"));
        s.setHidden("osm", true);

        final ProviderCatalog catalog = ProviderCatalog.load(s.file());
        assertTrue(catalog.find("my-wmts").isPresent());
        assertTrue(catalog.isHidden("osm"));
    }

    // -------------------------------------------------------------------------------------------
    // Validation. Every message here is one somebody will read.
    // -------------------------------------------------------------------------------------------

    private static String refusal(final ProviderStore s, final Provider p) {
        return assertThrows(IllegalArgumentException.class, () -> s.check(p)).getMessage();
    }

    @Test
    void anIdHasToWorkAsAFolderName(@TempDir final Path dir) {
        final ProviderStore s = store(dir);
        for (final String bad : List.of("Upper", "has space", "slash/es", "", "-leading")) {
            assertThrows(IllegalArgumentException.class,
                    () -> s.check(ProviderStore.blank(bad, "Name")),
                    () -> "should have refused the id '" + bad + "'");
        }
        assertDoesNotThrow(() -> s.check(ProviderStore.blank("fine-1.0_x", "Name")));
    }

    @Test
    void theAddressNeedsTheTileTokens(@TempDir final Path dir) {
        final ProviderStore s = store(dir);
        final Provider p = ProviderStore.blank("t", "T");
        final Provider noY = withTemplate(p, "https://example.com/{z}/{x}.png");
        assertTrue(refusal(s, noY).contains("{y}"));

        final Provider notHttp = withTemplate(p, "ftp://example.com/{z}/{x}/{y}.png");
        assertTrue(refusal(s, notHttp).contains("http"));
    }

    @Test
    void anAttributionIsRequiredBecauseTheLicensesRequireIt(@TempDir final Path dir) {
        final Provider p = ProviderStore.blank("t", "T");
        final Provider noCredit = new Provider(p.id(), p.displayName(), p.urlTemplate(), p.auth(),
                p.minZoom(), p.maxZoom(), p.expectedFormat(), p.themes(), p.subdomains(),
                "  ", p.signupUrl(), p.signupHint(), p.termsUrl(), p.bulkDownload(), p.quotaNote(),
                p.coverage(), p.languages(), p.logo());
        assertTrue(refusal(store(dir), noCredit).toLowerCase(java.util.Locale.ROOT)
                .contains("attribution"));
    }

    @Test
    void twoMapsMayNotShareAnId(@TempDir final Path dir) {
        final Provider p = ProviderStore.blank("t", "T");
        final Provider clash = withThemes(p, List.of(
                new Provider.Theme("a", "A", null, null, null, null, false, null),
                new Provider.Theme("a", "Also A", null, null, null, null, false, null)));
        assertTrue(refusal(store(dir), clash).contains("'a'"));
    }

    /** Their tiles would share a folder, which is lower case. */
    @Test
    void mapIdsThatDifferOnlyInCaseAreOneId(@TempDir final Path dir) {
        final Provider clash = withThemes(ProviderStore.blank("t", "T"), List.of(theme("topo"), theme("Topo")));
        assertTrue(refusal(store(dir), clash).contains("'Topo'"));
    }

    /**
     * A map id keeps its case, because the server spells it that way, and is still one folder name.
     * A provider id stays lower case: {@link #anIdHasToWorkAsAFolderName}.
     */
    @Test
    void aMapIdMayHaveCapitalsButNoPathInIt(@TempDir final Path dir) {
        final ProviderStore s = store(dir);
        final Provider p = ProviderStore.blank("t", "T");
        assertDoesNotThrow(() -> s.check(withThemes(p, List.of(theme("USGSTopo")))));
        assertDoesNotThrow(() -> s.check(withThemes(p, List.of(theme("World_Imagery")))));
        for (final String bad : List.of("", "..", "../x", "a/b", "a\\b", "has space", "-leading", "C:")) {
            assertThrows(IllegalArgumentException.class,
                    () -> s.check(withThemes(p, List.of(theme(bad)))),
                    () -> "should have refused the map id '" + bad + "'");
        }
    }

    @Test
    void aKeyInAHeaderNeedsTheHeadersName(@TempDir final Path dir) {
        final Provider p = ProviderStore.blank("t", "T");
        assertTrue(refusal(store(dir), withAuth(p, new Provider.Auth(Provider.AuthKind.HEADER, "  ")))
                .contains("header"));
        assertTrue(refusal(store(dir), withAuth(p, new Provider.Auth(Provider.AuthKind.HEADER, "X-Key", "Bearer")))
                .contains("{key}"), "a value that leaves the key out sends nothing of it");
        assertDoesNotThrow(() -> store(dir).check(withAuth(p,
                new Provider.Auth(Provider.AuthKind.HEADER, "Authorization", "Stadia-Auth {key}"))));
    }

    // -------------------------------------------------------------------------------------------
    // The shipped catalog, through the same check
    // -------------------------------------------------------------------------------------------

    static Stream<String> shippedIds() {
        return ProviderCatalog.builtIn().all().stream().map(Provider::id);
    }

    /**
     * What a shipped definition must pass to be duplicated or overridden, since both write it
     * through {@link ProviderStore#check}. A shipped provider that failed it – on its map ids, say
     * – would offer a copy in the Providers dialog that could never be saved.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("shippedIds")
    @DisplayName("every shipped provider passes the check a saved one has to pass")
    void everyShippedProviderPassesTheCheck(final String id, @TempDir final Path dir) {
        assertDoesNotThrow(() -> store(dir).check(ProviderCatalog.builtIn().require(id)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"usgs", "esri"})
    @DisplayName("a shipped provider whose map ids have capitals can be copied and overridden")
    void capitalsInMapIdsCanBeCopiedAndOverridden(final String id, @TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        final Provider shipped = ProviderCatalog.builtIn().require(id);
        assertTrue(shipped.themes().stream().anyMatch(t -> !t.id().equals(t.id().toLowerCase(Locale.ROOT))),
                "the case this is about");

        final String copyId = s.freeIdLike(id);
        s.put(ProviderStore.copyUnder(shipped, copyId, "A copy"));
        s.put(ProviderStore.copyUnder(shipped, id, "Mine"));

        final ProviderCatalog loaded = ProviderCatalog.load(s.file());
        assertEquals("Mine", loaded.require(id).displayName());
        assertEquals(ProviderStore.Origin.OVERRIDDEN, s.originOf(id));
        assertEquals(shipped.themes(), loaded.require(copyId).themes(),
                "the map ids come back as the server spells them");
    }

    @Test
    void aProviderNeedsAtLeastOneMap(@TempDir final Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> store(dir).check(withThemes(ProviderStore.blank("t", "T"), List.of())));
    }

    @Test
    void anUnknownTemplateTokenIsATypo(@TempDir final Path dir) {
        final Provider p = withTemplate(ProviderStore.blank("t", "T"),
                "https://example.com/{z}/{x}/{y}?style={styel}");
        assertTrue(refusal(store(dir), p).contains("styel"));
    }

    @Test
    void aRefusedDefinitionIsNotWritten(@TempDir final Path dir) {
        final ProviderStore s = store(dir);
        assertThrows(IllegalArgumentException.class, () -> s.put(ProviderStore.blank("BAD", "x")));
        assertFalse(Files.exists(s.file()), "nothing should have been written");
        assertTrue(s.userProviders().isEmpty());
    }

    // -------------------------------------------------------------------------------------------
    // Ids
    // -------------------------------------------------------------------------------------------

    @Test
    void suggestsAFreeIdRatherThanCollide(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        assertEquals("osm-2", s.freeIdLike("osm"), "osm is built in");
        s.put(simple("osm-2", "Copy"));
        assertEquals("osm-3", s.freeIdLike("osm"));
    }

    @Test
    void turnsANameIntoAUsableId(@TempDir final Path dir) {
        final ProviderStore s = store(dir);
        assertEquals("usgs-national-map", s.freeIdLike("USGS National Map"));
        assertEquals("provider", s.freeIdLike("!!!"));
    }

    @Test
    void knowsWhenAnIdIsTaken(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        assertTrue(s.isIdTaken("mapy-com"));
        assertFalse(s.isIdTaken("something-new"));
        s.put(simple("something-new", "New"));
        assertTrue(s.isIdTaken("something-new"));
    }

    // -------------------------------------------------------------------------------------------
    // Robustness
    // -------------------------------------------------------------------------------------------

    @Test
    void aBrokenUserFileIsIgnoredRatherThanFatal(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, "{ this is not json");
        assertDoesNotThrow(() -> new ProviderStore(file));
        assertTrue(new ProviderStore(file).userProviders().isEmpty());
        // and the catalog still comes up with the built-ins
        assertTrue(ProviderCatalog.load(file).size() > 1);
    }

    /**
     * One bad entry should cost that entry. Dropping the whole file – or refusing to start – over
     * a provider the user was halfway through adding is the wrong trade.
     */
    @Test
    void oneUnloadableEntryDoesNotTakeTheOthersWithIt(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, """
                { "providers": [
                  { "id": "good", "displayName": "Good",
                    "urlTemplate": "https://example.com/{z}/{x}/{y}.png",
                    "attribution": "x",
                    "themes": [ { "id": "d", "displayName": "D" } ] },
                  { "id": "bad", "displayName": "Bad",
                    "urlTemplate": "https://example.com/{z}/{x}/{nonsense}.png",
                    "attribution": "x",
                    "themes": [ { "id": "d", "displayName": "D" } ] }
                ] }
                """);
        final ProviderCatalog catalog = ProviderCatalog.load(file);
        assertTrue(catalog.find("good").isPresent(), "the good one survived");
        assertTrue(catalog.find("bad").isEmpty(), "the bad one was dropped");
    }

    /**
     * A hand-edited file meets the check a saved one meets, entry by entry: a template without
     * {@code {x}} is dropped rather than loaded to put one image under four tiles, for good; and an
     * entry without {@code themes} costs that entry, not the whole file with every user provider in
     * it.
     */
    @Test
    void aHandEditedFileMeetsTheCheckASavedOneMeets(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, """
                { "providers": [
                  { "id": "no-themes", "displayName": "No themes",
                    "urlTemplate": "https://example.com/{z}/{x}/{y}.png", "attribution": "x" },
                  { "id": "no-x", "displayName": "No x",
                    "urlTemplate": "https://example.com/{z}/{y}.png", "attribution": "x",
                    "themes": [ { "id": "d", "displayName": "D" } ] },
                  { "id": "no-credit", "displayName": "No credit",
                    "urlTemplate": "https://example.com/{z}/{x}/{y}.png",
                    "themes": [ { "id": "d", "displayName": "D" } ] },
                  { "id": "unreadable", "displayName": "Zoom is not a number", "maxZoom": "high",
                    "urlTemplate": "https://example.com/{z}/{x}/{y}.png", "attribution": "x",
                    "themes": [ { "id": "d", "displayName": "D" } ] },
                  { "id": "good", "displayName": "Good",
                    "urlTemplate": "https://example.com/{z}/{x}/{y}.png", "attribution": "x",
                    "themes": [ { "id": "d", "displayName": "D" } ] }
                ], "hidden": [ "osm" ] }
                """);
        final ProviderCatalog catalog = ProviderCatalog.load(file);
        assertTrue(catalog.find("good").isPresent(), "the good one loads");
        assertTrue(catalog.isHidden("osm"), "and so does the rest of the file");
        for (final String bad : List.of("no-themes", "no-x", "no-credit", "unreadable")) {
            assertTrue(catalog.find(bad).isEmpty(), bad + " was dropped");
        }
        assertTrue(catalog.size() > 1, "the shipped providers are all there");

        // The loader's check is the store's: what one refuses, the other refuses, with the reason
        final Provider noX = withTemplate(ProviderStore.blank("no-x", "No x"), "https://example.com/{z}/{y}.png");
        assertEquals(ProviderReason.ADDRESS_WITHOUT_TOKEN,
                assertThrows(Refusal.class, () -> ProviderCatalog.validate(noX)).reason());
        assertEquals(ProviderReason.ADDRESS_WITHOUT_TOKEN,
                assertThrows(Refusal.class, () -> store(dir).check(noX)).reason());
    }

    /**
     * A hand edit with one comma too many is moved aside, not left where it lies for the next
     * provider saved to replace all of it with that one entry.
     */
    @Test
    void anUnparsableFileIsMovedAsideAndNeverWrittenOver(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        final String handWritten = """
                { "providers": [ { "id": "mine", "displayName": "Mine", } ] }
                """;
        Files.writeString(file, handWritten);

        final ProviderStore s = new ProviderStore(file);
        final ProviderStore.Unreadable unreadable = s.unreadable();
        assertNotNull(unreadable, "it says so");
        assertEquals(dir.resolve("providers.unreadable.json"), unreadable.movedTo());
        assertFalse(unreadable.reason().isBlank(), "and why, for whoever fixes it");
        assertEquals(handWritten, Files.readString(unreadable.movedTo()), "every byte kept");
        assertFalse(Files.exists(file));
        assertTrue(s.userProviders().isEmpty());

        s.put(simple("my-wmts", "My WMTS"));
        assertEquals(List.of("my-wmts"), new ProviderStore(file).userProviders().stream().map(Provider::id).toList());
        assertEquals(handWritten, Files.readString(unreadable.movedTo()), "and not touched by the write");

        // Broken again, while the first one is still set aside: the next free name
        Files.writeString(file, "{ still not json");
        assertEquals(dir.resolve("providers.unreadable-2.json"),
                Objects.requireNonNull(new ProviderStore(file).unreadable()).movedTo());
        assertEquals(handWritten, Files.readString(unreadable.movedTo()));
    }

    /** A folder where the file should be: there, and nothing anybody can read. */
    @Test
    void aFileThatCannotBeReadIsNotWrittenOver(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.createDirectory(file);

        final ProviderStore s = new ProviderStore(file);
        assertNull(s.unreadable(), "not moved: it could not even be read");
        final String why = assertThrows(IllegalStateException.class,
                () -> s.put(simple("my-wmts", "My WMTS"))).getMessage();
        assertTrue(why.contains("providers.json"), why);
        assertThrows(IllegalStateException.class, () -> s.setHidden("osm", true));
        assertTrue(s.userProviders().isEmpty(), "nothing changed here either");
        assertFalse(s.isHidden("osm"));
        assertTrue(Files.isDirectory(file));
    }

    @Test
    void anEmptyStoreWritesNothingUntilItHasSomethingToSay(@TempDir final Path dir) throws IOException {
        final ProviderStore s = store(dir);
        assertFalse(Files.exists(s.file()));
        s.setHidden("osm", true);
        assertTrue(Files.exists(s.file()));
        assertTrue(Files.readString(s.file()).contains("osm"));
    }

    // -------------------------------------------------------------------------------------------
    // An entry the parser cannot read
    // -------------------------------------------------------------------------------------------

    /** Two providers and, between them, one the parser cannot read: a zoom that is not a number. */
    private static final String ONE_BAD_ENTRY = """
            { "providers": [
              { "id": "one", "displayName": "One",
                "urlTemplate": "https://example.com/one/{z}/{x}/{y}.png", "attribution": "x",
                "themes": [ { "id": "d", "displayName": "D" } ] },
              { "id": "bad", "displayName": "Bad", "maxZoom": "high",
                "urlTemplate": "https://example.com/bad/{z}/{x}/{y}.png", "attribution": "x",
                "themes": [ { "id": "d", "displayName": "D" } ] },
              { "id": "two", "displayName": "Two",
                "urlTemplate": "https://example.com/two/{z}/{x}/{y}.png", "attribution": "x",
                "themes": [ { "id": "d", "displayName": "D" } ] }
            ], "hidden": [ "osm" ] }
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The entries of a file as it stands, each as its JSON. */
    private static List<JsonNode> entriesOf(final Path file) throws IOException {
        final List<JsonNode> entries = new ArrayList<>();
        JSON.readTree(file.toFile()).path("providers").forEach(entries::add);
        return entries;
    }

    private static List<String> idsOf(final List<JsonNode> entries) {
        return entries.stream().map(e -> e.path("id").asText()).toList();
    }

    private static List<String> idsOf(final ProviderStore s) {
        return s.userProviders().stream().map(Provider::id).toList();
    }

    /**
     * One entry the parser cannot read costs that provider, in the dialog as on the map, and not
     * the whole file: opening the Providers dialog does not take every provider of the user's off
     * the map from the next start.
     */
    @Test
    void anEntryThatCannotBeReadIsNamedAndKeptAsItWasFound(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, ONE_BAD_ENTRY);
        final String bad = entriesOf(file).get(1).toString();

        final ProviderStore s = new ProviderStore(file);
        assertEquals(List.of("one", "two"), idsOf(s), "the two that parse are listed");
        assertEquals(1, s.unreadableEntries().size());
        final ProviderStore.UnreadableEntry named = s.unreadableEntries().getFirst();
        assertEquals("bad", named.id(), "the third is named by its id");
        assertEquals(2, named.place(), "and by its place in the list");
        assertTrue(named.reason().startsWith("maxZoom: ") && named.reason().contains("high"), named.reason());
        assertNull(s.unreadable(), "nothing was moved aside");
        assertEquals(ONE_BAD_ENTRY, Files.readString(file), "the file stays where it is, as it was");
        try (Stream<Path> beside = Files.list(dir)) {
            assertEquals(List.of(file), beside.toList(), "with nothing set beside it");
        }
        assertTrue(s.isHidden("osm"), "the rest of the file is read as well");

        // Every write the dialog makes keeps the entry as it was found, in its place
        s.put(simple("three", "Three"));
        assertEquals(List.of("one", "bad", "two", "three"), idsOf(entriesOf(file)));
        assertEquals(bad, entriesOf(file).get(1).toString(), "every field and value, in their order");
        s.setHidden("osm", false);
        assertEquals(bad, entriesOf(file).get(1).toString());
        assertTrue(s.remove("one"));
        assertEquals(List.of("bad", "two", "three"), idsOf(entriesOf(file)));
        assertEquals(bad, entriesOf(file).getFirst().toString());

        // And the file reads as it did: the map drops the entry, and the store names it again
        final ProviderCatalog catalog = ProviderCatalog.load(file);
        assertTrue(catalog.find("two").isPresent() && catalog.find("three").isPresent());
        assertTrue(catalog.find("bad").isEmpty());
        final ProviderStore reopened = new ProviderStore(file);
        assertEquals(List.of("two", "three"), idsOf(reopened));
        assertEquals(List.of("bad"), reopened.unreadableEntries().stream()
                .map(ProviderStore.UnreadableEntry::id).toList());
    }

    /**
     * The dialog names an entry it could not read when it opens, and a provider saved under that id
     * is the answer to it: it takes the entry's place, rather than leaving two entries of one id in
     * the file. An id the store suggests is never one of those, so that a copy cannot take the place
     * by accident.
     */
    @Test
    void aProviderSavedUnderTheIdOfOneThatCouldNotBeReadTakesItsPlace(@TempDir final Path dir)
            throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, ONE_BAD_ENTRY);
        final ProviderStore s = new ProviderStore(file);
        assertFalse(s.isIdTaken("bad"), "it may be written anew");
        assertEquals("bad-2", s.freeIdLike("bad"), "but is not offered for anything else");

        s.put(simple("bad", "Bad, again"));
        final List<JsonNode> written = entriesOf(file);
        assertEquals(List.of("one", "bad", "two"), idsOf(written), "in its place, and once");
        assertEquals("Bad, again", written.get(1).path("displayName").asText());
        assertTrue(written.get(1).path("maxZoom").isInt(), "the new definition, not the old text");
        assertTrue(s.unreadableEntries().isEmpty());
        assertTrue(new ProviderStore(file).unreadableEntries().isEmpty());
        assertEquals("Bad, again", ProviderCatalog.load(file).require("bad").displayName());

        // Two of one id that could not be read: the first place is taken, and the other goes
        Files.writeString(file, """
                { "providers": [
                  { "id": "bad", "maxZoom": "high" },
                  { "id": "one", "displayName": "One",
                    "urlTemplate": "https://example.com/one/{z}/{x}/{y}.png", "attribution": "x",
                    "themes": [ { "id": "d", "displayName": "D" } ] },
                  { "id": "bad", "minZoom": "low" }
                ] }
                """);
        final ProviderStore twice = new ProviderStore(file);
        assertEquals(2, twice.unreadableEntries().size());
        twice.put(simple("bad", "Bad, again"));
        assertEquals(List.of("bad", "one"), idsOf(entriesOf(file)));
        assertEquals("Bad, again", entriesOf(file).getFirst().path("displayName").asText());
    }

    @Test
    void anEntryWithNoIdToGoByIsNamedByItsPlace(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, """
                { "providers": [
                  { "id": "one", "displayName": "One",
                    "urlTemplate": "https://example.com/one/{z}/{x}/{y}.png", "attribution": "x",
                    "themes": [ { "id": "d", "displayName": "D" } ] },
                  { "displayName": "No id" },
                  "a name where an entry goes",
                  { "id": { "not": "text" }, "displayName": "An id that is no id" }
                ] }
                """);
        final ProviderStore s = new ProviderStore(file);
        assertEquals(List.of("one"), idsOf(s));
        final List<ProviderStore.UnreadableEntry> named = s.unreadableEntries();
        assertEquals(List.of(2, 3, 4), named.stream().map(ProviderStore.UnreadableEntry::place).toList());
        assertTrue(named.stream().allMatch(e -> e.id() == null), "none has an id to go by");
        assertTrue(named.get(2).reason().startsWith("id: "), "the field the parser named: " + named.get(2).reason());
    }

    /**
     * Only a file none of which can be read is still set aside – one the catalog reads nothing of
     * either. An empty one holds nothing to lose.
     */
    @Test
    void onlyAFileNothingCanBeReadOfIsMovedAside(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        for (final String nothing : List.of("", "  \n", "null")) {
            Files.writeString(file, nothing);
            assertNull(new ProviderStore(file).unreadable(), () -> "moved aside: '" + nothing + "'");
            assertTrue(Files.exists(file));
        }
        for (final String unreadable : List.of("[ { \"id\": \"mine\" } ]", "\"providers\"",
                "{ \"hidden\": [ { \"id\": \"osm\" } ] }")) {
            Files.writeString(file, unreadable);
            final ProviderStore.Unreadable moved = new ProviderStore(file).unreadable();
            assertNotNull(moved, () -> "not moved aside: " + unreadable);
            assertEquals(unreadable, Files.readString(moved.movedTo()));
            assertFalse(Files.exists(file));
        }
    }

    // -------------------------------------------------------------------------------------------
    // Languages
    // -------------------------------------------------------------------------------------------

    @Test
    void theLanguagesAProviderNamesAreWrittenAndReadBack(@TempDir final Path dir) throws IOException {
        final Provider plain = simple("my-wmts", "My WMTS");
        final Provider lettered = withLanguages(withTemplate(simple("lettered", "Lettered"),
                "https://x.example/{z}/{x}/{y}.png?lang={lang}"), List.of("de", "en", "de", ""));
        assertEquals(List.of("de", "en"), lettered.languages(), "once each, and nothing blank");
        assertNull(withLanguages(plain, List.of()).languages(), "an empty list is no list");

        final ProviderStore s = store(dir);
        s.put(plain);
        s.put(lettered);
        final ProviderCatalog loaded = ProviderCatalog.load(s.file());
        assertEquals(List.of("de", "en"), loaded.require("lettered").languages());
        assertNull(loaded.require("my-wmts").languages());
        assertEquals(ProviderCatalog.builtIn().require("mapy-com").languages(),
                ProviderStore.copyUnder(ProviderCatalog.builtIn().require("mapy-com"), "mine", "Mine")
                        .languages(), "a copy keeps them");
        assertEquals(1, Files.readString(s.file()).split("\"languages\"", -1).length - 1,
                "written for the provider that names them, and for no other");
    }

    @Test
    void aLanguageHasToWorkAsAFolderName(@TempDir final Path dir) {
        final Provider bad = withLanguages(simple("lettered", "Lettered"), List.of("cs", "../x"));
        final Refusal r = assertThrows(Refusal.class, () -> store(dir).check(bad));
        assertEquals(ProviderReason.LANGUAGE_UNFIT, r.reason());
        assertEquals(List.of("../x"), r.args());
    }

    // -------------------------------------------------------------------------------------------

    private static Provider withLanguages(final Provider p, final List<String> languages) {
        return new Provider(p.id(), p.displayName(), p.urlTemplate(), p.auth(), p.minZoom(), p.maxZoom(),
                p.expectedFormat(), p.themes(), p.subdomains(), p.attribution(), p.signupUrl(),
                p.signupHint(), p.termsUrl(), p.bulkDownload(), p.quotaNote(), p.coverage(), languages, p.logo());
    }

    private static Provider withTemplate(final Provider p, final String template) {
        return new Provider(p.id(), p.displayName(), template, p.auth(), p.minZoom(), p.maxZoom(),
                p.expectedFormat(), p.themes(), p.subdomains(), p.attribution(), p.signupUrl(),
                p.signupHint(), p.termsUrl(), p.bulkDownload(), p.quotaNote(), p.coverage(), p.languages(),
                p.logo());
    }

    private static Provider.Theme theme(final String id) {
        return new Provider.Theme(id, id, null, null, null, null, false, null);
    }

    private static Provider withAuth(final Provider p, final Provider.Auth auth) {
        return new Provider(p.id(), p.displayName(), p.urlTemplate(), auth, p.minZoom(), p.maxZoom(),
                p.expectedFormat(), p.themes(), p.subdomains(), p.attribution(), p.signupUrl(),
                p.signupHint(), p.termsUrl(), p.bulkDownload(), p.quotaNote(), p.coverage(), p.languages(),
                p.logo());
    }

    private static Provider withThemes(final Provider p, final List<Provider.Theme> themes) {
        return new Provider(p.id(), p.displayName(), p.urlTemplate(), p.auth(), p.minZoom(),
                p.maxZoom(), p.expectedFormat(), themes, p.subdomains(), p.attribution(),
                p.signupUrl(), p.signupHint(), p.termsUrl(), p.bulkDownload(), p.quotaNote(),
                p.coverage(), p.languages(), p.logo());
    }
}
