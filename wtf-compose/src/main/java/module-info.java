/**
 * Shapes, print sizing, sheet layout, the two regions and the exporters.
 * <p>
 * <b>Must never require javafx.*</b>: the whole point of the split is that an export can be driven
 * from a test, a CLI or an agent with no display attached. See CLAUDE.md.
 */
module net.trdlo.wtf.compose {
    requires static org.jetbrains.annotations;

    requires transitive net.trdlo.wtf.tiles;
    requires org.locationtech.jts;
    requires org.apache.pdfbox;
    requires org.apache.pdfbox.io;
    requires java.desktop;
    // ShapeImport reads KML, which is XML.
    requires java.xml;
    requires org.slf4j;
    requires com.fasterxml.jackson.databind;

    exports net.trdlo.wtf.compose;

    // Jackson binds the project-file records by parameter name.
    opens net.trdlo.wtf.compose to com.fasterxml.jackson.databind;
}
