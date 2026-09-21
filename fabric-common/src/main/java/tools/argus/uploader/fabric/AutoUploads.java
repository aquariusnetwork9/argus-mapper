package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ArgusUploadClient;
import tools.argus.uploader.core.AutoUploadCoordinator;
import tools.argus.uploader.core.AutoUploadSelection;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.BlackzoneStore;
import tools.argus.uploader.core.CoordLimits;
import tools.argus.uploader.core.RegionFile;
import tools.argus.uploader.core.TeleportBlackzones;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadProgressListener;
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.UploadTracker;
import tools.argus.uploader.fabric.gui.AutoBlackzoneScreen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The two per-session upload switches: live upload (automatic, on a random 3-10 minute delay) and
 * whole-map upload (lifts the region distance limit). Both are in-memory only - never written to
 * config - so both are off after a restart, a crash, or leaving the server. Each needs its own
 * confirm popup to turn on. Live upload pauses when a teleport lands outside the default upload
 * area and asks before resuming.
 */
public final class AutoUploads {

    public enum Kind { LIVE, WHOLE_MAP }

    private static final String LIVE_BODY = "ARGUS Mapper will upload the regions you explore from now on, "
            + "automatically, about every 3-10 minutes (random), until you turn it off, disconnect or close "
            + "the game. A changed region replaces its older copy. "
            + "Regions still being written to wait until they have been untouched for 10 minutes. "
            + "Blackzones still apply.\n\n"
            + "If you teleport (128+ blocks in one step, or a dimension change) and land outside that "
            + "upload area, it pauses, blackzones about " + TeleportBlackzones.RADIUS_REGIONS * 512
            + " blocks around where you landed in the Overworld and Nether, and asks you to keep, cancel "
            + "or modify that blackzone before it can resume. Teleports inside the area change nothing.";

    private static final String WHOLE_MAP_BODY = "Uploads are normally limited to within "
            + CoordLimits.MAX_ABS_REGION + " regions (about " + CoordLimits.MAX_ABS_REGION * 512
            + " blocks) of the world origin. This lifts that limit, so regions anywhere on the map - "
            + "including far-away bases - can be uploaded.\n\nBlackzones still apply. It turns itself off "
            + "when you disconnect or close the game.";

    private static final AutoUploadCoordinator coordinator = new AutoUploadCoordinator(new HostImpl(), new Random());

    private static volatile Kind pendingEnable;
    private static volatile AutoUploadCoordinator.Teleport pendingTeleport;
    private static volatile List<BlackZone> arrivalZones = List.of();
    private static volatile boolean awaitingMapClose;
    private static volatile UploadRunner autoRunner;
    private static volatile String enabledLayer;

    private AutoUploads() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoUploads::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetAll());
    }

    public static boolean isLive() {
        return coordinator.isLive();
    }

    public static boolean isPaused() {
        return coordinator.isPaused();
    }

    public static boolean isWholeMap() {
        return CoordLimits.isLifted();
    }

    public static String liveStatus() {
        return coordinator.statusLine(System.currentTimeMillis());
    }

    /** Shows the confirm popup over {@code previous}; {@code afterAnswered} runs on either answer. */
    public static void requestEnable(Kind kind, Screen previous, Runnable afterAnswered) {
        showEnablePopup(MinecraftClient.getInstance(), kind, previous, afterAnswered);
    }

    /** Chat-command entry point: the popup opens on the next tick, after chat has closed. */
    public static void requestEnableFromCommand(Kind kind) {
        pendingEnable = kind;
    }

    public static void disable(Kind kind) {
        if (kind == Kind.LIVE) {
            coordinator.setLive(false, System.currentTimeMillis());
            enabledLayer = null;
        } else {
            CoordLimits.setLifted(false);
        }
    }

    public static void cancelLiveRun() {
        UploadRunner runner = autoRunner;
        if (runner != null && runner.isRunning()) {
            runner.cancel();
        }
    }

    public static void resumeLive() {
        coordinator.resume(System.currentTimeMillis());
    }

    private static void resetAll() {
        coordinator.reset();
        CoordLimits.setLifted(false);
        enabledLayer = null;
        pendingEnable = null;
        pendingTeleport = null;
        arrivalZones = List.of();
        awaitingMapClose = false;
    }

    private static void onTick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (client.player == null || client.world == null) {
            coordinator.onPlayerGone();
        } else {
            coordinator.onPlayerPosition(dimensionOf(client.world),
                    client.player.getX(), client.player.getY(), client.player.getZ(), now);
        }
        coordinator.tick(now);

        Kind kind = pendingEnable;
        if (kind != null) {
            pendingEnable = null;
            showEnablePopup(client, kind, client.currentScreen, () -> {
            });
        }
        if (awaitingMapClose && client.currentScreen == null) {
            awaitingMapClose = false;
            showResumePrompt(client);
        }
        AutoUploadCoordinator.Teleport teleport = pendingTeleport;
        if (teleport != null && client.currentScreen == null) {
            pendingTeleport = null;
            showBlackzonePrompt(client, teleport);
        }
    }

    static String dimensionOf(ClientWorld world) {
        RegistryKey<World> key = world.getRegistryKey();
        if (key == World.OVERWORLD) {
            return "overworld";
        }
        if (key == World.NETHER) {
            return "the_nether";
        }
        if (key == World.END) {
            return "theend";
        }
        return key.toString();
    }

    // ------------------------------------------------------------ enabling

    private static void showEnablePopup(MinecraftClient client, Kind kind, Screen previous, Runnable afterAnswered) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (!config.isUsable()) {
            feedback(client, "Set a token and layer first (/argus settoken, /argus setlayer).");
            afterAnswered.run();
            return;
        }
        boolean alreadyOn = kind == Kind.LIVE ? coordinator.isLive() : CoordLimits.isLifted();
        if (alreadyOn) {
            feedback(client, (kind == Kind.LIVE ? "Live" : "Whole-map") + " upload is already on.");
            afterAnswered.run();
            return;
        }
        String title = kind == Kind.LIVE ? "Turn on live upload?" : "Turn on whole-map upload?";
        String body = kind == Kind.LIVE ? LIVE_BODY : WHOLE_MAP_BODY;
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    client.setScreen(previous);
                    if (confirmed) {
                        enable(client, kind);
                    }
                    afterAnswered.run();
                },
                Text.literal(title), Text.literal(body), Text.literal("Turn on"), Text.literal("Cancel")));
    }

    private static void enable(MinecraftClient client, Kind kind) {
        if (kind == Kind.WHOLE_MAP) {
            CoordLimits.setLifted(true);
            feedback(client, "Whole-map upload is on: the distance limit is lifted until you turn it off, disconnect or close the game.");
            return;
        }
        RegionResolver.Resolved resolved = RegionResolver.resolve(null);
        if (resolved.isError()) {
            feedback(client, "Can't turn on live upload: " + firstLine(resolved.error()));
            return;
        }
        try {
            // Baseline what's already on disk so only saves made from now on count as changes.
            UploadManifest.load(ArgusUploaderClientMod.manifestPath()).adoptBaselines(resolved.regions());
        } catch (IOException ignored) {
            // Best-effort: without a baseline an old region just won't be re-sent until it is.
        }
        enabledLayer = ArgusUploaderClientMod.config().layer;
        coordinator.setLive(true, System.currentTimeMillis());
        feedback(client, "Live upload is on. The first upload is 3-10 minutes from now, then on a random delay.");
    }

    // ------------------------------------------------------------ cycles

    private static void startCycle(long liveSinceMillis) {
        MinecraftClient client = MinecraftClient.getInstance();
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (!config.isUsable() || !config.layer.equals(enabledLayer)) {
            feedback(client, "Live upload turned itself off: the token or server layer changed.");
            resetAll();
            return;
        }
        RegionResolver.Resolved resolved = RegionResolver.resolve(null);
        if (resolved.isError()) {
            feedback(client, "Live upload skipped this time: " + firstLine(resolved.error()));
            return;
        }
        try {
            UploadManifest manifest = UploadManifest.load(ArgusUploaderClientMod.manifestPath());
            AutoUploadSelection.Selection selection = AutoUploadSelection.select(
                    resolved.regions(), manifest, liveSinceMillis, System.currentTimeMillis());
            if (selection.ready().isEmpty()) {
                feedback(client, selection.held() == 0
                        ? "Live upload: nothing new to send."
                        : "Live upload: " + selection.held() + " region(s) are still being mapped, so they wait for the next upload.");
                return;
            }
            BlackzoneStore blackzones = ArgusUploaderClientMod.blackzoneStore();
            ArgusUploadClient uploadClient = new ArgusUploadClient(config, blackzones);
            UploadTracker tracker = new UploadTracker(new LiveProgressListener(client, manifest, selection.held()));
            ArgusUploaderClientMod.setActiveUpload(tracker);
            UploadRunner runner = new UploadRunner(uploadClient, config, manifest, tracker);
            ArgusUploaderClientMod.setActiveRunner(runner);
            autoRunner = runner;
            runner.setReadyCheck(region -> AutoUploadSelection.isStillQuiet(region, System.currentTimeMillis()));
            runner.start(selection.ready(), "live-run-" + System.currentTimeMillis(),
                    region -> !blackzones.isBlackzoned(region.dimension(), config.layer, region), true);
        } catch (IOException e) {
            feedback(client, "Live upload failed to start: " + e.getMessage());
        }
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    // ------------------------------------------------------------ teleport prompts

    private static boolean protectArrival(AutoUploadCoordinator.Teleport teleport) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        BlackzoneStore store = ArgusUploaderClientMod.blackzoneStore();
        int regionX = (int) Math.floor(teleport.x()) >> 9;
        int regionZ = (int) Math.floor(teleport.z()) >> 9;
        for (BlackZone zone : store.forServer(teleport.dimension(), config.layer)) {
            if (zone.bounds().contains(regionX, regionZ)) {
                return false;
            }
        }
        List<BlackZone> made = new ArrayList<>(arrivalZones);
        for (BlackZone zone : TeleportBlackzones.plan(teleport.dimension(), teleport.x(), teleport.z(),
                config.layer, "auto-" + System.currentTimeMillis())) {
            try {
                store.addOrReplace(zone);
                made.add(zone);
            } catch (IOException e) {
                feedback(MinecraftClient.getInstance(), "Failed to save blackzone: " + e.getMessage());
            }
        }
        arrivalZones = made;
        return true;
    }

    private static void showBlackzonePrompt(MinecraftClient client, AutoUploadCoordinator.Teleport teleport) {
        List<BlackZone> zones = arrivalZones;
        arrivalZones = List.of();
        if (zones.isEmpty()) {
            showResumePrompt(client);
            return;
        }
        client.setScreen(new AutoBlackzoneScreen(
                "Teleported outside the upload area",
                arrivalBody(teleport, zones),
                choice -> {
                    switch (choice) {
                        case OK -> showResumePrompt(client);
                        case CANCEL -> {
                            removeArrivalZones(client, zones);
                            showResumePrompt(client);
                        }
                        case MODIFY -> modifyOnMap(client);
                    }
                }));
    }

    private static String arrivalBody(AutoUploadCoordinator.Teleport teleport, List<BlackZone> zones) {
        StringBuilder where = new StringBuilder();
        for (BlackZone zone : zones) {
            if (!where.isEmpty()) {
                where.append(" and ");
            }
            where.append(dimensionName(zone.dimension()));
        }
        return "You landed at " + Math.round(teleport.x()) + ", " + Math.round(teleport.z()) + " in "
                + dimensionName(teleport.dimension()) + ", outside the area uploads normally cover, so live "
                + "upload is paused.\n\nARGUS blackzoned about "
                + String.format("%,d", TeleportBlackzones.RADIUS_REGIONS * 512) + " blocks in every direction "
                + "from there in " + where + ". Nothing inside it will be uploaded.\n\n"
                + "OK keeps it. Cancel removes it. Modify opens Xaero's World Map: drag a box, right-click "
                + "it and pick Mark Blackzone.";
    }

    private static String dimensionName(String dimension) {
        return switch (dimension) {
            case "the_nether" -> "the Nether";
            case "theend" -> "the End";
            default -> "the Overworld";
        };
    }

    private static void removeArrivalZones(MinecraftClient client, List<BlackZone> zones) {
        BlackzoneStore store = ArgusUploaderClientMod.blackzoneStore();
        try {
            for (BlackZone zone : zones) {
                store.remove(zone.id());
            }
            feedback(client, "Removed the automatic blackzone.");
        } catch (IOException e) {
            feedback(client, "Failed to remove the blackzone: " + e.getMessage());
        }
    }

    private static void modifyOnMap(MinecraftClient client) {
        if (!openXaeroMap(client)) {
            feedback(client, "Xaero's World Map isn't available, so the automatic blackzone was kept. "
                    + "Change it from the GUI's Blackzones tab.");
            showResumePrompt(client);
            return;
        }
        awaitingMapClose = true;
        feedback(client, "Drag a box over the area, right-click it and pick Mark Blackzone; right-click a "
                + "blackzone to remove it. Close the map when you're done.");
    }

    private static boolean openXaeroMap(MinecraftClient client) {
        if (!FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
            return false;
        }
        try {
            return XaeroMapOpener.open(client);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static void showResumePrompt(MinecraftClient client) {
        client.setScreen(new ConfirmScreen(
                resume -> {
                    client.setScreen(null);
                    if (resume) {
                        coordinator.resume(System.currentTimeMillis());
                        feedback(client, "Live upload resumed. The next upload is on a fresh random delay.");
                    } else {
                        coordinator.promptFinished();
                        feedback(client, "Live upload stays paused. Use /argus live resume when you're ready.");
                    }
                },
                Text.literal("Resume live upload?"),
                Text.literal("It stays paused until you say so."),
                Text.literal("Resume"), Text.literal("Stay paused")));
    }

    // ------------------------------------------------------------ plumbing

    private static void feedback(MinecraftClient client, String message) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[ARGUS] " + message), false);
            }
        });
    }

    private static final class HostImpl implements AutoUploadCoordinator.Host {

        @Override
        public boolean isUploadRunning() {
            UploadRunner runner = ArgusUploaderClientMod.activeRunner();
            return runner != null && runner.isRunning();
        }

        @Override
        public void startCycle(long liveSinceMillis) {
            AutoUploads.startCycle(liveSinceMillis);
        }

        @Override
        public void cancelAutoRun() {
            UploadRunner runner = autoRunner;
            if (runner != null && runner.isRunning()) {
                runner.cancel();
            }
        }

        @Override
        public boolean protectArrival(AutoUploadCoordinator.Teleport teleport) {
            return AutoUploads.protectArrival(teleport);
        }

        @Override
        public void promptTeleport(AutoUploadCoordinator.Teleport teleport) {
            pendingTeleport = teleport;
        }
    }

    private static final class LiveProgressListener implements UploadProgressListener {
        private final MinecraftClient client;
        private final UploadManifest manifest;
        private final int heldAtStart;
        private final AtomicInteger heldLater = new AtomicInteger();

        LiveProgressListener(MinecraftClient client, UploadManifest manifest, int heldAtStart) {
            this.client = client;
            this.manifest = manifest;
            this.heldAtStart = heldAtStart;
        }

        @Override
        public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload) {
            feedback(client, "Live upload: sending " + toUpload + " region(s)"
                    + (heldAtStart > 0 ? ", holding back " + heldAtStart + " still being mapped." : "."));
        }

        @Override
        public void onRegionDeferred(RegionFile region) {
            heldLater.incrementAndGet();
        }

        @Override
        public void onHeldForMapping(int held) {
            feedback(client, "Live upload: " + held + " region(s) are still being auto-mapped, so they were left out.");
        }

        @Override
        public void onRegionUploaded(RegionFile region, int done, int total) {
        }

        @Override
        public void onRegionFailed(RegionFile region, String reason, int done, int total) {
            feedback(client, "Live upload failed " + region.filename() + " (" + region.dimension() + "): " + reason);
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
            int held = heldAtStart + heldLater.get();
            feedback(client, "Live upload finished: " + succeeded + " ok, " + failed + " failed"
                    + (held > 0 ? ", " + held + " held for next time." : "."));
            ArgusCommand.onUploadRunComplete(manifest, succeeded, failed, (message, isError) -> feedback(client, message));
        }
    }
}
