package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.TileKey;

import java.util.List;

/**
 * Which map and which language a provider opens on when it is chosen: the ones last used with it,
 * as far as it still has them.
 * <p>
 * Apart from {@link MapSource} so that it can be tested without the toolkit, which is where the
 * mistakes were: the map compared by value landed on a provider's first map on every switch.
 */
final class ProviderChoice {

    private ProviderChoice() {
    }

    /**
     * The map last used with the provider if it still has it, else the current one if it has a map
     * of that id, else its first.
     */
    static Provider.@NotNull Theme theme(final @NotNull Provider p, final @Nullable String remembered,
                                         final Provider.@Nullable Theme current) {
        if (remembered != null) {
            final var kept = p.theme(remembered);
            if (kept.isPresent()) {
                return kept.get();
            }
        }
        if (current != null) {
            final var same = p.theme(current.id());
            if (same.isPresent()) {
                return same.get();
            }
        }
        return p.themes().getFirst();
    }

    /**
     * The language last used with the provider if it takes it, else the current one if it takes
     * that, else the one {@link MapLanguages#fitting} picks from its list. A provider that names no
     * languages takes any code.
     */
    static @NotNull String language(final @NotNull Provider p, final @Nullable String remembered,
                                    final @Nullable String current) {
        final List<String> listed = p.languages();
        if (remembered != null && takes(listed, remembered)) {
            return remembered;
        }
        if (current != null && takes(listed, current)) {
            return current;
        }
        return listed == null ? ProjectModel.defaultMapLang() : MapLanguages.fitting(listed);
    }

    private static boolean takes(final @Nullable List<String> listed, final @NotNull String lang) {
        return TileKey.LANG.matcher(lang).matches() && (listed == null || listed.contains(lang));
    }
}
