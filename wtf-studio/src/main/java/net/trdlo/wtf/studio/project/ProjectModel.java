package net.trdlo.wtf.studio.project;

import javafx.beans.Observable;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.tiles.Provider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Everything the five steps decide, as observable properties, and the derived numbers they imply.
 * <p>
 * The panels only ever write to this; the canvas overlays, the scale table and the export run only
 * ever read from it. That is what keeps the steps from having to know about each other – changing
 * the roll width in step 3 makes the sheet overlay redraw and the export estimate change without
 * step 3 knowing either exists.
 * <p>
 * <b>Nothing derivable is stored.</b> {@link #fill()}, {@link #printRect()} and {@link #layout()}
 * are computed, cached against {@link #revisionProperty()} and thrown away on any change – the
 * same rule the project file follows, and for the same reason: a stored DPI is a DPI that can
 * disagree with the zoom beside it.
 * <p>
 * The model is the properties, and what undo needs of them. What is worked out from the subject or
 * the Custom shape is {@link ProjectDerivation}'s, and the job built from all of it is
 * {@link JobBuilder}'s; the methods here that answer for either hand the question on.
 */
public final class ProjectModel {

    /** A roll is cut one way, sheets both; the distinction changes which controls make sense. */
    public enum MediumKind { ROLL, SHEET }

    // --- source ---------------------------------------------------------------------------
    private final ObjectProperty<Provider> provider = new SimpleObjectProperty<>();
    private final ObjectProperty<Provider.Theme> theme = new SimpleObjectProperty<>();
    /**
     * The language asked of the providers that take one, which is the language of whoever is
     * looking at the map rather than the language of whoever wrote the application.
     */
    private final StringProperty mapLang = new SimpleStringProperty(defaultMapLang());

    /**
     * The map language a new project starts with: the system's, or English on a system whose
     * locale names none. The splash and the opening map read the cache in it as well. Not what a
     * file that names none is read in, which is its provider's own on every machine (D40).
     */
    public static @NotNull String defaultMapLang() {
        final String system = java.util.Locale.getDefault().getLanguage();
        return net.trdlo.wtf.tiles.TileKey.LANG.matcher(system).matches() ? system : "en";
    }

    // --- identity -------------------------------------------------------------------------
    private final StringProperty title = new SimpleStringProperty("");
    private final StringProperty notes = new SimpleStringProperty("");
    private final ObjectProperty<Path> file = new SimpleObjectProperty<>();

    // --- step 1, subject -------------------------------------------------------------------
    private final ObjectProperty<Geometry> subject = new SimpleObjectProperty<>();
    private final StringProperty subjectOrigin = new SimpleStringProperty();

    // --- step 2, area ------------------------------------------------------------------------
    /**
     * What the area grows from: the subject, fitted, or a shape of the person's own.
     * <p>
     * <b>Not which of two areas to show.</b> The area is always worked out, from one or the other,
     * and there is one Custom shape, held only while it is the one chosen: choosing From the
     * subject drops it rather than keeping it out of sight. Somebody who edited it, switched to the
     * subject and back would otherwise find their edit waiting for them, which is a shape the
     * application was keeping and not showing. Undo brings it back with the source, in one step.
     */
    public enum AreaSource { FROM_SUBJECT, CUSTOM }

    /**
     * <b>The subject first, the area from it.</b>
     * <p>
     * The subject is the thing somebody wants to print – a country, a region, a lake – and it is
     * the one shape they can state exactly. How much world goes around it is a consequence:
     * its bounding box, its convex hull, or its own outline, and then room around the edge. Asking
     * for the area first and the subject second had it backwards, and left two shapes to keep in
     * step by hand.
     * <p>
     * A Custom area stays available, because a print is not always the subject and a margin –
     * Czechia on a map of Europe with Svalbard taken out of it, or "this valley, and I will decide
     * the cut later". It is any shape, built of parts as the subject is, and it grows and fades as
     * the fit does: the area is always worked out, from the one or the other ({@link #areaFit}).
     * <p>
     * Touching the fit is what transfers it. A write to {@link #areaFit} that the model did not make
     * itself – a corner of the box dragged – makes the box as it is let go the Custom shape, keeps
     * the growth, and moves this to {@link AreaSource#CUSTOM}, so moving a corner is the whole of
     * "take it over" and there is no button to press first. Choosing Custom with no Custom shape
     * takes the fit over the same way.
     */
    private final ObjectProperty<AreaSource> areaSource = new SimpleObjectProperty<>(AreaSource.FROM_SUBJECT);

    private final ObjectProperty<Shapes.Simplify> simplify =
            new SimpleObjectProperty<>(Shapes.Simplify.BOUNDING_BOX);
    private final DoubleProperty bufferValue = new SimpleDoubleProperty(0);
    /**
     * The shape the area grows from when it is not the subject: any shape, made of parts that stay
     * parts as the subject's do – Europe from the library with Svalbard taken out, two valleys
     * drawn. Null while the subject decides the area, and while nothing has been drawn.
     */
    private final ObjectProperty<Geometry> custom = new SimpleObjectProperty<>();
    /** Where the Custom shape came from, in the subject's terms: a library entry, a file, "drawn". */
    private final StringProperty customOrigin = new SimpleStringProperty();
    /**
     * The edge the growth starts from, worked out with the area: under From the subject the subject
     * itself for the outline and its box or hull for the other fits, under Custom the Custom shape
     * itself – the same objects, where they are the shape. The handles in the Area step are on it,
     * not on the grown edge, and a write to it the model did not make takes the area over; see
     * {@link #areaSource}.
     */
    private final ObjectProperty<Geometry> areaFit = new SimpleObjectProperty<>();
    /** What is printed: {@link #areaFit} grown. Always worked out, so only the model writes it. */
    private final ReadOnlyObjectWrapper<Geometry> area = new ReadOnlyObjectWrapper<>();
    /**
     * Whether a rectangular area is printed to its edge and no further, the spare paper staying
     * paper. Off, the rectangle grows with the print and the spare is more map (D34). Either way
     * an outline or a hull is its own edge.
     */
    private final BooleanProperty areaExact = new SimpleBooleanProperty(false);
    /**
     * Whether the map fades out across the growth, from the shape the area grows from to the
     * area's edge. Kept while the area is not grown, when there is nothing to fade across, so that
     * going back to a grown area finds it as it was left.
     */
    private final BooleanProperty areaFade = new SimpleBooleanProperty(false);

    // --- step 3, medium ------------------------------------------------------------------------
    //
    // These are what a new project starts as, so they are the one place where somebody else's
    // habits are most easily mistaken for the application's opinion. What a person is certain to
    // have is a sheet of A4, so that is where a new project starts, and everything larger is a
    // choice they make rather than one they undo.
    private final ObjectProperty<MediumKind> mediumKind = new SimpleObjectProperty<>(MediumKind.SHEET);
    /** 36 inches: the common large-format roll, and only reached once somebody picks rolls. */
    private final DoubleProperty rollWidthMm = new SimpleDoubleProperty(914);
    private final ObjectProperty<Medium.Axis> stripAxis = new SimpleObjectProperty<>(Medium.Axis.VERTICAL);
    private final ObjectProperty<Medium.Paper> paper = new SimpleObjectProperty<>(Medium.paper("A4"));
    private final ObjectProperty<Medium.Orientation> orientation =
            new SimpleObjectProperty<>(Medium.Orientation.PORTRAIT);
    private final DoubleProperty overlapMm = new SimpleDoubleProperty(15);
    /**
     * The printer's white edge, which the outermost sheets keep free round the print. The
     * project's, since it decides the layout; a new one starts with the edge last used here.
     */
    private final DoubleProperty edgeMm = new SimpleDoubleProperty(Marks.DEFAULT_EDGE_MM);
    /** Whether the print's size is stated in millimeters, in sheets, as a DPI or as a scale. */
    public enum SizeBy { MILLIMETERS, SHEETS, DPI, SCALE }

    /**
     * <b>The paper first, then how much of it.</b>
     * <p>
     * Somebody knows what their printer takes; they do not use this to discover what paper they
     * need. So the medium is the first question, and the size is the second – and once the sheet
     * is known, "two across" is a far more natural way to say how big than "four hundred and five
     * millimeters". The count on one axis is given; the other follows from the area's own shape,
     * rounded up to a whole sheet.
     * <p>
     * A count is turned into millimeters and handed to the same {@link PrintSize} as before, so
     * nothing downstream knows this control exists.
     */
    private final ObjectProperty<SizeBy> sizeBy = new SimpleObjectProperty<>(SizeBy.MILLIMETERS);
    private final IntegerProperty sheetCount = new SimpleIntegerProperty(2);
    /** True when the count is sheets across the print, false when it is sheets down it. */
    private final BooleanProperty countAcross = new SimpleBooleanProperty(true);
    /**
     * The DPI the print is asked for at, when it is sized that way. With one tile pixel one printed
     * dot, it and the zoom fix the print's width – {@link PrintSize#atDpi} – and it alone fixes how
     * tall a place name comes out, at any zoom.
     */
    private final IntegerProperty dpi = new SimpleIntegerProperty(150);
    /**
     * The denominator of the scale the print is asked for at, when it is sized that way: 50000 for
     * 1:50 000. It and the area fix the print's width – {@link PrintSize#atScale} – and the zoom
     * then decides only the DPI.
     */
    private final IntegerProperty mapScale = new SimpleIntegerProperty(50_000);

    private final ObjectProperty<PrintSize.Mode> printMode =
            new SimpleObjectProperty<>(PrintSize.Mode.FIT_WIDTH);
    /** One sheet of the default paper, which is a print somebody can actually make today. */
    private final DoubleProperty printWidthMm = new SimpleDoubleProperty(210);
    private final DoubleProperty printHeightMm = new SimpleDoubleProperty(297);

    // --- step 4, detail ------------------------------------------------------------------------
    /** The zoom of the whole print, both regions alike. */
    private final IntegerProperty zoom = new SimpleIntegerProperty(13);
    /**
     * 1, or 2 for the zoom's {@code @2x} tiles where the map has them: the DPI of the zoom above with
     * the names of this one. Chosen with the zoom, as a row of the Detail table.
     */
    private final IntegerProperty scale = new SimpleIntegerProperty(1);
    private final ObjectProperty<TileEffect> insideEffect = new SimpleObjectProperty<>(TileEffect.NONE);
    private final ObjectProperty<TileEffect> outsideEffect = new SimpleObjectProperty<>(TileEffect.NONE);
    private final BooleanProperty insideDrawn = new SimpleBooleanProperty(true);
    private final BooleanProperty outsideDrawn = new SimpleBooleanProperty(true);

    /**
     * Whether the ground outside the subject is treated differently from the ground inside it.
     * <p>
     * <b>Off by default.</b> Most prints are one map. While this is off the outside has the
     * inside's treatment – {@link #outsideTreatment} – and keeps its own in {@link #outsideEffect},
     * so that turning this off and on again gives back what it had. Whether each side is drawn is
     * its own, either way: each region's heading is its own checkbox.
     * <p>
     * It is not in the project file. A file records what the two regions are, and whether they
     * differ is a question that can be asked of them when it is read.
     * <p>
     * <b>On, whatever was asked, while only the outside is drawn</b>: the inside's treatment is
     * then nobody's to set, and the outside's own controls are the only ones left. Forced on, the
     * outside takes the treatment it had, the inside's, so that hiding the inside changes nothing
     * else on the map.
     */
    private final BooleanProperty outsideDiffers = new SimpleBooleanProperty(false);

    {
        final javafx.beans.InvalidationListener alone = o -> {
            if (outsideAlone() && !outsideDiffers.get()) {
                outsideEffect.set(insideEffect.get());
                outsideDiffers.set(true);
            }
        };
        insideDrawn.addListener(alone);
        outsideDrawn.addListener(alone);
        outsideDiffers.addListener(alone);
    }

    /** Whether the outside is drawn and the inside is not, so that the outside has to differ. */
    private boolean outsideAlone() {
        return !insideDrawn.get() && outsideDrawn.get();
    }

    /** What the outside is drawn with: the inside's treatment, until somebody says otherwise. */
    public @NotNull TileEffect outsideTreatment() {
        return outsideDiffers.get() ? outsideEffect.get() : insideEffect.get();
    }
    private final BooleanProperty boundaryStroked = new SimpleBooleanProperty(false);
    private final DoubleProperty boundaryWidthMm = new SimpleDoubleProperty(1.0);
    /**
     * The subject's color: its outline and fill on the map, and the boundary line on the print.
     * <p>
     * One color for both, because they are the same line – the map shows where the print will draw
     * it, and a map outline in one color above a printed line in another would ask which is right.
     */
    public static final int SUBJECT_RGB = 0xFFA94D;

    private final IntegerProperty boundaryRgb = new SimpleIntegerProperty(SUBJECT_RGB);
    private final IntegerProperty backgroundRgb = new SimpleIntegerProperty(0xFFFFFF);

    // --- step 5, export ------------------------------------------------------------------------
    private final ObservableSet<ComposeJob.OutputFormat> formats =
            FXCollections.observableSet(EnumSet.of(ComposeJob.OutputFormat.PDF));
    private final ObjectProperty<ComposeJob.OutputGrouping> grouping =
            new SimpleObjectProperty<>(ComposeJob.OutputGrouping.PER_SHEET);
    private final ObjectProperty<Path> outputDir = new SimpleObjectProperty<>();
    private final StringProperty baseName = new SimpleStringProperty(I18n.get("project.baseName"));
    private final ObjectProperty<MissingTilePolicy> missingTiles =
            new SimpleObjectProperty<>(MissingTilePolicy.REFUSE);

    /** Bumped on every change, so a listener can watch one thing instead of forty. */
    private final ReadOnlyIntegerWrapper revision = new ReadOnlyIntegerWrapper();

    /** What is worked out from the subject, and the job made of it all; see the class comment. */
    private final ProjectDerivation derivation;
    private final JobBuilder builder;

    /**
     * Everything a person decides, in the order {@link #put} writes it back – for an undo and for a
     * file opened; the export formats are beside it, being a set.
     * <p>
     * <b>One list for two jobs.</b> A change to any of these is what makes the project unsaved, and
     * these are what undo puts back – so nothing can be changed that undo does not restore, or
     * restored without the project knowing it changed. A property left out of it is neither.
     * <p>
     * The order is the listeners'. The provider comes before its map, because picking a provider
     * picks a map for it. What the area is worked out from and how – the subject, the fit, the
     * growth, the source and the Custom shape – come before the area, and the edge it grows from
     * just before it, so that whoever looks at them when the area changes finds them agreeing; and
     * the two derived ones are here at all so that undo puts them back rather than working them out
     * again. The kind of medium comes before the sheet count's axis, which the medium step rewrites
     * for a roll. Whether the outside differs comes before what is drawn, which turns it on while
     * only the outside is, and the outside's treatment after that, which the same rule overwrites.
     */
    private final List<Property<?>> decided;

    /** Undo and redo, and whether the project is saved; see {@link History}. */
    private final History history;

    public ProjectModel() {
        decided = List.of(
                provider, theme, mapLang, title, notes,
                subject, subjectOrigin, simplify, bufferValue, areaSource, custom, customOrigin, areaFit, area,
                mediumKind, rollWidthMm, stripAxis, paper, orientation, overlapMm, edgeMm,
                sizeBy, sheetCount, countAcross, dpi, mapScale, printMode, printWidthMm, printHeightMm,
                zoom, scale, outsideDiffers, insideEffect, insideDrawn, outsideEffect, outsideDrawn,
                boundaryStroked, boundaryWidthMm, boundaryRgb, backgroundRgb,
                grouping, outputDir, baseName, missingTiles, creditOnPrint, scaleOnPrint, scaleMark,
                areaExact, areaFade);
        // Before the history, whose first step asks the derivation how the area stands
        derivation = new ProjectDerivation(this, area, this::unionLanded);
        builder = new JobBuilder(this, derivation);
        // Before the listeners, which tell it about changes; its first step is the model as it starts
        history = new History(this);
        final List<Observable> watched = new ArrayList<>(decided);
        watched.add(formats);
        for (final Observable o : watched) {
            o.addListener(this::touch);
        }

        derivation.install();
    }

    // -------------------------------------------------------------------------------------------
    // Deriving in the background
    // -------------------------------------------------------------------------------------------

    /**
     * How long typing has to pause before an expensive area is worked out.
     * <p>
     * Long enough that "50000" typed into Grow by is one derivation and not five, short enough
     * that a single change still feels immediate.
     */
    static final long DERIVE_DELAY_MILLIS = 400;

    /**
     * Works the expensive areas out off the FX thread, after typing pauses.
     * <p>
     * <b>Why only some of them.</b> The outline itself means joining every part of the subject
     * and, with Grow by, buffering it – for the surveyed Czech border, 125 000 points, that is
     * long enough per keystroke to make "50000" painful to type – and a Custom area is always its
     * shape's parts joined and grown, Europe's 575 among them. A bounding box or a hull of the
     * subject costs milliseconds and stays immediate, which also keeps the area's corners under
     * the pointer while a subject is being dragged.
     * <p>
     * Off by default: the model is also used without a JavaFX toolkit, by the tests and by the
     * command line, where everything should simply happen when it is asked for.
     *
     * @param fx where to hand a finished area back – {@code Platform::runLater} in the application
     */
    public void deriveInBackground(final java.util.concurrent.@NotNull Executor fx) {
        derivation.deriveInBackground(fx);
    }

    /** True while an area is being worked out, so the panel can say so instead of showing the last one as current. */
    public ReadOnlyBooleanProperty areaPendingProperty() {
        return derivation.areaPendingProperty();
    }

    /**
     * Makes sure the area is the finished one, working it out now if it was still on its way.
     * <p>
     * For whatever must not use a stale area: saving the project, and exporting it. A few hundred
     * milliseconds of waiting there are worth a file that says what the settings say.
     */
    public void settleArea() {
        derivation.settleArea();
    }

    // -------------------------------------------------------------------------------------------
    // Changes, batches, and the undo stack's primitives
    // -------------------------------------------------------------------------------------------

    /** How deep the batches go, and whether anything inside them changed; see {@link #batch}. */
    private int batches;
    private boolean touchedInBatch;
    /** Above zero while the model is written without a person doing it; see {@link #quietly}. */
    private int quiet;

    /**
     * Something a person decides has changed: the job is out of date, the history hears of it, and
     * the listeners are told – now, or once when the batch around this ends.
     */
    private void touch(final @NotNull Observable what) {
        // Now, not with the revision: inside a batch the revision waits, and a listener that asks
        // for the job in the meantime must not be handed the one from before
        builder.invalidate();
        if (quiet == 0) {
            history.touched(what, derivation.isDeriving());
        }
        if (batches > 0) {
            touchedInBatch = true;
            return;
        }
        revision.set(revision.get() + 1);
    }

    /**
     * Runs several writes as one change to the revision.
     * <p>
     * <b>For the listeners, not for undo.</b> Every property bumps the revision, and every bump
     * rebuilds the rail, the status line, the scale table and whatever else watches it – so opening
     * a project write by write rebuilt all of them forty times over half-written states, while the
     * quiet it was opened in kept down nothing but the dirty flag. Where a step of undo ends is the
     * history's business.
     */
    private void batch(final @NotNull Runnable r) {
        batches++;
        try {
            r.run();
        } finally {
            if (--batches == 0 && touchedInBatch) {
                touchedInBatch = false;
                revision.set(revision.get() + 1);
            }
        }
    }

    /**
     * Writes nobody made by hand: in one batch, and unheard by the history.
     * <p>
     * The batch is inside the quiet, so its closing bump – and whatever the listeners write back in
     * answer to it, like the medium step filling in the side of the print it works out – is quiet
     * too, and cannot turn an undo into a change of its own.
     */
    private void quietly(final @NotNull Runnable r) {
        quiet++;
        try {
            batch(r);
        } finally {
            quiet--;
        }
    }

    /**
     * Replaces the project with what {@code r} writes – a new project, the settings the window starts
     * with – as a state with nothing to undo and nothing unsaved.
     * <p>
     * Written into this model as it goes, so an {@code r} that throws halfway leaves it half-written.
     * A file is not opened this way but through {@link #replaceWith}, read whole into a model of its
     * own first.
     */
    public void startOver(final @NotNull Runnable r) {
        quietly(r);
        history.startOver();
    }

    /**
     * The project at one moment, as the undo stack keeps it: every value in {@link #decided}, the
     * formats, and the area's working-out as it stood.
     * <p>
     * The values are shared, not copied. A shape is never changed once it is made – which is why
     * {@link Shapes#validate} hands back a copy – and everything else is a record, an enum, a string
     * or a number, so a snapshot costs a reference per setting and its neighbors share the rest.
     * <p>
     * <b>Nor is a point of one.</b> Shapes share their points as well: a dragged vertex's shape is
     * made of the parts and the points the drag left where they were ({@code RingModel}), and the
     * outline itself is worked out of its subject's points ({@link ProjectDerivation#withPointsOf}),
     * so neighboring steps share them too. Nothing may therefore sort, reverse, normalize or set the
     * points of a shape in place – a {@code normalize()} where {@code norm()} was meant would
     * rearrange every step that shares them, the one an undo goes back to among them. What changes
     * a shape works on a copy of it.
     * <p>
     * <b>The derived things it keeps are the area and the edge it grows from</b>, against the rule
     * in the class comment, so that putting a state back does not mean working them out again:
     * under "the outline itself" or a Custom area that is a second write, 400 ms after the first.
     */
    static final class Snapshot {
        /** The values of {@link #decided}, in its order; many of them may be null. */
        private final Object @NotNull [] values;
        private final @NotNull Set<ComposeJob.OutputFormat> formats;
        private final @Nullable String deriveProblem;
        /** False when the area was still on its way; restoring this works it out instead of trusting it. */
        private final boolean areaSettled;

        private Snapshot(final Object @NotNull [] values,
                         final @NotNull Set<ComposeJob.OutputFormat> formats,
                         final @Nullable String deriveProblem, final boolean areaSettled) {
            this.values = values;
            this.formats = formats;
            this.deriveProblem = deriveProblem;
            this.areaSettled = areaSettled;
        }
    }

    @NotNull Snapshot snapshot() {
        final Object[] values = new Object[decided.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = decided.get(i).getValue();
        }
        return new Snapshot(values, Set.copyOf(formats), derivation.problem(), derivation.isSettled());
    }

    private @Nullable Object in(final @NotNull Snapshot s, final @NotNull Property<?> p) {
        return s.values[decided.indexOf(p)];
    }

    /**
     * Whether two snapshots are the same project: what a person decided, as far as it is in effect.
     * <p>
     * Not every value. The provider and the map are compared by id, because a catalog reloaded after
     * the Providers dialog replaces their objects, and a definition edited there is not a change to
     * this project. The area and the edge it grows from are not compared: both are worked out from
     * the subject or the Custom shape, the fit and the growth, which are, and in one of the two the
     * area may still be on its way. The side of the print its mode does not fix is not in effect –
     * the medium step shows what that side comes out as in its place. The sheet count and its axis
     * are in effect only while the print is sized in sheets, the DPI and the scale only while it is
     * sized at one, and how the scale is printed only while it is. Counting any of those
     * would make a project that nobody has touched ask to be saved.
     */
    boolean sameProject(final @NotNull Snapshot a, final @NotNull Snapshot b) {
        final Object mode = in(a, printMode) == in(b, printMode) ? in(a, printMode) : null;
        final boolean inSheets = in(a, sizeBy) == SizeBy.SHEETS || in(b, sizeBy) == SizeBy.SHEETS;
        final boolean atDpi = in(a, sizeBy) == SizeBy.DPI || in(b, sizeBy) == SizeBy.DPI;
        final boolean atScale = in(a, sizeBy) == SizeBy.SCALE || in(b, sizeBy) == SizeBy.SCALE;
        final boolean scalePrinted = Boolean.TRUE.equals(in(a, scaleOnPrint))
                || Boolean.TRUE.equals(in(b, scaleOnPrint));
        for (int i = 0; i < decided.size(); i++) {
            final Property<?> p = decided.get(i);
            if (p == area || p == areaFit
                    || p == printHeightMm && mode == PrintSize.Mode.FIT_WIDTH
                    || p == printWidthMm && mode == PrintSize.Mode.FIT_HEIGHT
                    || !inSheets && (p == sheetCount || p == countAcross)
                    || !atDpi && p == dpi
                    || !atScale && p == mapScale
                    || !scalePrinted && p == scaleMark) {
                continue;
            }
            final boolean same = p == provider || p == theme
                    ? Objects.equals(idOf(a.values[i]), idOf(b.values[i]))
                    : Objects.equals(a.values[i], b.values[i]);
            if (!same) {
                return false;
            }
        }
        return a.formats.equals(b.formats);
    }

    private static @Nullable String idOf(final @Nullable Object providerOrTheme) {
        if (providerOrTheme instanceof final Provider p) {
            return p.id();
        }
        return providerOrTheme instanceof final Provider.Theme t ? t.id() : null;
    }

    /**
     * Whether edits to this come in runs that make one step: typed, dragged or held down.
     * <p>
     * Text and numbers – a field typed into, a slider, a spinner held – and the two treatments,
     * which four sliders edit. Never a shape, a checkbox or a choice: each of those is a step a click.
     */
    boolean comesInRuns(final @NotNull Observable p) {
        return p instanceof StringProperty || p instanceof DoubleProperty || p instanceof IntegerProperty
                || p == insideEffect || p == outsideEffect;
    }

    /**
     * Puts the project back as a snapshot has it.
     * <p>
     * <b>The area settled first, then everything written under the derivation's own flag.</b> An
     * area still being worked out belongs to the state being left and must not land on the one put
     * back; after {@link #settleArea} nothing is on its way, and a late answer is dropped by the
     * generation count. The rest is {@link #put}, with the provider and its map as the catalog
     * defines them now.
     */
    void restore(final @NotNull Snapshot s) {
        settleArea();
        final Provider source = definitionOf((Provider) in(s, provider));
        final Provider.Theme map = source == null ? (Provider.Theme) in(s, theme)
                : themeOf(source, (Provider.Theme) in(s, theme));
        put(s, source, map);
    }

    /**
     * Replaces the project with the one another model holds – a file read into a model of its own –
     * as a state with nothing to undo and nothing unsaved.
     * <p>
     * <b>Read whole, then swapped.</b> Opening a file fills a model nobody is looking at, and only one
     * that came out whole is put in here, so a file that fails halfway leaves this project and its
     * history as they were. The values go across as that model holds them, the provider too: it was
     * filled from the catalog as it is now, and looking it up again as undo does would pass over one
     * the person has hidden – and open their project on another.
     * <p>
     * An area still on its way here is dropped, not settled as undo settles it: the project it
     * belongs to is the one being replaced. And dropped it has to be, because {@link #put} leaves the
     * generation where it is, and a late answer would land on the project just opened.
     */
    void replaceWith(final @NotNull ProjectModel read) {
        derivation.abandon();
        final Snapshot s = read.snapshot();
        startOver(() -> put(s, (Provider) in(s, provider), (Provider.Theme) in(s, theme)));
    }

    /**
     * Replaces the project with a new one – what a fresh model holds, with the provider, the map,
     * the map language, the paper, its orientation and the printer's white edge kept – as a state
     * with nothing to undo and nothing unsaved.
     * <p>
     * <b>A fresh model, not a list of resets.</b> Clearing the shapes, the title and the notes and
     * leaving the rest would have the new project export into the previous one's folder under its
     * base name and replace its prints without a word; and clearing the edge the area grows from
     * before the subject reads, to the model, as somebody taking the area over. What a new project has is
     * what a model starts with, and going in through {@link #put} writes it all under the
     * derivation's flag, so the next subject picked gets its area. The provider, the map, its
     * language, the paper and the printer's edge are the machine's before they are the project's,
     * and stay – the map and the language being the ones last used with the provider; the seam
     * marks are not in a snapshot at all.
     */
    public void startNew() {
        startNew(fresh -> {
            fresh.provider.set(provider.get());
            fresh.theme.set(theme.get());
            fresh.mapLang.set(mapLang.get());
            fresh.paper.set(paper.get());
            fresh.orientation.set(orientation.get());
            fresh.edgeMm.set(edgeMm.get());
        });
    }

    /**
     * {@link #startNew()} with the machine's choices written by {@code machine} into the fresh
     * model, rather than taken from the project it replaces – which may be an example, whose
     * provider and paper are nobody's choice (D43).
     */
    public void startNew(final @NotNull java.util.function.Consumer<ProjectModel> machine) {
        final ProjectModel fresh = new ProjectModel();
        machine.accept(fresh);
        replaceWith(fresh);
    }

    /**
     * Writes a snapshot over the project, with the provider and the map given.
     * <p>
     * Every value is written under the derivation's flag, so neither its rederive, nor what choosing
     * a source brings with it, nor the takeover listener reacts: the area is put back rather than
     * worked out again 400 ms later as a second write, the Custom shape is the one the snapshot
     * has, and putting the fit back is not mistaken for somebody taking it over.
     * <p>
     * All of it quietly, in one batch: the history does not hear of it, and the listeners hear of it
     * once.
     */
    private void put(final @NotNull Snapshot s, final @Nullable Provider source,
                     final Provider.@Nullable Theme map) {
        quietly(() -> {
            derivation.restoring(s.deriveProblem, () -> {
                for (int i = 0; i < decided.size(); i++) {
                    final Property<?> p = decided.get(i);
                    final Object value = p == provider ? source : p == theme ? map : s.values[i];
                    write(p, value);
                }
                formats.retainAll(s.formats);
                formats.addAll(s.formats);
            });
            if (!s.areaSettled) {
                // Taken while the area was on its way: worked out now, not 400 ms from now
                derivation.deriveNow();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void write(final @NotNull Property<?> p, final @Nullable Object value) {
        ((Property<Object>) p).setValue(value);
    }

    /** How a provider named in an older snapshot is found as it is now; see {@link #setProviderLookup}. */
    private @Nullable Function<String, Optional<Provider>> providerLookup;

    /**
     * Where undo finds the providers it puts back, as they are defined now.
     * <p>
     * A snapshot holds a provider as it was when the snapshot was taken, and the Providers dialog
     * can have changed its definition since – its address, its key – or removed it. Without this an
     * undo could bring back an old URL template and download with it under the right name.
     */
    public void setProviderLookup(final @NotNull Function<String, Optional<Provider>> lookup) {
        this.providerLookup = lookup;
    }

    /**
     * The provider to put back for one a snapshot holds: the current one when it is the same one,
     * else the catalog's definition of it now.
     * <p>
     * One that has left the catalog is not brought back; the current provider stays, as a project
     * naming a provider that is no longer installed opens on one that is. With no lookup, as in the
     * tests, the snapshot's own object is all there is.
     */
    private @Nullable Provider definitionOf(final @Nullable Provider was) {
        final Provider now = provider.get();
        if (was == null) {
            return null;
        }
        if (now != null && now.id().equals(was.id())) {
            return now;
        }
        if (providerLookup == null) {
            return was;
        }
        return providerLookup.apply(was.id()).orElse(now != null ? now : was);
    }

    /** The map to put back, as the provider defines it now; its first when that map has gone. */
    private static Provider.@Nullable Theme themeOf(final @NotNull Provider p, final Provider.@Nullable Theme was) {
        return was == null ? null : p.theme(was.id()).orElse(p.themes().getFirst());
    }

    // -------------------------------------------------------------------------------------------
    // Derived. Computed, never stored; see the class comment.
    // -------------------------------------------------------------------------------------------

    /**
     * The outcome of trying to turn the current settings into a job.
     *
     * @param job     the job, or null when something is missing or contradictory
     * @param problem why not, in the user's language, or null when {@code job} is present
     */
    public record Built(@Nullable ComposeJob job, @Nullable String problem) {
        public boolean ok() {
            return job != null;
        }
    }

    public ReadOnlyIntegerProperty revisionProperty() {
        return revision.getReadOnlyProperty();
    }

    /** The job, or the first reason there is not one. Cached until something changes. */
    public @NotNull Built build() {
        return builder.build();
    }

    /**
     * Makes sure the shapes the job joins are there – the subject's parts, and a Custom shape's for
     * the edge its fade starts from – joining them on this thread if the worker has not delivered
     * them yet. For the export, which must not be refused for a union on its way.
     */
    public void settleUnions() {
        boolean joined = derivation.settleSubject();
        if (fades() && areaSource.get() == AreaSource.CUSTOM) {
            joined |= derivation.settleCustom();
        }
        if (joined) {
            builder.invalidate();
        }
    }

    /** A shape's parts have come back joined: a new job, and no change to the project. */
    private void unionLanded() {
        builder.invalidate();
        revision.set(revision.get() + 1);
    }

    public ObjectProperty<AreaSource> areaSourceProperty() {
        return areaSource;
    }

    public @NotNull PrintSize printSize() {
        return builder.printSize();
    }

    /**
     * What {@code n} sheets measure along the counted axis, once they have been overlapped.
     * <p>
     * Every seam but the last is shared, so n sheets of w with an overlap of o measure
     * {@code n*w - (n-1)*o}. A roll has one sheet size across the strips – the roll's own width –
     * and is counted the same way.
     */
    public double millimetersOf(final int n) {
        return builder.millimetersOf(n);
    }

    /** Whether the print can be counted in sheets along this axis at all; a roll has one. */
    public boolean isCountable(final boolean across) {
        return builder.isCountable(across);
    }

    /**
     * Whether the print is counted across rather than down: the axis chosen, where the medium can be
     * counted along it. A roll can be counted one way only – in strips side by side – so it is
     * counted that way whatever was chosen for sheets, and the choice is kept for when the medium is
     * sheets again. Worked out rather than written, so neither opening the medium step nor turning
     * the strips writes anything a person did not.
     */
    public boolean countsAcross() {
        return builder.countsAcross();
    }

    public ObjectProperty<SizeBy> sizeByProperty() {
        return sizeBy;
    }

    public IntegerProperty sheetCountProperty() {
        return sheetCount;
    }

    public BooleanProperty countAcrossProperty() {
        return countAcross;
    }

    public IntegerProperty dpiProperty() {
        return dpi;
    }

    public IntegerProperty mapScaleProperty() {
        return mapScale;
    }

    public @NotNull Medium medium() {
        return builder.medium();
    }

    /** With no subject shape there is one region, and the inside settings are the settings. */
    public @NotNull Regions regions(final boolean hasSubject) {
        return builder.regions(hasSubject);
    }

    /** {@code buffer(simplify(area))} – what is actually printed – or null when there is no area. */
    public @Nullable Geometry fill() {
        return builder.fill();
    }

    public @Nullable PrintRect printRect() {
        return builder.printRect();
    }

    /** The sheets, and the print rectangle they are cut from, grown by the paper they leave spare. */
    public @Nullable SheetLayout layout() {
        return builder.layout();
    }

    public static @NotNull Path defaultOutputDir() {
        return Path.of(System.getProperty("user.home"), "Documents", "WTF");
    }

    /**
     * The marks for putting the sheets together – the sheet's number and place, and arrows to the
     * sheet that goes over each edge – from the application's settings.
     * <p>
     * Not part of the project: they describe the person printing and how they put sheets together.
     * {@code MainWindow} hands them in at the start; the export step changes them. The printer's
     * white edge is not among them: it decides the layout, so it is the project's
     * ({@link #edgeMmProperty}); only the credit on the print is the project's among the marks.
     */
    public record Printing(boolean marks) {
        public static final Printing DEFAULTS = new Printing(true);
    }

    private @NotNull Printing printing = Printing.DEFAULTS;

    /** Whether the provider's credit goes on the finished print, bottom right. The project's. */
    private final BooleanProperty creditOnPrint = new SimpleBooleanProperty(true);

    public BooleanProperty creditOnPrintProperty() {
        return creditOnPrint;
    }

    /** Whether the map scale goes on the finished print, bottom left, and how. The project's. */
    private final BooleanProperty scaleOnPrint = new SimpleBooleanProperty(false);
    private final ObjectProperty<ScaleMark> scaleMark = new SimpleObjectProperty<>(ScaleMark.BOTH);

    public BooleanProperty scaleOnPrintProperty() {
        return scaleOnPrint;
    }

    public ObjectProperty<ScaleMark> scaleMarkProperty() {
        return scaleMark;
    }

    public @NotNull Printing printing() {
        return printing;
    }

    /**
     * New settings: the job is built again, and the overlap hint says what they mean.
     * <p>
     * Not a change to the project, so it does not mark it unsaved: these are the machine's, and
     * a project that says "unsaved changes" after a sheet number was switched off is saying
     * something about the wrong thing.
     */
    public void setPrinting(final @NotNull Printing p) {
        printing = p;
        revision.set(revision.get() + 1);
    }

    /**
     * The folder last exported to, from any project – what a project that never chose one uses.
     * <p>
     * A preference, not part of the project: a new or unsaved project has no folder of its own,
     * and sending it to Documents every time made somebody browse back to the same place after
     * every start. Not observable, because it only matters where no folder was chosen, and
     * choosing one bumps the revision anyway.
     */
    private @Nullable Path rememberedOutputDir;

    public void setRememberedOutputDir(final @Nullable Path dir) {
        this.rememberedOutputDir = dir;
    }

    /** The folder an export goes to: the project's own, else the last one used, else Documents. */
    public @NotNull Path effectiveOutputDir() {
        if (outputDir.get() != null) {
            return outputDir.get();
        }
        return rememberedOutputDir != null ? rememberedOutputDir : defaultOutputDir();
    }

    /** The zooms worth offering: everything the theme has, capped so the table stays a table. */
    public @NotNull List<Integer> availableZooms() {
        final Provider p = provider.get();
        final Provider.Theme t = theme.get();
        if (p == null || t == null) {
            return List.of();
        }
        final List<Integer> out = new ArrayList<>();
        for (int z = p.minZoomOf(t); z <= p.maxZoomOf(t); z++) {
            out.add(z);
        }
        return out;
    }

    // -------------------------------------------------------------------------------------------
    // Properties
    // -------------------------------------------------------------------------------------------

    public ObjectProperty<Provider> providerProperty() {
        return provider;
    }

    public ObjectProperty<Provider.Theme> themeProperty() {
        return theme;
    }

    public StringProperty mapLangProperty() {
        return mapLang;
    }

    public StringProperty titleProperty() {
        return title;
    }

    public StringProperty notesProperty() {
        return notes;
    }

    public ObjectProperty<Path> fileProperty() {
        return file;
    }

    /** Whether the project differs from what was last opened or saved; the history decides. */
    public ReadOnlyBooleanProperty dirtyProperty() {
        return history.dirtyProperty();
    }

    public @NotNull History history() {
        return history;
    }

    /** What is printed. Read only: it is worked out from the subject or the Custom shape. */
    public ReadOnlyObjectProperty<Geometry> areaProperty() {
        return area.getReadOnlyProperty();
    }

    /** The edge the area grows from; written by the model, and by a corner of the box dragged. */
    public ObjectProperty<Geometry> areaFitProperty() {
        return areaFit;
    }

    public ObjectProperty<Geometry> subjectProperty() {
        return subject;
    }

    public StringProperty subjectOriginProperty() {
        return subjectOrigin;
    }

    public ObjectProperty<Geometry> customProperty() {
        return custom;
    }

    public StringProperty customOriginProperty() {
        return customOrigin;
    }

    public ObjectProperty<Shapes.Simplify> simplifyProperty() {
        return simplify;
    }

    public DoubleProperty bufferValueProperty() {
        return bufferValue;
    }

    public BooleanProperty areaExactProperty() {
        return areaExact;
    }

    public BooleanProperty areaFadeProperty() {
        return areaFade;
    }

    /**
     * Whether the print fades out across the growth: asked for, a growth to fade across, and a
     * shape it grows from – the subject, or the Custom shape.
     */
    public boolean fades() {
        final Geometry base = areaSource.get() == AreaSource.CUSTOM ? custom.get() : subject.get();
        return areaFade.get() && bufferValue.get() > 0 && base != null;
    }

    public ObjectProperty<MediumKind> mediumKindProperty() {
        return mediumKind;
    }

    public DoubleProperty rollWidthMmProperty() {
        return rollWidthMm;
    }

    public ObjectProperty<Medium.Axis> stripAxisProperty() {
        return stripAxis;
    }

    public ObjectProperty<Medium.Paper> paperProperty() {
        return paper;
    }

    public ObjectProperty<Medium.Orientation> orientationProperty() {
        return orientation;
    }

    public DoubleProperty overlapMmProperty() {
        return overlapMm;
    }

    public DoubleProperty edgeMmProperty() {
        return edgeMm;
    }

    public ObjectProperty<PrintSize.Mode> printModeProperty() {
        return printMode;
    }

    public DoubleProperty printWidthMmProperty() {
        return printWidthMm;
    }

    public DoubleProperty printHeightMmProperty() {
        return printHeightMm;
    }

    public IntegerProperty zoomProperty() {
        return zoom;
    }

    public IntegerProperty scaleProperty() {
        return scale;
    }

    /**
     * The scale the print is made at: the one chosen, where the map has {@code @2x} tiles, and 1 on a
     * map that has none – kept, as the axis of a count is, for when the map is one that has them
     * again. Worked out rather than written, so picking another map writes nothing.
     */
    public int printScale() {
        return builder.scale();
    }

    public BooleanProperty outsideDiffersProperty() {
        return outsideDiffers;
    }

    public ObjectProperty<TileEffect> insideEffectProperty() {
        return insideEffect;
    }

    public ObjectProperty<TileEffect> outsideEffectProperty() {
        return outsideEffect;
    }

    public BooleanProperty insideDrawnProperty() {
        return insideDrawn;
    }

    public BooleanProperty outsideDrawnProperty() {
        return outsideDrawn;
    }

    public BooleanProperty boundaryStrokedProperty() {
        return boundaryStroked;
    }

    public DoubleProperty boundaryWidthMmProperty() {
        return boundaryWidthMm;
    }

    public IntegerProperty boundaryRgbProperty() {
        return boundaryRgb;
    }

    public IntegerProperty backgroundRgbProperty() {
        return backgroundRgb;
    }

    public @NotNull ObservableSet<ComposeJob.OutputFormat> formats() {
        return formats;
    }

    public ObjectProperty<ComposeJob.OutputGrouping> groupingProperty() {
        return grouping;
    }

    public ObjectProperty<Path> outputDirProperty() {
        return outputDir;
    }

    public StringProperty baseNameProperty() {
        return baseName;
    }

    public ObjectProperty<MissingTilePolicy> missingTilesProperty() {
        return missingTiles;
    }
}
