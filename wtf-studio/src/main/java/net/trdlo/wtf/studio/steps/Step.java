package net.trdlo.wtf.studio.steps;

import org.jetbrains.annotations.NotNull;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

/**
 * The Start page and the five steps, in the order the brief asks for them.
 * <p>
 * They are a <b>rail, not a wizard</b>: every step is reachable at any time, because the answer to
 * "how big is the print" changes what zoom is readable, and being sent back through three modal
 * pages to find that out is how a person stops using an application. The rail shows where you are
 * and what each step currently says; it never blocks the way forward.
 */
public enum Step {

    /** What WTF makes, what each step decides, and the examples to start from: step 0. */
    START("step.start", FontAwesomeSolid.HOME),

    /** Draw, import or pick the subject: the thing the print is about. */
    SUBJECT("step.subject", FontAwesomeSolid.DRAW_POLYGON),

    /** How much world goes around it: worked out from the subject, or drawn in its own right. */
    AREA("step.area", FontAwesomeSolid.OBJECT_GROUP),

    /** Roll or sheets, how they overlap, and how big the print is on the wall. */
    MEDIUM("step.medium", FontAwesomeSolid.SCROLL),

    /** Zoom, effects, and the number that decides it all: how tall a place name ends up. */
    DETAIL("step.detail", FontAwesomeSolid.SEARCH_PLUS),

    /** Check the cache, fetch what is missing, write the files. */
    EXPORT("step.export", FontAwesomeSolid.FILE_EXPORT);

    private final String key;
    private final FontAwesomeSolid icon;

    Step(final @NotNull String key, final @NotNull FontAwesomeSolid icon) {
        this.key = key;
        this.icon = icon;
    }

    public @NotNull String titleKey() {
        return key;
    }

    public @NotNull FontAwesomeSolid icon() {
        return icon;
    }

    /** The number on the rail, and the one Alt goes there with: 0 for the Start page, 1 to 5 for the steps. */
    public int number() {
        return ordinal();
    }
}
