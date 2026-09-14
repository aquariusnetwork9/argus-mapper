package tools.argus.uploader.fabric.gui;

import net.minecraft.client.MinecraftClient;

/**
 * 1.21.11's replacement for fabric-common's GuiLauncher (excluded for this module - see
 * build.gradle) - see the root README's "GUI" section for why: on this version, ArgusGuiScreen
 * and its widgets don't compile (PressableWidget's onPress/renderWidget/drawIcon signatures
 * changed) and KeyBinding's category parameter is a KeyBinding.Category record instead of the
 * String fabric-common's GuiLauncher constructs one from, so no keybinding is registered here
 * either. /argus gui reports itself unavailable instead of silently doing nothing.
 *
 * <p>Deliberately sits at gui-src/GuiLauncher.java rather than mirroring its package as
 * gui-src/tools/.../gui/GuiLauncher.java: build.gradle's exclude for fabric-common's copy of
 * this file matches that same relative path in every srcDir, this one included, so mirroring
 * the package here would exclude this replacement too and leave nothing behind. Gradle passes
 * javac an explicit file list rather than relying on sourcepath lookup, so the mismatch between
 * this file's location and its package statement below doesn't affect compilation.
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

    public static void tick(MinecraftClient client) {
        // no-op - no keybinding registered on this version
    }
}
