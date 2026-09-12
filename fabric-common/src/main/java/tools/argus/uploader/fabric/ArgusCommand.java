package tools.argus.uploader.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ArgusUploadClient;
import tools.argus.uploader.core.CoordLimits;
import tools.argus.uploader.core.DiscordWebhookClient;
import tools.argus.uploader.core.LeaderboardReport;
import tools.argus.uploader.core.RegionFile;
import tools.argus.uploader.core.ServerProfile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadProgressListener;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.UploadSummary;
import tools.argus.uploader.core.XaeroRootFinder;
import tools.argus.uploader.core.XaeroScanner;
import tools.argus.uploader.fabric.api.ArgusMapperEvents;
import tools.argus.uploader.fabric.gui.ArgusGuiScreen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

final class ArgusCommand {

    private static final Set<String> VALID_DIMENSIONS = Set.of("overworld", "the_nether", "theend");

    private static volatile UploadRunner activeRunner;

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
                .then(literal("gui").executes(ctx -> openGui(ctx.getSource()))));
    }

    private static int openGui(FabricClientCommandSource source) {
        MinecraftClient.getInstance().setScreen(new ArgusGuiScreen());
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
        // Runs off the client thread: this is a synchronous HTTP call and command
        // handlers execute on the main thread, so calling it inline would freeze
        // the game for the duration of the request.
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
                sendDiscordReport(source, manifest);
            } catch (IOException e) {
                error(source, "Failed to read manifest: " + e.getMessage());
            }
        });
        return 1;
    }

    /** Shared by the explicit /argus discord report command and the auto-report-on-completion path. */
    static void sendDiscordReport(FabricClientCommandSource source, UploadManifest manifest) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (config.discordWebhookUrl.isBlank()) {
            error(source, "No Discord webhook configured. Use /argus discord sethook <url>.");
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
            info(source, "Reported to Discord.");
        } else {
            error(source, "Discord report failed: " + (result.error() != null ? result.error() : ("HTTP " + result.statusCode())));
        }
    }

    private static int serverDetect(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
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
        Resolved resolved = resolveRoot(source, dimensionFilter);
        if (resolved == null) {
            return 0;
        }
        List<RegionFile> regions = resolved.regions;
        long totalBytes = regions.stream().mapToLong(RegionFile::sizeBytes).sum();
        info(source, "Root: " + resolved.root);
        info(source, "Found " + regions.size() + " region file(s), " + (totalBytes / 1_000_000) + " MB total.");
        for (String dim : VALID_DIMENSIONS) {
            long count = regions.stream().filter(r -> r.dimension().equals(dim)).count();
            if (count > 0) {
                info(source, "  " + dim + ": " + count);
            }
        }
        if (resolved.rejectedOutOfRange > 0) {
            info(source, "  skipped " + resolved.rejectedOutOfRange + " region(s) outside the +/-"
                    + CoordLimits.MAX_ABS_COORD + " coordinate limit.");
        }
        if (resolved.rejectedOffHighway > 0) {
            info(source, "  skipped " + resolved.rejectedOffHighway + " nether region(s) not near a known ARD highway.");
        }
        if (resolved.netherRestrictedButGeometryUnloaded) {
            info(source, "  nether restriction is on but ARD highway geometry hasn't loaded for this server yet - no nether regions are eligible right now. Try again shortly, or set restrictNetherToHighways=false in " + ArgusUploaderClientMod.configPath() + " to disable (not recommended).");
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
        if (activeRunner != null && activeRunner.isRunning()) {
            error(source, "A run is already in progress. Use /argus status or /argus cancel.");
            return 0;
        }
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (!config.isUsable()) {
            error(source, "Config incomplete: set token and layer in " + ArgusUploaderClientMod.configPath() + ", then /argus reload.");
            return 0;
        }
        Resolved resolved = resolveRoot(source, dimensionFilter);
        if (resolved == null) {
            return 0;
        }
        if (resolved.rejectedOffHighway > 0) {
            info(source, "Excluded " + resolved.rejectedOffHighway + " nether region(s) not near a known ARD highway.");
        }
        if (resolved.netherRestrictedButGeometryUnloaded) {
            info(source, "ARD highway geometry hasn't loaded for this server yet - no nether regions are eligible this run.");
        }

        try {
            UploadManifest manifest = UploadManifest.load(ArgusUploaderClientMod.manifestPath());
            ArgusUploadClient client = new ArgusUploadClient(config);
            UploadProgressListener listener = new ChatProgressListener(source, manifest);
            UploadRunner runner = new UploadRunner(client, config, manifest, listener);
            activeRunner = runner;
            String runId = "run-" + System.currentTimeMillis();
            runner.start(resolved.regions, runId);
            return 1;
        } catch (IOException e) {
            error(source, "Failed to start upload: " + e.getMessage());
            return 0;
        }
    }

    private static int status(FabricClientCommandSource source) {
        UploadRunner runner = activeRunner;
        if (runner == null || !runner.isRunning()) {
            info(source, "No upload in progress.");
        } else {
            info(source, "Upload in progress: " + runner.getDoneCount() + " / " + runner.getTotalCount());
        }
        return 1;
    }

    private static int cancel(FabricClientCommandSource source) {
        UploadRunner runner = activeRunner;
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

    private record Resolved(Path root, List<RegionFile> regions, int rejectedOutOfRange,
                             int rejectedOffHighway, boolean netherRestrictedButGeometryUnloaded) {
    }

    private static Resolved resolveRoot(FabricClientCommandSource source, String dimensionFilter) {
        if (dimensionFilter != null && !VALID_DIMENSIONS.contains(dimensionFilter)) {
            error(source, "Unknown dimension '" + dimensionFilter + "'. Valid: overworld, the_nether, theend.");
            return null;
        }
        ArgusConfig config = ArgusUploaderClientMod.config();
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            Path root;
            if (!config.xaeroRootOverride.isBlank()) {
                root = Path.of(config.xaeroRootOverride);
            } else {
                String token = GameWorldContext.currentWorldToken(client);
                List<Path> candidates = XaeroRootFinder.findCandidates(GameWorldContext.gameDir(), token);
                if (candidates.isEmpty()) {
                    error(source, "No Xaero world-map folder found matching '" + token + "'. Set xaeroRootOverride in the config to the exact folder and /argus reload.");
                    return null;
                }
                if (candidates.size() > 1) {
                    error(source, "Multiple candidate folders found, set xaeroRootOverride to disambiguate:");
                    candidates.forEach(p -> error(source, "  " + p));
                    return null;
                }
                root = candidates.get(0);
            }
            XaeroScanner.ScanResult scanResult = XaeroScanner.scan(root, config.includeCaves);
            List<RegionFile> regions = scanResult.regions();
            if (dimensionFilter != null) {
                regions = regions.stream().filter(r -> r.dimension().equals(dimensionFilter)).toList();
            }

            int rejectedOffHighway = 0;
            boolean netherRestrictedButGeometryUnloaded = false;
            if (config.restrictNetherToHighways) {
                NetherHighwayFilter gate = ArgusUploaderClientMod.netherHighwayFilter();
                boolean anyNetherPresent = regions.stream().anyMatch(r -> r.dimension().equals("the_nether"));
                if (anyNetherPresent && !gate.isGeometryLoaded()) {
                    netherRestrictedButGeometryUnloaded = true;
                }
                List<RegionFile> filtered = new ArrayList<>();
                for (RegionFile r : regions) {
                    if (!r.dimension().equals("the_nether") || gate.regionAllowed(r)) {
                        filtered.add(r);
                    } else {
                        rejectedOffHighway++;
                    }
                }
                regions = filtered;
            }

            return new Resolved(root, regions, scanResult.rejectedOutOfRange(), rejectedOffHighway, netherRestrictedButGeometryUnloaded);
        } catch (IOException e) {
            error(source, "Scan failed: " + e.getMessage());
            return null;
        }
    }

    // Upload progress callbacks fire from UploadRunner's background executor
    // thread, not the client thread; sendFeedback() touches chat HUD state
    // that isn't safe to mutate concurrently, so always marshal onto the
    // client thread. MinecraftClient.execute() runs inline if already there
    // (true for every direct command handler), so this is free in that case.
    private static void info(FabricClientCommandSource source, String message) {
        MinecraftClient.getInstance().execute(() -> source.sendFeedback(Text.literal("[ARGUS] " + message)));
    }

    private static void error(FabricClientCommandSource source, String message) {
        MinecraftClient.getInstance().execute(() -> source.sendFeedback(Text.literal("[ARGUS] " + message).formatted(Formatting.RED)));
    }

    private static final class ChatProgressListener implements UploadProgressListener {
        private final FabricClientCommandSource source;
        private final UploadManifest manifest;

        ChatProgressListener(FabricClientCommandSource source, UploadManifest manifest) {
            this.source = source;
            this.manifest = manifest;
        }

        @Override
        public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int toUpload) {
            info(source, "Found " + totalFound + " region(s): " + alreadyUploaded + " already uploaded, "
                    + tooLarge + " over the size limit, " + toUpload + " to upload.");
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
            ArgusConfig config = ArgusUploaderClientMod.config();
            if (config.autoReportToDiscord && !config.discordWebhookUrl.isBlank() && succeeded > 0) {
                sendDiscordReport(source, manifest);
            }
            // ArgusMapperEvents promises client-thread delivery (see its javadoc); onComplete
            // itself runs on UploadRunner's background executor, so marshal over.
            MinecraftClient.getInstance().execute(() -> {
                ArgusMapperEvents.UPLOAD_COMPLETED.invoker().onUploadCompleted(new UploadSummary(succeeded, failed));
                ArgusMapperEvents.STATS_CHANGED.invoker().onStatsChanged(
                        MapperStats.of(manifest.size(), ArgusUploaderClientMod.stats().totalDistanceBlocks));
            });
        }
    }
}
