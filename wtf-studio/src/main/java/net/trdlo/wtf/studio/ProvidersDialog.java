package net.trdlo.wtf.studio;

import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.*;

import java.util.List;

/**
 * Providers and their keys, and the answer to "where do I get one of these?".
 * <p>
 * The hint, the sign-up link and the terms link all come from the provider definition, so a
 * provider added by editing {@code providers.json} explains itself exactly as a shipped one does.
 * A user bounced here by a 403 needs to know where the key comes from; sending them to a search
 * engine is the difference between a dialog and a dead end.
 */
public final class ProvidersDialog extends StudioDialog<Void> {

    /** Where the user last put the divider. A layout choice is a preference, not a constant. */
    private static final String DIVIDER = "providers.divider";

    /**
     * Where the divider is the first time: a third, so the list has about what it needs and the
     * details, whose text wraps, get the rest of a window this wide.
     */
    private static final double FIRST_DIVIDER = 1.0 / 3;

    private final Prefs prefs;
    private final TileCache cache;
    private final ProviderStore store;

    /** True when the provider file changed, so the window knows to reload the catalog. */
    private boolean changed;

    /** True when empty tiles were forgotten, so the window knows its sea is out of date. */
    private boolean forgotEmpty;

    private final Label origin = new Label();
    private final CheckBox visible = new CheckBox();
    private final Button edit = new Button();
    private final Button duplicate = new Button();
    private final Button removeOrRestore = new Button();

    private final ListView<Provider> list = new ListView<>();
    private final Label title = new Label();
    private final Label hint = new Label();
    private final PasswordField key = new PasswordField();
    private final Label keyNote = new Label();
    private final Hyperlink getKey = new Hyperlink();
    private final Hyperlink terms = new Hyperlink();
    private final Button test = new Button();
    private final Label testResult = new Label();
    private final Label themes = new Label();
    private final Label cached = new Label();
    private final MenuButton forgetEmpty = new MenuButton();
    private final Label forgotten = new Label();

    public ProvidersDialog(final @Nullable Window owner, final @NotNull Prefs prefs,
                           final @NotNull ProviderCatalog catalog, final @NotNull TileCache cache,
                           final @Nullable Provider select) {
        super(owner, "providers.title", true, Buttons.CLOSE);
        this.prefs = prefs;
        this.cache = cache;
        this.store = new ProviderStore(ProviderStore.defaultFile());

        getDialogPane().setPrefSize(1000, 640);
        // The size it opens at the first time; after that, the size and place it was closed with,
        // kept on the stage JavaFX made for the dialog. Set before it is shown, so they win over
        // the pane's preferred size and the centering over the owner
        final Stage stage = (Stage) getDialogPane().getScene().getWindow();
        Windows.remember(stage, prefs, "providers");

        list.getItems().setAll(catalog.all());
        // No preferred width. A fixed one is a guess that is wrong for "USGS National Map" and
        // wasted on "Esri", and it stops growing when the window does, which is the part that
        // feels broken. The split below gives it a share of whatever there is.
        list.setMinWidth(150);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(final Provider p, final boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.displayName());
                setGraphic(empty || p == null ? null : ProviderBadge.of(p, 18));
                setGraphicTextGap(9);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> show(b));

        // A split rather than a box: anyone who disagrees with the list's share can drag the
        // divider, and where they drag it to is remembered. The details, whose text wraps, take
        // whatever the window gains or loses after that; the list keeps its width.
        final ScrollPane details = new ScrollPane(buildDetails());
        details.setFitToWidth(true);
        details.getStyleClass().add("md-scroll");
        SplitPane.setResizableWithParent(details, Boolean.TRUE);

        // No minimum of its own: the side is as narrow as the list and the buttons under it allow,
        // and the buttons allow no narrower than their words. The padding keeps both off the
        // window's edges and the divider, as the details' own keeps theirs.
        final VBox listSide = new VBox(6, list, listActions());
        VBox.setVgrow(list, Priority.ALWAYS);
        listSide.setPadding(new Insets(6, 6, 6, 12));
        SplitPane.setResizableWithParent(listSide, Boolean.FALSE);

        final SplitPane split = new SplitPane(listSide, details);
        getDialogPane().setContent(split);
        // Put once the window has its size, and read back when it closes. The pane is laid out
        // narrower than that before it is shown, and a share taken then, or a position written on
        // every layout, is a guess the list's minimum width made.
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e ->
                split.setDividerPositions(clampDivider(prefs.getDouble(DIVIDER, FIRST_DIVIDER))));
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e ->
                prefs.putDouble(DIVIDER, clampDivider(split.getDividerPositions()[0])));

        list.getSelectionModel().select(select != null ? select : catalog.all().iterator().next());

        // What the store could not read: a file it moved aside whole, or the entries of one it kept
        // where they are. Said over the dialog once it is up, because the providers missing from
        // the list are the first thing anybody would notice.
        final ProviderStore.Unreadable unreadable = store.unreadable();
        final List<ProviderStore.UnreadableEntry> kept = store.unreadableEntries();
        if (unreadable != null) {
            setOnShown(e -> javafx.application.Platform.runLater(() -> Dialogs.warn(
                    self(), I18n.get("providers.unreadable.title"),
                    I18n.format("providers.unreadable.body", store.file(),
                            unreadable.movedTo().getFileName(), unreadable.reason()))));
        } else if (!kept.isEmpty()) {
            setOnShown(e -> javafx.application.Platform.runLater(() -> Dialogs.warn(
                    self(), I18n.get("providers.unreadableEntries.title"),
                    I18n.format("providers.unreadableEntries.body", store.file(), named(kept)))));
        }
    }

    /**
     * The entries of the user's file the store could not read, a line each: by the id an entry
     * gives itself, or by its place in the list when it gives none, and what the parser said.
     */
    static @NotNull String named(final @NotNull List<ProviderStore.UnreadableEntry> entries) {
        return entries.stream()
                .map(e -> e.id() != null
                        ? I18n.format("providers.unreadableEntries.byId", e.id(), e.reason())
                        : I18n.format("providers.unreadableEntries.byPlace", I18n.integer(e.place()), e.reason()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * Add, duplicate and remove, under the list they act on.
     * <p>
     * A built-in is never edited in place. "Edit" on one makes an <b>override</b>: a copy under
     * the same id that the shipped definition no longer updates, and that "Restore" removes
     * again. The alternative – editing the shipped entry – means the next release either
     * silently undoes the user's change or fails to deliver a correction. Naming the trade is
     * better than hiding it.
     */
    private @NotNull Node listActions() {
        final Button add = new Button(I18n.get("providers.add"));
        add.setTooltip(new Tooltip(I18n.get("providers.add.tip")));
        add.setOnAction(e -> addNew());

        duplicate.setText(I18n.get("providers.duplicate"));
        duplicate.setTooltip(new Tooltip(I18n.get("providers.duplicate.tip")));
        duplicate.setOnAction(e -> duplicateSelected());

        edit.setOnAction(e -> editSelected());
        removeOrRestore.setOnAction(e -> removeSelected());

        final Button openFile = new Button(I18n.get("providers.openFile"));
        openFile.setTooltip(new Tooltip(I18n.format("providers.openFile.tip", store.file())));
        openFile.setOnAction(e -> openContainingFolder());

        for (final Button b : List.of(add, duplicate, edit, removeOrRestore, openFile)) {
            b.setFocusTraversable(false);
        }
        return buttonGrid(add, duplicate, edit, removeOrRestore, openFile);
    }

    /**
     * Buttons two to a row, in two columns of the same width that share whatever the side is
     * given, and a last one left alone across both. A button is never narrower than its words, so
     * neither column is narrower than the widest of them, and the side cannot be dragged narrower
     * than the two.
     */
    static @NotNull GridPane buttonGrid(final @NotNull Button... buttons) {
        // GridPane's own minimum for percentage columns falls a few pixels short of the widest
        // button twice, which is enough for an ellipsis, so the minimum is worked out here
        final GridPane grid = new GridPane(6, 6) {
            @Override
            protected double computeMinWidth(final double height) {
                final double widest = getChildren().stream()
                        .mapToDouble(n -> n.minWidth(-1)).max().orElse(0);
                return Math.max(super.computeMinWidth(height),
                        snappedLeftInset() + 2 * widest + snapSpaceX(getHgap()) + snappedRightInset());
            }
        };
        for (int c = 0; c < 2; c++) {
            final ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            column.setHgrow(Priority.ALWAYS);
            column.setFillWidth(true);
            grid.getColumnConstraints().add(column);
        }
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setMinWidth(Region.USE_PREF_SIZE);
            buttons[i].setMaxWidth(Double.MAX_VALUE);
            final boolean alone = i == buttons.length - 1 && i % 2 == 0;
            grid.add(buttons[i], i % 2, i / 2, alone ? 2 : 1, 1);
        }
        return grid;
    }

    private void addNew() {
        final ProviderEditor editor = new ProviderEditor(self(), store, null, false);
        editor.showAndWait().ifPresent(this::saveAndRefresh);
    }

    private void duplicateSelected() {
        final Provider p = list.getSelectionModel().getSelectedItem();
        if (p == null) {
            return;
        }
        final Provider copy = ProviderStore.copyUnder(p, store.freeIdLike(p.id()),
                I18n.format("providers.copyOf", p.displayName()));
        new ProviderEditor(self(), store, copy, false).showAndWait().ifPresent(this::saveAndRefresh);
    }

    private void editSelected() {
        final Provider p = list.getSelectionModel().getSelectedItem();
        if (p == null) {
            return;
        }
        final boolean builtIn = store.originOf(p.id()) == ProviderStore.Origin.BUILT_IN;
        if (builtIn && !Dialogs.confirm(self(), I18n.get("providers.override.title"),
                I18n.format("providers.override.body", p.displayName()))) {
            return;
        }
        // The id of an override is the whole point of it, so it is fixed
        new ProviderEditor(self(), store, p, store.isBuiltIn(p.id()))
                .showAndWait().ifPresent(this::saveAndRefresh);
    }

    /**
     * Removes a user entry, or restores a built-in that was overridden.
     * <p>
     * Cached tiles are never touched. Somebody who has spent hours and a quota filling a folder
     * should not lose it because they tidied a list, and the folder is still readable if they
     * add the provider back.
     */
    private void removeSelected() {
        final Provider p = list.getSelectionModel().getSelectedItem();
        if (p == null) {
            return;
        }
        final ProviderStore.Origin o = store.originOf(p.id());
        if (o == ProviderStore.Origin.BUILT_IN) {
            return;
        }
        final boolean restore = o == ProviderStore.Origin.OVERRIDDEN;
        if (!Dialogs.confirm(self(),
                I18n.get(restore ? "providers.restore.title" : "providers.remove.title"),
                I18n.format(restore ? "providers.restore.body" : "providers.remove.body",
                        p.displayName()))) {
            return;
        }
        try {
            store.remove(p.id());
            changed = true;
            refreshList(restore ? p.id() : null);
        } catch (final java.io.IOException | RuntimeException ex) {
            Dialogs.error(self(), ex);
        }
    }

    private void saveAndRefresh(final @NotNull Provider p) {
        try {
            store.put(p);
            changed = true;
            refreshList(p.id());
        } catch (final java.io.IOException | RuntimeException ex) {
            Dialogs.error(self(), ex);
        }
    }

    /** Rebuilds the list from the file that was just written, and keeps the selection. */
    private void refreshList(final @Nullable String selectId) {
        final ProviderCatalog reloaded = ProviderCatalog.load(store.file());
        final String wanted = selectId != null ? selectId
                : list.getSelectionModel().getSelectedItem() == null
                ? null : list.getSelectionModel().getSelectedItem().id();
        list.getItems().setAll(reloaded.all());
        reloaded.find(wanted == null ? "" : wanted)
                .ifPresentOrElse(list.getSelectionModel()::select,
                        () -> list.getSelectionModel().selectFirst());
        show(list.getSelectionModel().getSelectedItem());
    }

    private void openContainingFolder() {
        Dialogs.openInSystem(store.file().toAbsolutePath().getParent());
    }

    /** True when the provider file changed while this dialog was open. */
    public boolean hasChanges() {
        return changed;
    }

    /** True when a theme's empty tiles were forgotten while this dialog was open. */
    public boolean forgotEmpty() {
        return forgotEmpty;
    }

    /**
     * The key a field holds: what was typed or pasted into it, without the spaces around it – a
     * space of any kind, the no-break one a web page copies among them. No key has one at either
     * end, and one carried along by a paste would go out with every request, which the provider
     * then refuses.
     */
    static @NotNull String keyIn(final @NotNull String field) {
        // (?U): \s is every space Unicode knows of, not only the ASCII ones
        return field.replaceAll("(?U)^\\s+|\\s+$", "");
    }

    /** Keeps the remembered divider somewhere usable, however the preference got mangled. */
    private static double clampDivider(final double value) {
        return Double.isFinite(value) ? Math.max(0.15, Math.min(0.55, value)) : FIRST_DIVIDER;
    }

    private @NotNull Region buildDetails() {
        title.getStyleClass().add("dialog-heading");
        // No max width here either: inside a fit-to-width scroll pane these wrap to whatever
        // the pane gives them, which is the whole point of making the dialog resizable.
        hint.setWrapText(true);
        keyNote.getStyleClass().add("help-text");
        themes.setWrapText(true);
        themes.getStyleClass().add("help-text");
        cached.getStyleClass().add("help-text");
        testResult.setWrapText(true);
        forgetEmpty.setText(I18n.get("providers.forgetEmpty"));
        forgetEmpty.setTooltip(new Tooltip(I18n.get("providers.forgetEmpty.tip")));
        forgotten.getStyleClass().add("help-text");

        key.setPromptText(I18n.get("providers.key"));
        key.textProperty().addListener((o, a, b) -> {
            final Provider p = list.getSelectionModel().getSelectedItem();
            if (p != null) {
                prefs.setApiKey(p.id(), keyIn(b));
                testResult.setText("");
            }
            // Follow the field, not the preference as it was when the dialog opened. Decided once,
            // a pasted key would leave Test grayed out until the dialog was closed and reopened –
            // the value saved, the button never hearing about it.
            updateTestEnabled();
        });

        getKey.setText(I18n.get("providers.getKey"));
        getKey.setOnAction(e -> open(providerUrl(true)));
        terms.setText(I18n.get("providers.terms"));
        terms.setOnAction(e -> open(providerUrl(false)));

        test.setText(I18n.get("providers.test"));
        test.setOnAction(e -> testOne());

        origin.getStyleClass().add("step-summary");
        visible.setText(I18n.get("providers.visible"));
        visible.setTooltip(new Tooltip(I18n.get("providers.visible.tip")));
        visible.setOnAction(e -> {
            final Provider p = list.getSelectionModel().getSelectedItem();
            if (p == null) {
                return;
            }
            try {
                store.setHidden(p.id(), !visible.isSelected());
                changed = true;
            } catch (final java.io.IOException | RuntimeException ex) {
                Dialogs.error(self(), ex);
                visible.setSelected(!store.isHidden(p.id()));      // back to what the file says
            }
        });

        final HBox state = new HBox(14, origin, visible);
        state.setAlignment(Pos.CENTER_LEFT);

        final HBox forget = new HBox(8, forgetEmpty, forgotten);
        forget.setAlignment(Pos.CENTER_LEFT);

        final VBox box = new VBox(8, title, state, hint, new HBox(8, getKey, terms),
                new Separator(),
                new Label(I18n.get("providers.key")), key, keyNote,
                new HBox(8, test, testResult),
                new Separator(), themes, cached, forget);
        box.setPadding(new Insets(6, 10, 6, 12));
        box.setAlignment(Pos.TOP_LEFT);
        box.setFillWidth(true);
        return box;
    }

    private void show(final @Nullable Provider p) {
        if (p == null) {
            return;
        }
        title.setText(p.displayName());
        title.setGraphic(ProviderBadge.of(p, 22));
        title.setGraphicTextGap(10);

        final ProviderStore.Origin o = store.originOf(p.id());
        origin.setText(I18n.get("providers.origin." + o.name()));
        edit.setText(I18n.get(o == ProviderStore.Origin.BUILT_IN
                ? "providers.override" : "providers.edit"));
        removeOrRestore.setText(I18n.get(switch (o) {
            case OVERRIDDEN -> "providers.restore";
            default -> "providers.remove";
        }));
        removeOrRestore.setDisable(o == ProviderStore.Origin.BUILT_IN);
        visible.setSelected(!store.isHidden(p.id()));
        hint.setText(p.signupHint() == null ? "" : p.signupHint());
        getKey.setVisible(p.signupUrl() != null);
        terms.setVisible(p.termsUrl() != null);

        final boolean needsKey = p.needsKey();
        key.setDisable(!needsKey);
        // Basic authentication takes two things in the one field, and says how they go together
        key.setPromptText(I18n.get(p.authOrNone().kind() == Provider.AuthKind.BASIC
                ? "providers.key.basic" : "providers.key"));
        key.setText(needsKey ? nullToEmpty(prefs.apiKey(p.id())) : "");
        keyNote.setText(needsKey ? "" : I18n.get("providers.key.none"));
        updateTestEnabled();
        testResult.setText(p.bulkDownload() ? "" : I18n.get("providers.bulkRefused"));

        themes.setText(I18n.get("providers.themes") + ": "
                + String.join(", ", p.themes().stream().map(Provider.Theme::displayName).toList()));
        cached.setText("");
        countCached(p);

        forgetEmpty.getItems().setAll(p.themes().stream().map(t -> {
            final MenuItem item = new MenuItem(t.displayName());
            item.setOnAction(e -> forgetEmptyOf(p, t));
            return item;
        }).toList());
        forgotten.setText("");
    }

    /**
     * Forgets the tiles one of the provider's maps has answered "none here" for.
     * <p>
     * For an address that was wrong. It answered 404 everywhere, and until those answers are
     * forgotten the map shows sea, and the downloader skips, wherever the corrected address has
     * land. Nothing that was downloaded is touched.
     */
    private void forgetEmptyOf(final @NotNull Provider p, final Provider.@NotNull Theme theme) {
        if (!Dialogs.confirm(self(), I18n.get("providers.forgetEmpty.confirm.title"),
                I18n.format("providers.forgetEmpty.confirm.body", p.displayName(), theme.displayName()))) {
            return;
        }
        forgetEmpty.setDisable(true);
        final Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return cache.forgetEmpty(p.id(), theme.id());
            }
        };
        task.setOnSucceeded(e -> {
            forgetEmpty.setDisable(false);
            forgotEmpty |= task.getValue() > 0;
            if (list.getSelectionModel().getSelectedItem() == p) {
                forgotten.setText(I18n.format("providers.forgetEmpty.done", I18n.integer(task.getValue())));
            }
        });
        task.setOnFailed(e -> {
            forgetEmpty.setDisable(false);
            Dialogs.error(self(), task.getException());
        });
        final Thread t = new Thread(task, "forget-empty");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Test is available when the provider needs no key, or when there is one in the field.
     * <p>
     * Reads the field rather than the stored preference so that typing, pasting and clearing all
     * take effect immediately; a key is saved on every keystroke anyway, so the two agree.
     */
    private void updateTestEnabled() {
        final Provider p = list.getSelectionModel().getSelectedItem();
        if (p == null) {
            test.setDisable(true);
            return;
        }
        test.setDisable(p.needsKey() && key.getText().isBlank());
    }

    private void countCached(final @NotNull Provider p) {
        final Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                long files = 0, bytes = 0;
                for (final TileCache.ThemeStats s : cache.statistics()) {
                    if (s.providerId().equalsIgnoreCase(p.id())) {
                        files += s.files();
                        bytes += s.bytes();
                    }
                }
                return I18n.format("providers.cached", I18n.integer(files), I18n.bytes(bytes));
            }
        };
        task.setOnSucceeded(e -> {
            if (list.getSelectionModel().getSelectedItem() == p) {
                cached.setText(task.getValue());
            }
        });
        final Thread t = new Thread(task, "cache-stats");
        t.setDaemon(true);
        t.start();
    }

    /** Fetches one known-good tile and reports the status, which is what makes a key testable. */
    private void testOne() {
        final Provider p = list.getSelectionModel().getSelectedItem();
        if (p == null) {
            return;
        }
        test.setDisable(true);
        testResult.setText("...");
        final TileKey probe = probeTile(p);

        final Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return TileProbe.run(p, probe, prefs.apiKey(p.id()));
            }
        };
        task.setOnSucceeded(e -> {
            testResult.setText(task.getValue());
            test.setDisable(false);
        });
        task.setOnFailed(e -> {
            final Throwable ex = task.getException();
            // A 404 at the middle of the coverage is the address, and the engine says so in English
            testResult.setText(I18n.format("providers.test.failed", ex == null ? "?"
                    : ex instanceof NothingThereException ? ErrorText.explain(ex).body()
                    : EngineText.of(ex)));
            test.setDisable(false);
        });
        final Thread t = new Thread(task, "provider-test");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The tile the Test asks for: somewhere this provider actually has tiles. A fixed point, over
     * Prague, say, would make every national provider outside Czechia fail its own key test and
     * blame the key for it. {@link Provider#probePoint} is the middle of the declared
     * coverage. A map lettered in a language is asked in the provider's own (D40): the first it
     * lists, which its server takes, or English.
     */
    static @NotNull TileKey probeTile(final @NotNull Provider p) {
        final Provider.Theme theme = p.themes().getFirst();
        final int zoom = Math.max(p.minZoomOf(theme), Math.min(8, p.maxZoomOf(theme)));
        final LonLatBounds.Point where = p.probePoint();
        return p.key(theme.id(), p.defaultLanguage(), zoom,
                Mercator.tileX(where.lon(), zoom), Mercator.tileY(where.lat(), zoom));
    }

    private @Nullable String providerUrl(final boolean signup) {
        final Provider p = list.getSelectionModel().getSelectedItem();
        return p == null ? null : signup ? p.signupUrl() : p.termsUrl();
    }

    private void open(final @Nullable String url) {
        if (url == null) {
            return;
        }
        final HostServices services = WtfApp.hostServices();
        if (services != null) {
            services.showDocument(url);
        }
    }

    private static @NotNull String nullToEmpty(final @Nullable String s) {
        return s == null ? "" : s;
    }
}
