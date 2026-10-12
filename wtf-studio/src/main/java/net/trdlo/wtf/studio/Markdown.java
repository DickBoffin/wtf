package net.trdlo.wtf.studio;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small Markdown subset rendered straight into JavaFX nodes.
 * <p>
 * No dependency and no {@code WebView}. A Markdown library plus a browser engine to display its
 * HTML would be some thirty megabytes and a second styling system, to lay out a change log. Nodes
 * take the application's own stylesheet, so the text follows the light and dark themes for free.
 * <p>
 * What is understood: {@code #}/{@code ##}/{@code ###} headings, paragraphs, {@code -} and
 * {@code *} bullets nested by indentation, {@code |} tables with a header separator row,
 * {@code ---} rules, and inline {@code **bold**}, {@code *italic*}, {@code `code`} and
 * {@code [text](url)}. Anything else is shown as the text it is, which is the right failure for
 * a format whose plain form is meant to be readable.
 */
public final class Markdown {

    private static final Pattern BULLET = Pattern.compile("^( *)[-*+] (.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?[\\s:|-]*-[\\s:|-]*\\|?\\s*$");

    /** bold | code | link | italic. Italic last, so {@code **x**} is not read as two italics. */
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*"
                    + "|`([^`]+)`"
                    + "|\\[([^]]+)]\\(([^)]+)\\)"
                    + "|(?<![\\w*])\\*(?!\\s)([^*]+?)(?<!\\s)\\*(?![\\w*])");

    private Markdown() {
    }

    /** A scrollable pane of the rendered document, which is what every caller here wants. */
    public static @NotNull Node pane(final @NotNull String markdown,
                                     final @NotNull Consumer<String> linkOpener) {
        final VBox column = new VBox(0);
        column.getStyleClass().add("md");
        column.setPadding(new Insets(16, 20, 18, 20));
        column.setFillWidth(true);
        column.getChildren().setAll(render(markdown, linkOpener));

        final javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("md-scroll");
        return scroll;
    }

    public static @NotNull List<Node> render(final @NotNull String markdown,
                                             final @NotNull Consumer<String> linkOpener) {
        return new Renderer(linkOpener).run(markdown);
    }

    // -------------------------------------------------------------------------------------------

    private static final class Renderer {

        private final Consumer<String> linkOpener;
        private final List<Node> nodes = new ArrayList<>();
        private final StringBuilder paragraph = new StringBuilder();

        /** -1 while collecting a plain paragraph, else the indent of the bullet it belongs to. */
        private int bulletIndent = -1;

        private Renderer(final @NotNull Consumer<String> linkOpener) {
            this.linkOpener = linkOpener;
        }

        private @NotNull List<Node> run(final @NotNull String markdown) {
            final String[] lines = markdown.split("\r?\n");
            for (int i = 0; i < lines.length; i++) {
                final String line = lines[i].stripTrailing();

                // A table has to be recognized before anything else, because its rows look like
                // ordinary text and its divider looks like a horizontal rule.
                if (isTableStart(lines, i)) {
                    flush();
                    i = table(lines, i) - 1;
                    continue;
                }
                final Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    flush();
                    heading(heading.group(2), heading.group(1).length());
                    continue;
                }
                if (RULE.matcher(line).matches()) {
                    flush();
                    final Separator rule = new Separator();
                    VBox.setMargin(rule, new Insets(10, 0, 10, 0));
                    nodes.add(rule);
                    continue;
                }
                if (line.isBlank()) {
                    flush();
                    continue;
                }
                final Matcher bullet = BULLET.matcher(line);
                if (bullet.matches()) {
                    flush();
                    bulletIndent = bullet.group(1).length();
                    paragraph.append(bullet.group(2).trim());
                    continue;
                }
                // a continuation of the bullet or paragraph being collected
                if (!paragraph.isEmpty()) {
                    paragraph.append(' ');
                }
                paragraph.append(line.trim());
            }
            flush();
            return nodes;
        }

        // --- blocks ---------------------------------------------------------------------------

        private void heading(final @NotNull String text, final int level) {
            final Label label = new Label(text.trim());
            label.getStyleClass().addAll("md-heading", "md-h" + Math.min(level, 3));
            label.setWrapText(true);
            VBox.setMargin(label, new Insets(nodes.isEmpty() ? 0 : 16, 0, 4, 0));
            nodes.add(label);
        }

        private void flush() {
            if (paragraph.isEmpty()) {
                bulletIndent = -1;
                return;
            }
            final TextFlow flow = inline(paragraph.toString());
            paragraph.setLength(0);
            if (bulletIndent >= 0) {
                // en dash for a nested level, so depth reads without indentation alone carrying it
                final Label dot = new Label(bulletIndent == 0 ? "•" : "–");
                dot.getStyleClass().add("md-bullet");
                dot.setMinWidth(Label.USE_PREF_SIZE);
                final HBox item = new HBox(7, dot, flow);
                item.setAlignment(Pos.TOP_LEFT);
                item.setPadding(new Insets(2, 0, 2, 4 + bulletIndent * 10));
                HBox.setHgrow(flow, Priority.ALWAYS);
                nodes.add(item);
            } else {
                VBox.setMargin(flow, new Insets(3, 0, 3, 0));
                nodes.add(flow);
            }
            bulletIndent = -1;
        }

        /** A row of cells, and the next line is the {@code |---|} divider that makes it a table. */
        private static boolean isTableStart(final String @NotNull [] lines, final int i) {
            return lines[i].strip().startsWith("|")
                    && i + 1 < lines.length
                    && lines[i + 1].strip().startsWith("|")
                    && TABLE_DIVIDER.matcher(lines[i + 1]).matches();
        }

        /** @return the index of the first line after the table */
        private int table(final String @NotNull [] lines, final int start) {
            final List<String> header = cells(lines[start]);
            final List<List<String>> rows = new ArrayList<>();
            int i = start + 2;                       // past the header and the divider
            while (i < lines.length && lines[i].strip().startsWith("|")) {
                rows.add(cells(lines[i]));
                i++;
            }

            final GridPane grid = new GridPane();
            grid.getStyleClass().add("md-table");
            grid.setHgap(0);
            grid.setVgap(0);
            for (int c = 0; c < header.size(); c++) {
                final ColumnConstraints cc = new ColumnConstraints();
                // The first column holds the name and the last the prose; letting the last one
                // take the slack keeps a license table from spreading its first column to fill.
                cc.setHgrow(c == header.size() - 1 ? Priority.ALWAYS : Priority.NEVER);
                cc.setFillWidth(true);
                grid.getColumnConstraints().add(cc);
            }
            for (int c = 0; c < header.size(); c++) {
                grid.add(cell(header.get(c), true), c, 0);
            }
            for (int r = 0; r < rows.size(); r++) {
                final List<String> row = rows.get(r);
                for (int c = 0; c < header.size(); c++) {
                    grid.add(cell(c < row.size() ? row.get(c) : "", false), c, r + 1);
                }
            }
            GridPane.setHgrow(grid, Priority.ALWAYS);
            VBox.setMargin(grid, new Insets(8, 0, 10, 0));
            nodes.add(grid);
            return i;
        }

        private @NotNull Node cell(final @NotNull String text, final boolean header) {
            final TextFlow flow = inline(text);
            flow.getStyleClass().add(header ? "md-th" : "md-td");
            flow.setPadding(new Insets(5, 14, 5, 10));
            return flow;
        }

        private static @NotNull List<String> cells(final @NotNull String line) {
            final String trimmed = line.strip().replaceAll("^\\|", "").replaceAll("\\|$", "");
            final List<String> out = new ArrayList<>();
            for (final String cell : trimmed.split("\\|", -1)) {
                out.add(cell.strip());
            }
            return out;
        }

        // --- inline ---------------------------------------------------------------------------

        private @NotNull TextFlow inline(final @NotNull String text) {
            final TextFlow flow = new TextFlow();
            flow.setMinWidth(0);
            flow.setMaxWidth(Double.MAX_VALUE);
            final Matcher m = INLINE.matcher(text);
            int last = 0;
            while (m.find()) {
                if (m.start() > last) {
                    flow.getChildren().add(text(text.substring(last, m.start())));
                }
                if (m.group(1) != null) {
                    flow.getChildren().add(text(m.group(1), "md-bold"));
                } else if (m.group(2) != null) {
                    flow.getChildren().add(text(m.group(2), "md-code"));
                } else if (m.group(3) != null) {
                    final Hyperlink link = new Hyperlink(m.group(3));
                    link.getStyleClass().add("md-link");
                    link.setPadding(Insets.EMPTY);
                    final String url = m.group(4);
                    link.setOnAction(e -> linkOpener.accept(url));
                    flow.getChildren().add(link);
                } else {
                    flow.getChildren().add(text(m.group(5), "md-italic"));
                }
                last = m.end();
            }
            if (last < text.length()) {
                flow.getChildren().add(text(text.substring(last)));
            }
            return flow;
        }

        private static @NotNull Text text(final @NotNull String content,
                                          final @NotNull String... extraStyleClasses) {
            final Text t = new Text(content);
            t.getStyleClass().add("md-text");
            t.getStyleClass().addAll(extraStyleClasses);
            return t;
        }
    }
}
