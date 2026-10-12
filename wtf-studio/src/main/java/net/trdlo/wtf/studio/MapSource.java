package net.trdlo.wtf.studio;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.studio.map.Camera;
import net.trdlo.wtf.studio.map.TileCanvas;
import net.trdlo.wtf.studio.map.TileImageCache;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.*;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where the map comes from: the provider, its map and the map's language, picked in the toolbar;
 * the catalog they are picked from and the tile cache they are read from, both of which can be
 * replaced while the window is open; and the view the map opens on, which is asked of that cache.
 * <p>
 * Part of {@link MainWindow}, which puts the pickers in its toolbar. Everyone else asks
 * {@link #catalog} and {@link #cache} at the moment they need them, because either may have been
 * replaced since.
 * <p>
 * The window has <b>one {@link TileCache} per folder</b>, and this is where it is kept: the one
 * startup opened, replaced only when Settings points at another folder, and handed to the map,
 * the download, the export, the dialogs and the scale preview alike. A cache holds no state but
 * its root, so what matters is not that everyone shares the object but that everyone reads and
 * writes the folder the user chose – the preview included, which would otherwise keep fetching
 * into the previous one.
 */
final class MapSource {

    private static final Logger log = LoggerFactory.getLogger(MapSource.class);

    private final Prefs prefs;
    private final ProjectModel model;
    private final TileCanvas map;
    /** Told when the source has been looked at again, which is when the view may need fetching. */
    private final Runnable viewChanged;

    /** Not final: editing providers replaces it without a restart. */
    private ProviderCatalog catalog;
    /** Not final: a new cache folder in Settings replaces it; see {@link #rebuild}. */
    private TileCache cache;

    private final ComboBox<Provider> providerBox = new ComboBox<>();
    private final ComboBox<Provider.Theme> themeBox = new ComboBox<>();
    /** The map's language, shown only for a map lettered in one (D25). */
    private final ComboBox<String> langBox = new ComboBox<>();
    private final Label langLabel = new Label(I18n.get("toolbar.mapLang"));

    /** @param cache the one startup opened and swept, over the folder the settings name */
    MapSource(final @NotNull Prefs prefs, final @NotNull ProviderCatalog catalog, final @NotNull TileCache cache,
              final @NotNull ProjectModel model, final @NotNull TileCanvas map,
              final @NotNull Runnable viewChanged) {
        this.prefs = prefs;
        this.catalog = catalog;
        this.cache = cache;
        this.model = model;
        this.map = map;
        this.viewChanged = viewChanged;
    }

    /** The catalog as it is now: the Providers dialog replaces it. */
    @NotNull ProviderCatalog catalog() {
        return catalog;
    }

    /** The tile cache as it is now: a new cache folder in Settings replaces it. */
    @NotNull TileCache cache() {
        return cache;
    }

    @NotNull ComboBox<Provider> providerBox() {
        return providerBox;
    }

    @NotNull ComboBox<Provider.Theme> themeBox() {
        return themeBox;
    }

    @NotNull Label langLabel() {
        return langLabel;
    }

    @NotNull ComboBox<String> langBox() {
        return langBox;
    }

    /**
     * A provider as the catalog defines it now, and not one it no longer shows – where undo finds
     * the providers it puts back.
     */
    @NotNull Optional<Provider> lookup(final @NotNull String id) {
        return catalog.find(id).filter(p -> !catalog.isHidden(p.id()));
    }

    /** The provider, map and language pickers, and what follows from each: called from the toolbar. */
    void installPickers() {
        providerBox.getItems().setAll(orderedProviders());
        providerBox.setConverter(new StringConverterOf<>(Provider::displayName));
        providerBox.setCellFactory(v -> badgedProviderCell());
        providerBox.setButtonCell(badgedProviderCell());
        providerBox.setPrefWidth(200);
        providerBox.valueProperty().bindBidirectional(model.providerProperty());
        model.providerProperty().addListener((o, a, b) -> onProviderChanged(b));

        themeBox.setConverter(new StringConverterOf<>(Provider.Theme::displayName));
        themeBox.setPrefWidth(180);
        themeBox.valueProperty().bindBidirectional(model.themeProperty());
        model.themeProperty().addListener((o, a, b) -> onThemeChanged(b));
        langBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final String tag) {
                return MapLanguages.nameOf(tag);
            }

            @Override
            public String fromString(final String typed) {
                // Typed, where the provider names no languages; text that is no language keeps the one there was
                final String lang = MapLanguages.fromTyped(typed, langBox.getItems());
                return lang == null ? langBox.getValue() : lang;
            }
        });
        langBox.setPrefWidth(130);
        langBox.getItems().setAll(MapLanguages.offered(provider(), model.mapLangProperty().get()));
        langBox.valueProperty().bindBidirectional(model.mapLangProperty());
        final Tooltip langTip = new Tooltip(I18n.get("toolbar.mapLang.tip"));
        langBox.setTooltip(langTip);
        langLabel.setTooltip(langTip);
        // a map lettered in another language is other tiles, in another folder (D25)
        model.mapLangProperty().addListener((o, a, b) -> onLanguageChanged(b));
    }

    /**
     * True while a provider switch sets up the boxes and writes its map and language. Replacing a
     * combo box's items makes JavaFX write null into its value and then pick again by position, and
     * through the binding into the project; none of that is anybody choosing anything, so nothing
     * is remembered and nothing rebuilt until the switch has written what it chose.
     */
    private boolean adjusting;

    /**
     * The providers, in the order somebody looks for one.
     * <p>
     * Three groups: the one the map opens on, then the ones that can be used immediately, then
     * the ones that want an account first. Alphabetical inside each group, because past the first
     * few entries a list in catalog order is a list in no order at all. The grouping is not a
     * judgment about quality – a key is often the price of the better imagery – it is about what
     * a person can do in the next minute.
     */
    private @NotNull List<Provider> orderedProviders() {
        final String opening = prefs.openingProvider();
        return catalog.visible().stream()
                .sorted(Comparator.comparingInt((Provider p) -> providerGroup(p, opening))
                        .thenComparing(Provider::displayName, I18n.collator()))
                .toList();
    }

    private static int providerGroup(final @NotNull Provider p, final @NotNull String opening) {
        if (p.id().equals(opening)) {
            return 0;
        }
        return p.needsKey() ? 2 : 1;
    }

    /**
     * A provider in a list: its badge, then its name.
     * <p>
     * The same badge appears in the picker, in the Providers dialog and in About, so a provider
     * is recognizable by its color before its name has been read.
     */
    private static @NotNull ListCell<Provider> badgedProviderCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(final Provider item, final boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item.displayName());
                setGraphic(ProviderBadge.of(item, 16));
                setGraphicTextGap(8);
            }
        };
    }

    /** A tiny converter so the combo boxes show a display name without a class each. */
    private static final class StringConverterOf<T> extends javafx.util.StringConverter<T> {
        private final java.util.function.Function<T, String> name;

        StringConverterOf(final java.util.function.Function<T, String> name) {
            this.name = name;
        }

        @Override
        public String toString(final T value) {
            return value == null ? "" : name.apply(value);
        }

        @Override
        public T fromString(final String s) {
            return null;
        }
    }

    private @Nullable Provider provider() {
        return model.providerProperty().get();
    }

    /**
     * Where the map opens when no view was saved: the world the splash fetched.
     * <p>
     * Zoom 2 is the zoom those sixteen tiles are. The canvas substitutes a coarser tile for a
     * missing one only four zooms up, so a cache holding nothing but a world, opened at a city's
     * zoom, is a grid of empty rectangles – which is exactly what a first run should not be.
     */
    private static final double WORLD_LON = 0;
    private static final double WORLD_LAT = 0;
    private static final int WORLD_ZOOM = 2;

    /**
     * How far past {@link #WORLD_ZOOM} the world may be stretched to fill the canvas.
     * <p>
     * The canvas draws {@code round(fractionalZoom)}, so anything under half a zoom still draws
     * the tiles the cache holds, just scaled up. Half a zoom more and it would ask for zoom 3,
     * find none of it, and the status bar would call the whole view
     * missing over a world the zoom 2 tiles are still drawing, stretched.
     */
    private static final double WORLD_STRETCH = 0.49;

    /** The provider, map and language from last time, as where the project starts, and the view to open on. */
    void restore() {
        model.startOver(() -> writeChoice(model));
        openOnSomethingCached(model.providerProperty().get(), model.themeProperty().get());
        map.requestPaint();
    }

    /**
     * The provider last chosen, with its map and language as last used with it, written into a
     * project: the window's as it starts, and a new one's (D43).
     */
    void writeChoice(final @NotNull ProjectModel into) {
        // lookup, not find: a provider hidden since it was last used is not one to open on again
        final Provider p = lookup(prefs.openingProvider())
                .or(() -> catalog.visible().stream().findFirst())
                .orElseGet(() -> catalog.all().iterator().next());     // hidden beats nothing at all
        final Provider.Theme t = ProviderChoice.theme(p, prefs.themeOf(p.id()), null);
        final String lang = ProviderChoice.language(p, prefs.mapLangOf(p.id()), into.mapLangProperty().get());
        into.providerProperty().set(p);
        // Written again, for the case where the provider was already this one and nothing fired
        into.themeProperty().set(t);
        into.mapLangProperty().set(lang);
    }

    /**
     * Points the map at the view saved when it last closed ({@link #savedView}), or, with none
     * saved, at the world the splash fetched.
     * <p>
     * A first run has nothing saved and a world in the cache, so it opens on the world, as wide as
     * the canvas ({@link #worldZoomFor}). A provider whose cache lacks even that opens on the world
     * at {@link #WORLD_ZOOM}, and fetches what it is missing.
     */
    private void openOnSomethingCached(final @NotNull Provider p, final Provider.@NotNull Theme t) {
        final double[] saved = savedView();
        if (saved != null) {
            map.openOn(() -> map.camera().restore(saved[0], saved[1], saved[2]));
            return;
        }
        // Nothing saved, so this is a first run – and the only imagery on the machine is the
        // world the splash fetched. There is no third case worth inventing: a city picked by
        // whoever wrote the application is that person's home, not the user's.
        if (worldIsCached(cache, p, t, model.mapLangProperty().get())) {
            map.openOn(() -> map.camera().centerOn(WORLD_LON, WORLD_LAT, worldZoomFor(map.camera())));
        } else {
            map.openOn(() -> map.camera().centerOn(WORLD_LON, WORLD_LAT, WORLD_ZOOM));
        }
    }

    /** {@code originX;originY;pixelsPerUnit} as last saved, or null when there is nothing usable. */
    private double @Nullable [] savedView() {
        final String raw = prefs.get(Prefs.LAST_VIEW);
        if (raw == null) {
            return null;
        }
        try {
            final String[] parts = raw.split(";");
            final double[] view = {Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2])};
            // A preference file is a text file somebody can edit, and the camera clamps anyway;
            // this only has to refuse what would make it throw or draw nothing.
            return Double.isFinite(view[0]) && Double.isFinite(view[1]) && view[2] > 0 ? view : null;
        } catch (final RuntimeException ignored) {
            return null;        // a preference nobody can parse is a preference nobody set
        }
    }

    /** Where the map is, for the next start to open on; {@link #savedView} reads it back. */
    void rememberView() {
        final Camera camera = map.camera();
        prefs.put(Prefs.LAST_VIEW, camera.originXProperty().get() + ";"
                + camera.originYProperty().get() + ";" + camera.pixelsPerUnit());
    }

    /** As wide as the canvas, up to the point where the canvas would want the next zoom's tiles. */
    private static double worldZoomFor(final @NotNull Camera camera) {
        final double fitsTheWidth = Math.log(camera.viewWidth() / Mercator.TILE_SIZE) / Math.log(2);
        return Math.max(WORLD_ZOOM, Math.min(WORLD_ZOOM + WORLD_STRETCH, fitsTheWidth));
    }

    /** Every tile of it, because a world with holes in it is a worse opening view than a city. */
    private static boolean worldIsCached(final @NotNull TileCache cache, final @NotNull Provider p,
                                         final Provider.@NotNull Theme t, final @NotNull String lang) {
        final int n = Mercator.tileCount(WORLD_ZOOM);
        for (final int[] xy : new TileRange(0, 0, n - 1, n - 1)) {
            if (!cache.contains(p.key(t.id(), lang, WORLD_ZOOM, xy[0], xy[1]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A provider chosen: its map and language as last used with it ({@link ProviderChoice}), and its
     * languages in the box – its own and nothing else where it names them, any code typed where it
     * names none.
     * <p>
     * A project being opened, and an undo, write the provider before its map and language, so their
     * own land after these and win: what was last used with a provider never changes a project that
     * says otherwise, and is only what a switch, a new project and the next start open on.
     */
    private void onProviderChanged(final @Nullable Provider p) {
        if (p == null) {
            return;
        }
        final Provider.Theme theme = ProviderChoice.theme(p, prefs.themeOf(p.id()), model.themeProperty().get());
        final String lang = ProviderChoice.language(p, prefs.mapLangOf(p.id()), model.mapLangProperty().get());
        adjusting = true;
        try {
            themeBox.getItems().setAll(p.themes());
            langBox.setEditable(p.languages() == null);
            langBox.getItems().setAll(MapLanguages.offered(p, lang));
            // After the items, which is when JavaFX has had its say about the values
            model.themeProperty().set(theme);
            model.mapLangProperty().set(lang);
        } finally {
            adjusting = false;
        }
        rebuild();
        prefs.put(Prefs.LAST_PROVIDER, p.id());
    }

    private void onThemeChanged(final Provider.@Nullable Theme t) {
        if (adjusting || t == null) {
            return;
        }
        final Provider p = provider();
        if (p != null && p.theme(t.id()).isPresent()) {
            prefs.putTheme(p.id(), t.id());
        }
        rebuild();
    }

    private void onLanguageChanged(final @Nullable String lang) {
        if (adjusting || lang == null) {
            return;
        }
        if (!langBox.getItems().contains(lang)) {
            langBox.getItems().add(lang);     // a file named one the list lacks; setAll would drop the value
        }
        // Remembered for the provider, whether chosen here or brought in by an opened project: either
        // way it is the language last used with it. Not for a map without one, where the language is
        // whatever the last map had and says nothing about this provider.
        final Provider p = provider();
        final Provider.Theme t = model.themeProperty().get();
        if (p != null && t != null && p.theme(t.id()).isPresent() && p.takesLanguage(t)) {
            prefs.putMapLang(p.id(), lang);
        }
        rebuild();
    }

    /**
     * What a source is made of. Rebuilding when none of it changed is the expensive no-op below.
     * <p>
     * The provider and the theme by value, not by id. Id is what {@link ProviderChoice} and
     * {@link ProjectModel}'s history compare a provider by, because a catalog reload must not
     * touch a project that still names the same one. This is a different question – whether
     * {@link TileCanvas#setSource} has to run again – and an edit keeps the id: without this,
     * editing the map in use left the toolbar and the download reading its new definition while
     * the tiles kept drawing under its old one, since nothing here had changed. Package-private
     * so a test can build one without the toolkit.
     */
    record SourceKey(@NotNull Provider provider, Provider.@NotNull Theme theme, @NotNull String lang,
                     @NotNull Path cacheRoot) { }

    private @Nullable SourceKey sourceKey;

    /**
     * Points the map at the current provider, theme, map language and cache.
     * <p>
     * <b>Only when something actually changed.</b> {@code setSource} builds a new
     * {@link net.trdlo.wtf.studio.map.TileImageCache}, which throws away every decoded tile, so
     * calling it unconditionally after the settings dialog closes would blank the whole map and
     * make it fade back in tile by tile. The user sees that as the
     * window blinking, and they are right.
     */
    void rebuild() {
        final Provider p = provider();
        final Provider.Theme t = model.themeProperty().get();
        if (p == null || t == null) {
            return;
        }
        // Only a map lettered in a language has one to choose; for the rest it would change nothing
        final boolean lettered = p.takesLanguage(t);
        for (final javafx.scene.Node n : List.of(langLabel, langBox)) {
            n.setVisible(lettered);
            n.setManaged(lettered);
        }
        final Path root = TileCache.resolveRoot(prefs.getPath(Prefs.CACHE_DIR));
        final SourceKey wanted = new SourceKey(p, t, model.mapLangProperty().get(), root);
        if (wanted.equals(sourceKey)) {
            map.requestPaint();
            viewChanged.run();
            return;
        }
        sourceKey = wanted;
        cache = cacheOver(root);
        map.setSource(p, t.id(), wanted.lang(), CachedTileSource.cacheOnly(cache, p),
                TileImageCache.defaultBudget());
        map.requestPaint();
        viewChanged.run();
    }

    /**
     * The cache as it is when the folder is still the same, or a new one over the new folder.
     * <p>
     * The cache remembers no misses, so it is the folder that decides, and one object serves the
     * window until Settings moves it – not a new one on every change of provider, map or language.
     */
    private @NotNull TileCache cacheOver(final @NotNull Path root) {
        return cache.root().equals(root.toAbsolutePath().normalize()) ? cache : new TileCache(root);
    }

    /**
     * Picks up added, changed, hidden and removed providers without a restart.
     * <p>
     * The selection is kept when it still exists. When it does not – the provider was removed or
     * hidden – the map falls back to the first one rather than sitting on a definition that is
     * no longer in the catalog.
     */
    void reloadProviders() {
        final String wanted = provider() == null ? null : provider().id();
        catalog = ProviderCatalog.load(ProviderStore.defaultFile());
        providerBox.getItems().setAll(orderedProviders());

        final Provider next = wanted == null ? null
                : catalog.find(wanted).filter(p -> !catalog.isHidden(p.id())).orElse(null);
        if (next != null) {
            // Same id, possibly a different definition: force the listeners to see the new one
            model.providerProperty().set(null);
            model.providerProperty().set(next);
        } else if (!providerBox.getItems().isEmpty()) {
            model.providerProperty().set(providerBox.getItems().getFirst());
        }
        log.info("Provider catalog reloaded: {} known, {} hidden",
                catalog.size(), catalog.hidden().size());
    }
}
