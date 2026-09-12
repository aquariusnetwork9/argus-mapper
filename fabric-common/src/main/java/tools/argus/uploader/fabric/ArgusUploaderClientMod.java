package tools.argus.uploader.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ServerProfile;
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
    private static final NetherHighwayFilter netherHighwayFilter = new NetherHighwayFilter();

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
        PlayerDistanceTracker.register();

        // Drives NetherHighwayFilter's own geometry fetch (independent of ARD's reporter/HUD -
        // see that class's javadoc for why). Resolves the current ARD server id from whichever
        // of our own server profiles matches the active layer, same pattern as the Discord
        // report's server-name lookup.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            String serverName = registry.all().stream()
                    .filter(p -> p.layer().equals(config.layer))
                    .map(ServerProfile::name)
                    .findFirst().orElse(null);
            netherHighwayFilter.tick(NetherHighwayFilter.ardServerIdFor(serverName));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> netherHighwayFilter.shutdown());

        // Best-effort auto-detect: only announces a real match or an ambiguity
        // warning, never a "no match" (so joining an unrelated server stays quiet).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String address = GameWorldContext.currentWorldToken(client);
            ServerDetection.Outcome outcome = ServerDetection.detectAndApply(address, registry, config, configPath);
            if (client.player != null && outcome.matched()) {
                client.player.sendMessage(
                        Text.literal("[ARGUS] " + outcome.message()).formatted(outcome.isError() ? Formatting.RED : Formatting.GRAY),
                        false);
            }
        });
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

    static NetherHighwayFilter netherHighwayFilter() {
        return netherHighwayFilter;
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
