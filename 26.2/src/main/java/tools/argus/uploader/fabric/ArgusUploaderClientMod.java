package tools.argus.uploader.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.BlackzoneStore;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.ServerRegistry;
import tools.argus.uploader.core.StatsStore;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.UploadTracker;
import tools.argus.uploader.fabric.api.ArgusMapperEvents;
import tools.argus.uploader.fabric.gui.GuiLauncher;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 26.2's copy of fabric-common's ArgusUploaderClientMod, minus the pieces not yet ported here
 * (real ARD-backed nether highway geometry, PlayerDistanceTracker) - see this module's own
 * NOTES.md / build.gradle comments for what's deferred and why.
 */
public final class ArgusUploaderClientMod implements ClientModInitializer {

    public static final String MOD_ID = "argus-mapper";

    private static Path configPath;
    private static Path manifestPath;
    private static Path serverRegistryPath;
    private static Path statsPath;
    private static Path blackzonePath;
    private static volatile ArgusConfig config = new ArgusConfig();
    private static volatile ServerRegistry registry = ServerRegistry.empty();
    private static volatile StatsStore stats = StatsStore.empty();
    private static volatile BlackzoneStore blackzoneStore = BlackzoneStore.empty();
    private static volatile UploadTracker activeUpload;
    private static volatile UploadRunner activeRunner;
    private static final NetherHighwayFilter netherHighwayFilter = new NetherHighwayFilter();

    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        configPath = configDir.resolve("argus-mapper.properties");
        manifestPath = configDir.resolve("argus-mapper-manifest.txt");
        serverRegistryPath = configDir.resolve("argus-mapper-servers.properties");
        statsPath = configDir.resolve("argus-mapper-stats.properties");
        blackzonePath = configDir.resolve("argus-mapper-blackzones.properties");
        reloadConfig();
        reloadRegistry();
        try {
            stats = StatsStore.load(statsPath);
        } catch (IOException e) {
            // keep the default in-memory-only StatsStore rather than leaving it null
        }
        try {
            blackzoneStore = BlackzoneStore.load(blackzonePath);
        } catch (IOException e) {
            // Privacy-sensitive fallback: an empty store means every declared blackzone is
            // silently forgotten and nothing is excluded - see fabric-common's copy of this same
            // handling for the full rationale. Logged loudly on purpose.
            org.slf4j.LoggerFactory.getLogger(MOD_ID).error(
                    "Failed to load blackzones from {} - treating as if none are set until this is fixed. "
                            + "Run /argus blackzone list to check before uploading.", blackzonePath, e);
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> ArgusCommand.register(dispatcher));

        try {
            long regions = UploadManifest.load(manifestPath).size();
            ArgusMapperEvents.STATS_CHANGED.invoker().onStatsChanged(MapperStats.of(regions, stats.totalDistanceBlocks));
        } catch (IOException ignored) {
        }

        // Best-effort auto-detect: only announces a real match or an ambiguity warning, never a
        // "no match" (so joining an unrelated server stays quiet).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String address = GameWorldContext.currentWorldToken(client);
            ServerDetection.Outcome outcome = ServerDetection.detectAndApply(address, registry, config, configPath);
            if (client.player != null && outcome.matched()) {
                client.player.sendSystemMessage(
                        Component.literal("[ARGUS] " + outcome.message())
                                .withStyle(outcome.isError() ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
        });

        // Forces GuiLauncher's static init (which registers the keybinding) to run now, before
        // GameOptions loads - same hazard as every other version's GuiLauncher, see its own
        // javadoc for the NoClassDefFoundError this avoids.
        GuiLauncher.isAvailable();
        ClientTickEvents.END_CLIENT_TICK.register(GuiLauncher::tick);

        // Not wired here (see NOTES.md): PlayerDistanceTracker and real ARD-backed nether highway
        // geometry (NetherHighwayFilter is a fail-closed stub - see its own javadoc). Everything
        // else - the full command tree, blackzones, config, server registry, stats, Discord
        // reporting, Xaero World Map integration + region overlay, and the GUI - works.
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

    public static BlackzoneStore blackzoneStore() {
        return blackzoneStore;
    }

    public static UploadTracker activeUpload() {
        return activeUpload;
    }

    public static void setActiveUpload(UploadTracker tracker) {
        activeUpload = tracker;
    }

    public static UploadRunner activeRunner() {
        return activeRunner;
    }

    public static void setActiveRunner(UploadRunner runner) {
        activeRunner = runner;
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
