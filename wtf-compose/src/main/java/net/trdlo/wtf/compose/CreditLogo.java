package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The logo a provider's credit is printed with, where its definition names one: Mapy.com's terms
 * ask for its logo with the attribution. Read from the SVG file WTF bundles beside the definitions
 * ({@link ProviderCatalog#logo}) into the shapes it is filled with, which both exporters draw – the
 * PDF as vector paths, a raster into its pixels – where {@link SeamMarks} puts it.
 * <p>
 * <b>A reader for what a logo is made of, not for SVG.</b> Paths of straight lines and cubic
 * curves, each of them one of PDF's own path operators, so that a PDF draws exactly what the file
 * says, filled in flat colors by either rule. Anything else in the file is refused by name rather
 * than drawn wrong: a stroke, a transform, a group, a gradient, text, an arc. Only the files WTF
 * bundles are ever read – the catalog refuses a definition that names another – and a test reads
 * every one of them.
 * <p>
 * <b>Opaque.</b> A fill's opacity is taken as the color it makes over white, and drawn opaque: a
 * RIP flattens a page that has anything transparent on it, and the copied tiles under it with it.
 * Mapy.com's letters are black at 80 percent, which over white is {@code #333333}.
 */
public final class CreditLogo {

    /** One shape of the logo, filled in one color by its own rule, in the file's units. */
    record Fill(@NotNull Path2D path, int rgb) { }

    /** The file's own box, its {@code viewBox}: what the PDF's form of it is clipped to. */
    private final Rectangle2D box;
    /** What the fills cover, which is the logo's extent on the page. */
    private final Rectangle2D painted;
    private final List<Fill> fills;

    private CreditLogo(final @NotNull Rectangle2D box, final @NotNull Rectangle2D painted,
                       final @NotNull List<Fill> fills) {
        this.box = box;
        this.painted = painted;
        this.fills = List.copyOf(fills);
    }

    /** Each file read once a run: the bundled ones do not change while it runs. */
    private static final Map<String, CreditLogo> READ = new ConcurrentHashMap<>();

    /**
     * The logo this provider's credit is printed with, or null for a credit in words only.
     *
     * @throws IllegalStateException when the file the definition names cannot be drawn, which no
     *                               build ships: a test reads every bundled logo
     */
    public static @Nullable CreditLogo of(final @NotNull Provider provider) {
        final String name = provider.logo();
        return name == null ? null : READ.computeIfAbsent(name, n -> {
            try {
                return read(ProviderCatalog.logo(n), n);
            } catch (final IllegalArgumentException ex) {
                throw new IllegalStateException("The logo " + n + " cannot be drawn: " + ex.getMessage(), ex);
            }
        });
    }

    /** How much wider than tall what it paints is: its box on the page is the credit's height by this. */
    public double aspect() {
        return painted.getWidth() / painted.getHeight();
    }

    /** What its fills cover, in the file's units, y down. */
    @NotNull Rectangle2D painted() {
        return (Rectangle2D) painted.clone();
    }

    /** The file's {@code viewBox}, in its units, y down. */
    @NotNull Rectangle2D box() {
        return (Rectangle2D) box.clone();
    }

    /** Its shapes in the order they are painted, the later over the earlier. */
    @NotNull List<Fill> fills() {
        return fills.stream().map(f -> new Fill((Path2D) f.path().clone(), f.rgb())).toList();
    }

    // -------------------------------------------------------------------------------------------

    /** What the root may say: its size, the box the drawing is in, and the fill its shapes inherit. */
    private static final Set<String> SVG_ATTRIBUTES = Set.of("xmlns", "version", "width", "height", "viewBox", "fill");

    /**
     * What a shape may say. {@code clip-rule} too, which applies to a shape only inside a clipping
     * path, and so changes nothing about one drawn.
     */
    private static final Set<String> PATH_ATTRIBUTES = Set.of("d", "fill", "fill-opacity", "fill-rule", "clip-rule");

    /**
     * Reads an SVG logo.
     *
     * @param name what to call it in a refusal
     * @throws IllegalArgumentException saying what in the file this reader does not draw, or why it
     *                                  is not one
     */
    static @NotNull CreditLogo read(final byte @NotNull [] svg, final @NotNull String name) {
        final Element root;
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // No DTD, so no entity: the file is bundled, and is read as if it were not
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(svg)).getDocumentElement();
        } catch (final ParserConfigurationException | SAXException | IOException ex) {
            throw new IllegalArgumentException(name + " is not XML: " + ex.getMessage(), ex);
        }
        if (!"svg".equals(root.getTagName())) {
            throw refused(name, "its root is <" + root.getTagName() + ">, not <svg>");
        }
        only(root, SVG_ATTRIBUTES, name);
        final Rectangle2D box = viewBox(root.getAttribute("viewBox"), name);
        // What a shape that names no fill of its own is filled with: the root's, or black
        final String inherited = root.hasAttribute("fill") ? root.getAttribute("fill") : "black";

        final List<Fill> fills = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            switch (n.getNodeType()) {
                case Node.ELEMENT_NODE -> {
                    final Element e = (Element) n;
                    if (!"path".equals(e.getTagName())) {
                        throw refused(name, "it has a <" + e.getTagName() + ">, and this reader draws <path> only");
                    }
                    only(e, PATH_ATTRIBUTES, name);
                    final String fill = e.hasAttribute("fill") ? e.getAttribute("fill") : inherited;
                    if ("none".equals(fill)) {
                        continue;
                    }
                    final double opacity = e.hasAttribute("fill-opacity")
                            ? opacity(e.getAttribute("fill-opacity"), name) : 1;
                    final boolean evenOdd = switch (e.hasAttribute("fill-rule") ? e.getAttribute("fill-rule") : "nonzero") {
                        case "nonzero" -> false;
                        case "evenodd" -> true;
                        default -> throw refused(name, "a fill rule '" + e.getAttribute("fill-rule") + "'");
                    };
                    fills.add(new Fill(path(e.getAttribute("d"), evenOdd, name), overWhite(color(fill, name), opacity)));
                }
                case Node.TEXT_NODE -> {
                    if (!n.getTextContent().isBlank()) {
                        throw refused(name, "it has text outside its shapes");
                    }
                }
                case Node.COMMENT_NODE -> {
                    // Draws nothing
                }
                default -> throw refused(name, "it has a node of type " + n.getNodeType());
            }
        }
        if (fills.isEmpty()) {
            throw refused(name, "it fills nothing");
        }
        // The curves' own extremes, not their control points: what is seen is what is measured
        Rectangle2D painted = fills.getFirst().path().getBounds2D();
        for (final Fill f : fills) {
            painted = painted.createUnion(f.path().getBounds2D());
        }
        // The PDF clips a form to its box and a raster would not: one rule for both, none needed
        final double slack = 1e-6 * Math.max(box.getWidth(), box.getHeight());
        final Rectangle2D within = new Rectangle2D.Double(box.getX() - slack, box.getY() - slack,
                box.getWidth() + 2 * slack, box.getHeight() + 2 * slack);
        if (!within.contains(painted)) {
            throw refused(name, "it draws outside its viewBox");
        }
        return new CreditLogo(box, painted, fills);
    }

    private static @NotNull IllegalArgumentException refused(final @NotNull String name, final @NotNull String why) {
        return new IllegalArgumentException("WTF cannot draw the logo " + name + ": " + why);
    }

    /** Refuses an attribute that would change the drawing in a way this reader does not follow. */
    private static void only(final @NotNull Element e, final @NotNull Set<String> allowed, final @NotNull String name) {
        final NamedNodeMap attributes = e.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            final String attribute = attributes.item(i).getNodeName();
            if (!allowed.contains(attribute) && !attribute.startsWith("xmlns:")) {
                throw refused(name, "its <" + e.getTagName() + "> says " + attribute);
            }
        }
    }

    private static @NotNull Rectangle2D viewBox(final @NotNull String value, final @NotNull String name) {
        final PathData in = new PathData(value, name);
        final double[] v = new double[4];
        for (int i = 0; i < v.length; i++) {
            if (in.atEnd()) {
                throw refused(name, "its viewBox is not four numbers: '" + value + "'");
            }
            v[i] = in.number();
        }
        if (!in.atEnd() || !(v[2] > 0) || !(v[3] > 0)) {
            throw refused(name, "its viewBox is not a box: '" + value + "'");
        }
        return new Rectangle2D.Double(v[0], v[1], v[2], v[3]);
    }

    private static double opacity(final @NotNull String value, final @NotNull String name) {
        final PathData in = new PathData(value, name);
        final double opacity = in.atEnd() ? Double.NaN : in.number();
        if (!in.atEnd() || !(opacity >= 0 && opacity <= 1)) {
            throw refused(name, "a fill-opacity of '" + value + "'");
        }
        return opacity;
    }

    /** {@code #rrggbb}, {@code #rgb}, or black or white by name: what a logo's colors are written as. */
    private static int color(final @NotNull String value, final @NotNull String name) {
        if (value.matches("#[0-9a-fA-F]{6}")) {
            return Integer.parseInt(value.substring(1), 16);
        }
        if (value.matches("#[0-9a-fA-F]{3}")) {
            final int r = Character.digit(value.charAt(1), 16);
            final int g = Character.digit(value.charAt(2), 16);
            final int b = Character.digit(value.charAt(3), 16);
            return r * 0x110000 | g * 0x1100 | b * 0x11;
        }
        return switch (value) {
            case "black" -> 0x000000;
            case "white" -> 0xFFFFFF;
            default -> throw refused(name, "a fill of '" + value + "'");
        };
    }

    /** The color a fill this opaque makes over white, which is what it is drawn as. */
    private static int overWhite(final int rgb, final double opacity) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final int channel = rgb >> shift & 0xFF;
            out |= (int) Math.round(255 - opacity * (255 - channel)) << shift;
        }
        return out;
    }

    /**
     * A path's data: moves, straight lines and cubic curves, each absolute or relative, and closes.
     * After a move, more pairs are lines, as SVG has it; after a close, the point is where its
     * subpath started.
     */
    private static @NotNull Path2D path(final @NotNull String d, final boolean evenOdd, final @NotNull String name) {
        final Path2D.Double p = new Path2D.Double(evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
        final PathData in = new PathData(d, name);
        char command = 0;
        double x = 0, y = 0, startX = 0, startY = 0;
        while (!in.atEnd()) {
            if (in.atCommand()) {
                command = in.command();
            } else if (command == 0 || command == 'Z' || command == 'z') {
                throw refused(name, "its path data has a number where a command belongs: '" + abbreviated(d) + "'");
            }
            if (p.getCurrentPoint() == null && command != 'M' && command != 'm') {
                throw refused(name, "a path starts with '" + command + "' rather than a move");
            }
            final boolean relative = Character.isLowerCase(command);
            final double dx = relative ? x : 0, dy = relative ? y : 0;
            switch (command) {
                case 'M', 'm' -> {
                    x = dx + in.number();
                    y = dy + in.number();
                    p.moveTo(x, y);
                    startX = x;
                    startY = y;
                    command = relative ? 'l' : 'L';
                }
                case 'L', 'l' -> {
                    x = dx + in.number();
                    y = dy + in.number();
                    p.lineTo(x, y);
                }
                case 'H', 'h' -> {
                    x = dx + in.number();
                    p.lineTo(x, y);
                }
                case 'V', 'v' -> {
                    y = dy + in.number();
                    p.lineTo(x, y);
                }
                case 'C', 'c' -> {
                    final double x1 = dx + in.number(), y1 = dy + in.number();
                    final double x2 = dx + in.number(), y2 = dy + in.number();
                    x = dx + in.number();
                    y = dy + in.number();
                    p.curveTo(x1, y1, x2, y2, x, y);
                }
                case 'Z', 'z' -> {
                    p.closePath();
                    x = startX;
                    y = startY;
                }
                default -> throw refused(name, "its paths use '" + command
                        + "', and this reader draws M, L, H, V, C and Z, absolute or relative");
            }
        }
        if (p.getCurrentPoint() == null) {
            throw refused(name, "a path draws nothing");
        }
        return p;
    }

    private static @NotNull String abbreviated(final @NotNull String d) {
        return d.length() <= 40 ? d : d.substring(0, 40) + "…";
    }

    /**
     * The numbers and commands of path data, or of any list of numbers in the file: apart by white
     * space or a comma, or by nothing where a sign or a second decimal point starts the next one.
     */
    private static final class PathData {
        private final String s;
        private final String name;
        private int i;

        PathData(final @NotNull String s, final @NotNull String name) {
            this.s = s;
            this.name = name;
        }

        private void skipSeparators() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) {
                i++;
            }
        }

        boolean atEnd() {
            skipSeparators();
            return i >= s.length();
        }

        /** Whether a command letter comes next: numbers are read whole, an exponent with them. */
        boolean atCommand() {
            skipSeparators();
            return i < s.length() && Character.isLetter(s.charAt(i));
        }

        char command() {
            return s.charAt(i++);
        }

        double number() {
            skipSeparators();
            final int start = i;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int digits = digits();
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                digits += digits();
            }
            if (digits == 0) {
                throw refused(name, "a number was expected at '" + abbreviated(s.substring(start)) + "'");
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                final int mark = i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                if (digits() == 0) {
                    i = mark;
                }
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private int digits() {
            final int from = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            return i - from;
        }
    }
}
