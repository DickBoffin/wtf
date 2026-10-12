package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.Provider;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Words somebody typed, made into one file name that stays in the folder it is put in.
 * <p>
 * A project's title and its base name are both free text, and neither can be a file name
 * verbatim: {@code 1:25 000} is not a name Windows will write, {@code Brno/Jih} is a folder that
 * does not exist, and {@code ..\..\x} is a file somewhere else entirely. Letters of any alphabet
 * are kept – {@code Plzeň} stays {@code Plzeň} – and only what a file system refuses, or reads as
 * a path, is replaced.
 */
public final class FileNames {

    /** What Windows refuses in a name, which is a superset of what the other systems refuse. */
    private static final Pattern REFUSED = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    /**
     * Room left for what the exporters append – {@code -s01-proof.pdf} and the like – inside the
     * 255 characters most file systems allow.
     */
    private static final int MAX_LENGTH = 200;

    private FileNames() {
    }

    /**
     * A name safe to resolve against a folder, or {@code fallback} when nothing of the text is.
     * Each refused character becomes an underscore; a name Windows would trim or take for a device
     * is changed so it does neither.
     */
    public static @NotNull String safe(final @NotNull String text, final @NotNull String fallback) {
        String name = REFUSED.matcher(text).replaceAll("_").strip();
        if (name.length() > MAX_LENGTH) {
            name = name.substring(0, MAX_LENGTH);
        }
        // Windows drops trailing dots and spaces, so "a." and "a" would be one file; ".." is a path
        name = name.replaceAll("[. ]+$", "");
        if (name.isEmpty()) {
            return fallback;
        }
        if (Provider.DEVICE_NAME.matcher(name).matches()) {
            name = "_" + name;
        }
        return name;
    }
}
