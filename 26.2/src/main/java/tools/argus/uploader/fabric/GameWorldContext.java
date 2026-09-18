package tools.argus.uploader.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Best-effort lookup of a token identifying the current world/server, used for fuzzy-matching
 * against Xaero's folder names. Mojang-mapped equivalents verified via javap against the real
 * 26.2 client jar: {@code MinecraftClient.getCurrentServerEntry()/ServerInfo.address} became
 * {@code Minecraft.getCurrentServer()/ServerData.ip}, and {@code isInSingleplayer()/getServer()
 * .getSaveProperties().getLevelName()} became {@code hasSingleplayerServer()/
 * getSingleplayerServer().getWorldData().getLevelName()}. Same defensive shape as
 * fabric-common's copy: any failure degrades to an empty token rather than crashing the mod.
 */
final class GameWorldContext {

    private GameWorldContext() {
    }

    static String currentWorldToken(Minecraft client) {
        try {
            if (client.getCurrentServer() != null) {
                return client.getCurrentServer().ip;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
                return client.getSingleplayerServer().getWorldData().getLevelName();
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
