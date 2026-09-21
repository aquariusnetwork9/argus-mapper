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
import tools.argus.uploader.core.BlackzoneStore;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.ServerProfile;
import tools.argus.uploader.core.ServerRegistry;
import tools.argus.uploader.core.StatsStore;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.UploadTracker;
import tools.argus.uploader.fabric.api.ArgusMapperEvents;
import tools.argus.uploader.fabric.gui.GuiLauncher;

import java.io.IOException;
import java.nio.file.Path;

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
        tools.argus.uploader.core.UploadLog.setDirectory(FabricLoader.getInstance().getGameDir().resolve("argus-mapper-upload-log"));
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
            // Unlike the other stores' "keep the empty default" fallback, this one is privacy-
            // sensitive: an empty store means every declared blackzone is silently forgotten and
            // nothing is excluded. In practice this should only be reachable via a disk/permission
            // error (Properties parsing itself is lenient and rarely throws for bad content - see
            // BlackzoneStore.parse(), which already drops a malformed individual entry rather than
            // failing the whole load), but if it ever does happen it fails open rather than closed.
            // Logged loudly on purpose so it isn't silent; revisit if this turns out to matter more
            // than it seems today.
            org.slf4j.LoggerFactory.getLogger(MOD_ID).error(
                    "Failed to load blackzones from {} - treating as if none are set until this is fixed. "
                            + "Run /argus blackzone list to check before uploading.", blackzonePath, e);
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> ArgusCommand.register(dispatcher));
        PlayerDistanceTracker.register();

        // Give a fresh add-on listener an initial value instead of making it wait for the first
        // upload - best-effort; a manifest read failure here just means the first real upload's
        // own STATS_CHANGED fire is the first one, not a fatal init error.
        if (config.enableAddonApi) {
            try {
                long regions = UploadManifest.load(manifestPath).size();
                ArgusMapperEvents.STATS_CHANGED.invoker().onStatsChanged(MapperStats.of(regions, stats.totalDistanceBlocks));
            } catch (IOException ignored) {
            }
        }

        // Force GuiLauncher's static init (which registers the open-gui keybinding) to run now,
        // while we're still inside onInitializeClient() and GameOptions hasn't loaded yet - not
        // lazily on first tick. `GuiLauncher::tick` below is a method reference, which does NOT
        // trigger class loading at registration time; without this touch, the class only loads
        // when the callback first fires, by which point GameOptions is already initialised and
        // KeyBindingHelper.registerKeyBinding() throws IllegalStateException. Once that happens,
        // the JVM marks the class erroneous and every later access throws NoClassDefFoundError
        // for the rest of the session, permanently breaking /argus gui and the Xaero map
        // integration. Confirmed live on 1.21.11 - see MANUAL_TEST_PLAN.md scenario 1.
        GuiLauncher.isAvailable();
        ClientTickEvents.END_CLIENT_TICK.register(GuiLauncher::tick);
        ClientTickEvents.END_CLIENT_TICK.register(BlackzoneRemoval::tick);
        AutoUploads.register();
        AutoMapper.register();
        Bounty.register();

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

    public static BlackzoneStore blackzoneStore() {
        return blackzoneStore;
    }

    /** The currently-running (or most recently finished) upload's live per-region state, if any
     *  run has started this session - null before that. Survives GUI screens opening/closing,
     *  since the run itself lives on {@link tools.argus.uploader.core.UploadRunner}'s own
     *  background executor, not on any Screen. */
    public static UploadTracker activeUpload() {
        return activeUpload;
    }

    public static void setActiveUpload(UploadTracker tracker) {
        activeUpload = tracker;
    }

    /** The upload run currently in progress (or most recently finished), regardless of whether it
     *  was started by {@code /argus upload} or by a Xaero map-selection "Upload This Area" click -
     *  the single source of truth both consult before starting a new run, so the two trigger paths
     *  can never race each other into two runs uploading at once. */
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
