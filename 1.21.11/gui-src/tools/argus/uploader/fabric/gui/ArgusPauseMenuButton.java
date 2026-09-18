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
 * Adds an "ARGUS Menu" button to the vanilla pause screen, wired to open the same
 * {@link ArgusGuiScreen} as the /argus gui command and the open-gui keybinding (both go through
 * {@link GuiLauncher#open()}). Uses Fabric API's screen-api-v1 (ScreenEvents + Screens) instead
 * of a Mixin into GameMenuScreen's own initWidgets() - that method is private and builds its
 * GridWidget from locals that aren't exposed as fields (confirmed via javap against the real
 * 1.21.11 Yarn-mapped jar), so hooking it directly would mean matching internals that can shift
 * across Yarn builds. ScreenEvents.AFTER_INIT + Screens.getButtons() is Fabric API's own
 * supported mechanism for other mods to add widgets to a screen they don't own.
 *
 * <p>Since the real GridWidget instance isn't reachable, this doesn't become a row of that grid -
 * instead it reads the already-laid-out positions of the vanilla buttons (all public via
 * ClickableWidget's own getX/getY/getWidth/getHeight, no private access needed) and places
 * itself as one more row directly below the lowest one, matching that row's x/width so it lines
 * up with the rest of the stack. Falls back to a fixed bottom-left corner if that would run the
 * button off the bottom of the window (e.g. a short window with many rows already showing), or
 * if anything about the vanilla layout ever looks unexpected - this runs once per screen open, so
 * an uncaught exception here would break the pause menu's own Options/Quit buttons too, which is
 * worse than an oddly-placed ARGUS button.
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
