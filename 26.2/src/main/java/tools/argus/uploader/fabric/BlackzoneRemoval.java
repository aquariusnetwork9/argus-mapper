package tools.argus.uploader.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.BlackzoneRemovalPrompt;

import java.io.IOException;
import java.util.List;

/**
 * 26.2's copy of fabric-common's BlackzoneRemoval - see that file for the design (one confirm-popup
 * path for every removal entry point). Vanilla touchpoints re-verified via javap on the real 26.2
 * jar: {@code ConfirmScreen}'s (BooleanConsumer, Component, Component) constructor and
 * {@code setScreenAndShow} are unchanged from what MapUploadTrigger already uses. The current
 * screen is no longer a public field here (it moved to {@code Minecraft.gui.screen()}), so the
 * deferred command path returns to the game rather than to whatever was open - which is always
 * nothing, since the command runs from chat and chat has closed by the time {@link #tick} fires.
 */
public final class BlackzoneRemoval {

    private static volatile List<BlackZone> pendingFromCommand;

    private BlackzoneRemoval() {
    }

    public static void requestFromCommand(List<BlackZone> zones) {
        pendingFromCommand = List.copyOf(zones);
    }

    public static void tick(Minecraft client) {
        List<BlackZone> zones = pendingFromCommand;
        if (zones != null) {
            pendingFromCommand = null;
            confirmAndRemove(null, zones);
        }
    }

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
        Minecraft client = Minecraft.getInstance();
        client.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    client.setScreenAndShow(previousScreen);
                    if (confirmed) {
                        remove(client, zones);
                        afterRemoved.run();
                    }
                },
                Component.literal(BlackzoneRemovalPrompt.title(zones)),
                Component.literal(BlackzoneRemovalPrompt.body(zones))));
    }

    private static void remove(Minecraft client, List<BlackZone> zones) {
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

    private static void feedback(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[ARGUS] " + message));
        }
    }
}
