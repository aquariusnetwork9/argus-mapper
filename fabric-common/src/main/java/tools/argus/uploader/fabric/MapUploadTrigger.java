package tools.argus.uploader.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ArgusUploadClient;
import tools.argus.uploader.core.BlackzoneStore;
import tools.argus.uploader.core.RegionBounds;
import tools.argus.uploader.core.RegionFile;
import tools.argus.uploader.core.UploadHold;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadProgressListener;
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.UploadTracker;

import java.io.IOException;
import java.util.List;

/**
 * The map-side counterpart to {@code /argus upload}: a Xaero World Map right-click drag-selection
 * ("Upload This Area") resolves to a {@link Preview} of exactly what that selection would send,
 * a confirm popup shows it to the player, and only on "yes" does a real background
 * {@link UploadRunner} start - the same run type the Uploads tab, blackzone enforcement and
 * Discord auto-report already cover for {@code /argus upload}, so uploading via the map is never
 * a functionally different (and potentially less safe) path than uploading via chat.
 */
public final class MapUploadTrigger {

    private MapUploadTrigger() {
    }

    /** What a selection currently resolves to, before the player has confirmed anything - nothing
     *  is uploaded just by computing this. */
    public record Preview(List<RegionFile> regions, long totalBytes, String error) {

        public boolean isError() {
            return error != null;
        }

        private static Preview error(String message) {
            return new Preview(List.of(), 0, message);
        }
    }

    /** Resolves the same eligible-region list {@code /argus upload} would (blackzones, nether
     *  highway restriction and coordinate limits all already applied by {@link RegionResolver}),
     *  narrowed to just the region files inside {@code bounds}. */
    public static Preview preview(String dimension, RegionBounds bounds) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (!config.isUsable()) {
            return Preview.error("Config incomplete - set a token and layer first (/argus settoken, /argus setlayer).");
        }
        UploadRunner existing = ArgusUploaderClientMod.activeRunner();
        if (existing != null && existing.isRunning()) {
            return Preview.error("A run is already in progress. Use /argus status or /argus cancel.");
        }
        RegionResolver.Resolved resolved = RegionResolver.resolve(dimension);
        if (resolved.isError()) {
            return Preview.error(resolved.error());
        }
        UploadManifest manifest;
        try {
            manifest = UploadManifest.load(ArgusUploaderClientMod.manifestPath());
        } catch (IOException e) {
            return Preview.error("Couldn't read the upload manifest: " + e.getMessage());
        }
        // Counted the same way UploadRunner will, so the confirm popup states what will really be
        // sent - not regions the run is about to skip as already uploaded.
        List<RegionFile> scoped = resolved.regions().stream()
                .filter(bounds::contains)
                .filter(region -> manifest.needsUpload(region, config.reuploadChangedRegions))
                .filter(region -> !UploadHold.isHeld(region))
                .toList();
        long totalBytes = scoped.stream().mapToLong(RegionFile::sizeBytes).sum();
        return new Preview(scoped, totalBytes, null);
    }

    /**
     * Shows a confirm popup over {@code previousScreen} describing {@code preview}. Answering
     * either way returns to {@code previousScreen}; only "yes" starts the upload. This is the only
     * place a map-triggered run actually begins - {@link #preview} never uploads anything on its
     * own.
     */
    public static void confirmAndUpload(Screen previousScreen, String dimension, Preview preview) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (preview.isError()) {
            feedback(client, preview.error());
            return;
        }
        if (preview.regions().isEmpty()) {
            feedback(client, "Nothing to upload in that selection - already uploaded, blackzoned, too large, or empty.");
            return;
        }
        String message = preview.regions().size() + " region file(s), " + (preview.totalBytes() / 1_000_000)
                + " MB, dimension " + dimension + ". This uploads to the ARGUS API and cannot be undone.";
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    client.setScreen(previousScreen);
                    if (confirmed) {
                        startUpload(preview);
                    }
                },
                Text.literal("Upload " + preview.regions().size() + " region(s) to ARGUS?"),
                Text.literal(message)));
    }

    private static void startUpload(Preview preview) {
        MinecraftClient client = MinecraftClient.getInstance();
        UploadRunner existing = ArgusUploaderClientMod.activeRunner();
        if (existing != null && existing.isRunning()) {
            // Only reachable if another run (chat or a second map selection) started in the
            // window between preview() and this confirm click - same guard as ArgusCommand.upload().
            feedback(client, "A run is already in progress. Use /argus status or /argus cancel.");
            return;
        }
        ArgusConfig config = ArgusUploaderClientMod.config();
        try {
            UploadManifest manifest = UploadManifest.load(ArgusUploaderClientMod.manifestPath());
            BlackzoneStore blackzones = ArgusUploaderClientMod.blackzoneStore();
            ArgusUploadClient uploadClient = new ArgusUploadClient(config, blackzones);
            UploadTracker tracker = new UploadTracker(new MapChatProgressListener(client, manifest));
            ArgusUploaderClientMod.setActiveUpload(tracker);
            UploadRunner runner = new UploadRunner(uploadClient, config, manifest, tracker);
            ArgusUploaderClientMod.setActiveRunner(runner);
            String runId = "map-run-" + System.currentTimeMillis();
            // Checked here too (not just inside ArgusUploadClient.upload()) - same double
            // enforcement as ArgusCommand.upload() and for the same reason.
            runner.start(preview.regions(), runId, region -> !blackzones.isBlackzoned(region.dimension(), config.layer, region));
        } catch (IOException e) {
            feedback(client, "Failed to start upload: " + e.getMessage());
        }
    }

    private static void feedback(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[ARGUS] " + message), false);
        }
    }

    /** {@link UploadProgressListener} for a map-triggered run: no {@code FabricClientCommandSource}
     *  exists here (this isn't a command), so feedback goes straight to the player's chat, same as
     *  {@code MixinGuiMap}'s own feedback messages. Completion behavior (Discord auto-report, the
     *  STATS_CHANGED/UPLOAD_COMPLETED events) is shared with {@code /argus upload} via
     *  {@link ArgusCommand#onUploadRunComplete} so the two trigger paths can't drift apart. */
    private static final class MapChatProgressListener implements UploadProgressListener {
        private final MinecraftClient client;
        private final UploadManifest manifest;

        MapChatProgressListener(MinecraftClient client, UploadManifest manifest) {
            this.client = client;
            this.manifest = manifest;
        }

        @Override
        public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload) {
            feedback(client, "Starting upload of " + toUpload + " region(s) from the selected area ("
                    + alreadyUploaded + " already uploaded, " + tooLarge + " over the size limit, "
                    + excludedByBlackzone + " excluded by blackzone).");
        }

        @Override
        public void onHeldForMapping(int held) {
            feedback(client, held + " region(s) are still being auto-mapped, so they were left out.");
        }

        @Override
        public void onRegionUploaded(RegionFile region, int done, int total) {
            if (done % 10 == 0 || done == total) {
                feedback(client, "Uploaded " + done + " / " + total + " (" + region.filename() + ", " + region.dimension() + ")");
            }
        }

        @Override
        public void onRegionFailed(RegionFile region, String reason, int done, int total) {
            feedback(client, "Failed " + region.filename() + " (" + region.dimension() + "): " + reason);
        }

        @Override
        public void onNotice(String message) {
            feedback(client, message);
        }

        @Override
        public void onFatalError(String message) {
            feedback(client, message);
        }

        @Override
        public void onComplete(int succeeded, int failed) {
            feedback(client, "Upload run finished: " + succeeded + " ok, " + failed + " failed.");
            ArgusCommand.onUploadRunComplete(manifest, succeeded, failed, (message, isError) -> feedback(client, message));
        }
    }
}
