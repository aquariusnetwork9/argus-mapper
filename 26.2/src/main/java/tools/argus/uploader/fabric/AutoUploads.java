package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ArgusUploadClient;
import tools.argus.uploader.core.AutoUploadCoordinator;
import tools.argus.uploader.core.AutoUploadSelection;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.BlackzoneStore;
import tools.argus.uploader.core.CoordLimits;
import tools.argus.uploader.core.RegionBounds;
import tools.argus.uploader.core.RegionFile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadProgressListener;
import tools.argus.uploader.core.UploadRunner;
import tools.argus.uploader.core.UploadTracker;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 26.2's copy of fabric-common's AutoUploads - see that file for the design. Vanilla touchpoints
 * verified via javap on the real 26.2 jar: {@code Minecraft.level}/{@code player} (public fields),
 * {@code Level.dimension()} and {@code Level.OVERWORLD/NETHER/END}, {@code Entity.getX/Y/Z()},
 * {@code Minecraft.gui.screen()} for the open screen, {@code ConfirmScreen}'s 5-argument
 * constructor and {@code setScreenAndShow}; the Fabric message event's {@code Game} callback is
 * {@code (Component, boolean)}.
 */
public final class AutoUploads {

    public enum Kind { LIVE, WHOLE_MAP }

    private static final Set<String> BLACKZONE_DIMENSIONS = Set.of("overworld", "the_nether", "theend");

    private static final String LIVE_BODY = "ARGUS Mapper will upload the regions you explore from now on, "
            + "automatically, about every 45-80 minutes (random), until you turn it off, disconnect or close "
            + "the game. A changed region replaces its older copy. "
            + "Regions still being written to wait until they have been untouched for 10 minutes. "
            + "Blackzones still apply.\n\n"
            + "It pauses at once if you teleport (128+ blocks, a dimension change, or a server teleport "
            + "countdown). Once you've arrived and settled, it asks whether to blackzone the spot and "
            + "whether to resume.";

    private static final String WHOLE_MAP_BODY = "Uploads are normally limited to within "
            + CoordLimits.MAX_ABS_REGION + " regions (about " + CoordLimits.MAX_ABS_REGION * 512
            + " blocks) of the world origin. This lifts that limit, so regions anywhere on the map - "
            + "including far-away bases - can be uploaded.\n\nBlackzones still apply. It turns itself off "
            + "when you disconnect or close the game.";

    private static final AutoUploadCoordinator coordinator = new AutoUploadCoordinator(new HostImpl(), new Random());

    private static volatile Kind pendingEnable;
    private static volatile AutoUploadCoordinator.Teleport pendingTeleport;
    private static volatile UploadRunner autoRunner;
    private static volatile String enabledLayer;

    private AutoUploads() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoUploads::onTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                coordinator.onChatMessage(message.getString(), System.currentTimeMillis());
            }
        });
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

    public static void requestEnable(Kind kind, Screen previous, Runnable afterAnswered) {
        showEnablePopup(Minecraft.getInstance(), kind, previous, afterAnswered);
    }

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

    public static void resumeLive() {
        coordinator.resume(System.currentTimeMillis());
    }

    private static void resetAll() {
        coordinator.reset();
        CoordLimits.setLifted(false);
        enabledLayer = null;
        pendingEnable = null;
        pendingTeleport = null;
    }

    private static void onTick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (client.player == null || client.level == null) {
            coordinator.onPlayerGone();
        } else {
            coordinator.onPlayerPosition(dimensionOf(client.level),
                    client.player.getX(), client.player.getY(), client.player.getZ(), now);
        }
        coordinator.tick(now);

        Kind kind = pendingEnable;
        if (kind != null) {
            pendingEnable = null;
            showEnablePopup(client, kind, null, () -> {
            });
        }
        AutoUploadCoordinator.Teleport teleport = pendingTeleport;
        if (teleport != null && client.gui.screen() == null) {
            pendingTeleport = null;
            showBlackzonePrompt(client, teleport);
        }
    }

    private static String dimensionOf(ClientLevel level) {
        ResourceKey<Level> key = level.dimension();
        if (key == Level.OVERWORLD) {
            return "overworld";
        }
        if (key == Level.NETHER) {
            return "the_nether";
        }
        if (key == Level.END) {
            return "theend";
        }
        return key.toString();
    }

    // ------------------------------------------------------------ enabling

    private static void showEnablePopup(Minecraft client, Kind kind, Screen previous, Runnable afterAnswered) {
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
        client.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    client.setScreenAndShow(previous);
                    if (confirmed) {
                        enable(client, kind);
                    }
                    afterAnswered.run();
                },
                Component.literal(title), Component.literal(body),
                Component.literal("Turn on"), Component.literal("Cancel")));
    }

    private static void enable(Minecraft client, Kind kind) {
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
            UploadManifest.load(ArgusUploaderClientMod.manifestPath()).adoptBaselines(resolved.regions());
        } catch (IOException ignored) {
            // Best-effort: without a baseline an old region just won't be re-sent until it is.
        }
        enabledLayer = ArgusUploaderClientMod.config().layer;
        coordinator.setLive(true, System.currentTimeMillis());
        feedback(client, "Live upload is on. The first upload is 45-80 minutes from now, then on a random delay.");
    }

    // ------------------------------------------------------------ cycles

    private static void startCycle(long liveSinceMillis) {
        Minecraft client = Minecraft.getInstance();
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

    private static void showBlackzonePrompt(Minecraft client, AutoUploadCoordinator.Teleport teleport) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        if (!BLACKZONE_DIMENSIONS.contains(teleport.dimension()) || config.layer.isBlank()) {
            showResumePrompt(client);
            return;
        }
        String what = switch (teleport.cause()) {
            case JUMP -> "You moved about " + Math.round(teleport.blocksMoved()) + " blocks in one step.";
            case DIMENSION_CHANGE -> "You changed dimension.";
            case ANNOUNCED -> "The server announced a teleport.";
        };
        client.setScreenAndShow(new ConfirmScreen(
                blackzoneHere -> {
                    if (blackzoneHere) {
                        createBlackzone(client, teleport);
                    }
                    showResumePrompt(client);
                },
                Component.literal("Teleport detected - live upload paused"),
                Component.literal(what + "\nBlackzone the area around where you are now (3x3 regions, about 1,500 "
                        + "blocks across)? Nothing in it will ever be uploaded."),
                Component.literal("Blackzone here"), Component.literal("Skip")));
    }

    private static void createBlackzone(Minecraft client, AutoUploadCoordinator.Teleport teleport) {
        int regionX = (int) Math.floor(teleport.x()) >> 9;
        int regionZ = (int) Math.floor(teleport.z()) >> 9;
        RegionBounds bounds = RegionBounds.ofCorners(regionX - 1, regionZ - 1, regionX + 1, regionZ + 1);
        try {
            ArgusUploaderClientMod.blackzoneStore().addOrReplace(new BlackZone(
                    "auto-" + System.currentTimeMillis(), teleport.dimension(),
                    ArgusUploaderClientMod.config().layer, bounds, "teleport arrival"));
            feedback(client, "Blackzone saved around you. Remove it later from the GUI's Blackzones tab.");
        } catch (IOException e) {
            feedback(client, "Failed to save blackzone: " + e.getMessage());
        }
    }

    private static void showResumePrompt(Minecraft client) {
        client.setScreenAndShow(new ConfirmScreen(
                resume -> {
                    client.setScreenAndShow(null);
                    if (resume) {
                        coordinator.resume(System.currentTimeMillis());
                        feedback(client, "Live upload resumed. The next upload is on a fresh random delay.");
                    } else {
                        coordinator.promptFinished();
                        feedback(client, "Live upload stays paused. Use /argus live resume when you're ready.");
                    }
                },
                Component.literal("Resume live upload?"),
                Component.literal("It stays paused until you say so."),
                Component.literal("Resume"), Component.literal("Stay paused")));
    }

    // ------------------------------------------------------------ plumbing

    private static void feedback(Minecraft client, String message) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("[ARGUS] " + message));
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
        public void promptTeleport(AutoUploadCoordinator.Teleport teleport) {
            pendingTeleport = teleport;
        }
    }

    private static final class LiveProgressListener implements UploadProgressListener {
        private final Minecraft client;
        private final UploadManifest manifest;
        private final int heldAtStart;
        private final AtomicInteger heldLater = new AtomicInteger();

        LiveProgressListener(Minecraft client, UploadManifest manifest, int heldAtStart) {
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
        public void onRegionUploaded(RegionFile region, int done, int total) {
        }

        @Override
        public void onRegionFailed(RegionFile region, String reason, int done, int total) {
            feedback(client, "Live upload failed " + region.filename() + " (" + region.dimension() + "): " + reason);
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
