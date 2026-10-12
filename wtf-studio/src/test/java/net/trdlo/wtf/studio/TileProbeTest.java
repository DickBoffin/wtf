package net.trdlo.wtf.studio;

import com.sun.net.httpserver.HttpServer;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.TileKey;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Test of the provider editor and of the Providers dialog, run as their tasks run it –
 * {@link TileProbe#run} on the tile each one's {@code probeTile} picks – against a server in this
 * process, into a folder the test watches. Whatever the server answers, nothing is left in it.
 */
class TileProbeTest {

    private final Locale before = I18n.locale();
    private HttpServer server;
    /** What the server answers: 200 with a PNG, or this status with nothing. */
    private final AtomicInteger status = new AtomicInteger(200);
    /** The pixels of what it answers a {@code @2x} address with: 512, or 256 for a server that ignores it. */
    private final AtomicInteger retinaPixels = new AtomicInteger(512);
    private Provider provider;

    @BeforeEach
    void start() throws IOException {
        I18n.setLocale(Locale.ENGLISH);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final int code = status.get();
            final byte[] png = png(exchange.getRequestURI().getPath().contains("@2x") ? retinaPixels.get() : 256);
            if (code == 200) {
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
            } else {
                exchange.sendResponseHeaders(code, -1);
            }
            exchange.close();
        });
        server.start();
        provider = new Provider("probe-test", "Probe Test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{z}/{x}/{y}.png", null, 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, null, null);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        I18n.setLocale(before);
    }

    @Test
    @DisplayName("the editor's Test says what came back and leaves no folder of its own")
    void theEditorsTestLeavesNothing(@TempDir final Path parent) throws IOException {
        final String said = TileProbe.run(provider, ProviderEditor.probeTile(provider), null, parent);

        assertTrue(said.startsWith("A tile came back: PNG"), said);
        assertEmpty(parent);
    }

    @Test
    @DisplayName("the Providers dialog's Test says what came back and leaves no folder of its own")
    void theDialogsTestLeavesNothing(@TempDir final Path parent) throws IOException {
        final String said = TileProbe.run(provider, ProvidersDialog.probeTile(provider), null, parent);

        assertTrue(said.startsWith("A tile came back: PNG"), said);
        assertEmpty(parent);
    }

    @Test
    @DisplayName("a map with @2x tiles is asked at both scales, and the Test says each one's pixels")
    void retinaIsAskedForAndMeasured(@TempDir final Path parent) throws IOException {
        final Provider retina = new Provider("retina-test", "Retina Test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{z}/{x}/{y}{scale}.png", null, 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, null, null);
        final TileKey tile = ProvidersDialog.probeTile(retina);

        assertEquals("A tile came back: PNG, 256 × 256 px, " + I18n.bytes(png(256).length)
                        + ". At double density: 512 × 512 px, " + I18n.bytes(png(512).length) + ".",
                TileProbe.run(retina, tile, null, parent));
        assertEmpty(parent);

        // A server that ignores the @2x answers 200 all the same, and only its pixels say so
        retinaPixels.set(256);
        assertTrue(TileProbe.run(retina, tile, null, parent).endsWith(
                "At double density the server sends the usual tile: this map has no @2x tiles."));
        assertEmpty(parent);

        // A map without the token is asked once
        assertFalse(TileProbe.run(provider, ProvidersDialog.probeTile(provider), null, parent).contains("double"));
    }

    @Test
    @DisplayName("a probe that fails leaves no folder either, whether it says so or throws")
    void aFailedProbeLeavesNothing(@TempDir final Path parent) {
        final TileKey tile = ProviderEditor.probeTile(provider);
        for (final int code : new int[] {500, 403, 404}) {
            status.set(code);
            try {
                final String said = TileProbe.run(provider, tile, null, parent);
                assertFalse(said.startsWith("A tile came back"), code + ": " + said);
            } catch (final IOException | RuntimeException thrown) {
                // what the task's failure handler says; the folder is what this test is about
            }
            assertEmpty(parent);
        }
    }

    /**
     * Both Tests ask a map lettered in a language in its provider's own (D40) – the first
     * it lists, which is one its server takes – and a map of a provider that lists none in English.
     */
    @Test
    @DisplayName("both Tests ask a lettered map in its provider's first language, or in English")
    void theProbesAskInTheProvidersOwnLanguage() {
        final Provider listing = lettered(List.of("de", "fr"));
        assertEquals("de", ProviderEditor.probeTile(listing).lang());
        assertEquals("de", ProvidersDialog.probeTile(listing).lang(), "not English, which it does not list");
        final Provider listingNone = lettered(null);
        assertEquals("en", ProviderEditor.probeTile(listingNone).lang());
        assertEquals("en", ProvidersDialog.probeTile(listingNone).lang());
    }

    /** A provider whose one map is lettered in the language asked for, and takes these. */
    private static Provider lettered(final @Nullable List<String> languages) {
        return new Provider("lettered", "Lettered", "http://127.0.0.1:1/{z}/{x}/{y}.png?lang={lang}", null,
                0, 19, null, List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, languages, null);
    }

    private static void assertEmpty(final Path parent) {
        try (final Stream<Path> left = Files.list(parent)) {
            assertEquals(List.of(), left.toList());
        } catch (final IOException ex) {
            fail(ex);
        }
    }

    private static byte[] png(final int pixels) throws IOException {
        final var img = new java.awt.image.BufferedImage(pixels, pixels, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
