package net.trdlo.wtf.studio.map;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.PrintRect;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The paper in the 1:1 preview: white past what is printed, the map left alone inside it, and under
 * a fade the map going into the white across the growth.
 */
class PrintedPaperTest {

    private static final int SIZE = 400;
    private static final Color MAP = Color.BLACK;
    /** A triangle, which is printed to its own edge, standing on the south edge of its bounds. */
    private static final Geometry TRIANGLE = Shapes.polygon(new Coordinate[]{new Coordinate(-10, 46),
            new Coordinate(4, 46), new Coordinate(-3, 50), new Coordinate(-10, 46)});
    private static final LonLatBounds FIT = new LonLatBounds(-4, 46.5, -2, 47.5);

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    private static @NotNull ComposeJob job() {
        final ProjectModel m = new ProjectModel();
        final var p = ProviderCatalog.load(null).all().iterator().next();
        m.startOver(() -> {
            m.providerProperty().set(p);
            m.themeProperty().set(p.themes().getFirst());
            m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
            m.customProperty().set(TRIANGLE);
        });
        return Objects.requireNonNull(m.build().job(), () -> m.build().problem());
    }

    /** The same job, fading from this shape across to its edge. */
    private static @NotNull ComposeJob fading(final @NotNull ComposeJob j, final @Nullable Geometry from) {
        return new ComposeJob(j.fillShape(), j.subject(), j.provider(), j.themeId(), j.lang(), j.regions(),
                j.print(), j.medium(), j.overlapMm(), j.exactArea(), from, j.backgroundColor(),
                j.missingTiles(), j.formats(), j.grouping(), j.outputDir(), j.baseName(), j.marks(), j.words());
    }

    /** The print drawn over a black map, the whole of it in view, and the pixels it left. */
    private static @NotNull PixelReader drawn(final @NotNull PrintedPaper paper, final @NotNull PrintRect print,
                                              final @NotNull Camera camera) throws Exception {
        return onFx(() -> {
            camera.setViewSize(SIZE, SIZE);
            camera.pixelsPerUnitProperty().set(SIZE * 0.9 / print.unitWidth());
            camera.centerOn((print.x0() + print.x1()) / 2, (print.y0() + print.y1()) / 2);
            final Canvas canvas = new Canvas(SIZE, SIZE);
            canvas.getGraphicsContext2D().setFill(MAP);
            canvas.getGraphicsContext2D().fillRect(0, 0, SIZE, SIZE);
            paper.draw(canvas.getGraphicsContext2D(), camera, SIZE, SIZE);
            return canvas.snapshot(new SnapshotParameters(), null).getPixelReader();
        });
    }

    private static double lightness(final PixelReader px, final Camera camera, final double lon, final double lat) {
        final int x = (int) Math.round(camera.toScreenX(Mercator.lonToUnitX(lon)));
        final int y = (int) Math.round(camera.toScreenY(Mercator.latToUnitY(lat)));
        return px.getColor(x, y).getBrightness();
    }

    @Test
    @DisplayName("past the area's edge is white paper, and inside it the map is as it was")
    void paperPastTheEdge() throws Exception {
        final ComposeJob job = job();
        final PrintedPaper paper = new PrintedPaper(() -> { }, Runnable::run, Runnable::run);
        final Camera camera = new Camera();
        onFx(() -> {
            paper.update(job);
            return null;
        });
        final PixelReader px = drawn(paper, job.print(), camera);
        assertEquals(0, lightness(px, camera, -3, 47), 0.01, "inside the triangle");
        assertEquals(1, lightness(px, camera, -9, 49.5), 0.01, "in the bounds, past the triangle");
        assertEquals(1, px.getColor(2, 2).getBrightness(), 0.01, "past the print altogether");
    }

    @Test
    @DisplayName("under a fade the map is whole inside the fit and goes into the paper towards the edge")
    void theFade() throws Exception {
        final ComposeJob job = fading(job(), Shapes.rectangle(FIT));
        final AtomicInteger repaints = new AtomicInteger();
        final PrintedPaper paper = new PrintedPaper(repaints::incrementAndGet, Runnable::run, Runnable::run);
        final Camera camera = new Camera();
        onFx(() -> {
            paper.update(job);
            return null;
        });
        assertEquals(1, repaints.get(), "a repaint once the fade has been worked out");
        final PixelReader px = drawn(paper, job.print(), camera);
        assertEquals(0, lightness(px, camera, -3, 47), 0.02, "inside the fit");
        final double between = lightness(px, camera, -3, 48.5);
        assertTrue(between > 0.2 && between < 0.8, () -> "partway to the apex: " + between);
        assertTrue(lightness(px, camera, -3, 49.9) > 0.85, "near the apex, nearly paper");
    }

    @Test
    @DisplayName("a job that only renamed the export is not worked out again")
    void notAgainForTheSamePrint() throws Exception {
        final ComposeJob job = fading(job(), Shapes.rectangle(FIT));
        final AtomicInteger worked = new AtomicInteger();
        final PrintedPaper paper = new PrintedPaper(() -> { }, work -> {
            worked.incrementAndGet();
            work.run();
        }, Runnable::run);
        final ComposeJob renamed = new ComposeJob(job.fillShape(), job.subject(), job.provider(), job.themeId(),
                job.lang(), job.regions(), job.print(), job.medium(), job.overlapMm(),
                job.exactArea(), job.fadeFrom(), job.backgroundColor(), job.missingTiles(), job.formats(),
                job.grouping(), job.outputDir(), "renamed", job.marks(), job.words());
        onFx(() -> {
            paper.update(job);
            paper.update(renamed);
            return null;
        });
        assertEquals(1, worked.get());
    }

    @Test
    @DisplayName("white over the map as opaque as the map is faded")
    void paperOverTheFade() {
        assertArrayEquals(new int[]{0x00FFFFFF, 0xFFFFFFFF, 0x7FFFFFFF},
                PrintedPaper.paperOver(new byte[]{(byte) 255, 0, (byte) 128}));
    }
}
