package tools.argus.uploader.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;

/**
 * Opens Xaero's World Map the way its own keybind does (no parent screen, so closing returns to the
 * game). Reflective because GuiMap's superclass lives in Xaero's separate library mod, which this
 * build doesn't compile against; every failure just means "couldn't open it".
 */
final class XaeroMapOpener {

    private XaeroMapOpener() {
    }

    static boolean open(Minecraft client) {
        try {
            Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
            Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null || !(boolean) sessionClass.getMethod("isUsable").invoke(session)) {
                return false;
            }
            Object processor = sessionClass.getMethod("getMapProcessor").invoke(session);
            for (Constructor<?> constructor : Class.forName("xaero.map.gui.GuiMap").getConstructors()) {
                if (constructor.getParameterCount() == 4) {
                    client.setScreenAndShow((Screen) constructor.newInstance(null, null, processor, client.getCameraEntity()));
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
        return false;
    }
}
