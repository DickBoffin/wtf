package net.trdlo.wtf.studio;

import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileDownloader;
import net.trdlo.wtf.tiles.TileFormat;
import net.trdlo.wtf.tiles.TileKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Test of the Providers dialog and of the provider editor: one tile fetched into a folder of
 * its own, what came back said, and the folder deleted, whatever came back. Not the tile cache,
 * which a test of an address as typed has no business filling.
 * <p>
 * Where the map offers {@code @2x} tiles, the same tile is asked for at that scale too, and the
 * answer says its pixels: a server that ignores the {@code @2x} answers with a 256 px tile and a
 * 200, so the pixels are the only proof, which the downloader checks.
 */
final class TileProbe {

    private static final Logger log = LoggerFactory.getLogger(TileProbe.class);

    private TileProbe() {
    }

    /** {@link #run(Provider, TileKey, String, Path)} in the system's temporary folder. */
    static @NotNull String run(final @NotNull Provider provider, final @NotNull TileKey tile,
                               final @Nullable String key) throws IOException {
        return run(provider, tile, key, Path.of(System.getProperty("java.io.tmpdir")));
    }

    /**
     * Fetches {@code tile} and says what it is, or why there is none.
     *
     * @param parent where the probe's folder is made; it is gone again when this returns or throws
     */
    static @NotNull String run(final @NotNull Provider provider, final @NotNull TileKey tile,
                               final @Nullable String key, final @NotNull Path parent) throws IOException {
        final Path folder = Files.createTempDirectory(parent, "wtf-probe");
        try {
            final TileCache cache = new TileCache(folder);
            try (final TileDownloader downloader = new TileDownloader(cache, 1, AppInfo.get().userAgent())) {
                final TileDownloader.Report r = downloader.download(provider, List.of(tile), key,
                        ProgressSink.IGNORE);
                final byte[] data = r.downloaded() == 0 ? null : cache.read(tile);
                if (data == null) {
                    return I18n.format("providers.test.failed", ProgressText.report(r));
                }
                final String one = I18n.format("providers.test.ok", TileFormat.sniff(data), pixels(data),
                        I18n.bytes(data.length));
                if (!provider.offersRetina(provider.requireTheme(tile.themeId()))) {
                    return one;
                }
                final TileKey retina = tile.atScale(2);
                final TileDownloader.Report r2 = downloader.download(provider, List.of(retina), key,
                        ProgressSink.IGNORE);
                final byte[] data2 = r2.downloaded() == 0 ? null : cache.read(retina);
                if (data2 != null) {
                    return I18n.format("providers.test.retina", one, pixels(data2), I18n.bytes(data2.length));
                }
                // The downloader's own check: the answer came at the usual size
                return cache.knowsNoRetina(tile.providerId(), tile.themeId())
                        ? I18n.format("providers.test.retina.ignored", one)
                        : I18n.format("providers.test.retina.failed", one, ProgressText.report(r2));
            }
        } finally {
            delete(folder);
        }
    }

    /** "256 × 256", or "?" for an image this Java cannot read the size of. */
    static @NotNull String pixels(final byte @NotNull [] data) {
        try {
            final BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            return img == null ? "?" : I18n.format("providers.test.pixels", img.getWidth(), img.getHeight());
        } catch (final IOException ex) {
            return "?";
        }
    }

    /** The folder and everything in it, deepest first; what cannot be deleted is logged, not thrown. */
    private static void delete(final @NotNull Path folder) {
        try (final Stream<Path> walk = Files.walk(folder)) {
            for (final Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (final IOException ex) {
            log.warn("Could not delete the probe's folder {}", folder, ex);
        }
    }
}
