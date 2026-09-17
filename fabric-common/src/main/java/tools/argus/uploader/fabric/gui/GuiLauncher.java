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

    // A chat-command trigger runs synchronously from inside ChatScreen's own Enter-key handler,
    // which closes ChatScreen (setScreen(null)) itself right after the command executes - so an
    // inline setScreen() here gets clobbered invisibly. client.execute() alone isn't a deep enough
    // deferral: on 1.21.4/1.21.8 (confirmed live via a debug trace - init() ran, currentScreen
    // briefly held our screen, render() never fired) ChatScreen's own close is itself queued via
    // execute(), landing right after ours in the same tick's queue and clobbering it a step
    // removed. Piggybacking on the already-registered END_CLIENT_TICK hook instead is a genuine
    // next-*tick* deferral, not just next-queued-task - it runs after every execute() task queued
    // during the tick that triggered it has already drained, on 1.21.11 too (re-verified there).
    private static volatile boolean pendingOpen = false;

    private GuiLauncher() {
    }

    public static boolean isAvailable() {
        return true;
    }

    public static void open() {
        pendingOpen = true;
    }

    public static void tick(MinecraftClient client) {
        if (pendingOpen) {
            pendingOpen = false;
            client.setScreen(new ArgusGuiScreen());
        }
        while (OPEN_GUI_KEY.wasPressed()) {
            open();
        }
    }
}
