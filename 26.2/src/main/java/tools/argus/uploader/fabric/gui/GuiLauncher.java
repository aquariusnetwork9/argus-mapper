package tools.argus.uploader.fabric.gui;

import net.minecraft.client.Minecraft;

/**
 * No-op stub: the real GUI ({@code /argus gui}, the Xaero pause-menu button) hasn't been ported
 * to 26.2's Mojang mappings yet - it reaches into a much larger vanilla API surface (widgets,
 * screens, the pause menu) than the Xaero right-click integration did. {@link #isAvailable()}
 * returning false is the exact mechanism {@code ArgusCommand}'s {@code /argus gui} branch already
 * checks for (same pattern fabric-common uses), so this needs no changes on the caller side.
 */
public final class GuiLauncher {

    private GuiLauncher() {
    }

    public static boolean isAvailable() {
        return false;
    }

    public static void open() {
        // no-op - see class javadoc
    }

    public static void tick(Minecraft client) {
        // no-op - nothing to poll for until a real GUI exists
    }
}
