package net.trdlo.wtf.studio.steps;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.stage.Stage;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.TileEffect;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.trdlo.wtf.studio.Headless.find;
import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In the step the window builds: the three sliders of a region's treatment are there for
 * <i>Custom</i> only, and the outside's treatment is there once <i>Colors of its own</i> is
 * ticked and not before. What is not shown is not merely disabled: nothing of it is on the screen.
 * <p>
 * On JavaFX's headless platform ({@link Headless}). The step asks its context for the tile cache
 * only for a map with {@code @2x} tiles, which the test's provider has not, so there is none.
 */
class DetailPanelTest {

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** A project with a subject, as one opened from a file is: nothing unsaved. */
    private static ProjectModel project() {
        final ProjectModel m = new ProjectModel();
        final Provider nowhere = Headless.nowhere();
        m.startOver(() -> {
            m.providerProperty().set(nowhere);
            m.themeProperty().set(nowhere.themes().getFirst());
            m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
            m.customProperty().set(Shapes.rectangle(BISCAY));
            m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(-5, 47, 0, 49)));
            m.zoomProperty().set(6);
        });
        return m;
    }

    private static Stage show(final DetailPanel panel) {
        return Headless.show(new ScrollPane(panel.node()), 360, 1000);
    }

    /** Whether the node and every node between it and the step is shown, and takes room. */
    private static boolean shown(final Node step, final Node node) {
        for (Node n = node; n != null; n = n == step ? null : n.getParent()) {
            if (!n.isVisible() || !n.isManaged()) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Node> List<T> all(final Node root, final Class<T> type) {
        final List<T> found = new ArrayList<>();
        if (type.isInstance(root)) {
            found.add(type.cast(root));
        }
        if (root instanceof final Parent parent) {
            for (final Node child : List.copyOf(parent.getChildrenUnmodifiable())) {
                found.addAll(all(child, type));
            }
        }
        return found;
    }

    private static long slidersShown(final Node step) {
        return all(step, Slider.class).stream().filter(s -> shown(step, s)).count();
    }

    /** The treatment dropdowns, the inside's first. */
    @SuppressWarnings("unchecked")
    private static List<ComboBox<String>> presets(final Node step) {
        return all(step, ComboBox.class).stream()
                .filter(c -> ((ComboBox<?>) c).getItems().contains(I18n.get("detail.effect.custom")))
                .map(c -> (ComboBox<String>) c).toList();
    }

    @Test
    @DisplayName("the sliders are there for Custom only, and Custom can be picked before a slider is moved")
    void slidersForCustomOnly() throws Exception {
        Stage stage = null;
        try {
            final ProjectModel m = project();
            final DetailPanel panel = new DetailPanel(m, new StepContext(null, null, null, ProviderCatalog::builtIn, () -> null,
                    () -> false, () -> false, p -> { }));
            stage = onFx(() -> show(panel));
            final ComboBox<String> inside = onFx(() -> presets(panel.node()).getFirst());
            assertEquals(0, onFx(() -> slidersShown(panel.node())), "none while the treatment is None");

            run(() -> inside.setValue(I18n.get("detail.effect.custom")));
            assertEquals(3, onFx(() -> slidersShown(panel.node())), "three once Custom is picked");
            assertEquals(TileEffect.NONE, onFx(() -> m.insideEffectProperty().get()), "picking changes nothing");
            assertFalse(m.dirtyProperty().get());

            run(() -> inside.setValue(I18n.get("detail.effect.faded")));
            assertEquals(0, onFx(() -> slidersShown(panel.node())), "and none for Faded");
            assertEquals(TileEffect.FADED, onFx(() -> m.insideEffectProperty().get()));

            final TileEffect own = new TileEffect(0.5, 0.1, 0);
            run(() -> m.insideEffectProperty().set(own));
            assertEquals(3, onFx(() -> slidersShown(panel.node())), "a treatment of one's own is Custom");
            assertEquals(I18n.get("detail.effect.custom"), onFx(inside::getValue));

            run(() -> m.insideEffectProperty().set(TileEffect.NONE));
            assertEquals(0, onFx(() -> slidersShown(panel.node())), "undone to None, the sliders go");
            assertEquals(I18n.get("detail.effect.none"), onFx(inside::getValue));
        } finally {
            close(stage);
        }
    }

    @Test
    @DisplayName("a slider moved back to where None is leaves the sliders where they are")
    void aSliderPassingThroughNone() throws Exception {
        Stage stage = null;
        try {
            final ProjectModel m = project();
            final DetailPanel panel = new DetailPanel(m, new StepContext(null, null, null, ProviderCatalog::builtIn, () -> null,
                    () -> false, () -> false, p -> { }));
            stage = onFx(() -> show(panel));
            final ComboBox<String> inside = onFx(() -> presets(panel.node()).getFirst());
            run(() -> inside.setValue(I18n.get("detail.effect.custom")));
            final Slider color = onFx(() -> all(panel.node(), Slider.class).getFirst());

            run(() -> color.setValue(0.5));
            assertEquals(0.5, onFx(() -> m.insideEffectProperty().get().saturation()), 1e-9);
            run(() -> color.setValue(1));
            assertEquals(TileEffect.NONE, onFx(() -> m.insideEffectProperty().get()));
            assertEquals(3, onFx(() -> slidersShown(panel.node())), "the slider being dragged is not taken away");
        } finally {
            close(stage);
        }
    }

    @Test
    @DisplayName("the outside has no controls until Colors of its own is ticked")
    void theOutsideOpensWithTheBox() throws Exception {
        Stage stage = null;
        try {
            final ProjectModel m = project();
            final DetailPanel panel = new DetailPanel(m, new StepContext(null, null, null, ProviderCatalog::builtIn, () -> null,
                    () -> false, () -> false, p -> { }));
            stage = onFx(() -> show(panel));
            final ComboBox<String> outside = onFx(() -> presets(panel.node()).get(1));
            final CheckBox differs = onFx(() -> find(panel.node(), CheckBox.class,
                    c -> I18n.get("detail.outside.differs").equals(c.getText())).orElseThrow());

            assertFalse(onFx(() -> shown(panel.node(), outside)), "nothing to set yet");
            run(() -> differs.setSelected(true));
            assertTrue(onFx(() -> shown(panel.node(), outside)), "the treatment opens with the box");
            assertEquals(0, onFx(() -> slidersShown(panel.node())), "without sliders, for None");

            run(() -> outside.setValue(I18n.get("detail.effect.custom")));
            assertEquals(3, onFx(() -> slidersShown(panel.node())));

            run(() -> differs.setSelected(false));
            assertFalse(onFx(() -> shown(panel.node(), outside)), "and closes with it");
            assertEquals(0, onFx(() -> slidersShown(panel.node())));
        } finally {
            close(stage);
        }
    }

    private static void run(final Runnable call) throws Exception {
        onFx(() -> {
            call.run();
            return null;
        });
    }

    private static void close(final Stage stage) throws Exception {
        if (stage != null) {
            onFx(() -> {
                stage.close();
                return null;
            });
        }
    }
}
