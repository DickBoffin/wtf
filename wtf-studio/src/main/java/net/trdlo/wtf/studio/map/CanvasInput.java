package net.trdlo.wtf.studio.map;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.TileKey;

/**
 * The mouse and the keys on the map: to the installed tool first, and to the camera when the tool
 * does not want them – pan, zoom, the arrow keys – and where the pointer is, for the status bar.
 * <p>
 * Installed through the canvas's own {@code setOnMousePressed} and the rest, which hold one
 * handler each: the 1:1 preview puts a wheel handler of its own in place of this one's, and that
 * has to replace it, not run beside it.
 */
final class CanvasInput {

    private final TileCanvas canvas;
    private final Camera camera;
    private final TileLayer layer;

    private @Nullable ShapeTool tool;

    /** The 1:1 preview must stay 1:1, so it turns zooming off and keeps panning. */
    private boolean zoomLocked;

    private final ReadOnlyDoubleWrapper cursorLon = new ReadOnlyDoubleWrapper(Double.NaN);
    private final ReadOnlyDoubleWrapper cursorLat = new ReadOnlyDoubleWrapper(Double.NaN);
    private final ReadOnlyObjectWrapper<TileKey> cursorTile = new ReadOnlyObjectWrapper<>();

    private double dragAnchorX, dragAnchorY;
    /**
     * Whether the installed tool took the press this drag started from.
     * <p>
     * <b>The press decides the whole gesture, not each event.</b> A tool is installed for as long
     * as its step is open, and usually it wants nothing, so "there is a tool" says nothing about
     * whether "the tool wants the drag". Asking the tool
     * again on every drag event would also let a grab it refused turn into a pan halfway through
     * the same movement, which is how a shape ends up somewhere nobody dragged it.
     */
    private boolean toolOwnsDrag;

    CanvasInput(final @NotNull TileCanvas canvas, final @NotNull Camera camera, final @NotNull TileLayer layer) {
        this.canvas = canvas;
        this.camera = camera;
        this.layer = layer;
    }

    /** The handlers, on the canvas: installed by the canvas. */
    void install() {
        installMouse();
        installKeys();
    }

    /** See {@link TileCanvas#setTool}. */
    void setTool(final @Nullable ShapeTool tool) {
        if (this.tool != null) {
            this.tool.cancel();
        }
        this.tool = tool;
        canvas.setCursor(tool == null ? javafx.scene.Cursor.DEFAULT : tool.cursor());
        canvas.requestFocus();
        canvas.requestPaint();
    }

    @Nullable ShapeTool tool() {
        return tool;
    }

    void setZoomLocked(final boolean locked) {
        this.zoomLocked = locked;
    }

    ReadOnlyDoubleProperty cursorLonProperty() {
        return cursorLon.getReadOnlyProperty();
    }

    ReadOnlyDoubleProperty cursorLatProperty() {
        return cursorLat.getReadOnlyProperty();
    }

    ReadOnlyObjectProperty<TileKey> cursorTileProperty() {
        return cursorTile.getReadOnlyProperty();
    }

    private void installMouse() {
        canvas.setOnMousePressed(e -> {
            canvas.requestFocus();
            dragAnchorX = e.getX();
            dragAnchorY = e.getY();
            // Every button, not only the primary one: the editor takes a right-click as "remove
            // this vertex", and a tool that cannot hear the button cannot answer it. A tool that
            // does not want the press still returns false, and the canvas pans as before.
            toolOwnsDrag = tool != null && tool.pressed(e, camera);
            if (toolOwnsDrag) {
                canvas.requestOverlayPaint();
                e.consume();
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (toolOwnsDrag && e.getButton() == MouseButton.PRIMARY) {
                if (tool != null && tool.dragged(e, camera)) {
                    canvas.requestOverlayPaint();
                    updateCursor(e.getX(), e.getY());
                    return;
                }
            } else if (e.getButton() == MouseButton.PRIMARY || e.getButton() == MouseButton.MIDDLE) {
                camera.panByPixels(e.getX() - dragAnchorX, e.getY() - dragAnchorY);
                dragAnchorX = e.getX();
                dragAnchorY = e.getY();
                canvas.requestPaint();
            }
            updateCursor(e.getX(), e.getY());
        });
        canvas.setOnMouseReleased(e -> {
            if (tool != null && e.getButton() == MouseButton.PRIMARY && tool.released(e, camera)) {
                canvas.requestOverlayPaint();
                e.consume();
            }
            toolOwnsDrag = false;
        });
        canvas.setOnMouseMoved(e -> {
            updateCursor(e.getX(), e.getY());
            if (tool != null && tool.moved(e, camera)) {
                canvas.requestOverlayPaint();
            }
        });
        canvas.setOnMouseExited(e -> {
            if (tool != null && tool.exited()) {
                canvas.requestOverlayPaint();
            }
            cursorLon.set(Double.NaN);
            cursorLat.set(Double.NaN);
            cursorTile.set(null);
        });
        canvas.setOnScroll((ScrollEvent e) -> {
            if (zoomLocked) {
                return;
            }
            final double steps = e.getDeltaY() / 40.0;
            if (e.isControlDown()) {
                camera.zoomAt(canvas.getWidth() / 2, canvas.getHeight() / 2, steps * 0.25);
            } else {
                camera.zoomAt(e.getX(), e.getY(), steps * 0.25);
            }
            canvas.requestPaint();
            // The canvas handles the wheel completely. Letting it bubble hands the same gesture
            // to whatever scrollable ancestor the window grows next, which is a scroll nobody
            // asked for in a panel nobody was pointing at.
            e.consume();
        });
        canvas.setOnMouseClicked(e -> {
            if (tool != null && e.getButton() == MouseButton.PRIMARY && tool.clicked(e, camera)) {
                canvas.requestOverlayPaint();
                e.consume();
                return;
            }
            // JavaFX sends the click after a drag too, and a pan that ends off a handle is not a
            // double-click on the map
            if (!zoomLocked && e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && e.isStillSincePress()) {
                camera.zoomAt(e.getX(), e.getY(), 1);
                canvas.requestPaint();
            }
        });
    }

    private void installKeys() {
        canvas.setOnKeyPressed(e -> {
            // A half-drawn polygon owns Enter, Escape and Backspace; the map keeps the rest.
            if (tool != null && tool.keyPressed(e)) {
                e.consume();
                canvas.requestOverlayPaint();
                return;
            }
            final double step = 0.1;
            switch (e.getCode()) {
                case PLUS, ADD, EQUALS -> {
                    if (zoomLocked) {
                        return;
                    }
                    camera.zoomAt(canvas.getWidth() / 2, canvas.getHeight() / 2, 1);
                }
                case MINUS, SUBTRACT -> {
                    if (zoomLocked) {
                        return;
                    }
                    camera.zoomAt(canvas.getWidth() / 2, canvas.getHeight() / 2, -1);
                }
                case LEFT -> camera.panByPixels(canvas.getWidth() * step, 0);
                case RIGHT -> camera.panByPixels(-canvas.getWidth() * step, 0);
                case UP -> camera.panByPixels(0, canvas.getHeight() * step);
                case DOWN -> camera.panByPixels(0, -canvas.getHeight() * step);
                default -> {
                    return;
                }
            }
            e.consume();
            canvas.requestPaint();
        });
    }

    private void updateCursor(final double x, final double y) {
        cursorLon.set(camera.lonAt(x));
        cursorLat.set(camera.latAt(y));
        final TileKey origin = layer.origin();
        if (origin != null) {
            final int zoom = layer.drawZoom();    // the tile under the cursor is one that is drawn
            final int n = Mercator.tileCount(zoom);
            final int tx = (int) Math.floor(camera.toUnitX(x) * n);
            final int ty = (int) Math.floor(camera.toUnitY(y) * n);
            cursorTile.set(tx >= 0 && tx < n && ty >= 0 && ty < n
                    ? origin.at(zoom, tx, ty) : null);
        }
    }
}
