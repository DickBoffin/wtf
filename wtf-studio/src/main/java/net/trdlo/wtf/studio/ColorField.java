package net.trdlo.wtf.studio;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * A color, and a drop-down to choose another in: swatches, and a saturation square with a hue bar
 * for everything the swatches do not have.
 * <p>
 * <b>Why not {@code ColorPicker}.</b> Its drop-down is right, but anything beyond the swatches is a
 * separate "Custom Colors" window that belongs to the drop-down rather than to the application –
 * and on Windows, closing it hands the foreground to whatever program is next, so WTF dropped
 * behind it after every custom color. Bringing the window back afterwards worked, with a flash of
 * the other program first and a delay that was a guess. Here everything is in the one drop-down,
 * owned by the window it opens from, so there is nothing to hand focus away.
 * <p>
 * It remembers nothing: no custom colors, no recent ones. A boundary line needs a color, not a
 * library of them.
 */
public final class ColorField extends Button {

    /** Twelve hues across, the same idea as the stock picker's grid. */
    private static final double[] HUES = {0, 30, 45, 60, 90, 120, 160, 190, 210, 240, 275, 310};

    private static final int SWATCH = 14, SWATCH_GAP = 2, PICKER_GAP = 6, HUE_W = 16, SQUARE_H = 128;

    /**
     * The swatch grid's width, which everything below it is cut to so the three rows end on one
     * line. A swatch takes a pixel more than its size, because its one-pixel outline is centered on
     * the edge.
     */
    private static final int GRID_W = HUES.length * (SWATCH + 1) + (HUES.length - 1) * SWATCH_GAP;

    /** The saturation square: what the grid leaves beside the hue bar. */
    private static final int SQUARE_W = GRID_W - PICKER_GAP - HUE_W;

    private final ObjectProperty<Color> value = new SimpleObjectProperty<>(Color.BLACK);
    private final Rectangle chip = new Rectangle(14, 14);

    private final Popup popup = new Popup();
    private final Canvas square = new Canvas(SQUARE_W, SQUARE_H);
    private final Canvas hueBar = new Canvas(HUE_W, SQUARE_H);
    private final TextField hex = new TextField();
    private final Rectangle preview = new Rectangle(40, 22);

    /** The drop-down's own hue, kept apart so a gray does not throw it away. */
    private double hue;

    /**
     * When the drop-down last closed. A click on the button while it is open first closes it –
     * auto-hide sees the press outside – and then arrives at the button as a click, which would
     * open it again. A close this recent means that click was the one that closed it.
     */
    private long hiddenAt;

    public ColorField(final @NotNull Color initial) {
        setGraphic(chip);
        setMaxWidth(Double.MAX_VALUE);
        setAlignment(Pos.CENTER_LEFT);
        chip.setStroke(Color.web("#000000", 0.35));
        value.addListener((o, a, c) -> showValue(c));
        value.set(initial);
        showValue(initial);

        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setOnHidden(e -> hiddenAt = System.currentTimeMillis());
        popup.getContent().add(content());
        setOnAction(e -> toggle());
    }

    public @NotNull ObjectProperty<Color> valueProperty() {
        return value;
    }

    public @NotNull Color getValue() {
        return value.get();
    }

    public void setValue(final @NotNull Color c) {
        value.set(c);
    }

    // -------------------------------------------------------------------------------------------

    private void toggle() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        if (System.currentTimeMillis() - hiddenAt < 250) {
            return;
        }
        hue = value.get().getHue();
        paintSquare();
        paintHue();
        hex.setText(hexOf(value.get()));
        final Point2D below = localToScreen(0, getHeight() + 2);
        popup.show(this, below.getX(), below.getY());
    }

    private void showValue(final @NotNull Color c) {
        chip.setFill(c);
        preview.setFill(c);
        setText(hexOf(c));
        // Always, focused or not. The field applies what it holds when it loses focus, and if it
        // kept the previous color while it had focus and a swatch or the square chose a new one,
        // closing the drop-down would put the previous color straight back.
        hex.setText(hexOf(c));
    }

    private @NotNull Region content() {
        // The swatches: a row of grays, then each hue from light to dark
        final GridPane grid = new GridPane();
        grid.setHgap(SWATCH_GAP);
        grid.setVgap(SWATCH_GAP);
        for (int i = 0; i < HUES.length; i++) {
            grid.add(swatch(Color.gray(1 - i / (double) (HUES.length - 1))), i, 0);
        }
        final double[][] shades = {{0.25, 1.0}, {0.5, 1.0}, {0.85, 0.95}, {1.0, 0.75}, {1.0, 0.5}, {1.0, 0.3}};
        for (int r = 0; r < shades.length; r++) {
            for (int i = 0; i < HUES.length; i++) {
                grid.add(swatch(Color.hsb(HUES[i], shades[r][0], shades[r][1])), i, r + 1);
            }
        }

        // The square and the hue bar, for the rest
        square.addEventHandler(MouseEvent.MOUSE_PRESSED, this::pickInSquare);
        square.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::pickInSquare);
        hueBar.addEventHandler(MouseEvent.MOUSE_PRESSED, this::pickHue);
        hueBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::pickHue);
        final HBox picker = new HBox(PICKER_GAP, square, hueBar);

        // As narrow as it likes: the row is stretched to the grid's width, and the field takes up
        // whatever the preview leaves. Its own preferred width would make the row the widest thing
        // in the drop-down, and the swatches would stop short of the edge.
        hex.setPrefColumnCount(1);
        hex.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) {
                applyHex();
            }
        });
        // Applied on the press, closed on the release, both consumed. Closing on the press would
        // hand the rest of the keystroke to the button underneath, which would take it as a click
        // and open the drop-down again.
        hex.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                applyHex();
                e.consume();
            }
        });
        hex.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                e.consume();
                popup.hide();
            }
        });
        preview.setStroke(Color.web("#000000", 0.35));
        final HBox row = new HBox(8, preview, hex);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(hex, Priority.ALWAYS);

        final VBox box = new VBox(10, grid, picker, row);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: -color-bg-overlay; -fx-background-radius: 6;"
                + " -fx-border-color: -color-border-default; -fx-border-radius: 6;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 3);");
        return box;
    }

    private @NotNull Rectangle swatch(final @NotNull Color c) {
        final Rectangle r = new Rectangle(SWATCH, SWATCH, c);
        r.setStroke(Color.web("#000000", 0.25));
        r.setOnMouseClicked(e -> {
            value.set(c);
            popup.hide();
        });
        r.setOnMouseEntered(e -> r.setStroke(Color.WHITE));
        r.setOnMouseExited(e -> r.setStroke(Color.web("#000000", 0.25)));
        return r;
    }

    // -------------------------------------------------------------------------------------------

    private void pickInSquare(final @NotNull MouseEvent e) {
        final double s = clamp(e.getX() / SQUARE_W);
        final double b = 1 - clamp(e.getY() / SQUARE_H);
        value.set(Color.hsb(hue, s, b));
        paintSquare();
    }

    private void pickHue(final @NotNull MouseEvent e) {
        hue = 360 * clamp(e.getY() / SQUARE_H);
        final Color c = value.get();
        value.set(Color.hsb(hue, c.getSaturation(), c.getBrightness()));
        paintSquare();
        paintHue();
    }

    private void applyHex() {
        try {
            final Color c = Color.web("#" + hex.getText().trim().replaceFirst("^#", ""));
            value.set(c);
            hue = c.getHue();
            paintSquare();
            paintHue();
        } catch (final IllegalArgumentException notAColor) {
            hex.setText(hexOf(value.get()));
        }
    }

    /** Saturation across, brightness up, at the current hue – and a ring where the color is. */
    private void paintSquare() {
        final WritableImage img = new WritableImage(SQUARE_W, SQUARE_H);
        final PixelWriter w = img.getPixelWriter();
        for (int y = 0; y < SQUARE_H; y++) {
            for (int x = 0; x < SQUARE_W; x++) {
                w.setColor(x, y, Color.hsb(hue, x / (double) (SQUARE_W - 1), 1 - y / (double) (SQUARE_H - 1)));
            }
        }
        final GraphicsContext g = square.getGraphicsContext2D();
        g.drawImage(img, 0, 0);
        final Color c = value.get();
        final double x = c.getSaturation() * SQUARE_W, y = (1 - c.getBrightness()) * SQUARE_H;
        g.setLineWidth(1.5);
        g.setStroke(c.getBrightness() > 0.6 ? Color.BLACK : Color.WHITE);
        g.strokeOval(x - 5, y - 5, 10, 10);
    }

    private void paintHue() {
        final GraphicsContext g = hueBar.getGraphicsContext2D();
        for (int y = 0; y < SQUARE_H; y++) {
            g.setFill(Color.hsb(360.0 * y / SQUARE_H, 1, 1));
            g.fillRect(0, y, HUE_W, 1);
        }
        final double y = hue / 360 * SQUARE_H;
        g.setStroke(Color.WHITE);
        g.setLineWidth(2);
        g.strokeRect(1, y - 2, HUE_W - 2, 4);
    }

    private static double clamp(final double v) {
        return Math.max(0, Math.min(1, v));
    }

    static @NotNull String hexOf(final @NotNull Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }
}
