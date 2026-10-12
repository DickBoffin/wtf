package net.trdlo.wtf.studio.project;

import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Undo and redo over the whole project, and whether it is saved.
 * <p>
 * <b>Snapshots, not commands.</b> Each step is the project as it stood after it – every setting,
 * both shapes, the area as it was worked out – rather than an instruction for getting there and
 * back. There is no command per control to write and keep in step with the control, and nothing a
 * command could forget: what the model holds is what comes back. A snapshot shares its values
 * with the model and with the steps beside it rather than copying them, because a shape is never
 * changed once it is made, nor a point of one, so a step costs what changed. A dragged vertex is
 * the dearest step, and it shares what the drag did not touch: the subject's other parts and the
 * points that did not move, and under "the outline itself" the area's points, which are the
 * subject's. On OpenStreetMap's Czech border, 125 607 points in one ring, a step keeps half a
 * megabyte, and so does one under the outline itself, where a step holding the whole shape would
 * keep five and a half megabytes, and eleven. What is left is the arrays of the part dragged and of
 * the area, four bytes a point.
 * <p>
 * <b>A step is what happens between two presses.</b> The window closes the step being made at every
 * press of a mouse button or a key, before anything acts on it, and when the focus moves
 * ({@link #closeStep}). So a handle dragged and let go, a slider dragged, a spinner held, a country
 * picked from the library is one step, with everything it set off: the area worked out again, a
 * derived area taken over by hand, the outside following the inside, the medium step filling in
 * the side of the print it works out. A key is a step of its own, so typing is a run of them, and
 * a run of edits to one setting merges into one step until the focus moves or a mouse button is
 * let go ({@link #endRun}) – "50000" typed into Grow by is one step, not five.
 * <p>
 * <b>Unsaved is a position.</b> The project is unsaved when the stack is anywhere but where it was
 * saved, so undoing back to that point makes it clean again, and "Unsaved changes" is never asked
 * about a project that has none. A change that changes nothing – a handle clicked and not moved, a
 * provider reloaded as it was, typing back to where it started – is not a step and marks nothing.
 * <p>
 * <b>A worked-out area is not a person's change.</b> Under "the outline itself" the area arrives a
 * while after the change that asked for it; it joins the step that asked, and a step whose area
 * never arrived has it worked out when that step is put back.
 */
public final class History {

    /**
     * How far back undo reaches.
     * <p>
     * A step shares everything it did not change, so a hundred of them cost next to nothing – a
     * hundred vertices of a surveyed border dragged are fifty megabytes, a hundred under the outline
     * itself; see the class comment.
     */
    static final int LIMIT = 100;

    /**
     * One state of the project, and how it was reached.
     *
     * @param touched what a person wrote in the step, as an identity set: it decides whether the next
     *                step is more of the same. The model's own writes are not in it
     * @param run     the run the step began in; see {@link #endRun}
     */
    private record Step(ProjectModel.@NotNull Snapshot state, @NotNull Set<Observable> touched, long run) {
        @NotNull Step with(final ProjectModel.@NotNull Snapshot now) {
            return new Step(now, touched, run);
        }
    }

    private final ProjectModel model;
    /** Every state since the project was opened or started, oldest first; the first is where it began. */
    private final List<Step> steps = new ArrayList<>();
    /** Which of them the model is in, apart from what is {@link #pending}. */
    private int position;
    /** Which of them is on disk, or -1 once that one has been cut off or dropped. */
    private int saved;

    /** Whether a person has changed something since {@code steps[position]} was taken. */
    private boolean pending;
    /** What they changed, and the run the change began in. */
    private final Set<Observable> touched = identitySet();
    private long pendingRun;
    /** Counts runs; see {@link #endRun}. */
    private long run;
    /**
     * Whether the step being made may merge into the current one: true only right after that step
     * was made, and cleared by undo, redo, a save and a start – never by a step closed with nothing
     * in it, which changes nothing about which step is the latest.
     */
    private boolean mayMerge;

    private final ReadOnlyBooleanWrapper canUndo = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper canRedo = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper();

    History(final @NotNull ProjectModel model) {
        this.model = model;
        startOver();
    }

    public @NotNull ReadOnlyBooleanProperty canUndoProperty() {
        return canUndo.getReadOnlyProperty();
    }

    public @NotNull ReadOnlyBooleanProperty canRedoProperty() {
        return canRedo.getReadOnlyProperty();
    }

    /** Whether the project differs from what is on disk: the stack is anywhere but where it was saved. */
    public @NotNull ReadOnlyBooleanProperty dirtyProperty() {
        return dirty.getReadOnlyProperty();
    }

    /**
     * A write to something a person decides.
     *
     * @param derived true when the model made it, working the area out: part of whatever step asked
     *                for it, taken in when that step closes, and never itself a change
     */
    void touched(final @NotNull Observable what, final boolean derived) {
        if (derived) {
            return;
        }
        if (!pending) {
            pending = true;
            pendingRun = run;
        }
        touched.add(what);
        update();
    }

    /**
     * Makes whatever has changed since the last step into one.
     * <p>
     * Called by the window at every press of a mouse button or a key and when the focus moves, and
     * here before undo, redo and a save. With nothing a person decided having changed – an area that
     * has arrived since, an answer filled in, a write of what was already there – the step the model
     * is in is kept with the latest of it, and no step is made. A difference nobody made is not
     * recorded either: a listener rounding a value it has just been given back by an undo waits for
     * the next step somebody does make.
     */
    public void closeStep() {
        final ProjectModel.Snapshot now = model.snapshot();
        final Step here = steps.get(position);
        if (model.sameProject(now, here.state())) {
            steps.set(position, here.with(now));
        } else if (pending) {
            if (mergesInto(here)) {
                merge(now);
            } else {
                push(new Step(now, copyOf(touched), pendingRun));
            }
        }
        pending = false;
        touched.clear();
        update();
    }

    /**
     * Ends a run: what is typed next does not merge with what was typed before.
     * <p>
     * Called when the focus moves – after the step is closed, so a field's own tidying on the way
     * out still joins the typing it tidies – and after a mouse button is let go, so two clicks on one
     * spinner are two steps.
     */
    public void endRun() {
        run++;
    }

    /** Back one step. Whatever was being changed becomes a step first, so it is what goes. */
    public void undo() {
        // An area still on its way belongs to the step being left: settled, it is in that step, and
        // it cannot land on the one put back
        model.settleArea();
        closeStep();
        if (position > 0) {
            moveTo(position - 1);
        }
    }

    /** Forward one step, while there is one: a change made after an undo takes the way forward away. */
    public void redo() {
        model.settleArea();
        closeStep();
        if (position < steps.size() - 1) {
            moveTo(position + 1);
        }
    }

    /** The project has just been written: the state it is in is the one on disk. */
    void markSaved() {
        closeStep();
        saved = position;
        mayMerge = false;
        update();
    }

    /**
     * The project is not what is on disk, and no step of it is: a recovery copy of unsaved work has
     * just been opened. Only a save makes it clean again – there is nothing to undo back to.
     */
    void markUnsaved() {
        closeStep();
        saved = -1;
        mayMerge = false;
        update();
    }

    /** The project has been replaced: one step, as it is now, and nothing unsaved about it. */
    void startOver() {
        steps.clear();
        steps.add(new Step(model.snapshot(), identitySet(), run));
        position = 0;
        saved = 0;
        pending = false;
        mayMerge = false;
        touched.clear();
        update();
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Whether the step being closed is more of the one before it: a run of keys in one field.
     * <p>
     * Only onto the latest step, just made – not one undo went back to, not the one on disk, not
     * the start – and only when both are edits that come in runs and began in the same run. Never a
     * shape: two vertex drags are two things somebody might want back.
     */
    private boolean mergesInto(final @NotNull Step here) {
        return mayMerge && position > 0 && position == steps.size() - 1 && position != saved
                && here.run() == pendingRun && allComeInRuns(touched) && allComeInRuns(here.touched());
    }

    private void merge(final ProjectModel.@NotNull Snapshot now) {
        if (model.sameProject(now, steps.get(position - 1).state())) {
            // Back where the run started – typed and typed away again: no step, nothing unsaved
            steps.remove(position);
            position--;
            mayMerge = false;
            return;
        }
        final Step here = steps.get(position);
        final Set<Observable> both = copyOf(here.touched());
        both.addAll(touched);
        steps.set(position, new Step(now, both, here.run()));
    }

    private void push(final @NotNull Step step) {
        steps.subList(position + 1, steps.size()).clear();      // the way forward is gone
        if (saved > position) {
            saved = -1;                                           // and the saved state with it
        }
        steps.add(step);
        position++;
        mayMerge = true;
        if (steps.size() > LIMIT + 1) {
            steps.removeFirst();
            position--;
            saved = saved > 0 ? saved - 1 : -1;
        }
    }

    private void moveTo(final int to) {
        position = to;
        mayMerge = false;
        final Step step = steps.get(to);
        model.restore(step.state());
        // An area the restore had to work out afresh is kept, so coming back here is instant
        final ProjectModel.Snapshot now = model.snapshot();
        if (model.sameProject(now, step.state())) {
            steps.set(to, step.with(now));
        }
        update();
    }

    private void update() {
        // A person's write that changes nothing is no change: asked now, rather than when the step
        // closes, so the title never flashes an asterisk for a handle clicked and not moved
        final boolean changed = pending && !model.sameProject(model.snapshot(), steps.get(position).state());
        canUndo.set(position > 0 || changed);
        canRedo.set(!changed && position < steps.size() - 1);
        dirty.set(position != saved || changed);
    }

    private boolean allComeInRuns(final @NotNull Set<Observable> properties) {
        if (properties.isEmpty()) {
            return false;
        }
        for (final Observable p : properties) {
            if (!model.comesInRuns(p)) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull Set<Observable> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static @NotNull Set<Observable> copyOf(final @NotNull Set<Observable> properties) {
        final Set<Observable> copy = identitySet();
        copy.addAll(properties);
        return copy;
    }
}
