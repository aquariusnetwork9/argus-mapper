package tools.argus.uploader.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.XaeroRootFinder;
import tools.argus.uploader.core.XaeroScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * BEST-EFFORT / UNVERIFIED — see 26.1/NOTES.md. Only
 * {@code net.minecraft.text.Text}/{@code Formatting} are referenced from
 * vanilla here (for chat feedback); if those names moved under Mojang
 * mappings too, this is the file to fix alongside GameWorldContext.java.
 *
 * <p>Unlike fabric-common's copy, {@code info}/{@code error} here do NOT
 * marshal onto the client thread before calling sendFeedback - that fix
 * needs a MinecraftClient reference, which this module deliberately avoids
 * (see class javadoc). Once the real 26.1 mapping for that class is known,
 * port fabric-common's version of these two methods over.
 */
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
                        .then(literal("report").executes(ctx -> discordReport(ctx.getSource())))));
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
            info(source, "  skipped " + resolved.rejectedOffHighway + " nether region(s): this build has no verified way to check ARD highway-adjacency yet (see 26.1/NOTES.md), so nether uploads are excluded entirely while restrictNetherToHighways is on.");
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

    private static int serverDetect(FabricClientCommandSource source) {
        String address = GameWorldContext.currentWorldToken();
        if (address.isBlank()) {
            error(source, "Auto-detect isn't available in this build (see 26.1/NOTES.md). Use /argus server use <id> or /argus setlayer <layer> directly.");
            return 0;
        }
        List<ServerProfile> matches = ArgusUploaderClientMod.registry().matching(address);
        if (matches.isEmpty()) {
            info(source, "No known ARGUS server matches '" + address + "'.");
            return 1;
        }
        if (matches.size() > 1) {
            error(source, "Multiple known servers match '" + address + "', pick one with /argus server use <id>.");
            return 0;
        }
        return serverUse(source, matches.get(0).name());
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
                sendDiscordReport(source, manifest);
            } catch (IOException e) {
                error(source, "Failed to read manifest: " + e.getMessage());
            }
        });
        return 1;
    }

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
        info(source, "restrictNetherToHighways=" + c.restrictNetherToHighways + " (fail-closed: excludes ALL nether regions in this build - see 26.1/NOTES.md)");
        return 1;
    }

    private record Resolved(Path root, List<RegionFile> regions, int rejectedOutOfRange, int rejectedOffHighway) {
    }

    private static Resolved resolveRoot(FabricClientCommandSource source, String dimensionFilter) {
        if (dimensionFilter != null && !VALID_DIMENSIONS.contains(dimensionFilter)) {
            error(source, "Unknown dimension '" + dimensionFilter + "'. Valid: overworld, the_nether, theend.");
            return null;
        }
        ArgusConfig config = ArgusUploaderClientMod.config();
        try {
            Path root;
            if (!config.xaeroRootOverride.isBlank()) {
                root = Path.of(config.xaeroRootOverride);
            } else {
                String token = GameWorldContext.currentWorldToken();
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

            // Unlike the 1.21.x builds, this module doesn't bundle ARD's geometry fetch (see
            // 26.1/NOTES.md), so there's no verified way to check nether highway-adjacency here.
            // Fail closed the same way an unloaded-geometry case does elsewhere: exclude ALL
            // nether regions rather than upload them unfiltered.
            int rejectedOffHighway = 0;
            if (config.restrictNetherToHighways) {
                List<RegionFile> filtered = new ArrayList<>();
                for (RegionFile r : regions) {
                    if (!r.dimension().equals("the_nether")) {
                        filtered.add(r);
                    } else {
                        rejectedOffHighway++;
                    }
                }
                regions = filtered;
            }

            return new Resolved(root, regions, scanResult.rejectedOutOfRange(), rejectedOffHighway);
        } catch (IOException e) {
            error(source, "Scan failed: " + e.getMessage());
            return null;
        }
    }

    private static void info(FabricClientCommandSource source, String message) {
        source.sendFeedback(Text.literal("[ARGUS] " + message));
    }

    private static void error(FabricClientCommandSource source, String message) {
        source.sendFeedback(Text.literal("[ARGUS] " + message).formatted(Formatting.RED));
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
        }
    }
}
