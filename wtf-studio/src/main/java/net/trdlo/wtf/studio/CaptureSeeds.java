package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.map.TileCanvas;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.studio.steps.Step;
import net.trdlo.wtf.studio.steps.StepPanel;
import net.trdlo.wtf.studio.steps.StepRail;
import net.trdlo.wtf.tiles.LonLatBounds;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * States worth photographing, for the unattended capture runs (see {@link BatchMode}) and for them
 * only: a subject, a Custom area, a faded treatment, a many-part country with its editor, a view,
 * the 1:1 preview – and how many tiles are still decoding, so a run knows when to take the picture.
 * <p>
 * Kept out of {@link MainWindow}, which is the window people use.
 */
final class CaptureSeeds {

    private static final Logger log = LoggerFactory.getLogger(CaptureSeeds.class);

    private final ProjectModel model;
    private final TileCanvas map;
    private final StepRail rail;
    /** The window's panels, filled when it is built. */
    private final Map<Step, StepPanel> panels;

    CaptureSeeds(final @NotNull ProjectModel model, final @NotNull TileCanvas map, final @NotNull StepRail rail,
                 final @NotNull Map<Step, StepPanel> panels) {
        this.model = model;
        this.map = map;
        this.rail = rail;
        this.panels = panels;
    }

    /**
     * Fills the project from the view, so that an unattended run has something to photograph: the
     * step pictures, the preview's, and those of a Custom area or a faded surrounding. Nothing,
     * when a subject is seeded already or an example is open, which has an area.
     * <p>
     * A subject, and the area worked out from it with room around the edge – the path a new
     * project takes. Half the panels in steps 4 and 5 exist only when the print has two regions,
     * and a picture of the half that is always there proves nothing about the half that is not.
     */
    void seedAreaFromView() {
        if (model.subjectProperty().get() != null || model.areaProperty().get() != null) {
            return;
        }
        // The subject, and the area follows from it – which is the path a new project takes, so
        // it is the one the pictures should show.
        final LonLatBounds view = map.camera().visibleBounds();
        final double insetX = view.widthDegrees() / 4, insetY = view.heightDegrees() / 4;
        model.subjectProperty().set(net.trdlo.wtf.compose.Shapes.rectangle(new LonLatBounds(
                view.west() + insetX, view.south() + insetY,
                view.east() - insetX, view.north() - insetY)));
        model.subjectOriginProperty().set("drawn");
        model.bufferValueProperty().set(50_000);        // enough room that the two shapes differ
    }

    /** How many tile decodes are still in flight, so an unattended run knows when to snapshot. */
    BatchMode.@NotNull PendingCount pendingDecodes() {
        return () -> map.imageCache() == null ? 0 : map.imageCache().pending();
    }

    /**
     * The same for the 1:1 preview, whose canvas has an image cache of its own. Its source
     * downloads a tile the cache lacks inside the decode that asked for it, so a decode still in
     * flight is a download still in flight too.
     */
    BatchMode.@NotNull PendingCount pendingDecodes(final net.trdlo.wtf.studio.steps.@NotNull ScalePreview preview) {
        return preview::pendingDecodes;
    }

    /**
     * A treatment the eye can see, for the unattended capture; a new project has none. The ground
     * <b>outside</b> the subject faded and the inside left alone: the two-region case, which the
     * map and the 1:1 preview both draw in two passes.
     */
    void seedFadedTreatment() {
        model.outsideDiffersProperty().set(true);
        model.outsideEffectProperty().set(net.trdlo.wtf.compose.TileEffect.FADED);
    }

    /**
     * A many-part subject with the editor live on it, for the unattended capture.
     * <p>
     * Germany is twenty-two polygons and Greece is seventy-four, and a many-part shape is what the
     * editor has to handle – so a picture of a rectangle with handles on it would prove the wrong
     * thing.
     */
    void seedEditableSubject() {
        net.trdlo.wtf.studio.outline.OutlineLibrary.get().search("germany", 5).stream()
                .filter(e -> e.level() == net.trdlo.wtf.studio.outline.OutlineLibrary.COUNTRY)
                .findFirst()
                .ifPresent(entry -> {
                    try {
                        model.subjectProperty().set(
                                net.trdlo.wtf.studio.outline.OutlineLibrary.get().geometry(entry));
                        model.subjectOriginProperty().set("library:" + entry.id() + ":" + entry.name());
                    } catch (final java.io.IOException ex) {
                        log.warn("No subject to edit: {}", ex.toString());
                    }
                });
        final Geometry subject = model.subjectProperty().get();
        if (subject == null) {
            return;
        }
        // No tool is installed here. Step 1 installs its own for as long as it is
        // open, and a second one bound to the same property, put there behind the panel's back,
        // would be discarded the moment the step changed.
        map.openOn(() -> map.camera().zoomTo(net.trdlo.wtf.compose.Shapes.boundsOf(subject), 0.08));
    }

    /** A subject read from a file, for the unattended capture. */
    void seedSubjectFrom(final @NotNull Path file) {
        try {
            model.subjectProperty().set(net.trdlo.wtf.compose.ShapeImport.read(file));
            model.subjectOriginProperty().set(file.getFileName().toString());
        } catch (final IOException | RuntimeException ex) {
            System.err.println("[batch] could not read " + file + ": " + ex);
        }
    }

    /** The view, for the unattended capture: a place and a zoom. */
    void seedView(final double lon, final double lat, final double zoom) {
        map.openOn(() -> map.camera().centerOn(lon, lat, zoom));
    }

    /**
     * The other half of the area step, for the unattended capture: a Custom area, of a shape no fit
     * of the subject would give – two parts, one of them no rectangle – grown as the seeded subject's
     * area is.
     */
    void seedDrawnArea() {
        final LonLatBounds view = map.camera().visibleBounds();
        final double w = view.widthDegrees(), h = view.heightDegrees();
        final Geometry quad = Shapes.polygon(new Coordinate[]{
                new Coordinate(view.west() + w * 0.15, view.south() + h * 0.2),
                new Coordinate(view.west() + w * 0.5, view.south() + h * 0.15),
                new Coordinate(view.west() + w * 0.55, view.north() - h * 0.2),
                new Coordinate(view.west() + w * 0.2, view.north() - h * 0.3)});
        final Geometry box = Shapes.rectangle(new LonLatBounds(
                view.west() + w * 0.65, view.south() + h * 0.4, view.east() - w * 0.15, view.north() - h * 0.35));
        model.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        model.customProperty().set(
                Shapes.withPart(Shapes.withPart(null, quad, Shapes.Role.AREA), box, Shapes.Role.AREA));
        model.customOriginProperty().set("drawn");
    }

    /**
     * An area under a fit that has no corners to drag, for the unattended capture.
     * <p>
     * The one state most likely to come out wrong: the handles must be absent and the panel must
     * say where to change the shape instead.
     */
    void seedOutlineArea() {
        seedEditableSubject();
        model.simplifyProperty().set(net.trdlo.wtf.compose.Shapes.Simplify.NONE);
    }

    /**
     * A print this wide, for the unattended capture: 150 mm is one sheet of any paper and 1 000 mm
     * several, so the paper step can be photographed with a seam and without one, whatever paper
     * this machine last used.
     */
    void seedPrintWidth(final double mm) {
        model.printModeProperty().set(net.trdlo.wtf.compose.PrintSize.Mode.FIT_WIDTH);
        model.printWidthMmProperty().set(mm);
    }

    /** Opens the 1:1 preview, for the unattended capture of a window that is otherwise its own. */
    net.trdlo.wtf.studio.steps.@NotNull ScalePreview openPreview() {
        rail.select(net.trdlo.wtf.studio.steps.Step.DETAIL);
        return ((net.trdlo.wtf.studio.steps.DetailPanel)
                panels.get(net.trdlo.wtf.studio.steps.Step.DETAIL)).openPreviewWindow();
    }
}
