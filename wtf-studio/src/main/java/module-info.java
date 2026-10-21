/**
 * The JavaFX application: the map canvas, the toolbar, the dialogs.
 * <p>
 * The only module that may see JavaFX. The engine modules must not, and CI proves it by building
 * them without this one – see CLAUDE.md.
 */
module net.trdlo.wtf.studio {
    requires static org.jetbrains.annotations;

    requires transitive net.trdlo.wtf.compose;
    // The window edits geometry directly – the shape tools, the overlays and the
    // project file all speak JTS. The engine modules still owe JavaFX nothing. Not transitive:
    // no module reads this one, and JTS is an automatic module, which javac warns about passing on.
    requires org.locationtech.jts;

    requires javafx.base;
    requires javafx.graphics;
    requires javafx.controls;
    requires javafx.swing;
    requires atlantafx.base;
    requires org.kordamp.ikonli.core;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fontawesome5;
    requires java.desktop;
    requires java.prefs;
    requires java.net.http;
    // UpdateCheck reads GitHub's release list, and the outline library is GeoJSON in the jar.
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;
    // LogDir tells logback.xml where the log goes
    requires ch.qos.logback.core;

    exports net.trdlo.wtf.studio;
    exports net.trdlo.wtf.studio.map;
    exports net.trdlo.wtf.studio.outline;
    exports net.trdlo.wtf.studio.project;
    exports net.trdlo.wtf.studio.steps;
}
