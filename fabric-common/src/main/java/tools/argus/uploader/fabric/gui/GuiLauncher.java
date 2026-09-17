package tools.argus.uploader.fabric.gui;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * The only point ArgusCommand/ArgusUploaderClientMod touch the real GUI classes through -
 * kept this thin so 1.21.11 can swap in a no-op replacement (see its own gui-src/) without
 * forking the much larger command/init classes. 1.21.11 needs its own version because
 * {@code KeyBinding}'s category parameter there is a {@code KeyBinding.Category} record, not
 * the {@code String} used here, and {@code PressableWidget} (which ArgusGuiScreen's widgets
 * extend) changed onPress/renderWidget/drawIcon in a way this GUI hasn't been ported to yet.
 */
public final class GuiLauncher {

    private static final KeyBinding OPEN_GUI_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.argus-mapper.open_gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.argus-mapper"));

    private GuiLauncher() {
    }

    public static boolean isAvailable() {
        return true;
    }

    public static void open() {
        // Deferred to the next client tick via execute(), not called inline - see 1.21.11's copy
        // of this class for the full explanation. Short version: a chat-command trigger runs
        // synchronously from inside ChatScreen's own Enter-key handler, which closes ChatScreen
        // (setScreen(null)) itself right after the command executes, on the same tick, before a
        // single frame renders - clobbering an inline setScreen() here invisibly. Confirmed live
        // on 1.21.11 (same command-dispatch code path, shared with this module).
        MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new ArgusGuiScreen()));
    }

    public static void tick(MinecraftClient client) {
        while (OPEN_GUI_KEY.wasPressed()) {
            open();
        }
    }
}
