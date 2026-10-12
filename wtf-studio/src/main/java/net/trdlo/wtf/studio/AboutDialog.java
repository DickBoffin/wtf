package net.trdlo.wtf.studio;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.studio.outline.OutlineLibrary;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Optional;

/**
 * About, what is new, and who the map data belongs to.
 * <p>
 * The attributions are not decoration: several of the shipped providers require the credit, and
 * an application that makes it easy to print their imagery five meters wide had better make the
 * credit easy to find. The change log comes from {@code CHANGELOG.md} rather than from a list
 * kept in here, which would be wrong by the second release, and it is <b>rendered</b> as
 * Markdown by {@link Markdown} rather than shown as its own source.
 */
public final class AboutDialog {

    private static final String NATURAL_EARTH_URL = "https://www.naturalearthdata.com/";

    private AboutDialog() {
    }

    public static void show(final @Nullable Window owner, final @NotNull ProviderCatalog catalog,
                            final @NotNull Prefs prefs) {
        show(owner, catalog, prefs, false);
    }

    /** @param openOnWhatsNew true after an update, when the change log is the point */
    public static void show(final @Nullable Window owner, final @NotNull ProviderCatalog catalog,
                            final @NotNull Prefs prefs, final boolean openOnWhatsNew) {
        final AppInfo info = AppInfo.get();
        if (Dialogs.isBatchMode()) {
            System.out.println("[batch] " + info.name() + " " + info.version()
                    + ", commit " + info.shortCommit());
            return;
        }
        dialog(owner, catalog, prefs, openOnWhatsNew).showAndWait();
    }

    /** The window {@link #show} opens, built and not yet shown. */
    static @NotNull Dialog<Void> dialog(final @Nullable Window owner, final @NotNull ProviderCatalog catalog,
                                        final @NotNull Prefs prefs, final boolean openOnWhatsNew) {
        final AppInfo info = AppInfo.get();
        final Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.format("about.title", info.name()));
        dialog.setResizable(true);
        StudioDialog.style(dialog.getDialogPane());

        final TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                tab("about.tab.about", aboutPane(info, prefs)),
                tab("about.tab.whatsNew", whatsNewPane()),
                tab("about.tab.mapData", mapDataPane(catalog)),
                tab("about.tab.licenses", licensesPane()));
        if (openOnWhatsNew) {
            tabs.getSelectionModel().select(1);
        }

        tabs.setPrefSize(700, 520);
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().add(Buttons.CLOSE);
        return dialog;
    }

    private static @NotNull Tab tab(final @NotNull String key, final @NotNull Node content) {
        final Tab t = new Tab(I18n.get(key), content);
        t.setClosable(false);
        return t;
    }

    private static void openUrl(final @NotNull String url) {
        if (WtfApp.hostServices() != null) {
            WtfApp.hostServices().showDocument(url);
        }
    }

    // -------------------------------------------------------------------------------------------

    private static @NotNull Node aboutPane(final @NotNull AppInfo info, final @NotNull Prefs prefs) {
        final Label name = new Label(info.name());
        name.getStyleClass().add("dialog-heading");
        final Label version = new Label(I18n.format("about.version", info.version(), info.shortCommit()));
        version.getStyleClass().add("help-text");

        final Label author = new Label(I18n.format("about.author", info.author()));
        author.getStyleClass().add("help-text");

        final VBox text = new VBox(2, name, version, author);
        final HBox header = new HBox(14, icon(), text);
        header.setAlignment(Pos.CENTER_LEFT);

        final Label blurb = new Label(I18n.get("about.blurb"));
        blurb.setWrapText(true);

        final Hyperlink repo = link(info.repositoryUrl());
        // Free software, and said so where the name and the version are: the terms are what keep
        // the credit with the work – a copy passed on keeps these names, and a changed one says so
        final Label license = new Label(I18n.get("about.license"));
        license.setWrapText(true);
        final Hyperlink terms = link(AppInfo.LICENSE_URL);
        final Label status = new Label();
        status.getStyleClass().add("help-text");

        final Button check = new Button(I18n.get("about.checkNow"));
        check.setOnAction(e -> {
            check.setDisable(true);
            status.setText(I18n.get("about.checking"));
            UpdateCheck.check(prefs, true).thenAccept(found -> javafx.application.Platform.runLater(() -> {
                check.setDisable(false);
                status.setText(switch (found.status()) {
                    case NEWER -> I18n.format("about.updateFound",
                            java.util.Objects.requireNonNull(found.release()).version());
                    case UP_TO_DATE -> I18n.get("about.upToDate");
                    case UNKNOWN -> I18n.get("about.couldNotCheck");
                });
                // Over this dialog, which is where the button was pressed
                found.newer().ifPresent(r -> offer(check.getScene().getWindow(), prefs, r));
            }));
        });

        final HBox row = new HBox(10, check, status);
        row.setAlignment(Pos.CENTER_LEFT);

        final VBox box = new VBox(12, header, blurb, repo, new VBox(0, license, terms), row);
        box.setPadding(new Insets(18, 20, 18, 20));
        return box;
    }

    private static @NotNull Node icon() {
        try {
            final ImageView view = new ImageView(new Image(java.util.Objects.requireNonNull(
                    AboutDialog.class.getResourceAsStream("icon-64.png"))));
            view.setFitWidth(64);
            view.setFitHeight(64);
            return view;
        } catch (final Exception ignored) {
            return new Region();    // an absent icon is not worth an empty dialog
        }
    }

    private static @NotNull Node whatsNewPane() {
        final String markdown = Changelog.source();
        if (markdown.isBlank()) {
            return padded(new Label(I18n.get("about.noChangelog")));
        }
        return Markdown.pane(markdown, AboutDialog::openUrl);
    }

    /**
     * Every provider, with its badge, its required credit and a link to its terms.
     * <p>
     * Built as nodes rather than from Markdown because the badge has to sit beside the text
     * rather than in it, and because the credit strings come from the catalog.
     */
    private static @NotNull Node mapDataPane(final @NotNull ProviderCatalog catalog) {
        final VBox column = new VBox(0);
        column.setPadding(new Insets(16, 20, 18, 20));
        column.setFillWidth(true);

        final Label intro = new Label(I18n.get("about.mapData.intro"));
        intro.setWrapText(true);
        intro.getStyleClass().add("help-text");
        VBox.setMargin(intro, new Insets(0, 0, 14, 0));
        column.getChildren().add(intro);

        for (final Provider p : catalog.all()) {
            column.getChildren().add(providerEntry(p));
        }
        final OutlineLibrary outlines = OutlineLibrary.get();
        if (!outlines.isEmpty()) {
            column.getChildren().add(outlinesEntry(outlines.source()));
        }
        final ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("md-scroll");
        return scroll;
    }

    private static @NotNull Node providerEntry(final @NotNull Provider p) {
        final Label name = new Label(p.displayName());
        name.getStyleClass().add("md-bold");

        final Label attribution = new Label(p.attribution());
        attribution.setWrapText(true);
        attribution.getStyleClass().add("md-text");

        final VBox text = new VBox(2, name, attribution);
        text.setFillWidth(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        if (p.termsUrl() != null) {
            final Hyperlink terms = new Hyperlink(p.termsUrl());
            terms.getStyleClass().add("md-link");
            terms.setPadding(Insets.EMPTY);
            terms.setOnAction(e -> openUrl(p.termsUrl()));
            text.getChildren().add(terms);
        }

        final HBox entry = new HBox(12, ProviderBadge.of(p, 34), text);
        entry.setAlignment(Pos.TOP_LEFT);
        entry.setPadding(new Insets(9, 0, 9, 0));
        entry.getStyleClass().add("provider-entry");
        return entry;
    }

    /**
     * Where the subject outlines come from. Public domain asks for no credit, but somebody who
     * prints Bavaria's border should be able to find out whose border it is, and the source is
     * named by the library's own index, so a refreshed import names itself.
     */
    private static @NotNull Node outlinesEntry(final @NotNull String source) {
        final Label name = new Label(I18n.get("about.mapData.outlines"));
        name.getStyleClass().add("md-bold");

        final Label credit = new Label(source);
        credit.setWrapText(true);
        credit.getStyleClass().add("md-text");

        final Hyperlink home = new Hyperlink(NATURAL_EARTH_URL);
        home.getStyleClass().add("md-link");
        home.setPadding(Insets.EMPTY);
        home.setOnAction(e -> openUrl(NATURAL_EARTH_URL));

        final VBox text = new VBox(2, name, credit, home);
        text.setFillWidth(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        // No badge, but the text lines up with the providers' above it
        final Region gap = new Region();
        gap.setMinWidth(34);
        final HBox entry = new HBox(12, gap, text);
        entry.setAlignment(Pos.TOP_LEFT);
        entry.setPadding(new Insets(9, 0, 9, 0));
        entry.getStyleClass().add("provider-entry");
        return entry;
    }

    /**
     * A real table, laid out by the Markdown renderer's table support rather than by padding a
     * monospace font until the columns happen to line up.
     */
    private static @NotNull Node licensesPane() {
        final List<String> table = new java.util.ArrayList<>(List.of(
                row(I18n.get("about.licenses.component"), I18n.get("about.licenses.license")),
                "| --- | --- |",
                row(I18n.get("about.licenses.self"), I18n.get("about.licenses.gpl3"))));
        for (final String[] r : licenses()) {
            table.add(row(r[0], r[1]));
        }
        final String markdown = I18n.get("about.licenses.intro")
                + System.lineSeparator() + System.lineSeparator()
                + String.join(System.lineSeparator(), table)
                + System.lineSeparator() + System.lineSeparator()
                + I18n.get("about.licenses.dataNote");
        final Node pane = Markdown.pane(markdown, AboutDialog::openUrl);
        final List<Path> files = licenseFiles(applicationFolder());
        if (!files.isEmpty() && pane instanceof final ScrollPane scroll
                && scroll.getContent() instanceof final VBox column) {
            column.getChildren().add(filesEntry(files));
        }
        return pane;
    }

    /**
     * The notices and the license texts themselves, which the table above only names: links to
     * the files every installation carries, opened as the system opens a text file and a folder.
     */
    private static @NotNull Node filesEntry(final @NotNull List<Path> files) {
        final Label intro = new Label(I18n.get("about.licenses.files"));
        intro.setWrapText(true);
        intro.getStyleClass().add("md-text");
        final VBox box = new VBox(2, intro);
        for (final Path file : files) {
            final Hyperlink link = new Hyperlink(file.toString());
            link.getStyleClass().add("md-link");
            link.setPadding(Insets.EMPTY);
            link.setOnAction(e -> openUrl(file.toUri().toString()));
            box.getChildren().add(link);
        }
        box.setPadding(new Insets(10, 0, 0, 0));
        return box;
    }

    /**
     * Where the application's jars are: beside its own jar in an installation, and the libs
     * folder the build assembles when it runs from its classes, as Maven and the IDE run it.
     * Null when the code says nothing of where it came from.
     */
    static @Nullable Path applicationFolder() {
        try {
            final CodeSource source = AboutDialog.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            final Path where = Path.of(source.getLocation().toURI());
            return Files.isDirectory(where) ? where.resolveSibling("libs") : where.getParent();
        } catch (final URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code THIRD-PARTY.txt} and the {@code licenses} folder the build puts beside the jars
     * (wtf-studio's pom), or nothing when the folder does not have both.
     */
    static @NotNull List<Path> licenseFiles(final @Nullable Path folder) {
        if (folder == null) {
            return List.of();
        }
        final Path notices = folder.resolve("THIRD-PARTY.txt");
        final Path texts = folder.resolve("licenses");
        return Files.isRegularFile(notices) && Files.isDirectory(texts)
                ? List.of(notices.toAbsolutePath(), texts.toAbsolutePath())
                : List.of();
    }

    private static @NotNull String row(final @NotNull String component, final @NotNull String license) {
        return "| " + component + " | " + license + " |";
    }

    /**
     * What WTF is built on, and each one's license in the interface language: names stay names.
     * A dual license is a choice, so it says "or"; Font Awesome licenses its parts separately.
     * Everything an installer carries is here: the Java runtime jlink puts in every one, and a jar
     * that comes in only because another needs it, as Commons Logging comes with PDFBox.
     */
    private static @NotNull List<String[]> licenses() {
        return List.of(
                new String[]{"OpenJDK", I18n.get("about.licenses.gpl2Classpath")},
                new String[]{"OpenJFX", I18n.get("about.licenses.gpl2Classpath")},
                new String[]{"AtlantaFX", "MIT"},
                new String[]{"Ikonli", "Apache 2.0"},
                new String[]{"Font Awesome Free", I18n.format("about.licenses.iconsAndFont", "CC BY 4.0", "SIL OFL 1.1")},
                new String[]{"Apache PDFBox", "Apache 2.0"},
                new String[]{"Apache Commons Logging", "Apache 2.0"},
                new String[]{"JTS Topology Suite", I18n.format("about.licenses.either", "EPL 2.0", "EDL 1.0")},
                new String[]{"Liberation Sans", "SIL OFL 1.1"},
                new String[]{"Jackson", "Apache 2.0"},
                new String[]{"SLF4J", "MIT"},
                new String[]{"Logback", I18n.format("about.licenses.either", "EPL 1.0", "LGPL 2.1")});
    }

    private static @NotNull Node padded(final @NotNull Node node) {
        final VBox box = new VBox(node);
        box.setPadding(new Insets(18, 20, 18, 20));
        return box;
    }

    private static @NotNull Hyperlink link(final @Nullable String url) {
        final Hyperlink h = new Hyperlink(url == null ? "" : url);
        h.setOnAction(e -> {
            if (url != null) {
                openUrl(url);
            }
        });
        h.setVisible(url != null);
        return h;
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Offers a newer release. Three answers, because two is not enough: now, later, and never for
     * this one – otherwise the only way to stop being asked is to turn the whole check off.
     */
    public static void offer(final @Nullable Window owner, final @NotNull Prefs prefs,
                             final UpdateCheck.@NotNull Release release) {
        if (Dialogs.isBatchMode()) {
            System.out.println("[batch] update available: " + release.version());
            return;
        }
        final Optional<ButtonType> answer = offerAlert(owner, release).showAndWait();
        if (answer.isEmpty()) {
            return;
        }
        if (answer.get().getButtonData() == ButtonBar.ButtonData.OK_DONE) {
            openUrl(release.url());
        } else if (answer.get().getButtonData() == ButtonBar.ButtonData.OTHER) {
            UpdateCheck.skip(prefs, release);
        }
    }

    /**
     * The alert {@link #offer} shows, built and not yet shown: open is the button that is done,
     * later the one that cancels, and never for this release the other one.
     */
    static @NotNull Alert offerAlert(final @Nullable Window owner, final UpdateCheck.@NotNull Release release) {
        final Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle(I18n.get("update.title"));
        StudioDialog.style(alert.getDialogPane());
        alert.setHeaderText(I18n.format("update.header", release.version(), AppInfo.get().version()));

        // Release notes on GitHub are Markdown, so they are rendered as such
        final Node body = release.notes().isBlank()
                ? new Label(I18n.get("update.noNotes"))
                : Markdown.pane(release.notes(), AboutDialog::openUrl);
        if (body instanceof final ScrollPane scroll) {
            scroll.setPrefSize(520, 260);
        }
        alert.getDialogPane().setContent(body);

        final ButtonType open = new ButtonType(I18n.get("update.open"), ButtonBar.ButtonData.OK_DONE);
        final ButtonType later = new ButtonType(I18n.get("update.later"), ButtonBar.ButtonData.CANCEL_CLOSE);
        final ButtonType never = new ButtonType(I18n.get("update.skip"), ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().setAll(open, later, never);
        return alert;
    }

    /** Kept so the license table can be checked without opening a window. */
    static @NotNull List<String> licenseRows() {
        return licenses().stream().map(r -> r[0]).toList();
    }
}
