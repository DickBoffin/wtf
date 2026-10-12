package net.trdlo.wtf.tiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The en dash, the ellipsis and the multiplication sign, spelled in ASCII on a console whose code
 * page lacks any of them, and left alone where it has them.
 */
class ConsoleOutputTest {

    /** Code page 852, a Czech Windows console's. */
    private static final Charset CONSOLE_852 = Charset.forName("IBM852");

    private static String captured(final Charset charset, final java.util.function.Consumer<PrintStream> use) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream wrapped = ConsoleOutput.wrap(new PrintStream(bytes, true, charset));
        use.accept(wrapped);
        return bytes.toString(charset);
    }

    private static String captured(final java.util.function.Consumer<PrintStream> use) {
        return captured(CONSOLE_852, use);
    }

    @Test
    @DisplayName("a UTF-8 terminal keeps all three characters")
    void aUtf8TerminalKeepsThem() {
        final PrintStream utf8 = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertSame(utf8, ConsoleOutput.wrap(utf8));
        assertEquals("blocked – not rejected… 3 × 4",
                captured(StandardCharsets.UTF_8, p -> p.println("blocked – not rejected… 3 × 4")).strip());
    }

    @Test
    @DisplayName("println substitutes the en dash and the ellipsis")
    void printlnIsSanitized() {
        final String out = captured(p -> p.println("blocked – not rejected…"));
        assertEquals("blocked - not rejected...", out.strip());
    }

    @Test
    @DisplayName("printf substitutes them too, in the format string and in an argument alike")
    void printfIsSanitized() {
        final String out = captured(p -> p.printf(Locale.ROOT, "%s–%s%n", "a", "b…"));
        assertEquals("a-b...", out.strip());
    }

    @Test
    @DisplayName("code page 437, which lacks the multiplication sign, gets an x for it")
    void theMultiplicationSignSurvivesCodePage437() {
        final String out = captured(Charset.forName("IBM437"),
                p -> p.println("Print size must be positive: 0 × 200 mm"));
        assertEquals("Print size must be positive: 0 x 200 mm", out.strip());
    }

    @Test
    @DisplayName("every other character passes through untouched")
    void everythingElsePassesThrough() {
        final String text = "Provider 'x' rejected HTTP 403 - the API key is wrong (100 %)";
        assertEquals(text, captured(p -> p.print(text)));
    }

    @Test
    @DisplayName("sanitize alone: only the en dash, the ellipsis and the multiplication sign are touched")
    void sanitizeIsExact() {
        assertEquals("a-b...c", ConsoleOutput.sanitize("a–b…c"));
        assertEquals("3 x 4", ConsoleOutput.sanitize("3 × 4"));
        assertEquals("unchanged", ConsoleOutput.sanitize("unchanged"));
        assertEquals("null", ConsoleOutput.sanitize(null));
    }
}
