package net.trdlo.wtf.studio.project;

import javafx.collections.ObservableSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.studio.EngineText;
import net.trdlo.wtf.studio.FileText;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.project.ProjectDerivation.Union;
import net.trdlo.wtf.studio.project.ProjectModel.AreaSource;
import net.trdlo.wtf.studio.project.ProjectModel.Built;
import net.trdlo.wtf.studio.project.ProjectModel.MediumKind;
import net.trdlo.wtf.studio.project.ProjectModel.Printing;
import net.trdlo.wtf.studio.project.ProjectModel.SizeBy;
import net.trdlo.wtf.tiles.Provider;

import java.util.EnumSet;

/**
 * The project's settings made into a {@link ComposeJob}, or the first reason they cannot be – built
 * when it is asked for, and kept until the revision moves or something is written.
 * <p>
 * Part of {@link ProjectModel}, whose settings it reads. The subject made one shape, and a Custom
 * shape made one for its fade, come from the {@link ProjectDerivation}, which may still be working
 * on them.
 */
final class JobBuilder {

    private final ProjectModel model;
    private final ProjectDerivation derivation;

    private @Nullable Built cached;
    private int cachedRevision = -1;

    /**
     * A copy the job was last given for one of the model's own geometries, kept by identity so a
     * revision that leaves the shape untouched – a keystroke in the export name among them, since
     * every write bumps the revision – does not copy it again.
     * <p>
     * Its envelope is worked out here, on the FX thread, before any worker has it: the count and
     * the export can run at once over the same copy, and would otherwise race each other for it.
     */
    private static final class CopyOnChange {
        private @Nullable Geometry source;
        private @Nullable Geometry copy;

        @NotNull Geometry of(final @NotNull Geometry current) {
            if (current != source) {
                source = current;
                copy = current.copy();
                copy.getEnvelopeInternal();
            }
            return java.util.Objects.requireNonNull(copy);
        }
    }

    private final CopyOnChange fillCopy = new CopyOnChange();
    private final CopyOnChange subjectCopy = new CopyOnChange();

    /** The subject's fit a fade starts from, and the subject copy and the fit it was worked out for. */
    private @Nullable Geometry fitSubject;
    private Shapes.@Nullable Simplify fitSimplify;
    private @Nullable Geometry fit;

    JobBuilder(final @NotNull ProjectModel model, final @NotNull ProjectDerivation derivation) {
        this.model = model;
        this.derivation = derivation;
    }

    /** Something was written: the next {@link #build} makes the job again, whatever the revision says. */
    void invalidate() {
        cached = null;
    }

    /**
     * The subject's fit the area was grown from, for a fade across the growth to start at. Worked
     * out again only when the subject or the fit changes, since this runs on the FX thread at every
     * revision.
     * <p>
     * An outline's fit is the subject made one, which the derivation has already done – the same
     * union {@link Shapes#fitOf} would make again, of 125 000 points for a surveyed border.
     */
    private @NotNull Geometry fitOfSubject(final @NotNull Geometry subjectShape) {
        final Shapes.Simplify simplify = model.simplifyProperty().get();
        final Geometry kept = fit;
        if (kept != null && subjectShape == fitSubject && simplify == fitSimplify) {
            return kept;
        }
        final Geometry made = simplify == Shapes.Simplify.NONE ? subjectShape
                : Shapes.fitOf(subjectShape, simplify);
        made.getEnvelopeInternal();         // before a worker has it, as CopyOnChange does
        fitSubject = subjectShape;
        fitSimplify = simplify;
        fit = made;
        return made;
    }

    /** See {@link ProjectModel#build}. */
    @NotNull Built build() {
        if (cached != null && cachedRevision == model.revisionProperty().get()) {
            return cached;
        }
        cachedRevision = model.revisionProperty().get();
        cached = doBuild();
        return cached;
    }

    private @NotNull Built doBuild() {
        final Provider p = model.providerProperty().get();
        final Provider.Theme t = model.themeProperty().get();
        if (p == null || t == null) {
            return new Built(null, I18n.get("problem.noSource"));
        }
        final Geometry area = model.areaProperty().get();
        if (area == null) {
            final String deriveProblem = derivation.problem();
            if (deriveProblem != null) {
                return new Built(null, deriveProblem);
            }
            return new Built(null, I18n.get(model.areaSourceProperty().get() == AreaSource.FROM_SUBJECT
                    ? "problem.noSubject" : "problem.noArea"));
        }
        try {
            // The subject's parts are made one shape here and nowhere earlier: it can be Germany
            // and Slovakia and a rectangle over the bit that matters, and each of those stays a
            // part of its own until the last moment so it can still be edited or removed. The
            // area needs none of that – it is one shape already, worked out by the derivation,
            // and it is what gets printed.
            final Geometry parts = model.subjectProperty().get();
            final Union one = parts == null ? null : derivation.subjectAsOne(parts);
            if (parts != null && one == null) {
                return new Built(null, I18n.get("area.working"));
            }
            if (one != null && one.problem() != null) {
                return new Built(null, one.problem());
            }
            // Copies, for the count and export threads: the map goes on drawing the model's own
            // area and subject while they run, and JTS fills a shape's envelope in lazily the
            // first time it is asked for it, with no lock around it – the same reason
            // ProjectDerivation.deriveLater copies for its worker.
            final Geometry fill = fillCopy.of(area);
            final Geometry subjectShape = one == null ? null
                    : subjectCopy.of(java.util.Objects.requireNonNull(one.one()));

            // The edge a fade across the growth starts from, which under Custom is its parts made
            // one: on the worker too, and the job waits for it as it waits for the subject's
            Geometry fadeFrom = null;
            if (model.fades() && model.areaSourceProperty().get() == AreaSource.CUSTOM) {
                final Union edge = derivation.customAsOne(
                        java.util.Objects.requireNonNull(model.customProperty().get()));
                if (edge == null) {
                    return new Built(null, I18n.get("area.working"));
                }
                if (edge.problem() != null) {
                    return new Built(null, edge.problem());
                }
                fadeFrom = edge.one();
            } else if (model.fades() && subjectShape != null) {
                fadeFrom = fitOfSubject(subjectShape);
            }

            final PrintRect rect = printSize().apply(Shapes.boundsOf(fill));
            final Regions regions = regions(subjectShape != null);

            final ObservableSet<ComposeJob.OutputFormat> formats = model.formats();
            final Printing printing = model.printing();
            // The name step 5 gives the print, which its files are written under – or the word
            // they fall back to when it is blank – and so what each sheet's label calls it. Not
            // the project's title, which nothing in the window shows: Save proposes it as the
            // project file's name, and that is all it is for.
            final String name = FileNames.safe(model.baseNameProperty().get(), I18n.get("project.baseName"));
            return new Built(new ComposeJob(fill, subjectShape, p, t.id(), model.mapLangProperty().get(),
                    regions, rect, medium(), model.overlapMmProperty().get(),
                    model.areaExactProperty().get(),
                    fadeFrom, model.backgroundRgbProperty().get(),
                    model.missingTilesProperty().get(),
                    EnumSet.copyOf(formats.isEmpty()
                            ? EnumSet.of(ComposeJob.OutputFormat.PDF) : formats),
                    model.groupingProperty().get(), model.effectiveOutputDir(), name,
                    new Marks(model.edgeMmProperty().get(), printing.marks(), printing.marks(),
                            false, model.creditOnPrintProperty().get(),
                            model.scaleOnPrintProperty().get() ? model.scaleMarkProperty().get() : null,
                            name, Marks.today(),
                            // The label's words in the interface language, which the engine has
                            // none of. Its numbers are the engine's to fill in on each sheet, so
                            // here each placeholder is filled in with itself.
                            I18n.format("export.marks.sheet", "{0}", "{1}", "{2}", "{3}")),
                    // The layout and the attribution beside the print, in the interface language
                    // as well: the engine lays them out and asks these for every line
                    FileText.WORDS), null);
        } catch (final RuntimeException ex) {
            // Every one of these is a message the user can act on: an empty shape, a negative
            // buffer that ate it, an overlap wider than the sheet – each a refusal with its reason,
            // which EngineText says in the interface language.
            return new Built(null, EngineText.of(ex));
        }
    }

    @NotNull PrintSize printSize() {
        if (model.sizeByProperty().get() == SizeBy.SHEETS) {
            final double mm = millimetersOf(Math.max(1, model.sheetCountProperty().get()));
            return countsAcross() ? PrintSize.width(mm) : PrintSize.height(mm);
        }
        final Geometry area = model.areaProperty().get();
        if (model.sizeByProperty().get() == SizeBy.DPI && area != null) {
            return PrintSize.atDpi(Shapes.boundsOf(fillCopy.of(area)), model.zoomProperty().get(),
                    scale(), Math.max(1, model.dpiProperty().get()));
        }
        if (model.sizeByProperty().get() == SizeBy.SCALE && area != null) {
            return PrintSize.atScale(Shapes.boundsOf(fillCopy.of(area)),
                    Math.max(1, model.mapScaleProperty().get()));
        }
        return new PrintSize(model.printModeProperty().get(), model.printWidthMmProperty().get(),
                model.printHeightMmProperty().get());
    }

    double millimetersOf(final int n) {
        final Medium medium = medium();
        // A roll answers -1 along the direction it is not cut in, because that direction is the
        // whole print and is not made of sheets at all.
        final double sheet = countsAcross() ? medium.acrossMm() : medium.downMm();
        if (sheet <= 0) {
            return Math.max(1, model.printWidthMmProperty().get());
        }
        // The outermost sheets keep the printer's white edge free round the print, once each side
        return Math.max(1, n * sheet - (n - 1) * model.overlapMmProperty().get()
                - 2 * model.edgeMmProperty().get());
    }

    /** See {@link ProjectModel#printScale}. */
    int scale() {
        final Provider p = model.providerProperty().get();
        final Provider.Theme t = model.themeProperty().get();
        return model.scaleProperty().get() == 2 && p != null && t != null && p.offersRetina(t) ? 2 : 1;
    }

    /** See {@link ProjectModel#countsAcross}. */
    boolean countsAcross() {
        final boolean chosen = model.countAcrossProperty().get();
        return isCountable(chosen) ? chosen : !chosen;
    }

    boolean isCountable(final boolean across) {
        return (across ? medium().acrossMm() : medium().downMm()) > 0;
    }

    @NotNull Medium medium() {
        return model.mediumKindProperty().get() == MediumKind.ROLL
                ? new Medium.Roll(model.rollWidthMmProperty().get(), model.stripAxisProperty().get())
                : model.paperProperty().get().sheet(model.orientationProperty().get());
    }

    @NotNull Regions regions(final boolean hasSubject) {
        final int zoom = model.zoomProperty().get();
        final int scale = scale();
        final Regions.RegionSpec in = new Regions.RegionSpec(model.insideEffectProperty().get(),
                model.insideDrawnProperty().get());
        if (!hasSubject) {
            return new Regions(zoom, scale, in, in, null);
        }
        return new Regions(zoom, scale, in,
                new Regions.RegionSpec(model.outsideTreatment(), model.outsideDrawnProperty().get()),
                model.boundaryStrokedProperty().get()
                        ? new Regions.BoundaryStroke(model.boundaryWidthMmProperty().get(),
                                model.boundaryRgbProperty().get()) : null);
    }

    @Nullable Geometry fill() {
        final Built b = build();
        return b.ok() ? b.job().fillShape() : null;
    }

    @Nullable PrintRect printRect() {
        final Built b = build();
        return b.ok() ? b.job().print() : null;
    }

    @Nullable SheetLayout layout() {
        final Built b = build();
        if (!b.ok()) {
            return null;
        }
        try {
            return b.job().layout();
        } catch (final RuntimeException ex) {
            return null;    // an impossible overlap; the export panel says so
        }
    }
}
