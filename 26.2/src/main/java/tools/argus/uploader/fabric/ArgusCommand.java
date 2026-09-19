package tools.argus.uploader.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ArgusUploadClient;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.BlackzoneStore;
import tools.argus.uploader.core.CoordLimits;
import tools.argus.uploader.core.DiscordWebhookClient;
import tools.argus.uploader.core.LeaderboardReport;
import tools.argus.uploader.core.RegionBounds;
import tools.argus.uploader.core.RegionFile;
import tools.argus.uploader.core.ServerProfile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadProgressListener;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.UploadSummary;
import tools.argus.uploader.core.UploadTracker;
import tools.argus.uploader.fabric.api.ArgusMapperEvents;
import tools.argus.uploader.fabric.gui.GuiLauncher;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * 26.2's copy of fabric-common's ArgusCommand - same command tree and behavior, ported against
 * real Mojang mappings (verified via javap, not guessed): {@code MinecraftClient} -&gt;
 * {@code Minecraft}, {@code Text}/{@code Formatting} -&gt; {@code Component}/{@code
 * ChatFormatting} ({@code Text.literal(...).formatted(Formatting.RED)} -&gt; {@code
 * Component.literal(...).withStyle(ChatFormatting.RED)}), and {@code ClientCommandManager} was
 * renamed outright to {@code ClientCommands} (same static {@code literal}/{@code argument}
 * methods, just a different holder class). {@code FabricClientCommandSource.sendFeedback/
 * sendError} and {@code Minecraft.execute(Runnable)} (inherited from {@code
 * BlockableEventLoop<Runnable>}) kept their names unchanged.
 */
final class ArgusCommand {

    private static final Set<String> VALID_DIMENSIONS = Set.of("overworld", "the_nether", "theend");

    private ArgusCommand() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("argus")
                .then(literal("scan")
                        .executes(ctx -> scan(ctx.getSource(), null))
                        .then(argument("dimension", StringArgumentType.word())
                                .executes(ctx -> scan(ctx.getSource(), StringArgumentType.getString(ctx, "dimension")))))
                .then(literal("upload")
                        .executes(ctx -> upload(ctx.getSource(), null))
                        .then(argument("dimension", StringArgumentType.word())
                                .executes(ctx -> upload(ctx.getSource(), StringArgumentType.getString(ctx, "dimension")))))
                .then(literal("status").executes(ctx -> status(ctx.getSource())))
                .then(literal("cancel").executes(ctx -> cancel(ctx.getSource())))
                .then(literal("reload").executes(ctx -> reload(ctx.getSource())))
                .then(literal("config").executes(ctx -> showConfig(ctx.getSource())))
                .then(literal("settoken")
                        .then(argument("token", StringArgumentType.greedyString())
                                .executes(ctx -> setToken(ctx.getSource(), StringArgumentType.getString(ctx, "token")))))
                .then(literal("setlayer")
                        .then(argument("layer", StringArgumentType.word())
                                .executes(ctx -> setLayer(ctx.getSource(), StringArgumentType.getString(ctx, "layer")))))
                .then(literal("server")
                        .executes(ctx -> serverDetect(ctx.getSource()))
                        .then(literal("list").executes(ctx -> serverList(ctx.getSource())))
                        .then(literal("use")
                                .then(argument("id", StringArgumentType.word())
                                        .executes(ctx -> serverUse(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                        .then(literal("add")
                                .then(argument("id", StringArgumentType.word())
                                        .then(argument("layer", StringArgumentType.word())
                                                .then(argument("matches", StringArgumentType.greedyString())
                                                        .executes(ctx -> serverAdd(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "layer"),
                                                                StringArgumentType.getString(ctx, "matches"))))))))
                .then(literal("discord")
                        .then(literal("sethook")
                                .then(argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> discordSetHook(ctx.getSource(), StringArgumentType.getString(ctx, "url")))))
                        .then(literal("test").executes(ctx -> discordTest(ctx.getSource())))
                        .then(literal("report").executes(ctx -> discordReport(ctx.getSource()))))
                .then(literal("blackzone")
                        .executes(ctx -> blackzoneList(ctx.getSource()))
                        .then(literal("list").executes(ctx -> blackzoneList(ctx.getSource())))
                        .then(literal("add")
                                .then(argument("id", StringArgumentType.word())
                                        .then(argument("dimension", StringArgumentType.word())
                                                .then(argument("minX", IntegerArgumentType.integer())
                                                        .then(argument("minZ", IntegerArgumentType.integer())
                                                                .then(argument("maxX", IntegerArgumentType.integer())
                                                                        .then(argument("maxZ", IntegerArgumentType.integer())
                                                                                .executes(ctx -> blackzoneAdd(ctx.getSource(),
                                                                                        StringArgumentType.getString(ctx, "id"),
                                                                                        StringArgumentType.getString(ctx, "dimension"),
                                                                                        IntegerArgumentType.getInteger(ctx, "minX"),
                                                                                        IntegerArgumentType.getInteger(ctx, "minZ"),
                                                                                        IntegerArgumentType.getInteger(ctx, "maxX"),
                                                                                        IntegerArgumentType.getInteger(ctx, "maxZ"),
                                                                                        ""))
                                                                                .then(argument("label", StringArgumentType.greedyString())
                                                                                        .executes(ctx -> blackzoneAdd(ctx.getSource(),
                                                                                                StringArgumentType.getString(ctx, "id"),
                                                                                                StringArgumentType.getString(ctx, "dimension"),
                                                                                                IntegerArgumentType.getInteger(ctx, "minX"),
                                                                                                IntegerArgumentType.getInteger(ctx, "minZ"),
                                                                                                IntegerArgumentType.getInteger(ctx, "maxX"),
                                                                                                IntegerArgumentType.getInteger(ctx, "maxZ"),
                                                                                                StringArgumentType.getString(ctx, "label")))))))))))
                        .then(literal("remove")
                                .then(argument("id", StringArgumentType.word())
                                        .executes(ctx -> blackzoneRemove(ctx.getSource(), StringArgumentType.getString(ctx, "id"))))))
                .then(literal("gui").executes(ctx -> openGui(ctx.getSource()))));
    }

    private static int openGui(FabricClientCommandSource source) {
        if (!GuiLauncher.isAvailable()) {
            source.sendError(Component.literal("[ARGUS] /argus gui isn't available on this Minecraft version yet."));
            return 0;
        }
        GuiLauncher.open();
        return 1;
    }

    private static int discordSetHook(FabricClientCommandSource source, String url) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        config.discordWebhookUrl = url.trim();
        try {
            config.save(ArgusUploaderClientMod.configPath());
            info(source, "Discord webhook saved.");
        } catch (IOException e) {
            error(source, "Failed to save webhook: " + e.getMessage());
        }
        return 1;
    }

    private static int discordTest(FabricClientCommandSource source) {
        Thread.startVirtualThread(() -> {
            DiscordWebhookClient.Result result = new DiscordWebhookClient().postEmbed(
                    ArgusUploaderClientMod.config().discordWebhookUrl,
                    "ARGUS Mapper", "Test message - if you can see this, the webhook works.", List.of());
            if (result.success()) {
                info(source, "Test message sent.");
            } else {
                error(source, "Test failed: " + (result.error() != null ? result.error() : ("HTTP " + result.statusCode())));
            }
        });
        return 1;
    }

    private static int discordReport(FabricClientCommandSource source) {
        Thread.startVirtualThread(() -> {
            try {
                UploadManifest manifest = UploadManifest.load(ArgusUploaderClientMod.manifestPath());
                sendDiscordReport(manifest, (message, isError) -> {
                    if (isError) {
                        error(source, message);
                    } else {
                        info(source, message);
                    }
                });
            } catch (IOException e) {
                error(source, "Failed to read manifest: " + e.getMessage());
            }
        });
        return 1;
    }

    static void sendDiscordReport(UploadManifest manifest, BiConsumer<String, Boolean> feedback) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (config.discordWebhookUrl.isBlank()) {
            feedback.accept("No Discord webhook configured. Use /argus discord sethook <url>.", true);
            return;
        }
        String serverName = ArgusUploaderClientMod.registry().all().stream()
                .filter(p -> p.layer().equals(config.layer))
                .map(ServerProfile::name)
                .findFirst().orElse(null);
        DiscordWebhookClient.Result result = new DiscordWebhookClient().postEmbed(
                config.discordWebhookUrl,
                LeaderboardReport.title(),
                LeaderboardReport.description(serverName),
                LeaderboardReport.fields(manifest.size(), ArgusUploaderClientMod.stats().totalDistanceBlocks));
        if (result.success()) {
            feedback.accept("Reported to Discord.", false);
        } else {
            feedback.accept("Discord report failed: " + (result.error() != null ? result.error() : ("HTTP " + result.statusCode())), true);
        }
    }

    static void onUploadRunComplete(UploadManifest manifest, int succeeded, int failed, BiConsumer<String, Boolean> discordFeedback) {
        RegionOverlayState.invalidateUploadedCache();
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (config.autoReportToDiscord && !config.discordWebhookUrl.isBlank() && succeeded > 0) {
            sendDiscordReport(manifest, discordFeedback);
        }
        // Gated on enableAddonApi - see ArgusConfig's own javadoc for what that toggle does and
        // doesn't protect against.
        if (config.enableAddonApi) {
            Minecraft.getInstance().execute(() -> {
                ArgusMapperEvents.UPLOAD_COMPLETED.invoker().onUploadCompleted(new UploadSummary(succeeded, failed));
                ArgusMapperEvents.STATS_CHANGED.invoker().onStatsChanged(
                        MapperStats.of(manifest.size(), ArgusUploaderClientMod.stats().totalDistanceBlocks));
            });
        }
    }

    private static int blackzoneList(FabricClientCommandSource source) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        List<BlackZone> all = ArgusUploaderClientMod.blackzoneStore().all();
        if (all.isEmpty()) {
            info(source, "No blackzones. Add one with /argus blackzone add <id> <minX> <minZ> <maxX> <maxZ> [label] "
                    + "(region-file coordinates, i.e. the numbers in a Xaero region filename like '3_-1.zip').");
            return 1;
        }
        info(source, "Blackzones (current server layer '" + config.layer + "' marked ACTIVE):");
        for (BlackZone zone : all) {
            boolean active = zone.layer().equals(config.layer);
            info(source, "  " + zone.id() + " [" + zone.dimension() + ", layer=" + zone.layer() + (active ? ", ACTIVE" : "")
                    + "] region " + zone.bounds().minRegionX() + ".." + zone.bounds().maxRegionX()
                    + ", " + zone.bounds().minRegionZ() + ".." + zone.bounds().maxRegionZ()
                    + (zone.label().isBlank() ? "" : " \"" + zone.label() + "\""));
        }
        return 1;
    }

    private static int blackzoneAdd(FabricClientCommandSource source, String id, String dimension, int minX, int minZ, int maxX, int maxZ, String label) {
        if (!VALID_DIMENSIONS.contains(dimension)) {
            error(source, "Unknown dimension '" + dimension + "'. Valid: overworld, the_nether, theend.");
            return 0;
        }
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (config.layer.isBlank()) {
            error(source, "Set a server layer first (/argus server use <id> or /argus setlayer <layer>) - a blackzone is per-server.");
            return 0;
        }
        RegionBounds bounds;
        try {
            bounds = RegionBounds.ofCorners(minX, minZ, maxX, maxZ);
        } catch (IllegalArgumentException e) {
            error(source, "Invalid bounds: " + e.getMessage());
            return 0;
        }
        try {
            ArgusUploaderClientMod.blackzoneStore().addOrReplace(new BlackZone(id, dimension, config.layer, bounds, label));
            info(source, "Blackzone '" + id + "' saved for layer '" + config.layer
                    + "'. It will never be uploaded, and existing /argus scan or /argus upload runs will exclude it from now on. "
                    + "This is local only - it is never sent anywhere.");
            return 1;
        } catch (IOException e) {
            error(source, "Failed to save blackzone: " + e.getMessage());
            return 0;
        }
    }

    private static int blackzoneRemove(FabricClientCommandSource source, String id) {
        Optional<BlackZone> zone = ArgusUploaderClientMod.blackzoneStore().find(id);
        if (zone.isEmpty()) {
            error(source, "No blackzone with id '" + id + "'. See /argus blackzone list.");
            return 0;
        }
        BlackzoneRemoval.requestFromCommand(List.of(zone.get()));
        info(source, "Confirm in the popup to remove blackzone '" + id + "'.");
        return 1;
    }

    private static int serverDetect(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        String address = GameWorldContext.currentWorldToken(client);
        ServerDetection.Outcome outcome = ServerDetection.detectAndApply(address, ArgusUploaderClientMod.registry(), ArgusUploaderClientMod.config(), ArgusUploaderClientMod.configPath());
        if (outcome.isError()) {
            error(source, outcome.message());
        } else {
            info(source, outcome.message());
        }
        return 1;
    }

    private static int serverList(FabricClientCommandSource source) {
        List<ServerProfile> all = ArgusUploaderClientMod.registry().all();
        if (all.isEmpty()) {
            info(source, "No servers registered. Add one with /argus server add <id> <layer> <matchSubstrings>.");
            return 1;
        }
        info(source, "Known servers:");
        for (ServerProfile p : all) {
            info(source, "  " + p.name() + " -> layer=" + p.layer() + ", matches=" + String.join(",", p.addressMatches()));
        }
        return 1;
    }

    private static int serverUse(FabricClientCommandSource source, String id) {
        Optional<ServerProfile> found = ArgusUploaderClientMod.registry().byName(id);
        if (found.isEmpty()) {
            error(source, "Unknown server '" + id + "'. See /argus server list.");
            return 0;
        }
        ArgusConfig config = ArgusUploaderClientMod.config();
        config.layer = found.get().layer();
        try {
            config.save(ArgusUploaderClientMod.configPath());
            info(source, "Layer set to '" + config.layer + "' (server '" + id + "').");
        } catch (IOException e) {
            error(source, "Failed to save config: " + e.getMessage());
        }
        return 1;
    }

    private static int serverAdd(FabricClientCommandSource source, String id, String layer, String matchesCsv) {
        List<String> matches = Arrays.stream(matchesCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (matches.isEmpty()) {
            error(source, "Provide at least one address match substring, comma-separated.");
            return 0;
        }
        try {
            ArgusUploaderClientMod.registry().addOrReplace(new ServerProfile(id, layer, matches));
            info(source, "Registered server '" + id + "' -> layer '" + layer + "', matches " + matches);
        } catch (IOException e) {
            error(source, "Failed to save server registry: " + e.getMessage());
        }
        return 1;
    }

    private static int scan(FabricClientCommandSource source, String dimensionFilter) {
        RegionResolver.Resolved resolved = resolveRoot(source, dimensionFilter);
        if (resolved == null) {
            return 0;
        }
        List<RegionFile> regions = resolved.regions();
        long totalBytes = regions.stream().mapToLong(RegionFile::sizeBytes).sum();
        info(source, "Root: " + resolved.root());
        info(source, "Found " + regions.size() + " region file(s), " + (totalBytes / 1_000_000) + " MB total.");
        for (String dim : VALID_DIMENSIONS) {
            long count = regions.stream().filter(r -> r.dimension().equals(dim)).count();
            if (count > 0) {
                info(source, "  " + dim + ": " + count);
            }
        }
        if (resolved.rejectedOutOfRange() > 0) {
            info(source, "  skipped " + resolved.rejectedOutOfRange() + " region(s) outside the +/-"
                    + CoordLimits.MAX_ABS_COORD + " coordinate limit.");
        }
        if (resolved.rejectedOffHighway() > 0) {
            info(source, "  skipped " + resolved.rejectedOffHighway() + " nether region(s) not near a known ARD highway.");
        }
        if (resolved.netherRestrictedButGeometryUnloaded()) {
            info(source, "  nether restriction is on but ARD highway geometry isn't available on this build yet - no nether regions are eligible. Set restrictNetherToHighways=false in " + ArgusUploaderClientMod.configPath() + " to disable (not recommended), or use a version with real ARD support.");
        }
        if (resolved.rejectedByBlackzone() > 0) {
            info(source, "  skipped " + resolved.rejectedByBlackzone() + " region(s) covered by a blackzone (see /argus blackzone list).");
        }
        info(source, "Run /argus upload" + (dimensionFilter != null ? " " + dimensionFilter : "") + " to send these. This does not send anything yet.");
        return 1;
    }

    private static int setToken(FabricClientCommandSource source, String token) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        config.token = token.trim();
        try {
            config.save(ArgusUploaderClientMod.configPath());
            info(source, "Token saved. Consider clearing your chat history / logs/latest.log afterwards, since it was typed here.");
        } catch (IOException e) {
            error(source, "Failed to save token: " + e.getMessage());
        }
        return 1;
    }

    private static int setLayer(FabricClientCommandSource source, String layer) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        config.layer = layer.trim();
        try {
            config.save(ArgusUploaderClientMod.configPath());
            info(source, "Layer set to '" + config.layer + "'.");
        } catch (IOException e) {
            error(source, "Failed to save layer: " + e.getMessage());
        }
        return 1;
    }

    private static int upload(FabricClientCommandSource source, String dimensionFilter) {
        UploadRunner activeRunner = ArgusUploaderClientMod.activeRunner();
        if (activeRunner != null && activeRunner.isRunning()) {
            error(source, "A run is already in progress. Use /argus status or /argus cancel.");
            return 0;
        }
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (!config.isUsable()) {
            error(source, "Config incomplete: set token and layer in " + ArgusUploaderClientMod.configPath() + ", then /argus reload.");
            return 0;
        }
        RegionResolver.Resolved resolved = resolveRoot(source, dimensionFilter);
        if (resolved == null) {
            return 0;
        }
        if (resolved.rejectedOffHighway() > 0) {
            info(source, "Excluded " + resolved.rejectedOffHighway() + " nether region(s) not near a known ARD highway.");
        }
        if (resolved.netherRestrictedButGeometryUnloaded()) {
            info(source, "ARD highway geometry isn't available on this build yet - no nether regions are eligible this run.");
        }
        if (resolved.rejectedByBlackzone() > 0) {
            info(source, "Excluded " + resolved.rejectedByBlackzone() + " region(s) covered by a blackzone.");
        }

        try {
            UploadManifest manifest = UploadManifest.load(ArgusUploaderClientMod.manifestPath());
            BlackzoneStore blackzones = ArgusUploaderClientMod.blackzoneStore();
            ArgusUploadClient client = new ArgusUploadClient(config, blackzones);
            UploadTracker tracker = new UploadTracker(new ChatProgressListener(source, manifest));
            ArgusUploaderClientMod.setActiveUpload(tracker);
            UploadRunner runner = new UploadRunner(client, config, manifest, tracker);
            ArgusUploaderClientMod.setActiveRunner(runner);
            String runId = "run-" + System.currentTimeMillis();
            runner.start(resolved.regions(), runId, region -> !blackzones.isBlackzoned(region.dimension(), config.layer, region));
            return 1;
        } catch (IOException e) {
            error(source, "Failed to start upload: " + e.getMessage());
            return 0;
        }
    }

    private static int status(FabricClientCommandSource source) {
        UploadRunner runner = ArgusUploaderClientMod.activeRunner();
        if (runner == null || !runner.isRunning()) {
            info(source, "No upload in progress.");
        } else {
            info(source, "Upload in progress: " + runner.getDoneCount() + " / " + runner.getTotalCount());
        }
        return 1;
    }

    private static int cancel(FabricClientCommandSource source) {
        UploadRunner runner = ArgusUploaderClientMod.activeRunner();
        if (runner == null || !runner.isRunning()) {
            info(source, "No upload in progress.");
        } else {
            runner.cancel();
            info(source, "Cancelling after the in-flight request...");
        }
        return 1;
    }

    private static int reload(FabricClientCommandSource source) {
        ArgusUploaderClientMod.reloadConfig();
        info(source, "Config reloaded from " + ArgusUploaderClientMod.configPath());
        return 1;
    }

    private static int showConfig(FabricClientCommandSource source) {
        ArgusConfig c = ArgusUploaderClientMod.config();
        info(source, "Config file: " + ArgusUploaderClientMod.configPath());
        info(source, "apiBaseUrl=" + c.apiBaseUrl);
        info(source, "layer=" + (c.layer.isBlank() ? "(not set)" : c.layer));
        info(source, "token=" + (c.token.isBlank() ? "(not set)" : "(set, hidden)"));
        info(source, "xaeroRootOverride=" + (c.xaeroRootOverride.isBlank() ? "(auto-detect)" : c.xaeroRootOverride));
        info(source, "includeCaves=" + c.includeCaves + " paceMillis=" + c.paceMillis + " maxPerBatch=" + c.maxPerBatch);
        info(source, "restrictNetherToHighways=" + c.restrictNetherToHighways);
        return 1;
    }

    private static RegionResolver.Resolved resolveRoot(FabricClientCommandSource source, String dimensionFilter) {
        if (dimensionFilter != null && !VALID_DIMENSIONS.contains(dimensionFilter)) {
            error(source, "Unknown dimension '" + dimensionFilter + "'. Valid: overworld, the_nether, theend.");
            return null;
        }
        RegionResolver.Resolved resolved = RegionResolver.resolve(dimensionFilter);
        if (resolved.isError()) {
            for (String line : resolved.error().split("\n")) {
                error(source, line);
            }
            return null;
        }
        return resolved;
    }

    private static void info(FabricClientCommandSource source, String message) {
        Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal("[ARGUS] " + message)));
    }

    private static void error(FabricClientCommandSource source, String message) {
        Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal("[ARGUS] " + message).withStyle(ChatFormatting.RED)));
    }

    private static final class ChatProgressListener implements UploadProgressListener {
        private final FabricClientCommandSource source;
        private final UploadManifest manifest;

        ChatProgressListener(FabricClientCommandSource source, UploadManifest manifest) {
            this.source = source;
            this.manifest = manifest;
        }

        @Override
        public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload) {
            info(source, "Found " + totalFound + " region(s): " + alreadyUploaded + " already uploaded, "
                    + tooLarge + " over the size limit, " + excludedByBlackzone + " excluded by blackzone, "
                    + toUpload + " to upload.");
            if (toUpload == 0) {
                info(source, "Nothing to do.");
            } else {
                info(source, "Starting upload of " + toUpload + " region(s)...");
            }
        }

        @Override
        public void onRegionUploaded(RegionFile region, int done, int total) {
            if (done % 10 == 0 || done == total) {
                info(source, "Uploaded " + done + " / " + total + " (" + region.filename() + ", " + region.dimension() + ")");
            }
        }

        @Override
        public void onRegionFailed(RegionFile region, String reason, int done, int total) {
            error(source, "Failed " + region.filename() + " (" + region.dimension() + "): " + reason);
        }

        @Override
        public void onFatalError(String message) {
            error(source, message);
        }

        @Override
        public void onComplete(int succeeded, int failed) {
            info(source, "Upload run finished: " + succeeded + " ok, " + failed + " failed.");
            onUploadRunComplete(manifest, succeeded, failed, (message, isError) -> {
                if (isError) {
                    error(source, message);
                } else {
                    info(source, message);
                }
            });
        }
    }
}
