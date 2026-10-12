package net.trdlo.wtf.studio;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Provider;

import java.util.Locale;

/**
 * A small square mark for a provider: a rounded tile in a color derived from its id, with its
 * initial on it.
 * <p>
 * <b>Deliberately not the provider's logo.</b> Shipping Seznam's, Mapbox's or Swisstopo's marks
 * would be using their trademarks as our own decoration, in an application that already has to
 * be careful about their terms – and it would mean bundling and updating sixteen images. A
 * generated mark is honest, weighs nothing and cannot go stale.
 * <p>
 * The color is a hash of the id, so a provider looks the same everywhere it appears and two
 * providers are rarely confusable. Saturation and lightness are fixed at values that hold up on
 * both the light and the dark theme, since the same node is used in either.
 */
public final class ProviderBadge {

    /** Enough color to tell apart, not enough to shout in a toolbar. */
    private static final double SATURATION = 0.42;
    private static final double BRIGHTNESS = 0.60;

    private ProviderBadge() {
    }

    public static @NotNull Node of(final @NotNull Provider provider, final double size) {
        final Rectangle tile = new Rectangle(size, size);
        tile.setArcWidth(size * 0.34);
        tile.setArcHeight(size * 0.34);
        tile.setFill(colorOf(provider.id()));

        final Label initial = new Label(initialOf(provider));
        initial.setTextFill(Color.web("#FFFFFF", 0.94));
        initial.setFont(Font.font("System", FontWeight.BOLD, Math.max(8, size * 0.56)));

        final StackPane badge = new StackPane(tile, initial);
        badge.setAlignment(Pos.CENTER);
        badge.setMinSize(size, size);
        badge.setPrefSize(size, size);
        badge.setMaxSize(size, size);
        badge.getStyleClass().add("provider-badge");
        return badge;
    }

    /**
     * A stable color for an id.
     * <p>
     * {@code String.hashCode} rather than anything cleverer, because it is specified: the same id
     * gives the same color on every machine and every release, which is the only property that
     * matters here.
     */
    public static @NotNull Color colorOf(final @NotNull String providerId) {
        final int hash = providerId.hashCode();
        // The golden-angle step spreads sequential-ish ids around the wheel instead of clustering
        final double hue = Math.floorMod(hash, 360) * 1.0;
        return Color.hsb(hue, SATURATION, BRIGHTNESS);
    }

    /**
     * One letter. Two initials read badly for names like "Mapy.com" or "basemap.de", where the
     * second token is a domain suffix rather than a word, and at sixteen pixels one letter is all
     * that survives anyway. The color does the telling apart.
     */
    static @NotNull String initialOf(final @NotNull Provider provider) {
        for (final char c : provider.displayName().toCharArray()) {
            if (Character.isLetter(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return provider.id().isEmpty() ? "?"
                : provider.id().substring(0, 1).toUpperCase(Locale.ROOT);
    }
}
