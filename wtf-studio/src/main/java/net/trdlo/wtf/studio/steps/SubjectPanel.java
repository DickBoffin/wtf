package net.trdlo.wtf.studio.steps;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.studio.Forms;
import net.trdlo.wtf.studio.map.ShapeTool;
import net.trdlo.wtf.studio.project.ProjectModel;

/**
 * Step 1: the subject – the thing the print is about.
 * <p>
 * <b>One shape, and it is the one somebody can state exactly.</b> A print is of Czechia, of the
 * Alps, of a lake; how much world goes around it is a consequence and belongs to the next step.
 * Asking for the area first had it backwards and left two shapes to keep in step by hand.
 * <p>
 * The subject is also the cut line when the print has two regions: it is what divides inside from
 * outside in step 4. It is used exactly as given – nothing here simplifies or grows it, because
 * both of those move a border, and a border that has been moved is the wrong border.
 */
public final class SubjectPanel extends StepPanel {

    private final StepContext ctx;
    private final Label hint = new Label();
    /** Kept so the step can hand it the map when it opens, and take it back when it closes. */
    private @org.jetbrains.annotations.Nullable ShapeSlot slot;

    public SubjectPanel(final @NotNull ProjectModel model, final @NotNull StepContext ctx) {
        super(model);
        this.ctx = ctx;
    }

    @Override
    public @NotNull Step step() {
        return Step.SUBJECT;
    }

    @Override
    protected @NotNull Node build() {
        hint.getStyleClass().add("step-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(290);

        slot = new ShapeSlot(model, ctx, ShapeTool.Slot.SUBJECT, "subject.slot", null, "subject.help",
                model.subjectProperty(), model.subjectOriginProperty(), hint);

        final VBox box = column();
        box.getChildren().addAll(
                Forms.heading("subject.title"),
                hint("subject.intro"),
                slot.build(),
                hint);
        return box;
    }

    /**
     * The subject is editable here, and only here.
     * <p>
     * The handles are the step rather than a mode inside it, so there is nothing to switch on and
     * nothing left switched on afterwards.
     */
    @Override
    public void shown() {
        if (slot != null) {
            slot.activate();
        }
    }

    @Override
    public void hidden() {
        if (slot != null) {
            slot.deactivate();
        }
    }

    @Override
    public @NotNull String summary() {
        return ShapeSlot.summary(model.subjectProperty().get(), model.subjectOriginProperty().get());
    }
}
