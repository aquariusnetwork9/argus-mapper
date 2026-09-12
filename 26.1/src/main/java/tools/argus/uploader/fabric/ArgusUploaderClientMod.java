package tools.argus.uploader.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ServerRegistry;
import tools.argus.uploader.core.StatsStore;

import java.io.IOException;
import java.nio.file.Path;

public final class ArgusUploaderClientMod implements ClientModInitializer {

    public static final String MOD_ID = "argus-mapper";

    private static Path configPath;
    private static Path manifestPath;
    private static Path serverRegistryPath;
    private static Path statsPath;
    private static volatile ArgusConfig config = new ArgusConfig();
    private static volatile ServerRegistry registry = ServerRegistry.empty();
    private static volatile StatsStore stats = StatsStore.empty();

    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        configPath = configDir.resolve("argus-mapper.properties");
        manifestPath = configDir.resolve("argus-mapper-manifest.txt");
        serverRegistryPath = configDir.resolve("argus-mapper-servers.properties");
        statsPath = configDir.resolve("argus-mapper-stats.properties");
        reloadConfig();
        reloadRegistry();
        try {
            stats = StatsStore.load(statsPath);
        } catch (IOException e) {
            // keep the default in-memory-only StatsStore rather than leaving it null
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> ArgusCommand.register(dispatcher));

        // NOT wired here: distance tracking needs the same vanilla player-position
        // APIs GameWorldContext.java stubs out for this module - see NOTES.md.
        // /argus server auto-detect on join is also unavailable for the same
        // reason; /argus server use / add and Discord reporting still work fully.
    }

    public static ArgusConfig config() {
        return config;
    }

    public static ServerRegistry registry() {
        return registry;
    }

    public static StatsStore stats() {
        return stats;
    }

    public static Path manifestPath() {
        return manifestPath;
    }

    public static Path configPath() {
        return configPath;
    }

    public static void reloadConfig() {
        try {
            config = ArgusConfig.load(configPath);
        } catch (IOException e) {
            config = new ArgusConfig();
        }
    }

    public static void reloadRegistry() {
        try {
            registry = ServerRegistry.load(serverRegistryPath);
        } catch (IOException e) {
            // keep the previous (or default empty) registry rather than leaving it null
        }
    }
}
