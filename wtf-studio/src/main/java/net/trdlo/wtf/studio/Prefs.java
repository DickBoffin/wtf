package net.trdlo.wtf.studio;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Typed application preferences, and window geometry that survives a monitor being unplugged.
 * <p>
 * The window-bounds check is the part worth having: a window restored onto a monitor that has been
 * unplugged is a window nobody can find.
 */
public final class Prefs {

    private static final String NODE = "trdlo.net/wtf";

    // Keys, in one place so a typo is a compile error rather than a silently lost setting.
    public static final String CACHE_DIR = "cache.dir";
    /** How many tiles are fetched at once; the downloader's own default until Settings says. */
    public static final Count DOWNLOAD_THREADS = new Count("download.threads",
            net.trdlo.wtf.tiles.TileDownloader.DEFAULT_CONCURRENCY);
    public static final Flag DARK_THEME = new Flag("display.dark", true);
    public static final String LANGUAGE = "display.language";
    /** Lengths on paper, {@code mm} or {@code inch}; absent means what the machine's country uses. */
    public static final String UNITS = "display.units";
    public static final Flag SHOW_SPLASH = new Flag("startup.splash", true);
    public static final Flag CHECK_UPDATES = new Flag("startup.checkUpdates", true);
    public static final Flag SHOW_WHATS_NEW = new Flag("startup.whatsNew", true);
    public static final String LAST_PROVIDER = "map.provider";
    /** Whether a finished export is opened; the checkbox is under the Export button. */
    public static final Flag OPEN_AFTER_EXPORT = new Flag("export.openAfter", true);
    /** The folder last exported to, for projects that have not chosen one. */
    public static final String LAST_EXPORT_DIR = "export.lastDir";
    /** The folder a project was last saved to or opened from; where Open and Save start. */
    public static final String LAST_PROJECT_DIR = "project.lastDir";
    /**
     * The project's file when the window last closed, which the next start opens again; absent
     * when the window closed on a project with no file, a new one.
     */
    public static final String LAST_PROJECT = "project.last";
    /** The folder a shape was last imported from. */
    public static final String LAST_IMPORT_DIR = "import.lastDir";
    /** The printer's white edge last used, in millimeters: what a new project starts with. */
    public static final String PRINT_EDGE_MM = "print.edgeMm";
    /** The marks for putting the sheets together, on or off; see SeamMarks. */
    public static final String SEAM_MARKS = "marks.seams";
    /** The sheet size and its orientation last used, so a new project starts on the paper at hand. */
    public static final String LAST_PAPER = "medium.paper";
    public static final String LAST_ORIENTATION = "medium.orientation";
    public static final String SCREEN_DPI = "display.screenDpi";
    /** Where the map was looking when the window last closed; {@code originX;originY;pixelsPerUnit}. */
    public static final String LAST_VIEW = "map.view";
    /** The provider a first world was fetched from, written by {@link Splash}; see {@link #openingProvider}. */
    public static final String FIRST_WORLD = "startup.firstWorld";

    /**
     * What the map shows when nothing else says otherwise: OpenStreetMap, which needs no key, so a
     * fresh install opens on a map rather than on a grid of empty tiles and a request for one.
     */
    public static final String DEFAULT_PROVIDER = "osm";

    /** Followed by a provider id: the map language last used with it. */
    private static final String MAP_LANG_PREFIX = "map.lang.";
    /** Followed by a provider id: the map of it last used. */
    private static final String MAP_THEME_PREFIX = "map.theme.";

    private final Preferences node;
    private final String path;
    /** The providers' keys, which on Windows are not in the node at all. */
    private final ApiKeys keys;

    public Prefs(final @NotNull String path) {
        this.path = path;
        this.node = Preferences.userRoot().node(path);
        this.keys = ApiKeys.forPlatform(path, node);
    }

    /**
     * The application's preferences. One instance, so every part of the window that asks for a
     * key gets it from the memory of the one {@link ApiKeys} that asked the store already.
     */
    public static @NotNull Prefs app() {
        return App.PREFS;
    }

    private static final class App {
        static final Prefs PREFS = new Prefs(NODE);
    }

    private static volatile boolean readOnly;

    /**
     * Every write dropped, for an unattended run. A capture seeds an area, a paper and a view and
     * then closes the window, and none of those may land in the preferences of whoever ran it, or
     * the next ordinary start would open on the capture's view and paper, not on theirs.
     */
    public static void setReadOnly(final boolean on) {
        readOnly = on;
    }

    public @NotNull Prefs sub(final @NotNull String name) {
        return new Prefs(path + "/" + name);
    }

    // -------------------------------------------------------------------------------------------

    public @Nullable String get(final @NotNull String key) {
        final String v = node.get(key, null);
        return v == null || v.isBlank() ? null : v;
    }

    public @NotNull String get(final @NotNull String key, final @NotNull String def) {
        final String v = get(key);
        return v == null ? def : v;
    }

    public void put(final @NotNull String key, final @Nullable String value) {
        if (readOnly) {
            return;
        }
        if (value == null || value.isBlank()) {
            node.remove(key);
        } else {
            node.put(key, value);
        }
    }

    public boolean getBoolean(final @NotNull String key, final boolean def) {
        return node.getBoolean(key, def);
    }

    public void putBoolean(final @NotNull String key, final boolean value) {
        if (!readOnly) {
            node.putBoolean(key, value);
        }
    }

    public int getInt(final @NotNull String key, final int def) {
        return node.getInt(key, def);
    }

    /**
     * A switch read in more than one place, with the default it has when nothing is stored. The
     * default is part of the key so that two readers cannot disagree about a first run – not a
     * default written out wherever the switch is read, and a literal in the Settings dialog beside
     * the downloader's own constant.
     */
    public record Flag(@NotNull String key, boolean byDefault) {
    }

    /** A whole number read in more than one place, with its default; see {@link Flag}. */
    public record Count(@NotNull String key, int byDefault) {
    }

    public boolean get(final @NotNull Flag flag) {
        return node.getBoolean(flag.key(), flag.byDefault());
    }

    public void put(final @NotNull Flag flag, final boolean value) {
        putBoolean(flag.key(), value);
    }

    public int get(final @NotNull Count count) {
        return node.getInt(count.key(), count.byDefault());
    }

    public void put(final @NotNull Count count, final int value) {
        putInt(count.key(), value);
    }

    public void putInt(final @NotNull String key, final int value) {
        if (!readOnly) {
            node.putInt(key, value);
        }
    }

    public double getDouble(final @NotNull String key, final double def) {
        return node.getDouble(key, def);
    }

    public void putDouble(final @NotNull String key, final double value) {
        if (!readOnly) {
            node.putDouble(key, value);
        }
    }

    public @Nullable Path getPath(final @NotNull String key) {
        final String v = get(key);
        return v == null ? null : Path.of(v);
    }

    public void putPath(final @NotNull String key, final @Nullable Path value) {
        put(key, value == null ? null : value.toString());
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Which provider the map opens on: the last one chosen, else the one the splash fetched a
     * first world from, else the built-in default.
     * <p>
     * The middle case is what stops a first run opening on a provider the cache holds nothing
     * for. {@link Splash} fetches a world from whichever global provider needs no key, and the
     * map has to agree with it, or the user's first sight of the application is a grid of empty
     * rectangles and a demand for an API key.
     */
    public @NotNull String openingProvider() {
        final String chosen = get(LAST_PROVIDER);
        return chosen != null ? chosen : get(FIRST_WORLD, DEFAULT_PROVIDER);
    }

    /**
     * The map language last used with a provider, or null when none was: what the map opens in
     * with that provider until a project says otherwise. Per provider, because a language one
     * provider letters its map in may be one the next one has never heard of.
     */
    public @Nullable String mapLangOf(final @NotNull String providerId) {
        final String lang = get(MAP_LANG_PREFIX + providerId);
        return lang != null && net.trdlo.wtf.tiles.TileKey.LANG.matcher(lang).matches() ? lang : null;
    }

    public void putMapLang(final @NotNull String providerId, final @NotNull String lang) {
        put(MAP_LANG_PREFIX + providerId, lang);
    }

    /**
     * The id of the map of a provider last used, or null when none was: what choosing the provider
     * again, a new project and the next start open on. Per provider, as the language is, so that
     * going back and forth between two providers keeps each on its own map.
     */
    public @Nullable String themeOf(final @NotNull String providerId) {
        final String theme = get(MAP_THEME_PREFIX + providerId);
        return theme != null && net.trdlo.wtf.tiles.Provider.THEME_ID.matcher(theme).matches() ? theme : null;
    }

    public void putTheme(final @NotNull String providerId, final @NotNull String themeId) {
        put(MAP_THEME_PREFIX + providerId, themeId);
    }

    /**
     * The choices remembered as last used – the provider, the map and the language last used with
     * each provider, the paper, the way it is turned and the printer's edge – as they stand now, for
     * {@link Kept#putBack} to put back as they were. An example opened is a visit, and what it brings
     * is nobody's choice to remember (D43).
     */
    public @NotNull Kept keepChoices() {
        final Map<String, String> now = new HashMap<>();
        for (final String key : keys()) {
            if (isChoice(key)) {
                now.put(key, node.get(key, null));
            }
        }
        return new Kept(now);
    }

    /** The remembered choices as {@link #keepChoices} found them. */
    public final class Kept {

        private final Map<String, String> was;

        private Kept(final @NotNull Map<String, String> was) {
            this.was = was;
        }

        /** Every choice back as it was: changed ones put back, and one remembered since forgotten. */
        public void putBack() {
            for (final String key : keys()) {
                if (isChoice(key) && !was.containsKey(key)) {
                    put(key, null);
                }
            }
            was.forEach(Prefs.this::put);
        }
    }

    private static boolean isChoice(final @NotNull String key) {
        return key.equals(LAST_PROVIDER) || key.equals(LAST_PAPER) || key.equals(LAST_ORIENTATION)
                || key.equals(PRINT_EDGE_MM) || key.startsWith(MAP_THEME_PREFIX) || key.startsWith(MAP_LANG_PREFIX);
    }

    /** Every key the node holds, or none when the store cannot say. */
    private @NotNull String @NotNull [] keys() {
        try {
            return node.keys();
        } catch (final BackingStoreException ex) {
            return new String[0];
        }
    }

    public void flush() {
        try {
            node.flush();
        } catch (final BackingStoreException ignored) {
            // a preference we could not persist is not worth failing over
        }
    }

    // -------------------------------------------------------------------------------------------
    // Credentials. Never in a project file – a .wtf is a thing you can mail somebody – and on
    // Windows not in the preference store either: see ApiKeys.
    // -------------------------------------------------------------------------------------------

    public @Nullable String apiKey(final @NotNull String providerId) {
        return keys.get(providerId);
    }

    public void setApiKey(final @NotNull String providerId, final @Nullable String key) {
        if (!readOnly) {
            keys.set(providerId, key);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Window geometry
    // -------------------------------------------------------------------------------------------

    private record Bounds(double x, double y, double w, double h, boolean maximized) {
        @Override
        public @NotNull String toString() {
            return String.format(Locale.ROOT, "%.1f;%.1f;%.1f;%.1f;%b", x, y, w, h, maximized);
        }

        static @Nullable Bounds parse(final @Nullable String s) {
            if (s == null) {
                return null;
            }
            final String[] p = s.split(";");
            if (p.length != 5) {
                return null;
            }
            try {
                return new Bounds(Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                        Double.parseDouble(p[2]), Double.parseDouble(p[3]), Boolean.parseBoolean(p[4]));
            } catch (final NumberFormatException ex) {
                return null;
            }
        }
    }

    /** Enough of the window must be on some screen for it to be findable with a mouse. */
    private static final double MIN_VISIBLE = 80;

    public void saveWindow(final @NotNull Stage stage, final @NotNull String name) {
        if (stage.isIconified()) {
            return;
        }
        put(key(name), new Bounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                stage.isMaximized()).toString());
        flush();
    }

    /**
     * Puts the window back where it was, unless that is now off every screen – which is what
     * happens when a laptop comes home from the office and the second monitor is gone.
     */
    public void restoreWindow(final @NotNull Stage stage, final @NotNull String name) {
        final Bounds b = Bounds.parse(get(key(name)));
        if (b == null || !isOnSomeScreen(b)) {
            return;
        }
        stage.setX(b.x());
        stage.setY(b.y());
        stage.setWidth(b.w());
        stage.setHeight(b.h());
        stage.setMaximized(b.maximized());
    }

    /**
     * The monitor a remembered window will open on: the one its saved bounds overlap most, or the
     * primary one when there are none, or none of them is there any more. For the splash, which
     * belongs where the main window is about to appear, not on whichever monitor is primary.
     */
    public @NotNull Screen screenOf(final @NotNull String name) {
        final Bounds b = Bounds.parse(get(key(name)));
        Screen best = Screen.getPrimary();
        if (b == null) {
            return best;
        }
        double most = 0;
        for (final Screen screen : Screen.getScreens()) {
            final Rectangle2D s = screen.getVisualBounds();
            final double w = Math.min(s.getMaxX(), b.x() + b.w()) - Math.max(s.getMinX(), b.x());
            final double h = Math.min(s.getMaxY(), b.y() + b.h()) - Math.max(s.getMinY(), b.y());
            if (w > 0 && h > 0 && w * h > most) {
                most = w * h;
                best = screen;
            }
        }
        return best;
    }

    private static boolean isOnSomeScreen(final @NotNull Bounds b) {
        for (final Screen screen : Screen.getScreens()) {
            final Rectangle2D s = screen.getVisualBounds();
            if (b.x() <= s.getMaxX() - MIN_VISIBLE && b.x() + b.w() >= s.getMinX() + MIN_VISIBLE
                    && b.y() <= s.getMaxY() - MIN_VISIBLE && b.y() + b.h() >= s.getMinY() + MIN_VISIBLE) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull String key(final @NotNull String name) {
        return "window." + name.toLowerCase(Locale.ROOT);
    }
}
