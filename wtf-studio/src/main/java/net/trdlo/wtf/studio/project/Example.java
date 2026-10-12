package net.trdlo.wtf.studio.project;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.studio.I18n;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The projects bundled with the studio, which the Start page opens: each a {@code .wtf} file in
 * the studio's resources, as the editor writes one, and opened as a new project
 * ({@link ProjectIo#openExample}).
 * <p>
 * Their maps are swisstopo's and IGN's, which need no key and allow a whole print to be downloaded,
 * so each exports as it opens. Nothing of the maps is bundled: the window fetches what it shows,
 * and the export the print, as for any other project.
 * <p>
 * The files are what {@code ExampleProjectsTest}'s recipes make, and are made again from them when
 * the format changes: WTF reads its current format and nothing older.
 */
public enum Example {

    /** No subject, a Custom rectangle round the route, sized at a scale, with a scale bar. */
    HIKE("hike.wtf", "example.hike"),

    /** A subject from the library, the ground outside it not drawn, its outline drawn, one sheet. */
    POSTER("poster.wtf", "example.poster"),

    /** A roll in vertical strips, filling a size exactly, with names of two millimeters or more. */
    WALLPAPER("wallpaper.wtf", "example.wallpaper"),

    /** Sheets by the number across, and the marks for putting them together. */
    CITY("city.wtf", "example.city");

    /** Where the files are, in the studio's resources. */
    private static final String FOLDER = "/net/trdlo/wtf/studio/examples/";

    private final String file;
    private final String nameKey;

    Example(final @NotNull String file, final @NotNull String nameKey) {
        this.file = file;
        this.nameKey = nameKey;
    }

    /** The file's name, which is also what a refusal of it says. */
    public @NotNull String fileName() {
        return file;
    }

    /** The name its card on the Start page has, in the interface's language: the project's title too. */
    public @NotNull String title() {
        return I18n.get(nameKey);
    }

    /** The file as it is bundled. */
    byte @NotNull [] read() throws IOException {
        try (final InputStream in = Example.class.getResourceAsStream(FOLDER + file)) {
            if (in == null) {
                throw new FileNotFoundException(FOLDER + file);
            }
            return in.readAllBytes();
        }
    }
}
