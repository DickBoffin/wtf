package net.trdlo.wtf.studio.project;

import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * New is what a new project has – not the old one with its shapes taken out.
 */
class NewProjectTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);
    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.5, 18.9, 51.1);

    @Test
    @DisplayName("after New the export folder and base name are the defaults, and the machine's settings stay")
    void newProjectIsAFreshOne() {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        final Provider.Theme map = p.themes().getLast();
        final String lang = "de".equals(ProjectModel.defaultMapLang()) ? "fr" : "de";   // not what a fresh model has
        m.startOver(() -> {
            m.providerProperty().set(p);
            m.themeProperty().set(map);
            m.mapLangProperty().set(lang);
            m.paperProperty().set(Medium.paper("A3"));
            m.orientationProperty().set(Medium.Orientation.LANDSCAPE);
            m.edgeMmProperty().set(9);
        });
        m.setPrinting(new ProjectModel.Printing(false));
        m.titleProperty().set("Bohemia");
        m.outputDirProperty().set(Path.of("somewhere", "else"));
        m.baseNameProperty().set("bohemia");
        m.formats().add(ComposeJob.OutputFormat.PNG);
        m.zoomProperty().set(15);
        m.overlapMmProperty().set(20);
        m.history().closeStep();
        assertTrue(m.dirtyProperty().get());

        m.startNew();

        final ProjectModel fresh = new ProjectModel();
        assertEquals("", m.titleProperty().get());
        assertNull(m.outputDirProperty().get(), "the folder is the default again");
        assertEquals(I18n.get("project.baseName"), m.baseNameProperty().get());
        assertEquals(fresh.formats(), m.formats());
        assertEquals(fresh.zoomProperty().get(), m.zoomProperty().get());
        assertEquals(fresh.overlapMmProperty().get(), m.overlapMmProperty().get());
        assertSame(p, m.providerProperty().get(), "the provider is the machine's");
        assertSame(map, m.themeProperty().get(), "so is the map");
        assertEquals(lang, m.mapLangProperty().get(), "and its language");
        assertEquals(Medium.paper("A3"), m.paperProperty().get(), "and the paper");
        assertEquals(Medium.Orientation.LANDSCAPE, m.orientationProperty().get());
        assertEquals(9, m.edgeMmProperty().get(), "and the printer's edge");
        assertFalse(m.printing().marks(), "and the seam marks");
        assertFalse(m.dirtyProperty().get(), "nothing unsaved");
        assertFalse(m.history().canUndoProperty().get(), "nothing to undo");
    }

    @Test
    @DisplayName("a subject picked after New gets its area")
    void subjectAfterNewGetsItsArea() {
        final ProjectModel m = new ProjectModel();
        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        assertNotNull(m.areaProperty().get());

        m.startNew();
        assertNull(m.subjectProperty().get());
        assertNull(m.areaProperty().get());
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get(),
                "clearing the area is not somebody taking it over");

        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get());
        assertNotNull(m.areaProperty().get(), "the subject decides the area again");
    }
}
