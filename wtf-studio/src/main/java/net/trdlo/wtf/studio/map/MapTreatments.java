package net.trdlo.wtf.studio.map;

import javafx.scene.effect.Effect;
import net.trdlo.wtf.compose.TileEffect;
import net.trdlo.wtf.studio.project.ProjectModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The two regions' treatments, as the main map shows them while they are being chosen.
 * <p>
 * One treatment is the whole map's, which costs nothing. Two are the base layer for the ground
 * outside the subject and a clipped copy over it for the ground inside, so "faded outside, full
 * color inside" can be judged by eye rather than by exporting a PDF and opening it. A region that
 * is not drawn at all is shown as the background it will be printed as. It is kept apart from the
 * map's canvas so that the rule can be tested without the toolkit.
 */
public final class MapTreatments {

    /**
     * What the map is to show.
     *
     * @param base         the whole map's filter, or the ground outside the subject's when split
     * @param region       the subject, when the inside is shown apart; null otherwise
     * @param regionEffect the inside's filter, when split
     * @param drawInside   whether the ground inside is drawn at all
     * @param drawOutside  whether the ground outside is
     */
    public record Shown(@Nullable Effect base, @Nullable Geometry region, @Nullable Effect regionEffect,
                        boolean drawInside, boolean drawOutside) {
    }

    private MapTreatments() {
    }

    /**
     * Shows the treatments now and whenever what they depend on changes: a treatment, whether the
     * two differ, the subject, and whether a region is drawn.
     *
     * @param show what puts a {@link Shown} on the map
     */
    public static void watch(final @NotNull ProjectModel model, final @NotNull Consumer<Shown> show) {
        final Runnable again = () -> show.accept(shown(model));
        model.insideEffectProperty().addListener((o, a, b) -> again.run());
        model.outsideEffectProperty().addListener((o, a, b) -> again.run());
        model.outsideDiffersProperty().addListener((o, a, b) -> again.run());
        model.subjectProperty().addListener((o, a, b) -> again.run());
        model.insideDrawnProperty().addListener((o, a, b) -> again.run());
        model.outsideDrawnProperty().addListener((o, a, b) -> again.run());
        again.run();
    }

    static @NotNull Shown shown(final @NotNull ProjectModel model) {
        final TileEffect inside = model.insideEffectProperty().get();
        final TileEffect outside = model.outsideTreatment();
        final Geometry subject = model.subjectProperty().get();
        final boolean drawInside = model.insideDrawnProperty().get();
        final boolean drawOutside = model.outsideDrawnProperty().get();
        final boolean split = subject != null && !subject.isEmpty()
                && (!Objects.equals(inside, outside) || !drawInside || !drawOutside);
        if (!split) {
            return new Shown(FxEffects.toFx(inside), null, null, true, true);
        }
        // A side that is not drawn is white paper on the map, and no filter of its own may gray it
        return new Shown(drawOutside ? FxEffects.toFx(outside) : null, subject,
                drawInside ? FxEffects.toFx(inside) : null, drawInside, drawOutside);
    }
}
