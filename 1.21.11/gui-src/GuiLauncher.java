package tools.argus.uploader.fabric.gui;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * 1.21.11's GuiLauncher - unlike fabric-common's copy, this one actually opens the GUI. Ported
 * against the real 1.21.11 Yarn mappings (net.fabricmc:yarn:1.21.11+build.6:v2) and a real
 * compile, not guessed: {@code KeyBinding}'s old 4-arg constructor (id, InputUtil.Type, code,
 * categoryTranslationKey) is gone here. The 3-arg one used below - (id, code, KeyBinding.Category)
 * - implies KEYSYM the same way the old call always passed InputUtil.Type.KEYSYM explicitly, so
 * behavior is unchanged. {@code KeyBinding.Category.create(String)} exists but is private - only
 * the {@code Identifier} overload is public, so the ad-hoc custom category the old String
 * translation key argument used to make is built from an Identifier instead.
 *
 * <p>Deliberately sits at gui-src/GuiLauncher.java rather than mirroring its package as
 * gui-src/tools/.../gui/GuiLauncher.java - build.gradle's exclude for fabric-common's copy of
 * this file matches that same relative path in every srcDir, this one included, so mirroring
 * the package here would exclude this replacement too and leave nothing behind. Gradle passes
 * javac an explicit file list rather than relying on sourcepath lookup, so the mismatch between
 * this file's location and its package statement below doesn't affect compilation.
 */
public final class GuiLauncher {

    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("argus-mapper", "open_gui"));

    private static final KeyBinding OPEN_GUI_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.argus-mapper.open_gui", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

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
