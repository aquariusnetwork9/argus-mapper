package tools.argus.uploader.fabric.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 26.2's port of 1.21.11's ArgusPauseMenuButton - adds an "ARGUS Menu" button to the pause
 * screen. {@code GameMenuScreen} -&gt; {@code PauseScreen}, {@code ButtonWidget} -&gt; {@code Button}
 * ({@code dimensions(...)} -&gt; {@code bounds(...)}), {@code ClickableWidget} -&gt;
 * {@code AbstractWidget}, {@code Screens.getButtons} -&gt; {@code Screens.getWidgets} - all
 * confirmed via javap against the real 26.2 jars, not assumed to carry over from the 1.21.11
 * port. See that file's own javadoc for the full rationale (why ScreenEvents/Screens instead of
 * a Mixin into PauseScreen's private internals, and why this reads the vanilla buttons' own live
 * positions instead of guessing at layout constants).
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
            if (screen instanceof PauseScreen) {
                addButton(screen, scaledHeight);
            }
        });
    }

    private static void addButton(Screen screen, int scaledHeight) {
        List<AbstractWidget> widgets = Screens.getWidgets(screen);
        int x = MARGIN;
        int width = BUTTON_WIDTH;
        int y = scaledHeight - MARGIN - BUTTON_HEIGHT;
        try {
            int lowestBottom = 0;
            for (AbstractWidget widget : widgets) {
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
        widgets.add(Button.builder(Component.literal("ARGUS Menu"), button -> GuiLauncher.open())
                .bounds(x, y, width, BUTTON_HEIGHT)
                .build());
    }
}
