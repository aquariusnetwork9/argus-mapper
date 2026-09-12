package tools.argus.uploader.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

/**
 * Best-effort lookup of a token identifying the current world/server, used
 * for fuzzy-matching against Xaero's folder names. Deliberately defensive:
 * these accessors (save properties / server entry) have drifted before, so
 * any failure here degrades to an empty token (which just means
 * {@code /argus scan} will need the user to disambiguate) rather than
 * crashing the mod.
 */
final class GameWorldContext {

    private GameWorldContext() {
    }

    static String currentWorldToken(MinecraftClient client) {
        try {
            if (client.getCurrentServerEntry() != null) {
                return client.getCurrentServerEntry().address;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            if (client.isInSingleplayer() && client.getServer() != null) {
                return client.getServer().getSaveProperties().getLevelName();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "";
    }

    static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
