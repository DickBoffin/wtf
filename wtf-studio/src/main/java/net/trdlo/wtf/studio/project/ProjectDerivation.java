package net.trdlo.wtf.studio.project;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.StringProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.EngineText;
import net.trdlo.wtf.studio.project.ProjectModel.AreaSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static net.trdlo.wtf.studio.project.ProjectModel.DERIVE_DELAY_MILLIS;

/**
 * What is worked out: the area and the edge it grows from – from the subject with the fit, or from
 * the Custom shape – and the shapes the job wants made one: the subject's parts, and a Custom
 * shape's for the edge its fade starts from.
 * <p>
 * They go to one worker when they are expensive and the application has one – the outline itself
 * of a detailed border, the join of Europe's 575 parts – and come back on the FX thread. Without a
 * worker, in the tests and on the command line, everything happens when it is asked for.
 * <p>
 * Part of {@link ProjectModel}, which holds the properties read and written here and registers
 * this after its own listeners; the model asks {@link #isDeriving} whether a write is this one's.
 */
final class ProjectDerivation {

    // What it reads, and what it writes: the model's own properties
    private final ObjectProperty<Geometry> subject;
    private final ObjectProperty<Shapes.Simplify> simplify;
    private final DoubleProperty bufferValue;
    private final ObjectProperty<AreaSource> areaSource;
    private final ObjectProperty<Geometry> custom;
    private final StringProperty customOrigin;
    private final ObjectProperty<Geometry> areaFit;
    private final ObjectProperty<Geometry> area;
    /** Told when a shape's parts come back joined: a new job, and no change to the project. */
    private final Runnable unionLanded;

    /** Set while this writes the area or its edge, so its own writes are not read as a person's. */
    private boolean deriving;
    /**
     * Above zero while writes go in that are one change – a source chosen and the Custom shape that
     * comes with it, a corner of the box let go – so that the area is worked out once, after all
     * of them, rather than after each.
     */
    private int holding;
    /** What went wrong working the area out, when nothing came of it – a buffer that ate it. */
    private @Nullable String deriveProblem;

    /** The worker, and how results get back to the FX thread; null means everything is immediate. */
    private java.util.concurrent.@Nullable ScheduledExecutorService background;
    private java.util.concurrent.@Nullable Executor fxThread;
    private java.util.concurrent.@Nullable ScheduledFuture<?> pendingDerivation;
    /** Counts derivations asked for, so a result that arrives after a newer request is dropped. */
    private long generation;
    private final ReadOnlyBooleanWrapper areaPending = new ReadOnlyBooleanWrapper();

    /**
     * A shape's parts made one shape, or why they could not be.
     *
     * @param from the shape it was made of; compared by identity, since a shape is replaced, not
     *             edited
     */
    record Union(@NotNull Geometry from, @Nullable Geometry one, @Nullable String problem) { }

    /** The subject's parts made one, for the job. */
    private final Joined subjectJoined;
    /** The Custom shape's parts made one: the edge a fade across its growth starts from. */
    private final Joined customJoined;

    /** @param area the area, which only this writes; the model hands out a view of it to read */
    ProjectDerivation(final @NotNull ProjectModel model, final @NotNull ObjectProperty<Geometry> area,
                      final @NotNull Runnable unionLanded) {
        this.subject = model.subjectProperty();
        this.simplify = model.simplifyProperty();
        this.bufferValue = model.bufferValueProperty();
        this.areaSource = model.areaSourceProperty();
        this.custom = model.customProperty();
        this.customOrigin = model.customOriginProperty();
        this.areaFit = model.areaFitProperty();
        this.area = area;
        this.unionLanded = unionLanded;
        this.subjectJoined = new Joined(subject,
                parts -> Shapes.validate(Shapes.unioned(parts), Shapes.Role.SUBJECT));
        // The edge a fade starts from is the one the area was grown from, so the same sequence
        this.customJoined = new Joined(custom, parts -> Shapes.fitOf(parts, Shapes.Simplify.NONE));
    }

    /**
     * The listeners that set the area off, bring a chosen source its shape, see a person take the
     * fit over, and start the join – called by the model after it has registered its own, so that
     * each property tells the model first.
     */
    void install() {
        // Change listeners, not invalidation listeners, and the difference matters here. An
        // invalidation listener fires only on the step from valid to invalid, so two writes with
        // no read between them are one notification – and the second write is the one that takes
        // the area over. A change listener validates the property itself and cannot miss it.
        final javafx.beans.value.ChangeListener<Object> derive = (o, was, is) -> rederive();
        for (final javafx.beans.value.ObservableValue<?> o :
                new javafx.beans.value.ObservableValue<?>[]{subject, simplify, bufferValue, custom}) {
            o.addListener(derive);
        }
        // A source chosen brings its shape with it, in the same step: Custom with nothing drawn
        // takes the fit over, and From the subject drops the Custom shape rather than keep it out
        // of sight. Then the area, once.
        areaSource.addListener((o, was, is) -> {
            if (!deriving) {
                holding(() -> chosen(is));
            }
            rederive();
        });
        // And the other direction: a write to the fit nobody here made is a person's hand on a
        // corner of the box. The box as it was let go is the Custom shape from now on, grown as
        // the box was, and the subject has stopped deciding this area.
        areaFit.addListener((o, was, is) -> {
            if (!deriving && areaSource.get() == AreaSource.FROM_SUBJECT) {
                holding(() -> {
                    custom.set(is);
                    customOrigin.set(is == null ? null : "subject");
                    areaSource.set(AreaSource.CUSTOM);
                });
                rederive();
            }
        });
        // The job will want the subject's parts joined, so the worker starts on it at once
        subject.addListener((o, was, is) -> {
            if (is != null && background != null) {
                subjectJoined.later(is);
            }
        });
    }

    /** Makes several writes one change: the area is worked out after them, not after each. */
    private void holding(final @NotNull Runnable writes) {
        holding++;
        try {
            writes.run();
        } finally {
            holding--;
        }
    }

    /**
     * What a source chosen brings with it: under Custom with no Custom shape, the fit taken over as
     * one – so that choosing it changes nothing on the paper until somebody changes the shape – and
     * under From the subject, no Custom shape at all.
     */
    private void chosen(final @Nullable AreaSource source) {
        if (source == AreaSource.CUSTOM) {
            final Geometry fit = areaFit.get();
            if (custom.get() == null && fit != null) {
                custom.set(fit);
                customOrigin.set("subject");
            }
            return;
        }
        custom.set(null);
        customOrigin.set(null);
    }

    /**
     * Works the area and the edge it grows from out again, whenever anything they depend on moves.
     * <p>
     * <b>There is one area, and this writes it.</b> Not inside {@code doBuild}, where the area on
     * screen would never be in the model at all – nothing for the file to record without recording
     * it twice. It writes the property the map draws and the job prints, and the edge the Area
     * step's handles are on.
     * <p>
     * The {@code deriving} flag is what keeps that from being circular: a write from here must not
     * be mistaken for somebody taking the area over.
     */
    private void rederive() {
        if (deriving || holding > 0) {
            return;
        }
        // Whatever was on its way is out of date now, whichever way this one goes
        dropPendingDerivation();
        final boolean fromSubject = areaSource.get() != AreaSource.CUSTOM;
        final Geometry base = fromSubject ? subject.get() : custom.get();
        final Shapes.Simplify fit = fromSubject ? simplify.get() : Shapes.Simplify.NONE;
        deriving = true;
        try {
            // The edge at once, whatever the area waits for: the shape itself, or a few points
            areaFit.set(edgeOf(base, fit));
        } finally {
            deriving = false;
        }
        if (background != null && fit == Shapes.Simplify.NONE && base != null) {
            deriveLater(base);
            return;
        }
        areaPending.set(false);
        deriving = true;
        try {
            if (base == null) {
                deriveProblem = null;
                area.set(null);
                return;
            }
            final Geometry worked = Shapes.areaFrom(base, fit, bufferValue.get());
            // The outline itself is as detailed as its shape; a box or a hull is a few points
            area.set(fit == Shapes.Simplify.NONE ? withPointsOf(worked, base, area.get()) : worked);
            deriveProblem = null;
        } catch (final RuntimeException ex) {
            // A negative buffer large enough to eat the shape. Saying so beats an empty map.
            deriveProblem = EngineText.of(ex);
            area.set(null);
        } finally {
            deriving = false;
        }
    }

    /**
     * The edge an area grows from: under the outline itself the shape itself, the same object – its
     * parts made one are the job's business, not the handles' – and its box or hull otherwise, a
     * few points. Null for no shape, or for one nothing can be made of, which the area says.
     */
    private static @Nullable Geometry edgeOf(final @Nullable Geometry base, final Shapes.@NotNull Simplify fit) {
        if (base == null || fit == Shapes.Simplify.NONE) {
            return base;
        }
        try {
            return Shapes.fitOf(base, fit);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /** See {@link ProjectModel#deriveInBackground}. */
    void deriveInBackground(final java.util.concurrent.@NotNull Executor fx) {
        this.fxThread = fx;
        this.background = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "derive-area");
            t.setDaemon(true);
            return t;
        });
    }

    ReadOnlyBooleanProperty areaPendingProperty() {
        return areaPending.getReadOnlyProperty();
    }

    /**
     * The area grown from all of a shape's parts made one – the outline itself, or a Custom area –
     * on the worker, once typing pauses.
     */
    private void deriveLater(final @NotNull Geometry parts) {
        final long mine = generation;
        // A copy for the worker: the map goes on drawing this one on the FX thread, and JTS fills
        // in a shape's envelope the first time it is asked for it, with no lock around it. The
        // worker reads the shape itself, and the area this one replaces, only for their points.
        final Geometry s = parts.copy();
        final Geometry was = area.get();
        final double grow = bufferValue.get();
        areaPending.set(true);
        pendingDerivation = java.util.Objects.requireNonNull(background).schedule(() -> {
            Geometry result = null;
            String problem = null;
            try {
                result = withPointsOf(Shapes.areaFrom(s, Shapes.Simplify.NONE, grow), parts, was);
            } catch (final RuntimeException | Error ex) {
                // An Error too – a stack overflow on a surveyed border – or the area stays
                // "on its way" for good, with nothing said
                problem = EngineText.of(ex);
            }
            final Geometry found = result;
            final String why = problem;
            java.util.Objects.requireNonNull(fxThread).execute(() -> finishDerivation(mine, found, why));
        }, DERIVE_DELAY_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void finishDerivation(final long mine, final @Nullable Geometry found, final @Nullable String problem) {
        if (mine != generation) {
            return;     // asked again since – a setting, a shape or the source: an answer to an old question
        }
        pendingDerivation = null;
        areaPending.set(false);
        deriving = true;
        try {
            deriveProblem = problem;
            area.set(found);
        } finally {
            deriving = false;
        }
    }

    /**
     * The area worked out, made of the points it has in common with the shapes it was worked out
     * from.
     * <p>
     * <b>For undo.</b> Under "the outline itself" and under Custom the area is as detailed as its
     * shape – a surveyed border's 125 000 points – and every vertex dragged works out another, which
     * the step keeps. Worked out afresh it would share nothing: not with the shape beside it in the
     * step, whose unmoved points the step before already has, nor with the area it replaces, and it
     * would be half of what the step keeps. So each of its points is one of theirs, the shape's
     * first, wherever theirs is the same point to the bit – x, y, z and m, and of the same kind, so
     * that not even the sign of a zero changes. Only the objects are exchanged: the polygons, their
     * rings and the order of their points are the ones worked out, and so is each ring's closing
     * point, which stays its own as {@code RingModel} closes its rings. The area is the one
     * {@link Shapes#areaFrom} gave, value for value, and what it shares is never changed in place,
     * as no point of a shape is – see {@link ProjectModel.Snapshot}.
     * <p>
     * Only the others' points are read, which is what makes this safe on the worker while the map
     * draws them on the FX thread: JTS works a shape's envelope out the first time it is asked for
     * it, with no lock around it. The area's own is worked out here, before it is handed over, as
     * the one worked out had it.
     */
    static @NotNull Geometry withPointsOf(final @NotNull Geometry worked,
                                          final @Nullable Geometry @NotNull ... others) {
        if (!(worked instanceof Polygonal)) {
            return worked;
        }
        final Map<Coordinate, Coordinate> points = new HashMap<>();
        for (final Geometry other : others) {
            for (final LinearRing ring : other == null ? List.<LinearRing>of() : ringsOf(other)) {
                final CoordinateSequence cs = ring.getCoordinateSequence();
                for (int i = 0; i < cs.size(); i++) {
                    final Coordinate c = cs.getCoordinate(i);
                    points.putIfAbsent(c, c);
                }
            }
        }
        final Polygon[] parts = new Polygon[worked.getNumGeometries()];
        for (int i = 0; i < parts.length; i++) {
            final Polygon p = (Polygon) worked.getGeometryN(i);
            final LinearRing[] holes = new LinearRing[p.getNumInteriorRing()];
            for (int h = 0; h < holes.length; h++) {
                holes[h] = madeOf(p.getInteriorRingN(h), points);
            }
            parts[i] = p.getFactory().createPolygon(madeOf(p.getExteriorRing(), points), holes);
        }
        final Geometry made = worked instanceof Polygon ? parts[0]
                : worked.getFactory().createMultiPolygon(parts);
        made.getEnvelopeInternal();
        return made;
    }

    /** Every ring of every polygon of a shape, each shell before its holes. */
    private static @NotNull List<LinearRing> ringsOf(final @NotNull Geometry g) {
        final List<LinearRing> rings = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (g.getGeometryN(i) instanceof final Polygon p) {
                rings.add(p.getExteriorRing());
                for (int h = 0; h < p.getNumInteriorRing(); h++) {
                    rings.add(p.getInteriorRingN(h));
                }
            }
        }
        return rings;
    }

    /**
     * The ring again, of these points wherever one is its point to the bit; its closing point stays
     * its own.
     */
    private static @NotNull LinearRing madeOf(final @NotNull LinearRing ring,
                                              final @NotNull Map<Coordinate, Coordinate> points) {
        final CoordinateSequence cs = ring.getCoordinateSequence();
        final Coordinate[] made = new Coordinate[cs.size()];
        for (int i = 0; i < made.length; i++) {
            final Coordinate own = cs.getCoordinate(i);
            final Coordinate same = i < made.length - 1 ? points.get(own) : null;
            made[i] = same != null && toTheBit(same, own) ? same : own;
        }
        return ring.getFactory().createLinearRing(made);
    }

    /**
     * Whether two points are one, which {@link Coordinate#equals} does not ask: it compares x and
     * y alone, and with {@code ==}, which takes -0 for 0.
     */
    private static boolean toTheBit(final @NotNull Coordinate a, final @NotNull Coordinate b) {
        return a.getClass() == b.getClass()
                && Double.doubleToLongBits(a.x) == Double.doubleToLongBits(b.x)
                && Double.doubleToLongBits(a.y) == Double.doubleToLongBits(b.y)
                && Double.doubleToLongBits(a.getZ()) == Double.doubleToLongBits(b.getZ())
                && Double.doubleToLongBits(a.getM()) == Double.doubleToLongBits(b.getM());
    }

    /** See {@link ProjectModel#settleArea}. */
    void settleArea() {
        if (areaPending.get()) {
            deriveNow();
        }
    }

    /** Works the area out on this thread, now, whatever the fit – once, then back to the background. */
    void deriveNow() {
        final java.util.concurrent.ScheduledExecutorService worker = background;
        background = null;
        try {
            rederive();
        } finally {
            background = worker;
        }
    }

    /**
     * Makes whatever area is on its way the answer to an old question: its work is canceled, and
     * should it arrive anyway, the generation count drops it.
     */
    private void dropPendingDerivation() {
        generation++;
        if (pendingDerivation != null) {
            pendingDerivation.cancel(false);
            pendingDerivation = null;
        }
    }

    /** Whether the write being made is this one's rather than a person's; see {@code ProjectModel.touch}. */
    boolean isDeriving() {
        return deriving;
    }

    /** Why the area came to nothing, or null. */
    @Nullable String problem() {
        return deriveProblem;
    }

    /** False while an area is on its way. */
    boolean isSettled() {
        return !areaPending.get();
    }

    /**
     * Writes as this writes the area: with the flag up, so that neither {@link #rederive}, nor what
     * a chosen source brings, nor the takeover listener reacts, and with the problem the writes come
     * with. For a snapshot put back.
     */
    void restoring(final @Nullable String problem, final @NotNull Runnable writes) {
        deriving = true;
        try {
            deriveProblem = problem;
            writes.run();
        } finally {
            deriving = false;
        }
    }

    /** Drops whatever area is on its way, and says none is: its project is being replaced. */
    void abandon() {
        dropPendingDerivation();
        areaPending.set(false);
    }

    /**
     * The subject's parts made one shape, worked out once per subject and off the FX thread; null
     * while the worker is still at it. See {@link Joined}.
     */
    @Nullable Union subjectAsOne(final @NotNull Geometry parts) {
        return subjectJoined.of(parts);
    }

    /**
     * A Custom shape's parts made one – the edge a fade across its growth starts from, worked out
     * as the area was grown – once per shape and off the FX thread; null while the worker is still
     * at it. Asked for only by a job that fades, since a Custom area of Europe is a half-second
     * join that nothing else needs.
     */
    @Nullable Union customAsOne(final @NotNull Geometry parts) {
        return customJoined.of(parts);
    }

    /**
     * Joins the subject's parts on this thread if the worker has not delivered them yet; see
     * {@link ProjectModel#settleUnions}.
     *
     * @return whether they were joined now, which puts the job out of date
     */
    boolean settleSubject() {
        return subjectJoined.settle();
    }

    /** The same for the Custom shape's parts. */
    boolean settleCustom() {
        return customJoined.settle();
    }

    /**
     * One shape's parts made one, kept by identity: the subject's for the job, and a Custom shape's
     * for the edge its fade starts from.
     * <p>
     * Every change to the project builds again – each character of the export name – and Europe
     * from the library is 575 parts, which take half a second to join. Once per shape keeps the
     * name field from hanging on every keystroke, and off the FX thread, on the worker that works
     * the area out, the first build after a new shape does not hang for as long either.
     */
    private final class Joined {

        /** Whose parts: the model's property, asked whether an answer is still the current one's. */
        private final ObjectProperty<Geometry> shape;
        private final UnaryOperator<Geometry> join;
        /** The last union made, which is the current shape's when {@code from} is the shape. */
        private @Nullable Union made;
        /** The shape a union was last asked of the worker for, so a second build does not ask again. */
        private @Nullable Geometry askedFor;

        Joined(final @NotNull ObjectProperty<Geometry> shape, final @NotNull UnaryOperator<Geometry> join) {
            this.shape = shape;
            this.join = join;
        }

        @Nullable Union of(final @NotNull Geometry parts) {
            if (made != null && made.from() == parts) {
                return made;
            }
            if (background == null) {
                made = make(parts, parts);        // no toolkit: everything happens when it is asked for
                return made;
            }
            later(parts);
            return null;
        }

        /**
         * Asks the worker for this shape's union, once. The answer lands on the FX thread as a new
         * revision – not a change to the project, so nothing is marked unsaved – and is dropped if
         * the shape has been replaced in the meantime.
         * <p>
         * A dropped answer forgets that it was asked for: a redo puts the same instance back, and
         * with the answer gone the shape has to be asked for again, or the job would say "working
         * out" until the shape changed. An answer whose shape is current again by the time it
         * lands – undone and redone while the worker was at it – is kept.
         */
        void later(final @NotNull Geometry parts) {
            if (parts == askedFor || (made != null && made.from() == parts)) {
                return;
            }
            askedFor = parts;
            // A copy, for the reason deriveLater gives
            final Geometry copy = parts.copy();
            java.util.Objects.requireNonNull(background).execute(() -> {
                final Union union = make(parts, copy);
                java.util.Objects.requireNonNull(fxThread).execute(() -> {
                    if (shape.get() == parts) {
                        made = union;
                        unionLanded.run();
                    } else if (askedFor == parts) {
                        askedFor = null;
                    }
                });
            });
        }

        /** Joins the current shape's parts on this thread unless they are joined; whether it did. */
        boolean settle() {
            final Geometry parts = shape.get();
            if (parts != null && (made == null || made.from() != parts)) {
                made = make(parts, parts);
                return true;
            }
            return false;
        }

        private @NotNull Union make(final @NotNull Geometry from, final @NotNull Geometry parts) {
            try {
                final Geometry one = join.apply(parts);
                // Here, before the threads that count and export are handed it: JTS works it out
                // the first time it is asked for, with no lock around it
                one.getEnvelopeInternal();
                return new Union(from, one, null);
            } catch (final RuntimeException | Error ex) {
                // An Error as well: on the worker it would otherwise leave the job saying "working
                // out" until the shape changed, with nothing said
                return new Union(from, null, EngineText.of(ex));
            }
        }
    }
}
