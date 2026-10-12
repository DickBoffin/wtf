///usr/bin/env java --source 25 "$0" "$@"; exit $?

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the application icon and writes every shape the platforms want.
 *
 *   java tools/icon/IconGen.java <output directory>
 *
 * Run it, commit what it writes; it is not part of the build. It exists so the icon set can be
 * regenerated from one definition rather than from eight PNGs somebody has to keep in step.
 *
 * <p><b>The mark is {@code wtf.svg}</b>, rendered by Inkscape at every size, so a change to the
 * artwork is a change to one file and a run of this. It needs {@code inkscape} on the path.
 *
 * <p>Formats written:
 * <ul>
 *   <li>PNG at 16..512, for JavaFX's {@code Stage.getIcons()} and for Linux packaging</li>
 *   <li>{@code icon.ico}, a Vista-style icon with PNG payloads, for the Windows installer</li>
 *   <li>{@code icon.icns}, for the macOS one</li>
 * </ul>
 */
public final class IconGen {

    /** JavaFX picks the closest of these; Windows and macOS want specific members. */
    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256, 512};

    /** The artwork, the one definition every size is rendered from. */
    private static final Path ARTWORK = Path.of("wtf-studio/src/main/resources/net/trdlo/wtf/studio/wtf.svg");

    public static void main(final String[] args) throws IOException {
        final Path out = Path.of(args.length > 0 ? args[0] : "wtf-studio/src/main/resources/net/trdlo/wtf/studio");
        Files.createDirectories(out);

        final Map<Integer, byte[]> pngs = new LinkedHashMap<>();
        for (final int size : SIZES) {
            final byte[] png = render(size);
            pngs.put(size, png);
            Files.write(out.resolve("icon-" + size + ".png"), png);
        }
        // What WtfApp loads by default
        Files.write(out.resolve("icon.png"), pngs.get(256));

        Files.write(out.resolve("icon.ico"), ico(pngs, new int[]{16, 24, 32, 48, 64, 128, 256}));
        Files.write(out.resolve("icon.icns"), icns(pngs));

        System.out.println("Wrote " + (SIZES.length + 3) + " files into " + out.toAbsolutePath());
    }

    // -------------------------------------------------------------------------------------------
    // The mark
    // -------------------------------------------------------------------------------------------

    /**
     * One size of the artwork, rendered by Inkscape.
     * <p>
     * Not by Java: the mark is set in a heavy typeface, and a text renderer that has to find
     * "Arial Black" on whatever machine runs this is the one that gets it right; Java2D would need
     * the SVG parsed first and would still guess at the font.
     */
    private static byte[] render(final int size) throws IOException {
        final Path png = Files.createTempFile("wtf-icon-" + size + "-", ".png");
        try {
            final Process p = new ProcessBuilder("inkscape", ARTWORK.toString(),
                    "--export-type=png", "--export-width=" + size, "--export-height=" + size,
                    "--export-filename=" + png)
                    .redirectErrorStream(true).start();
            final String output = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0 || Files.size(png) == 0) {
                throw new IOException("Inkscape could not render " + ARTWORK + " at " + size + " px: " + output);
            }
            return Files.readAllBytes(png);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        } finally {
            Files.deleteIfExists(png);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Containers. Both are short enough that a dependency would cost more than the code.
    // -------------------------------------------------------------------------------------------

    /**
     * A Windows icon whose entries are PNGs, which Vista and later read directly. The width and
     * height bytes are zero for 256, because the field is one byte and 256 does not fit - that is
     * the format's own convention, not a bug.
     */
    private static byte[] ico(final Map<Integer, byte[]> pngs, final int[] sizes) throws IOException {
        final List<byte[]> entries = new ArrayList<>();
        for (final int size : sizes) {
            entries.add(pngs.get(size));
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteBuffer header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0);                 // reserved
        header.putShort((short) 1);                 // type: icon
        header.putShort((short) entries.size());
        out.write(header.array());

        int offset = 6 + 16 * entries.size();
        for (int i = 0; i < entries.size(); i++) {
            final int size = sizes[i];
            final byte[] data = entries.get(i);
            final ByteBuffer e = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            e.put((byte) (size >= 256 ? 0 : size));
            e.put((byte) (size >= 256 ? 0 : size));
            e.put((byte) 0);                        // palette entries
            e.put((byte) 0);                        // reserved
            e.putShort((short) 1);                  // color planes
            e.putShort((short) 32);                 // bits per pixel
            e.putInt(data.length);
            e.putInt(offset);
            out.write(e.array());
            offset += data.length;
        }
        for (final byte[] data : entries) {
            out.write(data);
        }
        return out.toByteArray();
    }

    /** An Apple icon set. The {@code ic0x} types take PNG payloads, which keeps this short. */
    private static byte[] icns(final Map<Integer, byte[]> pngs) throws IOException {
        record Entry(String type, int size) { }
        final List<Entry> wanted = List.of(
                new Entry("icp4", 16), new Entry("icp5", 32), new Entry("icp6", 64),
                new Entry("ic07", 128), new Entry("ic08", 256), new Entry("ic09", 512),
                new Entry("ic11", 32), new Entry("ic12", 64), new Entry("ic13", 256),
                new Entry("ic14", 512));

        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (final Entry entry : wanted) {
            final byte[] data = pngs.get(entry.size());
            if (data == null) {
                continue;
            }
            write4(body, entry.type());
            writeInt(body, 8 + data.length);
            body.write(data);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        write4(out, "icns");
        writeInt(out, 8 + body.size());
        body.writeTo(out);
        return out.toByteArray();
    }

    private static void write4(final OutputStream out, final String s) throws IOException {
        out.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeInt(final OutputStream out, final int v) throws IOException {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array());
    }
}
