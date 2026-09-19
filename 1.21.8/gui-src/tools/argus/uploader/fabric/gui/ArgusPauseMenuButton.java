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
 * 1.21.8's copy of 1.21.11's ArgusPauseMenuButton - adds an "ARGUS Menu" button to the pause
 * screen. GameMenuScreen/ButtonWidget/ClickableWidget/ScreenEvents/Screens all confirmed to have
 * the exact same names and signatures here via javap against the real 1.21.8 Yarn-mapped jar
 * (net.fabricmc:yarn:1.21.8+build.1:v2) - not assumed to carry over from the 1.21.4/1.21.11
 * ports. See the 1.21.11 file's own javadoc for the full rationale (why ScreenEvents/Screens
 * instead of a Mixin into GameMenuScreen's private initWidgets(), and why this reads the vanilla
 * buttons' own live positions instead of guessing at layout constants).
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
