package tools.argus.uploader.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.BlackzoneRemovalPrompt;

import java.io.IOException;
import java.util.List;

/**
 * The one path every blackzone removal goes through (the GUI's Blackzones tab, the Xaero map
 * right-click, and {@code /argus blackzone remove}): a confirm popup first, and nothing is removed
 * unless the player answers yes. Removing a blackzone lets everything inside it be uploaded again,
 * so no entry point is allowed to skip the popup.
 */
public final class BlackzoneRemoval {

    private static volatile List<BlackZone> pendingFromCommand;

    private BlackzoneRemoval() {
    }

    /** Chat-command entry point. The command runs inside ChatScreen's own Enter handler, which
     *  closes the chat screen right after - an inline setScreen() would be clobbered, so the popup
     *  is opened from {@link #tick} on the next client tick instead (same problem, same fix as
     *  {@code GuiLauncher}). */
    public static void requestFromCommand(List<BlackZone> zones) {
        pendingFromCommand = List.copyOf(zones);
    }

    public static void tick(MinecraftClient client) {
        List<BlackZone> zones = pendingFromCommand;
        if (zones != null) {
            pendingFromCommand = null;
            confirmAndRemove(client.currentScreen, zones);
        }
    }

    /** Shows the confirm popup over {@code previousScreen} (null returns to the game); answering
     *  either way goes back to it, and only "yes" removes anything. */
    public static void confirmAndRemove(Screen previousScreen, List<BlackZone> zones) {
        confirmAndRemove(previousScreen, zones, () -> {
        });
    }

    /** @param afterRemoved runs only after a "yes" has actually removed them. A screen that lists
     *  blackzones must rebuild itself here: showing a screen again isn't guaranteed to
     *  re-initialise it, so it would otherwise keep listing what was just removed. */
    public static void confirmAndRemove(Screen previousScreen, List<BlackZone> zones, Runnable afterRemoved) {
        if (zones.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    client.setScreen(previousScreen);
                    if (confirmed) {
                        remove(client, zones);
                        afterRemoved.run();
                    }
                },
                Text.literal(BlackzoneRemovalPrompt.title(zones)),
                Text.literal(BlackzoneRemovalPrompt.body(zones))));
    }

    private static void remove(MinecraftClient client, List<BlackZone> zones) {
        int removed = 0;
        try {
            for (BlackZone zone : zones) {
                if (ArgusUploaderClientMod.blackzoneStore().remove(zone.id())) {
                    removed++;
                }
            }
        } catch (IOException e) {
            feedback(client, "Removed, but failed to save the change - it may come back after a restart: " + e.getMessage());
            return;
        }
        feedback(client, "Removed " + removed + " blackzone" + (removed == 1 ? "" : "s")
                + ". Anything inside can be uploaded again on the next run.");
    }

    private static void feedback(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[ARGUS] " + message), false);
        }
    }
}
