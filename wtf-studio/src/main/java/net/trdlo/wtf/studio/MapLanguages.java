package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.TileKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The languages a map lettered in one can be asked for, as the toolbar offers them.
 * <p>
 * The map's language, not the interface's: a Czech user printing a map for a German hall wants
 * German names on the paper and Czech words in the window. The two are never tied together.
 * <p>
 * The list is the provider's ({@link Provider#languages()}): the codes its server takes, which only
 * its definition can know. A provider that names none leaves the choice open, and the toolbar's
 * box then takes any code typed into it, with the interface's languages offered as a start.
 */
final class MapLanguages {

    private MapLanguages() {
    }

    /**
     * The offered languages, with the current one among them: a project file may name a language
     * the list does not have, and a choice that cannot show what is chosen would change it.
     */
    static @NotNull List<String> offered(final @Nullable Provider p, final @Nullable String current) {
        final List<String> listed = p == null ? null : p.languages();
        final List<String> all = new ArrayList<>(listed != null ? listed
                : I18n.LANGUAGES.stream().map(Locale::getLanguage).toList());
        if (current != null && TileKey.LANG.matcher(current).matches() && !all.contains(current)) {
            all.add(current);
        }
        return all;
    }

    /**
     * The language to switch to when a provider that names its languages does not name the current
     * one: the system's where it is listed, then English, then the first. Asking the server for a
     * language it does not take gets its default, which for Mapy.com is Czech.
     */
    static @NotNull String fitting(final @NotNull List<String> listed) {
        final String system = ProjectModel.defaultMapLang();
        if (listed.contains(system)) {
            return system;
        }
        return listed.contains("en") ? "en" : listed.getFirst();
    }

    /**
     * What was typed into the box, as a language: one of the offered ones by its name or its code,
     * or any other code; null for text that is neither, which keeps the language there was.
     */
    static @Nullable String fromTyped(final @Nullable String typed, final @NotNull List<String> offered) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        final String text = typed.trim();
        for (final String tag : offered) {
            if (tag.equalsIgnoreCase(text) || nameOf(tag).equalsIgnoreCase(text)) {
                return tag;
            }
        }
        return TileKey.LANG.matcher(text).matches() ? text : null;
    }

    /** A language as the list shows it: in that language, "Deutsch", so it is found by its speaker. */
    static @NotNull String nameOf(final @Nullable String tag) {
        return tag == null || tag.isBlank() ? "" : I18n.nativeName(Locale.forLanguageTag(tag));
    }
}
