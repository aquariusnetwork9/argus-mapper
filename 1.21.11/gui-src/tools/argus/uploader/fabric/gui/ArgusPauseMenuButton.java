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
import tools.argus.uploader.core.PauseButtonPlacement;

import java.util.ArrayList;
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
 * instead it reads the already-laid-out positions of every widget on the screen (all public via
 * ClickableWidget's own getX/getY/getWidth/getHeight, no private access needed) and hands them to
 * {@link tools.argus.uploader.core.PauseButtonPlacement}, which puts the button one row below the
 * main button stack or, if that doesn't fit, in a free corner - never on top of another mod's
 * widget (Simple World Downloader's bottom-left icon once did, see that class). A failure while
 * placing falls back to a fixed corner - this runs once per screen open, so an uncaught exception
 * here would break the pause menu's own Options/Quit buttons too, which is worse than an
 * oddly-placed ARGUS button.
 */
public final class ArgusPauseMenuButton {

    private static final Logger LOGGER = LoggerFactory.getLogger("argus-mapper");
    private ArgusPauseMenuButton() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof GameMenuScreen) {
                addButton(screen, scaledWidth, scaledHeight);
            }
        });
    }

    private static void addButton(Screen screen, int scaledWidth, int scaledHeight) {
        List<ClickableWidget> buttons = Screens.getButtons(screen);
        PauseButtonPlacement.Rect spot;
        try {
            List<PauseButtonPlacement.Rect> existing = new ArrayList<>();
            for (ClickableWidget widget : buttons) {
                existing.add(new PauseButtonPlacement.Rect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
            }
            spot = PauseButtonPlacement.place(existing, scaledWidth, scaledHeight);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to place the ARGUS Menu button among the pause menu's other buttons "
                    + "- using a fixed corner instead", e);
            spot = new PauseButtonPlacement.Rect(4, scaledHeight - 24,
                    PauseButtonPlacement.BUTTON_WIDTH, PauseButtonPlacement.BUTTON_HEIGHT);
        }
        buttons.add(ButtonWidget.builder(Text.literal("ARGUS Menu"), button -> GuiLauncher.open())
                .dimensions(spot.x(), spot.y(), spot.width(), spot.height())
                .build());
    }
}
