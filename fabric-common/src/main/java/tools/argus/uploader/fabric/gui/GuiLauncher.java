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
        MinecraftClient.getInstance().setScreen(new ArgusGuiScreen());
    }

    public static void tick(MinecraftClient client) {
        while (OPEN_GUI_KEY.wasPressed()) {
            open();
        }
    }
}
