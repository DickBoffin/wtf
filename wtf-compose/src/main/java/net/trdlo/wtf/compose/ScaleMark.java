package net.trdlo.wtf.compose;

/**
 * How the map scale is printed, in the print's bottom-left corner. See {@link SeamMarks}.
 */
public enum ScaleMark {
    /** "1:50 000". */
    RATIO,
    /** A bar a round distance long, which stays right when the print is enlarged or reduced. */
    BAR,
    /** The bar, and the ratio beside it. */
    BOTH;

    public boolean ratio() {
        return this != BAR;
    }

    public boolean bar() {
        return this != RATIO;
    }
}
