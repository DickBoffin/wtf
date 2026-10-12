package net.trdlo.wtf.studio;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The form behind "add a provider" and "change this one".
 * <p>
 * Separate from {@link ProvidersDialog} on purpose, and the split is not only about size: that
 * dialog holds the <b>key</b>, which is a secret belonging to this machine and never written to
 * the provider file, while this one holds the <b>definition</b>, which is the opposite – no
 * secrets, and exactly the thing you would mail to somebody so they could use the same server.
 * <p>
 * Nothing is written until OK, and OK refuses anything {@link ProviderStore#check} would refuse,
 * with the same wording. The window checks as you type so the OK button can say whether it will
 * work, but the store's check is the one that decides.
 */
public final class ProviderEditor extends StudioDialog<Provider> {

    private final ProviderStore store;
    private final boolean idFixed;
    private final @Nullable String originalId;

    /** Not editable, only preserved: see toProvider(). */
    private @Nullable TileFormat keptFormat;
    /** Not editable in the form yet; carried so editing a shipped provider does not widen it. */
    private @Nullable net.trdlo.wtf.tiles.LonLatBounds keptCoverage;
    /** Not editable; carried so that a provider made one's own still prints its credit with its logo. */
    private @Nullable String keptLogo;

    private final TextField id = new TextField();
    private final TextField displayName = new TextField();
    private final TextField urlTemplate = new TextField();
    private final ComboBox<Provider.AuthKind> authKind =
            Forms.enumCombo(Provider.AuthKind.values(), "editor.auth");
    private final TextField authName = new TextField();
    /** What a header carries around the key; empty is the key alone. */
    private final TextField authValue = new TextField();
    private final Spinner<Integer> minZoom = new Spinner<>(0, 22, 0);
    private final Spinner<Integer> maxZoom = new Spinner<>(0, 22, 19);
    private final TextField attribution = new TextField();
    private final TextField signupUrl = new TextField();
    private final TextArea signupHint = new TextArea();
    private final TextField termsUrl = new TextField();
    private final CheckBox bulkDownload = new CheckBox();
    private final TextField quotaNote = new TextField();
    private final TextField subdomains = new TextField();
    /** The codes the server takes for the lang token; empty lets any be typed in the toolbar. */
    private final TextField languages = new TextField();

    private final ObservableList<ThemeRow> themes = FXCollections.observableArrayList();
    private final Label problem = new Label();
    private final Label testResult = new Label();

    /** A theme as the table edits it: mutable, and turned back into a record on OK. */
    public static final class ThemeRow {
        private String id;
        private String name;
        private String template;
        private String minZoom;
        private String maxZoom;
        /** Not editable in the table, only preserved: see toProvider(). A new row has none of them. */
        private final @Nullable TileFormat expectedFormat;
        private final boolean transparent;
        private final @Nullable String note;
        /**
         * The address the note was written of – the theme's own, or the provider's it falls back
         * to. A note such as "Probed: JPEG, 40 kB." is not true of any other, so it is given back
         * only while the address is still this one.
         */
        private final @Nullable String noteAddress;

        ThemeRow(final String id, final String name, final String template, final String minZoom,
                 final String maxZoom) {
            this(id, name, template, minZoom, maxZoom, null, false, null, "");
        }

        /** @param providerTemplate the provider's address, which a theme without its own uses */
        ThemeRow(final String id, final String name, final String template, final String minZoom,
                 final String maxZoom, final @Nullable TileFormat expectedFormat,
                 final boolean transparent, final @Nullable String note,
                 final @NotNull String providerTemplate) {
            this.id = id;
            this.name = name;
            this.template = template;
            this.minZoom = minZoom;
            this.maxZoom = maxZoom;
            this.expectedFormat = expectedFormat;
            this.transparent = transparent;
            this.note = note;
            this.noteAddress = addressOf(template, providerTemplate);
        }

        public String getId() {
            return id;
        }

        public void setId(final String v) {
            id = v;
        }

        public String getName() {
            return name;
        }

        public void setName(final String v) {
            name = v;
        }

        public String getTemplate() {
            return template;
        }

        public void setTemplate(final String v) {
            template = v;
        }

        public String getMinZoom() {
            return minZoom;
        }

        public void setMinZoom(final String v) {
            minZoom = v;
        }

        public String getMaxZoom() {
            return maxZoom;
        }

        public void setMaxZoom(final String v) {
            maxZoom = v;
        }
    }

    /**
     * @param existing the definition to edit, or null to add a new one
     * @param idFixed  true when the id may not change, which is the case for an override of a
     *                 built-in: its whole purpose is to stand in for that id
     */
    public ProviderEditor(final @Nullable Window owner, final @NotNull ProviderStore store,
                          final @Nullable Provider existing, final boolean idFixed) {
        super(owner, existing == null ? "editor.title.add" : "editor.title.edit", true,
                ButtonType.OK, Buttons.CANCEL);
        this.store = store;
        this.idFixed = idFixed;
        this.originalId = existing == null ? null : existing.id();
        this.keptFormat = existing == null ? null : existing.expectedFormat();
        this.keptCoverage = existing == null ? null : existing.coverage();
        this.keptLogo = existing == null ? null : existing.logo();

        getDialogPane().setPrefSize(720, 620);

        build();
        fill(existing);

        final Node ok = getDialogPane().lookupButton(ButtonType.OK);
        // Checked as you type, so the button is honest about whether OK will do anything
        final Runnable recheck = this::revalidate;
        for (final TextInputControl field : List.of(id, displayName, urlTemplate, attribution, authName,
                authValue, languages)) {
            field.textProperty().addListener((o, a, b) -> recheck.run());
        }
        authKind.valueProperty().addListener((o, a, b) -> recheck.run());
        themes.addListener((javafx.collections.ListChangeListener<ThemeRow>) c -> recheck.run());
        revalidate();
        ok.disableProperty().bind(problem.textProperty().isNotEmpty());

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            try {
                final Provider built = toProvider();
                store.check(built);
                return built;
            } catch (final RuntimeException ex) {
                Dialogs.warn(self(), I18n.get("editor.refused"), EngineText.of(ex));
                return null;
            }
        });
    }

    // -------------------------------------------------------------------------------------------

    private void build() {
        id.setPromptText("my-provider");
        id.setDisable(idFixed);
        displayName.setPromptText("My Provider");
        urlTemplate.setPromptText("https://tiles.example.com/{z}/{x}/{y}.png");

        authKind.setValue(Provider.AuthKind.NONE);
        authName.setPromptText("X-Api-Key");
        // Basic authentication has its own header, and says nothing but the user and the password
        authName.disableProperty().bind(authKind.valueProperty().isEqualTo(Provider.AuthKind.NONE)
                .or(authKind.valueProperty().isEqualTo(Provider.AuthKind.BASIC)));
        authValue.setPromptText("{key}");
        authValue.disableProperty().bind(authKind.valueProperty().isNotEqualTo(Provider.AuthKind.HEADER));

        minZoom.setEditable(true);
        maxZoom.setEditable(true);
        for (final Spinner<Integer> zoom : List.of(minZoom, maxZoom)) {
            zoom.getValueFactory().setConverter(zoomConverter(zoom.getValueFactory()::getValue));
        }
        minZoom.setPrefWidth(80);
        maxZoom.setPrefWidth(80);

        attribution.setPromptText(I18n.get("editor.attribution.prompt"));
        signupHint.setPrefRowCount(3);
        signupHint.setWrapText(true);
        bulkDownload.setText(I18n.get("editor.bulk"));
        bulkDownload.setSelected(true);
        subdomains.setPromptText("a, b, c");
        languages.setPromptText("cs, de, en");

        problem.getStyleClass().add("editor-problem");
        problem.setWrapText(true);
        testResult.getStyleClass().add("step-summary");
        testResult.setWrapText(true);

        final GridPane grid = Forms.grid(120);

        int row = 0;
        row = Forms.section(grid, row, "editor.section.identity");
        row = Forms.field(grid, row, "editor.id", id, "editor.id.hint");
        row = Forms.field(grid, row, "editor.name", displayName, null);

        row = Forms.section(grid, row, "editor.section.address");
        row = Forms.field(grid, row, "editor.url", urlTemplate, "editor.url.hint");
        row = Forms.field(grid, row, "editor.subdomains", subdomains, "editor.subdomains.hint");
        row = Forms.field(grid, row, "editor.languages", languages, "editor.languages.hint");
        row = Forms.field(grid, row, "editor.auth", authKind, null);
        row = Forms.field(grid, row, "editor.authName", authName, "editor.authName.hint");
        row = Forms.field(grid, row, "editor.authValue", authValue, "editor.authValue.hint");

        final HBox zooms = new HBox(8, minZoom, new Label(I18n.get("editor.to")), maxZoom);
        zooms.setAlignment(Pos.CENTER_LEFT);
        row = Forms.section(grid, row, "editor.section.tiles");
        row = Forms.field(grid, row, "editor.zoom", zooms, "editor.zoom.hint");

        row = Forms.section(grid, row, "editor.section.legal");
        row = Forms.field(grid, row, "editor.attribution", attribution, "editor.attribution.hint");
        row = Forms.field(grid, row, "editor.signupUrl", signupUrl, null);
        row = Forms.field(grid, row, "editor.signupHint", signupHint, "editor.signupHint.hint");
        row = Forms.field(grid, row, "editor.termsUrl", termsUrl, null);
        row = Forms.field(grid, row, "editor.quota", quotaNote, null);
        grid.add(bulkDownload, 1, row++);
        grid.add(Forms.hint("editor.bulk.hint"), 1, row);

        final VBox all = new VBox(14, grid, themesSection(), testSection(), problem);
        all.setPadding(new Insets(14, 18, 14, 18));

        final ScrollPane scroll = new ScrollPane(all);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("md-scroll");
        getDialogPane().setContent(scroll);
    }

    // -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private @NotNull Node themesSection() {
        final TableView<ThemeRow> table = new TableView<>(themes);
        table.setEditable(true);
        // As tall as it has as rows. A scroll pane inside a scroll pane means two scrollbars
        // for one list, and the wheel lands on whichever one the pointer happens to be over.
        final double rowHeight = 28;
        table.setFixedCellSize(rowHeight);
        table.prefHeightProperty().bind(javafx.beans.binding.Bindings
                .size(themes).multiply(rowHeight).add(rowHeight + 2));
        table.setMinHeight(Region.USE_PREF_SIZE);
        table.setMaxHeight(Region.USE_PREF_SIZE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(I18n.get("editor.themes.empty")));

        table.getColumns().setAll(
                themeColumn("editor.themes.id", 100, ThemeRow::getId, ThemeRow::setId),
                themeColumn("editor.themes.name", 140, ThemeRow::getName, ThemeRow::setName),
                themeColumn("editor.themes.minZoom", 110, ThemeRow::getMinZoom, ThemeRow::setMinZoom),
                themeColumn("editor.themes.maxZoom", 110, ThemeRow::getMaxZoom, ThemeRow::setMaxZoom),
                themeColumn("editor.themes.template", 240, ThemeRow::getTemplate, ThemeRow::setTemplate));

        final Button add = new Button(I18n.get("editor.themes.add"));
        add.setOnAction(e -> {
            themes.add(new ThemeRow("map-" + (themes.size() + 1),
                    I18n.format("editor.themes.newName", themes.size() + 1), "", "", ""));
            revalidate();
        });
        final Button remove = new Button(I18n.get("editor.themes.remove"));
        remove.setOnAction(e -> {
            final ThemeRow selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                themes.remove(selected);
                revalidate();
            }
        });
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        return new VBox(6, Forms.heading("editor.section.themes"), Forms.hint("editor.themes.hint"),
                table, new HBox(6, add, remove));
    }

    private @NotNull TableColumn<ThemeRow, String> themeColumn(
            final @NotNull String titleKey, final double width,
            final java.util.function.@NotNull Function<ThemeRow, String> get,
            final java.util.function.@NotNull BiConsumer<ThemeRow, String> set) {

        final TableColumn<ThemeRow, String> column = new TableColumn<>(I18n.get(titleKey));
        column.setPrefWidth(width);
        column.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                get.apply(c.getValue())));
        column.setCellFactory(TextFieldTableCell.forTableColumn());
        column.setOnEditCommit(e -> {
            set.accept(e.getRowValue(), e.getNewValue() == null ? "" : e.getNewValue().trim());
            revalidate();
        });
        return column;
    }

    /**
     * One request against the address as typed.
     * <p>
     * This is the control that turns "I pasted a URL" into "it works", and it is why the editor
     * is worth having at all: the alternative is saving, closing, switching provider and panning
     * to find out.
     */
    private @NotNull Node testSection() {
        final Button test = new Button(I18n.get("editor.test"));
        test.setOnAction(e -> runTest(test));
        final HBox row = new HBox(10, test, testResult);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void runTest(final @NotNull Button test) {
        final Provider candidate;
        try {
            candidate = toProvider();
            store.check(candidate);
        } catch (final RuntimeException ex) {
            testResult.setText(EngineText.of(ex));
            return;
        }
        test.setDisable(true);
        testResult.setText(I18n.get("editor.test.running"));

        final String key = Prefs.app().apiKey(candidate.id());
        final javafx.concurrent.Task<String> task = new javafx.concurrent.Task<>() {
            @Override
            protected String call() throws Exception {
                return TileProbe.run(candidate, probeTile(candidate), key);
            }
        };
        task.setOnSucceeded(e -> {
            testResult.setText(task.getValue());
            test.setDisable(false);
        });
        task.setOnFailed(e -> {
            final Throwable ex = task.getException();
            // A 404 for the probe is most often the address, and the engine says so in English
            testResult.setText(I18n.format("providers.test.failed", ex == null ? "?"
                    : ex instanceof NothingThereException ? ErrorText.explain(ex).body()
                    : EngineText.of(ex)));
            test.setDisable(false);
        });
        final Thread thread = new Thread(task, "provider-editor-test");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The tile the Test asks for: one near the top of the first theme, in the provider's own
     * language (D40) – the first it lists, which its server takes, or English.
     */
    static @NotNull TileKey probeTile(final @NotNull Provider candidate) {
        final Provider.Theme theme = candidate.themes().getFirst();
        final int zoom = Math.max(candidate.minZoomOf(theme), 2);
        return candidate.key(theme.id(), candidate.defaultLanguage(), zoom, 2, 1);
    }

    // -------------------------------------------------------------------------------------------

    private void fill(final @Nullable Provider p) {
        if (p == null) {
            id.setText(store.freeIdLike("my-provider"));
            displayName.setText("");
            themes.add(new ThemeRow("default", "Default", "", "", ""));
            return;
        }
        id.setText(p.id());
        displayName.setText(p.displayName());
        urlTemplate.setText(p.urlTemplate());
        authKind.setValue(p.authOrNone().kind());
        authName.setText(p.authOrNone().name() == null ? "" : p.authOrNone().name());
        authValue.setText(nullToEmpty(p.authOrNone().value()));
        minZoom.getValueFactory().setValue(p.minZoom());
        maxZoom.getValueFactory().setValue(p.maxZoom());
        attribution.setText(p.attribution());
        signupUrl.setText(nullToEmpty(p.signupUrl()));
        signupHint.setText(nullToEmpty(p.signupHint()));
        termsUrl.setText(nullToEmpty(p.termsUrl()));
        quotaNote.setText(nullToEmpty(p.quotaNote()));
        bulkDownload.setSelected(p.bulkDownload());
        subdomains.setText(p.subdomains() == null ? "" : String.join(", ", p.subdomains()));
        languages.setText(p.languages() == null ? "" : String.join(", ", p.languages()));

        for (final Provider.Theme t : p.themes()) {
            themes.add(new ThemeRow(t.id(), t.displayName(),
                    nullToEmpty(t.urlTemplate()),
                    t.minZoom() == null ? "" : String.valueOf(t.minZoom()),
                    t.maxZoom() == null ? "" : String.valueOf(t.maxZoom()),
                    t.expectedFormat(), t.transparent(), t.note(), p.urlTemplate()));
        }
    }

    private @NotNull Provider toProvider() {
        final List<Provider.Theme> built = new ArrayList<>();
        for (final ThemeRow r : themes) {
            built.add(themeOf(r, urlTemplate.getText()));
        }
        final Provider.AuthKind kind = authKind.getValue();
        // Only what the kind uses, so a name typed before switching to another kind is not saved
        final Provider.Auth auth = switch (kind) {
            case NONE -> Provider.Auth.NONE;
            case HEADER -> new Provider.Auth(kind, blankToNull(authName.getText()),
                    blankToNull(authValue.getText()));
            case QUERY -> new Provider.Auth(kind, blankToNull(authName.getText()));
            case BASIC -> new Provider.Auth(kind, null);
        };

        final List<String> hosts = listOf(subdomains.getText());

        return new Provider(id.getText().trim().toLowerCase(Locale.ROOT), displayName.getText().trim(),
                urlTemplate.getText().trim(), auth,
                // The format is never taken from a setting: TileFormat.sniff reads the bytes,
                // which is the only answer that can be right for a provider whose aerial theme
                // is JPEG and whose map theme is PNG. The field is kept in the model because the
                // shipped catalog uses it to record what probing found.
                minZoom.getValue(), maxZoom.getValue(), keptFormat, built, hosts,
                attribution.getText().trim(), blankToNull(signupUrl.getText()),
                blankToNull(signupHint.getText()), blankToNull(termsUrl.getText()),
                bulkDownload.isSelected(), blankToNull(quotaNote.getText()), keptCoverage,
                listOf(languages.getText()), keptLogo);
    }

    /**
     * A row turned back into the theme it edits: the five columns, and what {@link ThemeRow} kept
     * beside them – a shipped theme's own format, transparency and note, which the table does not
     * show, come back from this dialog as they went in, not as null, false and null. The note comes
     * back only while the address it was written of is still the one the theme uses.
     * Package-private so a test can check it without the toolkit the rest of the dialog needs.
     *
     * @param providerTemplate the provider's address as typed, which a theme without its own uses
     */
    static @NotNull Provider.Theme themeOf(final @NotNull ThemeRow r,
                                          final @NotNull String providerTemplate) {
        final String address = addressOf(r.getTemplate(), providerTemplate);
        final String note = java.util.Objects.equals(r.noteAddress, address) ? r.note : null;
        return new Provider.Theme(r.getId().trim(), r.getName().trim(), zoomOf(r.getMinZoom(), r),
                zoomOf(r.getMaxZoom(), r), r.expectedFormat, blankToNull(r.getTemplate()), r.transparent,
                note);
    }

    /** A zoom column: blank is the provider's own, anything else a whole number. */
    private static @Nullable Integer zoomOf(final @NotNull String text, final @NotNull ThemeRow r) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (final NumberFormatException ex) {
            throw new IllegalArgumentException(
                    I18n.format("editor.themes.notAZoom", text, r.getId()), ex);
        }
    }

    /** The address a theme uses: its own, or else the provider's. */
    private static @Nullable String addressOf(final @Nullable String template,
                                             final @NotNull String providerTemplate) {
        final String own = blankToNull(template);
        return own != null ? own : blankToNull(providerTemplate);
    }

    /** A field of comma- or space-separated words, as a list; null when there are none. */
    static @Nullable List<String> listOf(final @NotNull String text) {
        final List<String> words = java.util.Arrays.stream(text.split("[,\\s]+"))
                .filter(s -> !s.isBlank()).toList();
        return words.isEmpty() ? null : words;
    }

    /** The message under the form, and the reason OK is off. Empty means it would save. */
    private void revalidate() {
        try {
            final Provider candidate = toProvider();
            if (!candidate.id().equals(originalId) && store.isIdTaken(candidate.id())) {
                problem.setText(I18n.format("editor.idTaken", candidate.id()));
                return;
            }
            store.check(candidate);
            problem.setText("");
        } catch (final RuntimeException ex) {
            problem.setText(EngineText.of(ex));
        }
    }

    /**
     * A zoom level read and written in the display locale. Text that is not a whole number keeps
     * the value the spinner had: the default converter threw on it, out of the spinner and into
     * the uncaught-exception dialog, for a stray letter in a field.
     */
    static @NotNull StringConverter<Integer> zoomConverter(final @NotNull Supplier<Integer> current) {
        return new StringConverter<>() {
            @Override
            public String toString(final Integer zoom) {
                return zoom == null ? "" : I18n.integer(zoom);
            }

            @Override
            public Integer fromString(final String text) {
                final Double typed = I18n.parseNumber(text);
                return typed == null || typed != Math.rint(typed) ? current.get() : typed.intValue();
            }
        };
    }

    private static @NotNull String nullToEmpty(final @Nullable String s) {
        return s == null ? "" : s;
    }

    private static @Nullable String blankToNull(final @Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
