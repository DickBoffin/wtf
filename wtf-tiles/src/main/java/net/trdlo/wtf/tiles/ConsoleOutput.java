package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

import java.io.PrintStream;
import java.util.Locale;

/**
 * A console stream that spells the en dash, the ellipsis and the multiplication sign in ASCII where
 * its charset lacks any of them, rather than printing {@code ?} for them.
 * <p>
 * The engine's English is written with all three. Since JDK 19 {@code System.out}'s charset is the
 * Windows console's own code page, and the ones still common there, 852 and 437, lack the en dash
 * and the ellipsis, and 437 the multiplication sign too; a UTF-8 terminal, or output sent to a file
 * in the Windows ANSI code page, has them all and keeps them. Only these three characters are
 * touched, and only on the way to such a stream: the message an exception carries keeps its en
 * dash for every other reader.
 * <p>
 * The stream's numbers are its caller's to write: a {@code printf} without a locale is the
 * machine's here as anywhere, so every one in the command lines passes {@link Locale#ROOT}.
 */
public final class ConsoleOutput {

    private ConsoleOutput() {
    }

    /**
     * {@code out} as it is when its charset can write all three characters, else wrapped so that
     * everything it prints is {@link #sanitize}d.
     */
    public static @NotNull PrintStream wrap(final @NotNull PrintStream out) {
        if (out.charset().newEncoder().canEncode("–…×")) {
            return out;
        }
        return new PrintStream(out, true, out.charset()) {
            @Override
            public void print(final String s) {
                super.print(sanitize(s));
            }

            @Override
            public void println(final String x) {
                super.println(sanitize(x));
            }

            @Override
            public PrintStream printf(final Locale l, final String format, final Object... args) {
                print(String.format(l, format, args));
                return this;
            }
        };
    }

    /**
     * Points logback at the command lines' own configuration, {@code wtf-cli-logback.xml}, unless
     * the caller named one with {@code -Dlogback.configurationFile}. Without it logback writes
     * everything down to DEBUG to stdout, into the middle of a {@code --json} answer; with it a
     * warning goes to stderr and the rest only when {@code -Dwtf.log.level} asks for it. Called
     * first in {@code main}: logback reads its configuration once, when the first logger is made.
     */
    public static void configureLogging() {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "wtf-cli-logback.xml");
        }
    }

    /**
     * The en dash, the ellipsis and the multiplication sign, spelled in ASCII; every other
     * character passes through untouched.
     */
    static @NotNull String sanitize(final String s) {
        if (s == null) {
            return "null";
        }
        return s.indexOf('–') < 0 && s.indexOf('…') < 0 && s.indexOf('×') < 0
                ? s
                : s.replace('–', '-').replace("…", "...").replace('×', 'x');
    }
}
