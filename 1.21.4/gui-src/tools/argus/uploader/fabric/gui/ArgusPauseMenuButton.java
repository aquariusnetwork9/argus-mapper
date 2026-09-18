package tools.argus.uploader.fabric.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 1.21.4's copy of 1.21.11's ArgusPauseMenuButton - adds an "ARGUS Menu" button to the pause
 * screen. GameMenuScreen/ButtonWidget/ClickableWidget/ScreenEvents/Screens all confirmed to have
 * the exact same names and signatures here via javap against the real 1.21.4 Yarn-mapped jar
 * (net.fabricmc:yarn:1.21.4+build.8:v2) - not assumed to carry over from the 1.21.11 port. See
 * the 1.21.11 file's own javadoc for the full rationale (why ScreenEvents/Screens instead of a
 * Mixin into GameMenuScreen's private initWidgets(), and why this reads the vanilla buttons' own
 * live positions instead of guessing at layout constants).
 */
public final class ArgusPauseMenuButton {

    private static final Logger LOGGER = LoggerFactory.getLogger("argus-mapper");
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MARGIN = 4;
    private static final int ROW_GAP = 4;

    private ArgusPauseMenuButton() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof GameMenuScreen) {
                addButton(screen, scaledHeight);
            }
        });
    }

    private static void addButton(Screen screen, int scaledHeight) {
        List<ClickableWidget> buttons = Screens.getButtons(screen);
        int x = MARGIN;
        int width = BUTTON_WIDTH;
        int y = scaledHeight - MARGIN - BUTTON_HEIGHT;
        try {
            int lowestBottom = 0;
            for (ClickableWidget widget : buttons) {
                int bottom = widget.getY() + widget.getHeight();
                if (bottom > lowestBottom) {
                    lowestBottom = bottom;
                    x = widget.getX();
                    width = widget.getWidth();
                }
            }
            if (lowestBottom > 0 && lowestBottom + ROW_GAP + BUTTON_HEIGHT <= scaledHeight - MARGIN) {
                y = lowestBottom + ROW_GAP;
            }
        } catch (RuntimeException e) {
            LOGGER.error("Failed to line up the ARGUS Menu button with the pause menu's own buttons "
                    + "- using a fixed corner instead", e);
            x = MARGIN;
            width = BUTTON_WIDTH;
            y = scaledHeight - MARGIN - BUTTON_HEIGHT;
        }
        buttons.add(ButtonWidget.builder(Text.literal("ARGUS Menu"), button -> GuiLauncher.open())
                .dimensions(x, y, width, BUTTON_HEIGHT)
                .build());
    }
}
