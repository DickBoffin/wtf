package net.trdlo.wtf.studio.outline;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.studio.Buttons;
import net.trdlo.wtf.studio.Dialogs;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.StudioDialog;

import java.util.Optional;

/**
 * Picks a continent, a country or a region out of the bundled library.
 * <p>
 * A search field and a list, and nothing else. Five thousand entries is too many to browse and
 * exactly right to search: somebody printing Bavaria types four letters and presses Enter.
 * <p>
 * <b>The list is in sections, one per level</b> – continents, countries, parts of countries,
 * regions – each under a heading that says how many matched. Not a tree: French Guiana is part of
 * France and not of Europe, so nothing here is drawn as inside anything else. Each section shows
 * its best matches rather than all of them, because a list box with four thousand cells in it
 * scrolls like treacle and nobody reads past the first screen anyway.
 * <p>
 * The geometry is read when the dialog is accepted, not while the selection moves – a keystroke
 * must not cost a parse of Russia – and off the FX thread, with the dialog open until it is there.
 */
public final class OutlinePicker {

    /** Per level: enough that the right answer is on screen, few enough that the list stays quick. */
    private static final int PER_LEVEL = 50;

    /** A row of the list: a level's heading, or an entry under it. */
    private sealed interface Row permits Heading, Item { }

    private record Heading(OutlineLibrary.@NotNull Group group) implements Row { }

    private record Item(OutlineLibrary.@NotNull Entry entry) implements Row { }

    private OutlinePicker() {
    }

    /** What the caller gets back: the outline, and the entry it came from for the attribution. */
    public record Chosen(@NotNull OutlineLibrary.Entry entry, @NotNull Geometry geometry) {
    }

    public static @NotNull Optional<Chosen> show(final @NotNull Stage owner) {
        return open(owner, null, null);
    }

    /**
     * Opens the picker on a phrase and hands back its scene without waiting, for the unattended
     * capture – the one window no other capture flag could reach.
     *
     * @return whether the picker opened; false when the library is empty, which is said and
     *         leaves the capture nothing to photograph
     */
    public static boolean openForCapture(final @NotNull Stage owner, final @NotNull String phrase,
                                         final java.util.function.@NotNull Consumer<javafx.scene.Scene> shown) {
        final boolean[] opened = {false};
        open(owner, phrase, scene -> {
            opened[0] = true;
            shown.accept(scene);
        });
        return opened[0];
    }

    private static @NotNull Optional<Chosen> open(final @NotNull Stage owner, final @Nullable String phrase,
                                                 final java.util.function.@Nullable Consumer<javafx.scene.Scene> shown) {
        final OutlineLibrary library = OutlineLibrary.get();
        if (library.isEmpty()) {
            Dialogs.warn(owner, I18n.get("outline.title"), I18n.get("outline.empty"));
            return Optional.empty();
        }

        final StudioDialog<ButtonType> dialog = new StudioDialog<>(owner, "outline.title", true,
                ButtonType.OK, Buttons.CANCEL);

        final TextField search = new TextField();
        search.setPromptText(I18n.get("outline.search"));

        final ListView<Row> list = new ListView<>();
        list.setPrefHeight(460);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(final Row row, final boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("outline-heading");
                setDisable(false);
                switch (row) {
                    case null -> setText(null);
                    case Heading h -> {
                        setText(heading(h.group()));
                        getStyleClass().add("outline-heading");
                        // Not a thing to pick; the mouse passes over it and the keys skip it
                        setDisable(true);
                    }
                    case Item i -> setText("    " + (i.entry().isMainPart()
                            ? I18n.format("outline.mainPart", i.entry().shown()) : i.entry().label()));
                }
                if (empty) {
                    setText(null);
                }
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        final Label detail = new Label();
        detail.getStyleClass().add("step-summary");
        detail.setWrapText(true);

        final Runnable refill = () -> {
            final var groups = library.grouped(search.getText(), PER_LEVEL);
            final var rows = FXCollections.<Row>observableArrayList();
            for (final OutlineLibrary.Group g : groups) {
                rows.add(new Heading(g));
                g.entries().forEach(e -> rows.add(new Item(e)));
            }
            list.setItems(rows);
            // The best match anywhere, not the first row: the sections are broadest first, and
            // somebody who typed "bavaria" exactly wants Bavaria and not whatever country came up.
            final OutlineLibrary.Entry best = library.best(groups, search.getText());
            if (best != null) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i) instanceof Item(final OutlineLibrary.Entry e) && e == best) {
                        list.getSelectionModel().select(i);
                        // To the heading of its section, or the top when that is on the first
                        // screen anyway – so the continents are never scrolled away above it.
                        int heading = i;
                        while (heading > 0 && !(rows.get(heading) instanceof Heading)) {
                            heading--;
                        }
                        list.scrollTo(i < 10 ? 0 : heading);
                        break;
                    }
                }
            }
        };
        search.textProperty().addListener((o, a, b) -> refill.run());
        // A heading cannot be the selection. Arrowing onto one steps past it in the direction the
        // selection was moving, so the keys walk from the last country to the first region.
        list.getSelectionModel().selectedIndexProperty().addListener((o, was, is) -> {
            final int i = is.intValue();
            if (i >= 0 && i < list.getItems().size() && list.getItems().get(i) instanceof Heading) {
                final int step = was.intValue() > i ? -1 : 1;
                final int next = i + step >= 0 && i + step < list.getItems().size() ? i + step : i - step;
                Platform.runLater(() -> list.getSelectionModel().select(next));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, row) ->
                detail.setText(row instanceof Item(final OutlineLibrary.Entry e) ? describe(e) : ""));

        final VBox box = new VBox(8, search, list, detail);
        box.setPadding(new Insets(12));
        box.setPrefWidth(600);   // wide enough for "South Georgia and the South Sandwich Islands"
        dialog.getDialogPane().setContent(box);

        final Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !(list.getSelectionModel().getSelectedItem() instanceof Item),
                list.getSelectionModel().selectedItemProperty()));

        // Enter in the search field is "the first match, that one" – the whole point of typing
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() instanceof Item) {
                ((Button) ok).fire();
            }
        });
        search.setOnAction(e -> {
            if (list.getSelectionModel().getSelectedItem() instanceof Item) {
                ((Button) ok).fire();
            }
        });

        // OK reads the outline on a task and closes the dialog when it is there. Parsing
        // Russia's file on the FX thread would freeze the window, with the dialog already gone,
        // for as long as it takes.
        final Chosen[] chosen = new Chosen[1];
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (chosen[0] != null) {
                return;             // read: let the button close the dialog
            }
            e.consume();
            if (box.isDisable()        // already reading
                    || !(list.getSelectionModel().getSelectedItem() instanceof Item(final OutlineLibrary.Entry entry))) {
                return;
            }
            final javafx.concurrent.Task<Geometry> read = new javafx.concurrent.Task<>() {
                @Override
                protected Geometry call() throws Exception {
                    return library.geometry(entry);
                }
            };
            box.setDisable(true);
            detail.setText(I18n.get("outline.reading"));
            read.setOnSucceeded(x -> {
                chosen[0] = new Chosen(entry, read.getValue());
                ((Button) ok).fire();
            });
            read.setOnFailed(x -> {
                box.setDisable(false);
                detail.setText(describe(entry));
                Dialogs.error(dialog.getDialogPane().getScene().getWindow(), read.getException());
            });
            dialog.setOnHidden(x -> read.cancel());
            final Thread t = new Thread(read, "outline-read");
            t.setDaemon(true);
            t.start();
        });

        refill.run();
        if (phrase != null) {
            search.setText(phrase);
        }
        Platform.runLater(search::requestFocus);
        if (shown != null) {
            dialog.show();
            shown.accept(dialog.getDialogPane().getScene());
            return Optional.empty();
        }

        if (dialog.showAndWait().orElse(Buttons.CANCEL) != ButtonType.OK) {
            return Optional.empty();
        }
        return Optional.ofNullable(chosen[0]);
    }

    /** "Countries – 3" or, when a section was cut short, "Regions – the first 50 of 312". */
    private static @NotNull String heading(final OutlineLibrary.@NotNull Group g) {
        final String name = I18n.get("outline.level." + g.level());
        return g.isTruncated()
                ? I18n.format("outline.level.some", name, I18n.integer(g.entries().size()), I18n.integer(g.total()))
                : I18n.format("outline.level.all", name, I18n.integer(g.total()));
    }

    /** The one line under the list: what kind of thing it is, and how finely it is drawn. */
    private static @NotNull String describe(final OutlineLibrary.@NotNull Entry e) {
        final StringBuilder sb = new StringBuilder();
        if (e.kind() != null) {
            sb.append(e.kind());
        }
        if (e.iso() != null) {
            sb.append(sb.isEmpty() ? "" : " · ").append(e.iso());
        }
        if (e.alsoKnownAs() != null) {
            sb.append(sb.isEmpty() ? "" : " · ").append(e.alsoKnownAs());
        }
        sb.append(sb.isEmpty() ? "" : " · ").append(I18n.format("outline.points",
                I18n.integer(e.points())));
        return sb.toString();
    }
}
