package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Tiles on disk: {@code <root>/<provider>/<theme>/<zz>/<x>_<y>.<ext>}.
 * A theme lettered in a language has a folder per language, {@code <theme>@<lang>} (D25).
 * <p>
 * The existing cache holds 242 000 files and 5.8 GB paid for out of a monthly request allowance, so
 * the rules here are conservative by design: <b>no tile is ever deleted or rewritten</b>, a file
 * that exists is complete (writes go to {@code .part} and are renamed), and a tile already present
 * is never fetched again. The one thing that can be forgotten is a {@code <x>_<y>.empty} marker,
 * which says the provider has no tile there.
 */
public final class TileCache {

    private static final Logger log = LoggerFactory.getLogger(TileCache.class);

    /** The extension of the zero-byte marker for a tile the provider says does not exist (D23). */
    private static final String EMPTY_EXTENSION = "empty";

    /** Between a theme and its language in the name of the theme's folder. */
    private static final char LANG_SEPARATOR = '@';

    /** What a tile is written as before it is renamed into place. */
    private static final String PART_SUFFIX = ".part";

    /** In the root; its time is when {@link #cleanPartFiles} last swept. */
    private static final String SWEEP_RECORD = ".part-sweep";

    /** How deep a zoom folder is below the root: provider, theme, zoom. */
    private static final int ZOOM_DEPTH = 3;

    private final Path root;

    /**
     * There is deliberately no memory of what {@link #find} did not find. A bounded set of "known
     * missing" keys, so that panning over empty ocean does not stat the same paths on every frame,
     * would be cleared by {@link #store} on the same object only. Every other writer of the folder
     * – a second cache object over it, the scale preview fetching through its own, another process
     * – would leave it saying "missing" over a tile that had just arrived, and a probe racing a
     * store would put the key back after the store removed it. The speed it would buy is bought
     * elsewhere: the studio's image cache remembers what a decode did
     * not find for as long as the view lasts, and everyone else asks about a tile once. The disk
     * is the only state, so every {@code TileCache} over one folder tells the same story.
     */
    public TileCache(final @NotNull Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Where the cache lives: the configured directory, or the platform default.
     * <p>
     * Two possibilities, not a chain of fallbacks: no system property, no directory in the working
     * directory. There is one cache and nobody to keep compatible with, so it lives in one place
     * unless somebody deliberately moves it – and
     * they might: 5.8 GB does not always want to be on the system drive.
     * <p>
     * Tests do not come through here at all; they construct a {@link TileCache} on a temporary
     * directory.
     */
    public static @NotNull Path resolveRoot(final @Nullable Path configured) {
        return configured != null ? configured : platformDefault();
    }

    public static @NotNull Path platformDefault() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            final String local = System.getenv("LOCALAPPDATA");
            final Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(home, "AppData", "Local");
            return base.resolve("WTF").resolve("tiles");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Caches", "WTF", "tiles");
        }
        final String xdg = System.getenv("XDG_CACHE_HOME");
        final Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".cache");
        return base.resolve("wtf").resolve("tiles");
    }

    public @NotNull Path root() {
        return root;
    }

    // -------------------------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------------------------

    /** Zero-padded to the digit count of the deepest index at this zoom. */
    static @NotNull String fileName(final int zoom, final int x, final int y, final @NotNull String ext) {
        final int digits = Integer.toString(Mercator.tileCount(zoom) - 1).length();
        return String.format(Locale.ROOT, "%0" + digits + "d_%0" + digits + "d.%s", x, y, ext);
    }

    public @NotNull Path directoryOf(final @NotNull String providerId, final @NotNull String themeId, final int zoom) {
        return directoryOf(providerId, themeId, null, zoom);
    }

    /** @param lang the language of a theme lettered in one, or null */
    public @NotNull Path directoryOf(final @NotNull String providerId, final @NotNull String themeId,
                                     final @Nullable String lang, final int zoom) {
        return directoryOf(providerId, themeId, lang, zoom, 1);
    }

    private @NotNull Path directoryOf(final @NotNull String providerId, final @NotNull String themeId,
                                      final @Nullable String lang, final int zoom, final int scale) {
        return themeDirectoryOf(providerId, themeId, lang).resolve(zoomFolderOf(zoom, scale));
    }

    private @NotNull Path directoryOf(final @NotNull TileKey key) {
        return directoryOf(key.providerId(), key.themeId(), key.lang(), key.zoom(), key.scale());
    }

    /**
     * {@code 12} for a zoom's tiles, {@code 12@2x} for its {@code @2x} ones: the same ground in other
     * files, beside them and never mixed with them.
     */
    private static @NotNull String zoomFolderOf(final int zoom, final int scale) {
        return String.format(Locale.ROOT, "%02d", zoom) + (scale == 1 ? "" : "@" + scale + "x");
    }

    private @NotNull Path themeDirectoryOf(final @NotNull String providerId, final @NotNull String themeId,
                                           final @Nullable String lang) {
        return root.resolve(folderOf(providerId)).resolve(themeFolderOf(themeId, lang));
    }

    /**
     * {@code <theme>} for a theme without a language, {@code <theme>@<lang>} for one lettered in
     * one (D25). An {@code @} is in neither an id nor a language, so the folder says unambiguously
     * which is which.
     */
    private static @NotNull String themeFolderOf(final @NotNull String themeId, final @Nullable String lang) {
        if (lang == null) {
            return folderOf(themeId);
        }
        if (!TileKey.LANG.matcher(lang).matches()) {
            throw new IllegalArgumentException("'" + lang + "' cannot name a folder in the tile cache");
        }
        return folderOf(themeId) + LANG_SEPARATOR + lang.toLowerCase(Locale.ROOT);
    }

    /**
     * An id as the one folder it names: lower case, and never a path.
     * <p>
     * The catalog refuses such ids when it loads, and this refuses them again where they become a
     * path, because an id reaches here from a command line and a project file as well – and
     * {@code ../..} resolved against the cache is a folder somewhere else on the disk.
     */
    private static @NotNull String folderOf(final @NotNull String id) {
        final String folder = id.toLowerCase(Locale.ROOT);
        if (!Provider.ID.matcher(folder).matches()) {
            throw new IllegalArgumentException("'" + id + "' cannot name a folder in the tile cache");
        }
        return folder;
    }

    public @NotNull Path pathOf(final @NotNull TileKey key, final @NotNull TileFormat format) {
        return directoryOf(key)
                .resolve(fileName(key.zoom(), key.x(), key.y(), format.extension()));
    }

    // -------------------------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------------------------

    /**
     * The tile's file, or null. Probes the known extensions rather than trusting one: a theme may
     * change format between zoom levels, and the same cache holds PNG map tiles and JPEG imagery.
     * Asks the disk every time, so a tile another object or process stored a moment ago is found.
     */
    public @Nullable Path find(final @NotNull TileKey key) {
        final Path dir = directoryOf(key);
        for (final String ext : TileFormat.KNOWN_EXTENSIONS) {
            final Path p = dir.resolve(fileName(key.zoom(), key.x(), key.y(), ext));
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    public boolean contains(final @NotNull TileKey key) {
        return find(key) != null;
    }

    public byte @Nullable [] read(final @NotNull TileKey key) throws IOException {
        final Path p = find(key);
        return p == null ? null : Files.readAllBytes(p);
    }

    /**
     * When the imagery is from, for a tile that has it. The file's modification time is set from
     * the server's {@code Last-Modified} on download, which for an aerial tile is the age of the
     * photograph rather than of the response – information worth showing for an aerial print.
     */
    public @Nullable Instant imageryDate(final @NotNull TileKey key) {
        final Path p = find(key);
        if (p == null) {
            return null;
        }
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (final IOException ex) {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------------------------

    /**
     * Stores a downloaded tile. Writes a {@code .part} and renames it, so a file that exists is a
     * file that is complete even if the process is killed mid-write.
     * <p>
     * The {@code .part} has a name of its own for each write. Were it the tile's name plus
     * {@code .part}, written with a truncating write, two writers of one tile – the scale
     * preview's loaders and a download, or two windows – could truncate each other's file between
     * the write and the rename and publish half a tile. And the server's date is set on the tile
     * after the rename, not on the {@code .part} before it: a {@code .part} dated 2018 is one that
     * {@link #cleanPartFiles} of another process takes for stale while it is being written.
     *
     * @param lastModified the server's {@code Last-Modified}, or null
     * @return where it landed
     * @throws IOException if the bytes are not an image, among the ordinary reasons
     */
    public @NotNull Path store(final @NotNull TileKey key, final byte @NotNull [] data,
                               final @Nullable Instant lastModified) throws IOException {
        final TileFormat format = TileFormat.sniff(data);
        if (!format.isImage()) {
            throw new IOException("Refusing to cache " + key + ": the bytes are not an image "
                    + "(usually an error page served with HTTP 200)");
        }
        final Path target = pathOf(key, format);
        Files.createDirectories(target.getParent());
        final Path part = Files.createTempFile(target.getParent(), target.getFileName() + ".", PART_SUFFIX);
        try {
            Files.write(part, data);
            try {
                moveIntoPlace(part, target);
            } catch (final IOException ex) {
                // Windows refuses to rename over a file another writer is renaming into place, or
                // a reader has open. That file is the same tile, and whole, so it is kept
                if (!Files.isRegularFile(target)) {
                    throw ex;
                }
                log.debug("{} was put in place by another writer first", key);
            }
        } finally {
            Files.deleteIfExists(part);     // only when the move did not happen
        }
        if (lastModified != null) {
            try {
                Files.setLastModifiedTime(target, FileTime.from(lastModified));
            } catch (final IOException ignored) {
                // not worth failing a download over
            }
        }
        return target;
    }

    private static void moveIntoPlace(final @NotNull Path part, final @NotNull Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ex) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Deletes {@code .part} files left by a killed process. Run at startup.
     * <p>
     * Only in the zoom folders touched since shortly before the last sweep: a {@code .part} is
     * written into a zoom folder, which changes the folder's time, and a {@code .part} that was
     * too young to go at the last sweep is younger than {@code olderThan} before it. The rest of a
     * 242 000-file cache has nothing to find and is not walked. The first sweep of a cache, which
     * has no record of a last one, walks all of it.
     */
    public int cleanPartFiles(final @NotNull Duration olderThan) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        final Instant now = Instant.now();
        final Instant cutoff = now.minus(olderThan);
        final Path record = root.resolve(SWEEP_RECORD);
        Instant touchedSince = Instant.EPOCH;
        try {
            if (Files.isRegularFile(record)) {
                touchedSince = Files.getLastModifiedTime(record).toInstant().minus(olderThan);
            }
        } catch (final IOException ignored) {
            // no record is a sweep of everything
        }
        final Instant since = touchedSince;
        final int[] removed = {0};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), ZOOM_DEPTH + 1,
                    new SimpleFileVisitor<>() {
                        @Override
                        public @NotNull FileVisitResult preVisitDirectory(final @NotNull Path dir,
                                                                          final @NotNull BasicFileAttributes attrs) {
                            final boolean zoomDir = root.relativize(dir).getNameCount() == ZOOM_DEPTH
                                    && !dir.equals(root);
                            return zoomDir && attrs.lastModifiedTime().toInstant().isBefore(since)
                                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NotNull FileVisitResult visitFile(final @NotNull Path file,
                                                                  final @NotNull BasicFileAttributes attrs) {
                            if (file.getFileName().toString().endsWith(PART_SUFFIX)
                                    && attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                                try {
                                    if (Files.deleteIfExists(file)) {
                                        removed[0]++;
                                    }
                                } catch (final IOException ignored) {
                                    // a .part we cannot delete is a .part we leave alone
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NotNull FileVisitResult visitFileFailed(final @NotNull Path file,
                                                                        final @NotNull IOException ex) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
            Files.write(record, new byte[0]);
            Files.setLastModifiedTime(record, FileTime.from(now));
        } catch (final IOException ex) {
            log.warn("Could not scan {} for .part files: {}", root, ex.getMessage());
        }
        if (removed[0] > 0) {
            log.info("Removed {} stale .part file(s) from {}", removed[0], root);
        }
        return removed[0];
    }

    // -------------------------------------------------------------------------------------------
    // A map whose @2x tiles are not
    // -------------------------------------------------------------------------------------------

    /**
     * Records that a map answered a {@code @2x} address with a tile of the usual size – a server that
     * ignores the {@code @2x}, as Esri's does, or a catalog wrong about a theme. A marker beside the
     * map's folders, {@code <theme>.no-retina}, for every language of it; forgetting a map's empty
     * tiles forgets it too, since both are what a corrected address makes wrong.
     */
    public void recordNoRetina(final @NotNull String providerId, final @NotNull String themeId) throws IOException {
        final Path marker = noRetinaMarkerOf(providerId, themeId);
        Files.createDirectories(marker.getParent());
        Files.write(marker, new byte[0]);
    }

    /** Whether a map has answered {@code @2x} with a tile of the usual size; see {@link #recordNoRetina}. */
    public boolean knowsNoRetina(final @NotNull String providerId, final @NotNull String themeId) {
        return Files.isRegularFile(noRetinaMarkerOf(providerId, themeId));
    }

    private @NotNull Path noRetinaMarkerOf(final @NotNull String providerId, final @NotNull String themeId) {
        return root.resolve(folderOf(providerId)).resolve(folderOf(themeId) + NO_RETINA_EXTENSION);
    }

    private static final String NO_RETINA_EXTENSION = ".no-retina";

    // -------------------------------------------------------------------------------------------
    // Tiles that do not exist, as opposed to tiles that are missing
    // -------------------------------------------------------------------------------------------

    /**
     * Records that the provider has no tile here – a 204 or a 404, which means open sea, or past
     * the edge of a national provider's coverage.
     * <p>
     * This is <b>not</b> the same as a missing tile, and conflating the two is what makes an export
     * either refuse for no reason or print holes for a good one. A tile that does not exist is the
     * map; a tile that was never fetched is a gap.
     * <p>
     * The record is a zero-byte {@code <x>_<y>.empty} beside where the tile would be, named as the
     * tile would be, rather than a line appended to one index per zoom, which would have to be
     * parsed whole again after every append – once per tile over an ocean – and could not forget a
     * line. A marker is one probe to find and one file to forget.
     */
    public void recordEmpty(final @NotNull TileKey key) throws IOException {
        final Path marker = emptyMarkerOf(key);
        Files.createDirectories(marker.getParent());
        Files.write(marker, new byte[0]);
    }

    /** Whether the provider has already told us there is nothing here. */
    public boolean isKnownEmpty(final @NotNull TileKey key) {
        return Files.isRegularFile(emptyMarkerOf(key));
    }

    /**
     * Forgets every tile of a theme that the provider said does not exist, so that each is asked
     * for again the next time it is wanted. This is for a template that was wrong: it answers 404
     * everywhere, and the markers it leaves would go on saying "sea" where there is land. The
     * tiles themselves are never touched. A theme lettered in a language forgets them in every
     * language: a wrong template is wrong in all of them.
     *
     * @return how many markers were removed
     */
    public int forgetEmpty(final @NotNull String providerId, final @NotNull String themeId) throws IOException {
        final Path providerDir = root.resolve(folderOf(providerId));
        if (!Files.isDirectory(providerDir)) {
            return 0;
        }
        final String theme = folderOf(themeId);
        final List<Path> markers = new ArrayList<>();
        try (final Stream<Path> themeDirs = Files.list(providerDir)) {
            for (final Path themeDir : themeDirs.filter(Files::isDirectory)
                    .filter(d -> theme.equals(themeOfFolder(d.getFileName().toString()))).toList()) {
                try (final Stream<Path> zoomDirs = Files.list(themeDir)) {
                    for (final Path zoomDir : zoomDirs.filter(Files::isDirectory).toList()) {
                        try (final DirectoryStream<Path> found =
                                     Files.newDirectoryStream(zoomDir, "*." + EMPTY_EXTENSION)) {
                            found.forEach(markers::add);
                        }
                    }
                }
            }
        }
        Files.deleteIfExists(noRetinaMarkerOf(providerId, themeId));
        int forgotten = 0;
        for (final Path marker : markers) {
            if (Files.deleteIfExists(marker)) {
                forgotten++;
            }
        }
        log.info("Forgot {} empty tile(s) of {}/{}", forgotten, providerId, themeId);
        return forgotten;
    }

    private @NotNull Path emptyMarkerOf(final @NotNull TileKey key) {
        return directoryOf(key)
                .resolve(fileName(key.zoom(), key.x(), key.y(), EMPTY_EXTENSION));
    }

    // -------------------------------------------------------------------------------------------
    // Statistics
    // -------------------------------------------------------------------------------------------

    /** @param scale 1, or 2 for the zoom's {@code @2x} tiles */
    public record ZoomStats(int zoom, int scale, long files, long bytes, @NotNull TileRange range) { }

    /** @param lang the language of a theme lettered in one, or null */
    public record ThemeStats(@NotNull String providerId, @NotNull String themeId, @Nullable String lang,
                             @NotNull List<ZoomStats> zooms) {
        public long files() {
            return zooms.stream().mapToLong(ZoomStats::files).sum();
        }

        public long bytes() {
            return zooms.stream().mapToLong(ZoomStats::bytes).sum();
        }
    }

    /**
     * Counts what is on disk. Walks the tree, so it takes a noticeable moment on 242 000 files –
     * call it off the UI thread. Nothing keeps the answer: every call walks the tree again.
     */
    public @NotNull List<ThemeStats> statistics() throws IOException {
        final List<ThemeStats> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (final Stream<Path> providers = Files.list(root)) {
            for (final Path providerDir : providers.filter(Files::isDirectory).sorted().toList()) {
                try (final Stream<Path> themes = Files.list(providerDir)) {
                    for (final Path themeDir : themes.filter(Files::isDirectory).sorted().toList()) {
                        final String folder = themeDir.getFileName().toString();
                        final String theme = themeOfFolder(folder);
                        out.add(statsOf(providerDir.getFileName().toString(), theme,
                                theme.length() < folder.length() ? folder.substring(theme.length() + 1) : null,
                                themeDir));
                    }
                }
            }
        }
        return out;
    }

    /** The theme a theme folder is of: {@code basic} of {@code basic@cs} as of {@code basic}. */
    private static @NotNull String themeOfFolder(final @NotNull String folder) {
        final int at = folder.indexOf(LANG_SEPARATOR);
        return at < 0 ? folder : folder.substring(0, at);
    }

    private static @NotNull ThemeStats statsOf(final @NotNull String provider, final @NotNull String theme,
                                               final @Nullable String lang,
                                               final @NotNull Path themeDir) throws IOException {
        final List<ZoomStats> zooms = new ArrayList<>();
        try (final Stream<Path> zoomDirs = Files.list(themeDir)) {
            for (final Path zoomDir : zoomDirs.filter(Files::isDirectory).sorted().toList()) {
                final String name = zoomDir.getFileName().toString();
                final int at = name.indexOf('@');
                final int zoom;
                final int scale;
                try {
                    zoom = Integer.parseInt(at < 0 ? name : name.substring(0, at));
                    scale = at < 0 ? 1 : name.endsWith("x") ? Integer.parseInt(name, at + 1, name.length() - 1, 10) : 0;
                } catch (final NumberFormatException ignored) {
                    continue;
                }
                if (scale != 1 && scale != 2) {
                    continue;
                }
                // The sizes come with the listing: a walk hands each file its attributes, which
                // Windows reads from the folder itself, where a size() per file opened each one
                final long[] filesAndBytes = new long[2];
                final TileRange[] range = {TileRange.EMPTY};
                Files.walkFileTree(zoomDir, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
                    @Override
                    public @NotNull FileVisitResult visitFile(final @NotNull Path tile,
                                                              final @NotNull BasicFileAttributes attrs) {
                        final int[] xy = parseTileName(tile.getFileName().toString());
                        if (xy != null && attrs.isRegularFile()) {
                            filesAndBytes[0]++;
                            filesAndBytes[1] += attrs.size();
                            range[0] = range[0].expandedToInclude(xy[0], xy[1]);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NotNull FileVisitResult visitFileFailed(final @NotNull Path tile,
                                                                    final @NotNull IOException ex) {
                        return FileVisitResult.CONTINUE;    // one unreadable entry is not a failed count
                    }
                });
                zooms.add(new ZoomStats(zoom, scale, filesAndBytes[0], filesAndBytes[1], range[0]));
            }
        }
        return new ThemeStats(provider, theme, lang, zooms);
    }

    /** {@code 17474_11057.png} to {@code {17474, 11057}}, or null when it is not a tile. */
    static int @Nullable [] parseTileName(final @NotNull String name) {
        final int us = name.indexOf('_');
        final int dot = name.lastIndexOf('.');
        if (us <= 0 || dot <= us || name.endsWith(".part")) {
            return null;
        }
        if (TileFormat.ofExtension(name.substring(dot + 1)) == TileFormat.UNKNOWN) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(name, 0, us, 10), Integer.parseInt(name, us + 1, dot, 10)};
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Wanted tiles split three ways. {@code empty} is the important one: the provider has said
     * there is no tile there, so it is not a gap to be filled but a part of the map that is sea,
     * or past the edge of a national provider's coverage.
     */
    public record Inventory(@NotNull List<TileKey> cached, @NotNull List<TileKey> missing,
                            @NotNull List<TileKey> empty) {
        public int needed() {
            return cached.size() + missing.size() + empty.size();
        }

        public double cachedFraction() {
            final int obtainable = cached.size() + missing.size();
            return obtainable == 0 ? 1 : (double) cached.size() / obtainable;
        }
    }

    /**
     * Sorts the wanted tiles into cached, missing and empty, in the order they were given.
     * <p>
     * A zoom folder asked about for many tiles is listed once, and the tiles are looked up in the
     * listing: asking the disk for each tile under each of four extensions and then for its
     * marker is five questions a tile, and a print is tens of thousands of tiles. A folder asked
     * about for a few – the view's download – is asked tile by tile, since listing a folder
     * of a hundred thousand tiles to answer for twenty costs more than the twenty.
     */
    public @NotNull Inventory inventory(final @NotNull Iterable<TileKey> wanted) {
        return Objects.requireNonNull(inventory(wanted, () -> false));
    }

    /**
     * {@link #inventory(Iterable)}, which looks whether {@code stop} wants it to end before each
     * folder it lists and each tile it sorts: the downloader's pre-scan, which a cancel ends.
     *
     * @return the inventory, or null when {@code stop} ended it
     */
    @Nullable Inventory inventory(final @NotNull Iterable<TileKey> wanted, final @NotNull BooleanSupplier stop) {
        final Map<Folder, Integer> asked = new HashMap<>();
        for (final TileKey k : wanted) {
            asked.merge(Folder.of(k), 1, Integer::sum);
        }
        final Map<Folder, Set<String>> listed = new HashMap<>();
        for (final Map.Entry<Folder, Integer> e : asked.entrySet()) {
            if (stop.getAsBoolean()) {
                return null;
            }
            if (e.getValue() >= LISTING_THRESHOLD) {
                final Folder f = e.getKey();
                final Set<String> names = namesIn(directoryOf(f.providerId(), f.themeId(), f.lang(), f.zoom(), f.scale()));
                if (names != null) {
                    listed.put(f, names);
                }
            }
        }

        final List<TileKey> cached = new ArrayList<>();
        final List<TileKey> missing = new ArrayList<>();
        final List<TileKey> empty = new ArrayList<>();
        for (final TileKey k : wanted) {
            if (stop.getAsBoolean()) {
                return null;
            }
            final Set<String> names = listed.get(Folder.of(k));
            final boolean isCached = names == null ? contains(k) : hasTile(names, k);
            if (isCached) {
                cached.add(k);
            } else if (names == null ? isKnownEmpty(k)
                    : names.contains(fileName(k.zoom(), k.x(), k.y(), EMPTY_EXTENSION))) {
                empty.add(k);       // the provider says there is no map here; that is an answer
            } else {
                missing.add(k);
            }
        }
        return new Inventory(cached, missing, empty);
    }

    /** How many tiles of one folder make listing the folder cheaper than asking for each. */
    static final int LISTING_THRESHOLD = 64;

    /** One zoom folder of one map in one language and scale. */
    private record Folder(@NotNull String providerId, @NotNull String themeId, @Nullable String lang, int zoom,
                          int scale) {
        static @NotNull Folder of(final @NotNull TileKey key) {
            return new Folder(key.providerId(), key.themeId(), key.lang(), key.zoom(), key.scale());
        }
    }

    /** The names in a folder, none when it does not exist, or null when it cannot be listed. */
    private static @Nullable Set<String> namesIn(final @NotNull Path dir) {
        final Set<String> names = new HashSet<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (final DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            entries.forEach(p -> names.add(p.getFileName().toString()));
        } catch (final IOException ex) {
            log.warn("Could not list {}, asking for each tile instead: {}", dir, ex.getMessage());
            return null;
        }
        return names;
    }

    private static boolean hasTile(final @NotNull Set<String> names, final @NotNull TileKey key) {
        for (final String ext : TileFormat.KNOWN_EXTENSIONS) {
            if (names.contains(fileName(key.zoom(), key.x(), key.y(), ext))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @NotNull String toString() {
        return "TileCache[" + root + "]";
    }

    /** Wraps the checked exception for use inside streams; the callers above avoid it. */
    static <T> T unchecked(final IOSupplier<T> s) {
        try {
            return s.get();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    interface IOSupplier<T> {
        T get() throws IOException;
    }
}
