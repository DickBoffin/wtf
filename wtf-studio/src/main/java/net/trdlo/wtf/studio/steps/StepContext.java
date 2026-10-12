package net.trdlo.wtf.studio.steps;

import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.studio.Prefs;
import net.trdlo.wtf.studio.map.TileCanvas;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What a panel needs from the window, without needing the window.
 * <p>
 * Passing {@code MainWindow} into five panels would make every one of them able to reach every
 * other, which is the shortest road to a panel that only works if another one was opened first.
 * This is the whole surface: somewhere to parent a dialog, the map to draw on, the settings, the
 * providers and the cache – suppliers both, asked at the time, because the Providers dialog
 * replaces the one and the settings dialog can move the other – and the
 * diskette button's save, so that a panel asking "save first?" saves exactly the way it does:
 * to the project's file, or through the file picker when there is none yet – and the Save as
 * button's, which always asks. Each answers whether anything was saved; a picker closed without
 * choosing is a no. {@code openProviders} is the same
 * hand-off a rejected key gets from the window itself: the Providers dialog, selecting the given
 * provider, or the current one for {@code null}.
 */
public record StepContext(@NotNull Stage stage,
                          @NotNull TileCanvas map,
                          @NotNull Prefs prefs,
                          @NotNull Supplier<ProviderCatalog> catalog,
                          @NotNull Supplier<TileCache> cache,
                          java.util.function.@NotNull BooleanSupplier saveProject,
                          java.util.function.@NotNull BooleanSupplier saveProjectAs,
                          @NotNull Consumer<@Nullable Provider> openProviders) {

    public @NotNull TileCache tileCache() {
        return cache.get();
    }

    /**
     * The provider of that id as the catalog defines it now: one added in the Providers dialog
     * since the window was built is found, and one edited there is its edited copy.
     *
     * @throws IllegalArgumentException for an id the catalog does not know
     */
    public @NotNull Provider provider(final @NotNull String id) {
        return catalog.get().require(id);
    }
}
