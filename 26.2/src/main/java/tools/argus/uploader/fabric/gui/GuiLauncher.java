package tools.argus.uploader.fabric.gui;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * 26.2's real GuiLauncher, replacing the earlier no-op stub now that the GUI itself is ported.
 * Verified via javap against the real 26.2 jars: {@code KeyBindingHelper.registerKeyBinding} -&gt;
 * {@code KeyMappingHelper.registerKeyMapping} (fabric-key-mapping-api-v1, not the older
 * fabric-key-binding-api-v1), {@code KeyBinding} -&gt; {@code KeyMapping}, and
 * {@code Identifier.of(ns, path)} -&gt; {@code Identifier.fromNamespaceAndPath(ns, path)} (the old
 * {@code of} overload is gone entirely on this version). {@code KeyMapping.Category} is a public
 * record here (was a private-constructor-plus-static-factory on 1.21.11) - {@code register(
 * Identifier)} is the equivalent of 1.21.11's {@code KeyBinding.Category.create(Identifier)}, and
 * the simple 3-arg {@code KeyMapping(String, int, Category)} constructor needs no separate
 * input-type argument the way 1.21.11's 3-arg {@code KeyBinding} constructor did.
 */
public final class GuiLauncher {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("argus-mapper", "open_gui"));

    private static final KeyMapping OPEN_GUI_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.argus-mapper.open_gui", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

    // Same next-tick deferral fabric-common/1.21.x's GuiLauncher uses - a chat-command trigger
    // runs inside ChatScreen's own Enter-key handler, which closes ChatScreen itself right after,
    // clobbering an inline setScreenAndShow() here. Piggybacking on END_CLIENT_TICK instead is a
    // genuine next-tick deferral, confirmed live on every other version this mod supports.
    private static volatile boolean pendingOpen = false;

    static {
        ArgusPauseMenuButton.register();
    }

    private GuiLauncher() {
    }

    public static boolean isAvailable() {
        return true;
    }

    public static void open() {
        pendingOpen = true;
    }

    public static void tick(Minecraft client) {
        if (pendingOpen) {
            pendingOpen = false;
            client.setScreenAndShow(new ArgusGuiScreen());
        }
        while (OPEN_GUI_KEY.consumeClick()) {
            open();
        }
    }
}
