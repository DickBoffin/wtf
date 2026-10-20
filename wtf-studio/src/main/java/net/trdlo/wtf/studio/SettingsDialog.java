package net.trdlo.wtf.studio;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.TileCache;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings.
 * <p>
 * The cache section carries the answer to "how do I get this onto my other machine": it opens the
 * folder and lets the file manager do the copying, because Explorer and Finder already resume,
 * verify and show progress better than this would, and the files are identical on every platform.
 * What the dialog adds is the part they cannot – noticing that the folder you just chose is empty
 * while your tiles are somewhere else, and offering to move them.
 */
public final class SettingsDialog extends StudioDialog<Void> {

    private final Prefs prefs;

    private final TextField cacheDir = new TextField();
    private final Label cacheStats = new Label();

    public SettingsDialog(final @Nullable Window owner, final @NotNull Prefs prefs,
                          final @NotNull TileCache cache) {
        super(owner, "settings.title", true, Buttons.CLOSE);
        this.prefs = prefs;

        getDialogPane().setPrefWidth(620);

        final TabPane tabs = new TabPane(
                tab(I18n.get("settings.cache"), buildCache(cache)),
                tab(I18n.get("settings.display"), buildDisplay()),
                tab(I18n.get("settings.download"), buildDownload()),
                tab(I18n.get("settings.startup"), buildStartup()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        getDialogPane().setContent(tabs);
    }

    private static @NotNull Tab tab(final @NotNull String title, final @NotNull Region content) {
        final Tab t = new Tab(title, content);
        content.setPadding(new Insets(12));
        return t;
    }

    // -------------------------------------------------------------------------------------------

    private @NotNull Region buildCache(final @NotNull TileCache cache) {
        cacheDir.setText(cache.root().toString());
        cacheDir.setEditable(false);
        HBox.setHgrow(cacheDir, Priority.ALWAYS);

        final Button browse = new Button(I18n.get("settings.cache.browse"));
        browse.setOnAction(e -> chooseDirectory());

        final Button useDefault = new Button(I18n.get("settings.cache.default"));
        useDefault.setOnAction(e -> {
            prefs.putPath(Prefs.CACHE_DIR, null);
            cacheDir.setText(TileCache.platformDefault().toString());
            refresh();
        });

        final Button open = new Button(I18n.get("settings.cache.open"));
        open.setTooltip(new Tooltip(I18n.get("settings.cache.openTip")));
        // The field shows the folder, so a machine that cannot open one still says where it is
        open.setOnAction(e -> Dialogs.openInSystem(Path.of(cacheDir.getText())));

        cacheStats.getStyleClass().add("help-text");

        final VBox box = new VBox(8,
                Forms.labeled("settings.cache.location", new HBox(6, cacheDir, browse, useDefault)),
                new HBox(6, open),
                cacheStats);
        refresh();
        return box;
    }

    private void chooseDirectory() {
        final DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("settings.cache.location"));
        final File current = new File(cacheDir.getText());
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        final File chosen = Modal.folder(chooser, self());
        if (chosen == null) {
            return;
        }
        prefs.putPath(Prefs.CACHE_DIR, chosen.toPath());
        cacheDir.setText(chosen.toString());
        refresh();
    }

    /** Counts what is in the chosen folder, off the FX thread: a large cache takes a while. */
    private void refresh() {
        final Path chosen = Path.of(cacheDir.getText());
        final Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                long files = 0, bytes = 0;
                if (Files.isDirectory(chosen)) {
                    for (final TileCache.ThemeStats t : new TileCache(chosen).statistics()) {
                        files += t.files();
                        bytes += t.bytes();
                    }
                }
                return I18n.format("settings.cache.stats", I18n.integer(files), I18n.bytes(bytes));
            }
        };
        task.setOnSucceeded(e -> cacheStats.setText(task.getValue()));
        task.setOnFailed(e -> cacheStats.setText(""));
        final Thread t = new Thread(task, "cache-count");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------------------------

    private @NotNull Region buildDisplay() {
        // Labeled for what it does. "Appearance" named the topic, not the switch, which left a
        // checkbox whose meaning you could only learn by ticking it.
        final CheckBox dark = new CheckBox(I18n.get("settings.display.theme"));
        dark.setTooltip(new Tooltip(I18n.get("settings.display.theme.tip")));
        dark.setSelected(prefs.get(Prefs.DARK_THEME));
        final ComboBox<Look> look = new ComboBox<>();
        look.getItems().setAll(Look.values());
        look.setConverter(new StringConverter<>() {
            @Override
            public String toString(final Look l) {
                return l == null ? "" : switch (l) {
                    case PRIMER -> I18n.get("settings.display.look.primer");
                    case CUPERTINO -> I18n.get("settings.display.look.cupertino");
                    case NORD -> I18n.get("settings.display.look.nord");
                    case DRACULA -> I18n.get("settings.display.look.dracula");
                    case MODENA -> I18n.get("settings.display.look.modena");
                    case CASPIAN -> I18n.get("settings.display.look.caspian");
                };
            }

            @Override
            public Look fromString(final String text) {
                return null;
            }
        });
        look.setValue(Look.named(prefs.get(Prefs.LOOK)));
        showVariant(dark, look.getValue());
        // Applied at once, both of them: a look is chosen by seeing it. A look with one variant
        // shows it in the switch, which it locks, and keeps the choice for the looks with two.
        look.valueProperty().addListener((o, a, b) -> {
            prefs.put(Prefs.LOOK, b.name());
            showVariant(dark, b);
            WtfApp.applyTheme(b, prefs.get(Prefs.DARK_THEME));
        });
        dark.selectedProperty().addListener((o, a, b) -> {
            if (!dark.isDisabled()) {
                prefs.put(Prefs.DARK_THEME, b);
                WtfApp.applyTheme(look.getValue(), b);
            }
        });

        // The language, in its own name: somebody looking for their language may not read the
        // one the window is in. Blank is the system's, which is also what a fresh install gets.
        final ComboBox<String> language = new ComboBox<>();
        language.getItems().add(I18n.get("settings.display.language.system"));
        I18n.LANGUAGES.forEach(l -> language.getItems().add(I18n.nativeName(l)));
        final String chosen = prefs.get(Prefs.LANGUAGE, "");
        int selected = 0;
        for (int i = 0; i < I18n.LANGUAGES.size(); i++) {
            if (I18n.LANGUAGES.get(i).toLanguageTag().equals(chosen)) {
                selected = i + 1;
            }
        }
        language.getSelectionModel().select(selected);
        language.getSelectionModel().selectedIndexProperty().addListener((o, a, i) ->
                prefs.put(Prefs.LANGUAGE, i.intValue() <= 0 ? ""
                        : I18n.LANGUAGES.get(i.intValue() - 1).toLanguageTag()));

        return new VBox(8,
                Forms.labeled("settings.display.look", look),
                dark,
                Forms.labeled("settings.display.language", language),
                note(I18n.get("settings.display.language.note")),
                Forms.labeled("settings.display.units", unitsChoice()),
                note(I18n.get("settings.display.units.note")));
    }

    /** The dark switch for a look: the choice kept for one with both variants, locked for one without. */
    private void showVariant(final @NotNull CheckBox dark, final @NotNull Look look) {
        dark.setDisable(!look.hasLight() || !look.hasDark());
        dark.setSelected(look.dark(prefs.get(Prefs.DARK_THEME)));
    }

    /**
     * Millimeters or inches, for every length on paper. Here and not beside a field, because it
     * is every field: the size, the overlap, the boundary, the ruler in the 1:1 preview.
     */
    private @NotNull ComboBox<String> unitsChoice() {
        final ComboBox<String> units = new ComboBox<>();
        units.getItems().addAll(I18n.get("settings.display.units.system"),
                I18n.get("settings.display.units.mm"), I18n.get("settings.display.units.inch"));
        final String chosen = prefs.get(Prefs.UNITS, "");
        units.getSelectionModel().select("mm".equals(chosen) ? 1 : "inch".equals(chosen) ? 2 : 0);
        units.getSelectionModel().selectedIndexProperty().addListener((o, a, i) ->
                prefs.put(Prefs.UNITS, i.intValue() == 1 ? "mm" : i.intValue() == 2 ? "inch" : ""));
        return units;
    }

    private @NotNull Region buildDownload() {
        final Spinner<Integer> threads = Forms.wholeNumbers(new Spinner<>(1, 16,
                prefs.get(Prefs.DOWNLOAD_THREADS)));
        threads.valueProperty().addListener((o, a, b) -> prefs.put(Prefs.DOWNLOAD_THREADS, b));

        return new VBox(8,
                Forms.labeled("settings.download.threads", threads),
                note(I18n.get("settings.download.note")));
    }

    private @NotNull Region buildStartup() {
        final CheckBox splash = check(I18n.get("settings.startup.splash"), Prefs.SHOW_SPLASH);
        final CheckBox updates = check(I18n.get("settings.startup.updates"), Prefs.CHECK_UPDATES);
        final CheckBox whatsNew = check(I18n.get("settings.startup.whatsNew"), Prefs.SHOW_WHATS_NEW);
        return new VBox(8, splash, updates, whatsNew);
    }

    private @NotNull CheckBox check(final @NotNull String text, final @NotNull Prefs.Flag flag) {
        final CheckBox box = new CheckBox(text);
        box.setSelected(prefs.get(flag));
        box.selectedProperty().addListener((o, a, b) -> prefs.put(flag, b));
        return box;
    }

    private static @NotNull Label note(final @NotNull String text) {
        final Label l = new Label(text);
        l.getStyleClass().add("help-text");
        l.setWrapText(true);
        l.setMaxWidth(540);
        return l;
    }
}
