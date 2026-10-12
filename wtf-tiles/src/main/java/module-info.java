/**
 * Providers, the slippy-map math, the tile cache and the downloader.
 * <p>
 * <b>This module must never require javafx.*</b> – see CLAUDE.md. The guarantee is
 * enforced by CI, which builds {@code wtf-compose} and its dependencies on their own.
 */
module net.trdlo.wtf.tiles {
    requires static org.jetbrains.annotations;

    requires java.net.http;
    requires org.slf4j;
    requires com.fasterxml.jackson.databind;

    exports net.trdlo.wtf.tiles;

    // Jackson binds the provider records by parameter name; it needs reflective access to do it.
    opens net.trdlo.wtf.tiles to com.fasterxml.jackson.databind;
}
