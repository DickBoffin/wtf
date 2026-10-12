package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.tiles.TileDownloader;

import static org.junit.jupiter.api.Assertions.*;

/** What the tile servers are told is asking (D37). */
class UserAgentTest {

    @Test
    @DisplayName("the application tells the tile servers its own version and a contact")
    void theApplicationsOwnVersion() {
        final AppInfo app = AppInfo.get();
        assertEquals(app.name().replace(' ', '-') + "/" + app.version() + " (+" + TileDownloader.CONTACT + ")",
                app.userAgent());
        assertFalse(app.userAgent().contains("/0.1 "), "not the version the engine once had written in");
    }
}
