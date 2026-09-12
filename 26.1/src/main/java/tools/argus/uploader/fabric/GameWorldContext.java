package tools.argus.uploader.fabric;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * BEST-EFFORT / UNVERIFIED — see 26.1/NOTES.md.
 *
 * <p>Deliberately does NOT reference vanilla Minecraft classes (e.g. the
 * client singleton), since their Mojang-mapped names/members for 26.1
 * weren't verifiable in this session and a wrong guess would be worse than
 * an honest stub. This only affects auto-detection of the current
 * world/server for {@code /argus scan}'s fuzzy folder match; set
 * {@code xaeroRootOverride} in the config to work around it in the
 * meantime, or fill in the real lookup once you have the actual mappings
 * (compare against fabric-common/GameWorldContext.java's Yarn-mapped intent).
 */
final class GameWorldContext {

    private GameWorldContext() {
    }

    static String currentWorldToken() {
        // TODO: return the current server address / world folder name once
        // the real 26.1 mappings are available. See NOTES.md.
        return "";
    }

    static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
