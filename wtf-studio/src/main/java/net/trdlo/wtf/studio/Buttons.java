package net.trdlo.wtf.studio;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

/**
 * Cancel and Close, in the interface language.
 * <p>
 * JavaFX's own {@link ButtonType#CANCEL} and {@link ButtonType#CLOSE} take their text from a bundle
 * inside JavaFX, which has a dozen languages and not Czech, so a Czech dialog ended in an English
 * button. These are the same buttons with the same roles – Escape still cancels, the platform still
 * decides the order – and words from our own bundle. OK stays JavaFX's: it is OK in every language
 * this application speaks.
 * <p>
 * A class of its own so that the constants are made the first time a dialog asks, which is after
 * the language has been chosen at startup.
 */
public final class Buttons {

    public static final ButtonType CANCEL =
            new ButtonType(I18n.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

    public static final ButtonType CLOSE =
            new ButtonType(I18n.get("button.close"), ButtonBar.ButtonData.CANCEL_CLOSE);

    private Buttons() {
    }
}
