package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The image format of a tile, <b>decided from the bytes and never from the extension</b>.
 * <p>
 * Sixteen provider themes were probed directly (docs/design/providers.md §4) and they returned
 * seven JPEG, five RGBA PNG, two RGB PNG and two indexed PNG – so format is a per-theme property,
 * and the indexed PNG the existing cache is full of is among the rarer ones. Anything that assumes
 * otherwise is a bug waiting for the first aerial print.
 */
public enum TileFormat {

    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
    /** Not an image at all – usually an error page served with HTTP 200. Never cached. */
    UNKNOWN("bin", "application/octet-stream");

    private final String extension;
    private final String contentType;

    TileFormat(final @NotNull String extension, final @NotNull String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public @NotNull String extension() {
        return extension;
    }

    public @NotNull String contentType() {
        return contentType;
    }

    public boolean isImage() {
        return this != UNKNOWN;
    }

    /** Extensions probed by the cache when looking a tile up, in order. */
    public static final String[] KNOWN_EXTENSIONS = {"png", "jpg", "jpeg", "webp"};

    public static @NotNull TileFormat ofExtension(final @NotNull String ext) {
        return switch (ext.toLowerCase(java.util.Locale.ROOT)) {
            case "png" -> PNG;
            case "jpg", "jpeg" -> JPEG;
            case "webp" -> WEBP;
            default -> UNKNOWN;
        };
    }

    /** The format the bytes actually are. This is the authority. */
    public static @NotNull TileFormat sniff(final byte @Nullable [] data) {
        if (data == null || data.length < 12) {
            return UNKNOWN;
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == '\r' && data[5] == '\n' && (data[6] & 0xFF) == 0x1A && data[7] == '\n') {
            return PNG;
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return WEBP;
        }
        return UNKNOWN;
    }

    /**
     * The image's width in pixels as its header states it, or -1 when the header cannot be read –
     * enough to tell a {@code @2x} tile from a 256 px one without decoding it, which this module
     * has nothing to do with.
     */
    public static int widthOf(final byte @Nullable [] data) {
        try {
            return switch (sniff(data)) {
                // IHDR is the first chunk: its width is the four bytes after its type
                case PNG -> data.length < 24 ? -1 : int32(data, 16);
                case JPEG -> jpegWidth(data);
                case WEBP -> webpWidth(data);
                default -> -1;
            };
        } catch (final ArrayIndexOutOfBoundsException truncated) {
            return -1;
        }
    }

    /** The width in the first start-of-frame segment, walking the segments before it. */
    private static int jpegWidth(final byte @NotNull [] d) {
        int i = 2;
        while (i + 9 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                return -1;
            }
            final int marker = d[i + 1] & 0xFF;
            // SOF0..SOF15, but not DHT, JPG and DAC, which share the range
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return (d[i + 7] & 0xFF) << 8 | d[i + 8] & 0xFF;
            }
            i += 2 + ((d[i + 2] & 0xFF) << 8 | d[i + 3] & 0xFF);
        }
        return -1;
    }

    /** Lossy, lossless or extended WebP, each of which keeps the size somewhere else. */
    private static int webpWidth(final byte @NotNull [] d) {
        if (d[12] == 'V' && d[13] == 'P' && d[14] == '8') {
            return switch (d[15]) {
                case ' ' -> (d[26] & 0xFF | (d[27] & 0xFF) << 8) & 0x3FFF;
                case 'L' -> 1 + (d[21] & 0xFF | (d[22] & 0x3F) << 8);
                case 'X' -> 1 + (d[24] & 0xFF | (d[25] & 0xFF) << 8 | (d[26] & 0xFF) << 16);
                default -> -1;
            };
        }
        return -1;
    }

    private static int int32(final byte @NotNull [] d, final int at) {
        return (d[at] & 0xFF) << 24 | (d[at + 1] & 0xFF) << 16 | (d[at + 2] & 0xFF) << 8 | d[at + 3] & 0xFF;
    }

    /**
     * What the response said it was, for the log line when it disagrees with {@link #sniff}.
     * A {@code Content-Type} of {@code image/*} is a hint; the bytes are the decision.
     */
    public static @NotNull TileFormat ofContentType(final @Nullable String contentType) {
        if (contentType == null) {
            return UNKNOWN;
        }
        final String ct = contentType.toLowerCase(java.util.Locale.ROOT);
        if (ct.startsWith("image/png")) {
            return PNG;
        }
        if (ct.startsWith("image/jpeg") || ct.startsWith("image/jpg")) {
            return JPEG;
        }
        if (ct.startsWith("image/webp")) {
            return WEBP;
        }
        return UNKNOWN;
    }
}
