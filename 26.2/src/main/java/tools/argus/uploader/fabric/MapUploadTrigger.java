package tools.argus.uploader.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ArgusUploadClient;
import tools.argus.uploader.core.BlackzoneStore;
import tools.argus.uploader.core.RegionBounds;
import tools.argus.uploader.core.RegionFile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadProgressListener;
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.UploadTracker;

import java.io.IOException;
import java.util.List;

/**
 * 26.2's copy of fabric-common's MapUploadTrigger. Real vanilla touchpoints verified via javap:
 * {@code MinecraftClient} -&gt; {@code Minecraft}, {@code Text} -&gt; {@code Component},
 * {@code ConfirmScreen}/{@code Screen} kept their names, just under the pluralized
 * {@code net.minecraft.client.gui.screens} package, and {@code Text.literal(...)} ->
 * {@code Component.literal(...)}, {@code player.sendMessage(Text, boolean)} -&gt;
 * {@code player.sendSystemMessage(Component)} (the overlay-vs-chat distinction is now two
 * separate methods rather than a boolean flag - see GameWorldContext/ArgusCommand for the same
 * rename). See fabric-common's copy for the full design rationale.
 */
public final class MapUploadTrigger {

    private MapUploadTrigger() {
    }

    public record Preview(List<RegionFile> regions, long totalBytes, String error) {

        public boolean isError() {
            return error != null;
        }

        private static Preview error(String message) {
            return new Preview(List.of(), 0, message);
        }
    }

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
                .toList();
        long totalBytes = scoped.stream().mapToLong(RegionFile::sizeBytes).sum();
        return new Preview(scoped, totalBytes, null);
    }

    public static void confirmAndUpload(Screen previousScreen, String dimension, Preview preview) {
        Minecraft client = Minecraft.getInstance();
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
        client.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    client.setScreenAndShow(previousScreen);
                    if (confirmed) {
                        startUpload(preview);
                    }
                },
                Component.literal("Upload " + preview.regions().size() + " region(s) to ARGUS?"),
                Component.literal(message)));
    }

    private static void startUpload(Preview preview) {
        Minecraft client = Minecraft.getInstance();
        UploadRunner existing = ArgusUploaderClientMod.activeRunner();
        if (existing != null && existing.isRunning()) {
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
            runner.start(preview.regions(), runId, region -> !blackzones.isBlackzoned(region.dimension(), config.layer, region));
        } catch (IOException e) {
            feedback(client, "Failed to start upload: " + e.getMessage());
        }
    }

    private static void feedback(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[ARGUS] " + message));
        }
    }

    private static final class MapChatProgressListener implements UploadProgressListener {
        private final Minecraft client;
        private final UploadManifest manifest;

        MapChatProgressListener(Minecraft client, UploadManifest manifest) {
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
