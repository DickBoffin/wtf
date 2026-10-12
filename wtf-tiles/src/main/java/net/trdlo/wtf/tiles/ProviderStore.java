package net.trdlo.wtf.tiles;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * The user's own provider definitions: the file behind "add, change, remove" in the window.
 * <p>
 * <b>Built-in providers are never edited in place.</b> A user entry with the same id
 * <i>overrides</i> the shipped one, and removing that entry restores it. The distinction is not
 * bureaucracy: the shipped definitions are probe-verified and get corrected in new releases, and
 * an in-place edit would either block that correction or be silently undone by it. An override
 * says out loud "this one is yours now", and there is a way back.
 * <p>
 * <b>What it cannot read, it keeps.</b> The file is read one entry at a time, as the catalog reads
 * it ({@link #read}): an entry the parser cannot turn into a provider costs that provider and
 * nothing else. The store names it ({@link #unreadableEntries}) and writes it back where it was on
 * every save, until it is mended by hand or a provider is saved under its id.
 * <p>
 * Everything here is deliberately outside the window, so the rules can be tested without one.
 */
public final class ProviderStore {

    private static final Logger log = LoggerFactory.getLogger(ProviderStore.class);

    /** Where an entry came from, which decides what may be done to it. */
    public enum Origin {
        /** Shipped and untouched. Read-only; may be hidden or duplicated. */
        BUILT_IN,
        /** Shipped, but replaced by a user entry of the same id. Editable; may be restored. */
        OVERRIDDEN,
        /** Entirely the user's. Editable and removable. */
        USER
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserFile(@Nullable List<Provider> providers, @Nullable List<String> hidden) {

        public @NotNull List<Provider> providersOrEmpty() {
            return providers == null ? List.of() : providers;
        }

        public @NotNull List<String> hiddenOrEmpty() {
            return hidden == null ? List.of() : hidden;
        }
    }

    /**
     * An entry of the file the parser could not turn into a provider, which the store keeps as it
     * was found and writes back in its place. Told by its id when it gives one, and otherwise by its
     * place, since the tree it was read from keeps no line.
     *
     * @param place  where it is in the file's list of providers, counting from 1
     * @param id     the id it gives itself, or null when it gives none that is text
     * @param reason what the parser said, after the field it said it of: English, as the parser's
     */
    public record UnreadableEntry(int place, @Nullable String id, @NotNull String reason) {

        /** How the log and the command line name it: {@code the entry 'osm'}, or {@code entry 3}. */
        public @NotNull String named() {
            return id != null ? "the entry '" + id + "'" : "entry " + place;
        }
    }

    /** An entry of the file as it was found: read as a provider, or kept because it could not be. */
    sealed interface Found {

        /** One the parser turned into a provider. */
        record Read(@NotNull Provider provider) implements Found { }

        /** One it could not, as the JSON it was read as, and why. */
        record Kept(@NotNull UnreadableEntry why, @NotNull JsonNode found) implements Found { }
    }

    /** A user file as read: every entry in its place, and the ids hidden. */
    record Contents(@NotNull List<Found> entries, @NotNull List<String> hidden) { }

    /** The file as the store writes it: every entry in its place, and the ids hidden. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Written(@Nullable List<Object> providers, @Nullable List<String> hidden) { }

    private final Path file;
    private final Set<String> builtInIds;

    private final Map<String, Provider> user = new LinkedHashMap<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    /**
     * Every entry of the file in its place: one for each provider in {@link #user}, and those kept
     * as they were found because they could not be read. What {@link #write} writes, in this order.
     */
    private final List<Found> entries = new ArrayList<>();

    /**
     * A file that was there and none of which could be read, and where it was moved to.
     *
     * @param movedTo the file as it was, under a name nothing writes to
     * @param reason  what the parser said, which usually names the line
     */
    public record Unreadable(@NotNull Path movedTo, @NotNull String reason) { }

    private @Nullable Unreadable unreadable;
    /**
     * Why nothing may be written, when nothing may: a file is there that could be neither read nor
     * moved aside, so what is in it is unknown and writing would replace it.
     */
    private @Nullable String untouchable;

    public ProviderStore(final @NotNull Path file) {
        this.file = file;
        this.builtInIds = Set.copyOf(builtInIds());
        reload();
    }

    public static @NotNull Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".wtf", "providers.json");
    }

    private static @NotNull Set<String> builtInIds() {
        final Set<String> ids = new LinkedHashSet<>();
        ProviderCatalog.builtIn().all().forEach(p -> ids.add(p.id()));
        return ids;
    }

    /** Read as the catalog reads, written indented and without the absent fields. */
    private static @NotNull ObjectMapper mapper() {
        return ProviderCatalog.mapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Reads a user file one entry at a time: the one reading of it, the catalog's and the store's,
     * so that what the Providers dialog lists is what the map loads.
     * <p>
     * An entry Jackson cannot turn into a {@link Provider} – an id that is not a string, a zoom that
     * is not a number – costs that entry and not the file: it is kept as the JSON it was read as,
     * with why, so a hand-edited file does not fail as a whole on one such entry and leave every
     * provider in it absent at start. An empty file, or one that says {@code null}, holds nothing.
     *
     * @throws IOException when none of it can be read: it is not JSON, or not an object, or its
     *                     {@code hidden} is not a list of ids
     */
    static @NotNull Contents read(final byte @NotNull [] bytes) throws IOException {
        final ObjectMapper mapper = ProviderCatalog.mapper();
        final JsonNode root = mapper.readTree(bytes);
        if (root == null || root.isNull() || root.isMissingNode()) {
            return new Contents(List.of(), List.of());
        }
        if (!root.isObject()) {
            throw new IOException("Expected an object with \"providers\" in it, found "
                    + (root.isArray() ? "a list" : "a " + root.getNodeType().name().toLowerCase(Locale.ROOT)));
        }
        final List<Found> entries = new ArrayList<>();
        final JsonNode list = root.path("providers");
        int place = 0;
        for (final JsonNode entry : list.isArray() ? list : List.of(list)) {
            place++;
            if (entry.isMissingNode() || entry.isNull()) {
                continue;
            }
            try {
                entries.add(new Found.Read(mapper.treeToValue(entry, Provider.class)));
            } catch (final IOException | RuntimeException ex) {
                entries.add(new Found.Kept(new UnreadableEntry(place, idOf(entry), reasonOf(ex)), entry));
            }
        }
        final JsonNode ids = root.path("hidden");
        final List<String> hiddenIds;
        try {
            hiddenIds = ids.isMissingNode() || ids.isNull()
                    ? List.of()
                    : mapper.convertValue(ids, new TypeReference<List<String>>() { });
        } catch (final IllegalArgumentException ex) {
            throw new IOException("\"hidden\" is not a list of ids: "
                    + reasonOf(ex.getCause() instanceof final Exception cause ? cause : ex), ex);
        }
        return new Contents(List.copyOf(entries), hiddenIds);
    }

    /** The id an entry gives itself, when it gives one that is text. */
    private static @Nullable String idOf(final @NotNull JsonNode entry) {
        final JsonNode id = entry.path("id");
        return id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
    }

    private static @Nullable String idOf(final @NotNull Found found) {
        return switch (found) {
            case Found.Read r -> r.provider().id();
            case Found.Kept k -> k.why().id();
        };
    }

    /**
     * What the parser said, after the field it said it of – {@code maxZoom: Cannot deserialize ...}
     * – and without where in the input, which for an entry read from a tree is nowhere.
     */
    private static @NotNull String reasonOf(final @NotNull Exception ex) {
        if (!(ex instanceof final JsonMappingException m)) {
            return ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
        final StringBuilder field = new StringBuilder();
        for (final JsonMappingException.Reference r : m.getPath()) {
            if (r.getFieldName() != null) {
                field.append(field.isEmpty() ? "" : ".").append(r.getFieldName());
            } else if (r.getIndex() >= 0) {
                field.append('[').append(r.getIndex()).append(']');
            }
        }
        final String said = Objects.requireNonNullElse(m.getOriginalMessage(), m.toString());
        return field.isEmpty() ? said : field + ": " + said;
    }

    /**
     * Reads the file again, one entry at a time.
     * <p>
     * <b>Nothing that could not be read is written over.</b> An entry the parser cannot turn into a
     * provider – a zoom that is not a number – is kept as it was found, named by
     * {@link #unreadableEntries()} and written back in its place by every save, while the rest of
     * the file loads, as it does on the map. Parsed whole, the file would be set aside over one such
     * entry, and opening the Providers dialog would take every provider of the user's off the map
     * from the next start.
     * <p>
     * A file none of which can be read – not JSON, not an object, a {@code hidden} that is not a
     * list of ids – is moved aside. Loud, and not fatal: a broken file must not stop the window
     * opening, because the window is where providers get repaired. Nor may it be ignored and kept
     * where it was, or the next provider saved replaces everything in it with one entry – a hand
     * edit with one comma missing, gone. So it is renamed {@code providers.unreadable.json},
     * {@link #unreadable()} says so, and the store starts empty. A file that is there and cannot
     * even be read, or cannot be moved, is left alone and refuses every write instead.
     */
    public void reload() {
        user.clear();
        hidden.clear();
        entries.clear();
        untouchable = null;
        if (!Files.exists(file)) {
            return;
        }
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (final IOException ex) {
            // A file system exception's message is the path, which the sentence already names
            final String why = ex instanceof final java.nio.file.FileSystemException f
                    ? Objects.requireNonNullElse(f.getReason(), f.getClass().getSimpleName())
                    : ex.getMessage();
            untouchable = file + " is there but could not be read, so nothing is written over it: " + why;
            log.error(untouchable);
            return;
        }
        final Contents contents;
        try {
            contents = read(bytes);
        } catch (final IOException ex) {
            moveAside(ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return;
        }
        for (final Found found : contents.entries()) {
            switch (found) {
                case Found.Read r -> {
                    // An id twice is one provider: the later definition, in the earlier place
                    if (user.put(r.provider().id(), r.provider()) == null) {
                        entries.add(r);
                    } else {
                        entries.replaceAll(e -> e instanceof final Found.Read old
                                && old.provider().id().equals(r.provider().id()) ? r : e);
                    }
                }
                case Found.Kept k -> {
                    entries.add(k);
                    log.warn("Keeping {} of {} as it was found, since it could not be read: {}",
                            k.why().named(), file, k.why().reason());
                }
            }
        }
        hidden.addAll(contents.hidden());
    }

    private void moveAside(final @NotNull String reason) {
        try {
            final Path aside = freeNameAside();
            Files.move(file, aside);
            unreadable = new Unreadable(aside, reason);
            log.error("{} could not be read and was moved to {}: {}", file, aside, reason);
        } catch (final IOException ex) {
            untouchable = file + " could not be read, nor moved aside, so nothing is written over it: "
                    + reason;
            log.error("{} ({})", untouchable, ex.toString());
        }
    }

    /** {@code providers.unreadable.json}, or {@code -2}, {@code -3} ... when an earlier one is still there. */
    private @NotNull Path freeNameAside() throws IOException {
        final String name = file.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        final String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; n < 1000; n++) {
            final Path candidate = file.resolveSibling(
                    stem + ".unreadable" + (n == 1 ? "" : "-" + n) + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("no free name beside " + file);
    }

    /** The file this store found unparsable and moved aside, or null when it found none. */
    public @Nullable Unreadable unreadable() {
        return unreadable;
    }

    /**
     * The entries of the file the parser could not turn into providers, in their order: each kept
     * as it was found, and written back in its place by every save.
     */
    public @NotNull List<UnreadableEntry> unreadableEntries() {
        final List<UnreadableEntry> kept = new ArrayList<>();
        for (final Found found : entries) {
            if (found instanceof final Found.Kept k) {
                kept.add(k.why());
            }
        }
        return List.copyOf(kept);
    }

    public @NotNull Path file() {
        return file;
    }

    public @NotNull List<Provider> userProviders() {
        return List.copyOf(user.values());
    }

    public @NotNull Set<String> hidden() {
        return Set.copyOf(hidden);
    }

    public boolean isHidden(final @NotNull String id) {
        return hidden.contains(id);
    }

    public @NotNull Origin originOf(final @NotNull String id) {
        if (!user.containsKey(id)) {
            return Origin.BUILT_IN;
        }
        return builtInIds.contains(id) ? Origin.OVERRIDDEN : Origin.USER;
    }

    public boolean isBuiltIn(final @NotNull String id) {
        return builtInIds.contains(id);
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Adds or replaces a definition and writes the file.
     * <p>
     * It goes where the first entry of its id is – one read, or one kept because it could not be –
     * and any other entry of that id goes, so the file holds it once; a new id goes at the end.
     * Saving a provider under the id of an entry that could not be read is the answer to that entry,
     * which the Providers dialog named when it opened.
     *
     * @throws Refusal               with a message meant for a person, when the definition would
     *                               not load
     * @throws IllegalStateException when the file is there and could not be read; see {@link #reload}
     */
    public void put(final @NotNull Provider provider) throws IOException {
        check(provider);
        requireWritable();
        boolean placed = false;
        for (final ListIterator<Found> it = entries.listIterator(); it.hasNext(); ) {
            if (provider.id().equals(idOf(it.next()))) {
                if (placed) {
                    it.remove();
                } else {
                    it.set(new Found.Read(provider));
                    placed = true;
                }
            }
        }
        if (!placed) {
            entries.add(new Found.Read(provider));
        }
        user.put(provider.id(), provider);
        write();
    }

    /**
     * Drops a user entry. For an overridden built-in this restores the shipped definition; for a
     * user-defined one it removes the provider altogether. An entry of the same id that could not
     * be read is not the one removed, and stays.
     *
     * @return true when something was removed
     */
    public boolean remove(final @NotNull String id) throws IOException {
        if (!user.containsKey(id)) {
            return false;
        }
        requireWritable();
        user.remove(id);
        entries.removeIf(e -> e instanceof final Found.Read r && r.provider().id().equals(id));
        write();
        return true;
    }

    /** Hidden providers stay resolvable by id – a project or a cache may still name one. */
    public void setHidden(final @NotNull String id, final boolean hide) throws IOException {
        if (hide == hidden.contains(id)) {
            return;
        }
        requireWritable();
        if (hide) {
            hidden.add(id);
        } else {
            hidden.remove(id);
        }
        write();
    }

    /**
     * Refuses a write that would replace a file nobody could read, before anything here changes.
     *
     * @throws IllegalStateException saying which file, and why
     */
    private void requireWritable() {
        if (untouchable != null) {
            throw new IllegalStateException(untouchable);
        }
    }

    /**
     * A copy under a new id, as the sane way to base a new provider on an existing one. Its logo
     * too: the tiles are the same provider's, and so is what their credit has to carry.
     */
    public static @NotNull Provider copyUnder(final @NotNull Provider source, final @NotNull String newId,
                                              final @NotNull String newName) {
        return new Provider(newId, newName, source.urlTemplate(), source.auth(),
                source.minZoom(), source.maxZoom(), source.expectedFormat(),
                List.copyOf(source.themes()),
                source.subdomains() == null ? null : List.copyOf(source.subdomains()),
                source.attribution(), source.signupUrl(), source.signupHint(), source.termsUrl(),
                source.bulkDownload(), source.quotaNote(), source.coverage(), source.languages(),
                source.logo());
    }

    /** A new entry with the least that will load, as the starting point for "Add". */
    public static @NotNull Provider blank(final @NotNull String id, final @NotNull String displayName) {
        return new Provider(id, displayName,
                "https://example.com/tiles/{z}/{x}/{y}.png", Provider.Auth.NONE,
                0, 19, null,
                List.of(new Provider.Theme("default", "Default", null, null, null, null, false, null)),
                null, "Set the attribution this provider requires", null, null, null, true, null,
                // Global until somebody says otherwise: see Provider.isGlobal
                null, null, null);
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Everything that must be true before a definition is written: {@link ProviderCatalog#validate},
     * the one check, which the loader runs over every entry too. The window checks the same things
     * as it types, and a file edited by hand meets the same rules when the catalog loads it.
     *
     * @throws Refusal saying why, for a person to read
     */
    public void check(final @NotNull Provider p) {
        ProviderCatalog.validate(p);
    }

    /**
     * True when adding this id would collide with something already there. An entry kept because it
     * could not be read does not count: a provider saved under its id takes its place ({@link #put}).
     */
    public boolean isIdTaken(final @NotNull String id) {
        return user.containsKey(id) || builtInIds.contains(id);
    }

    /**
     * {@code osm-2}, {@code osm-3}, ... – the first free id based on a name. Never the id of an entry
     * kept because it could not be read, which a copy saved under it would replace.
     */
    public @NotNull String freeIdLike(final @NotNull String base) {
        final String root = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[^a-z0-9]+", "");
        final String seed = root.isBlank() ? "provider" : root;
        if (isFree(seed)) {
            return seed;
        }
        for (int n = 2; n < 1000; n++) {
            if (isFree(seed + "-" + n)) {
                return seed + "-" + n;
            }
        }
        throw new IllegalStateException("Could not find a free id based on '" + base + "'");
    }

    private boolean isFree(final @NotNull String id) {
        return !isIdTaken(id) && entries.stream().noneMatch(e -> e instanceof Found.Kept && id.equals(idOf(e)));
    }

    // -------------------------------------------------------------------------------------------

    /** Written through a temporary file, so a crash mid-write cannot leave an unreadable one. */
    private void write() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        // Each entry in its place: a provider as the model writes it, one that could not be read as
        // it was found
        final List<Object> providers = new ArrayList<>(entries.size());
        for (final Found found : entries) {
            providers.add(switch (found) {
                case Found.Read r -> r.provider();
                case Found.Kept k -> k.found();
            });
        }
        final Written out = new Written(providers.isEmpty() ? null : providers,
                hidden.isEmpty() ? null : List.copyOf(hidden));

        final Path part = file.resolveSibling(file.getFileName() + ".part");
        mapper().writeValue(part.toFile(), out);
        try {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Wrote {} provider definition(s), {} kept as found, and {} hidden to {}",
                user.size(), entries.size() - user.size(), hidden.size(), file);
    }
}
