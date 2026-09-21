package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.world.Heightmap;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.UploadHold;
import tools.argus.uploader.core.automap.AutoMapController;
import tools.argus.uploader.core.automap.CalibrationController;
import tools.argus.uploader.core.automap.CalibrationReport;
import tools.argus.uploader.core.automap.CalibrationSchedule;
import tools.argus.uploader.core.automap.ChunkBox;
import tools.argus.uploader.core.automap.CorridorOverlap;
import tools.argus.uploader.core.automap.FlightPlan;
import tools.argus.uploader.core.automap.LanePlanner;
import tools.argus.uploader.core.automap.LaneWidthModel;
import tools.argus.uploader.core.automap.MeteorElytra;
import tools.argus.uploader.core.automap.Waypoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Flies a box of the map for you: Meteor's Elytra Fly (Vanilla mode) does the flying, this steers
 * it and paces it to what the map is keeping up with. It is per session and never saved, needs its
 * own confirm popup, and stops the moment anything looks off (teleport, dimension change, damage,
 * no longer gliding, Elytra Fly switched off). See {@link AutoMapController} for the decisions.
 */
public final class AutoMapper {

    private static final int MIN_ELYTRA_DURABILITY = 20;
    private static final int XAERO_GRACE_TICKS = 160;
    private static final int HOLD_MARGIN_REGIONS = 1;

    private static volatile ChunkBox pendingBox;
    private static volatile String pendingCalibration;
    private static boolean calibrating;
    private static List<String> calibrationEnvironment = List.of();
    private static String calibrationFastMapping = "unknown";
    private static FlightPlan plan;
    private static MeteorElytra meteor;
    private static XaeroCoverage xaero;
    private static boolean xaeroUnreliable;
    private static boolean xaeroSeenWritten;
    private static double originalHorizontalSpeed;
    private static double lastSpeedSet;
    private static float lastHealth;
    private static long startedAtMillis;
    private static long ticksRunning;

    private AutoMapper() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(AutoMapper::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (plan != null) {
                end(client, "you disconnected");
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (plan != null) {
                end(client, "the game is closing");
            }
        });
    }

    public static boolean isRunning() {
        return plan != null;
    }

    public static String status() {
        FlightPlan current = plan;
        return current == null ? "Off" : current.statusLine() + " (uploads of the area held)";
    }

    /** Chat-command entry point: the popup opens on the next tick, after chat has closed. */
    public static void requestStartFromCommand(ChunkBox box) {
        pendingBox = box;
    }

    public static void stop(String reason) {
        if (plan != null) {
            end(MinecraftClient.getInstance(), reason);
        }
    }

    public static void requestStart(ChunkBox box, Screen previous) {
        MinecraftClient client = MinecraftClient.getInstance();
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, problem.get());
            return;
        }
        MeteorElytra elytra = MeteorElytra.find().orElseThrow();
        AutoMapController.Settings settings = settingsFor(client, elytra);
        ClientPlayerEntity player = client.player;
        List<Waypoint> path = LanePlanner.plan(box, player.getX(), player.getZ(), settings.halfWidthChunks());
        double minutes = LanePlanner.length(path, player.getX(), player.getZ()) / (settings.startSpeed() * 20) / 60;
        String dimension = AutoUploads.dimensionOf(client.world);

        String body = "Area: " + box.widthChunks() + " x " + box.depthChunks() + " chunks, about "
                + path.size() / 2 + " lanes, roughly " + Math.max(1, Math.round(minutes)) + " min at "
                + String.format("%.2f", settings.startSpeed()) + " blocks/tick.\n\n"
                + "This steers your character and changes Meteor's Elytra Fly speed while it runs, at about Y "
                + settings.cruiseY() + ". It slows down when the map can't keep up and stops if you teleport, "
                + "change dimension, take damage, stop gliding or turn Elytra Fly off. "
                + "/argus automap stop cancels it.";
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    client.setScreen(confirmed ? null : previous);
                    if (confirmed) {
                        begin(client, elytra, box, settings, dimension);
                    }
                },
                Text.literal("Auto-map this area?"), Text.literal(body),
                Text.literal("Start"), Text.literal("Cancel")));
    }

    /** Why a flight couldn't start right now, if it couldn't - for the GUI's live status. */
    public static Optional<String> readinessProblem() {
        return checkCanStart(MinecraftClient.getInstance());
    }

    private static Optional<String> checkCanStart(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Optional.of("Join a world first.");
        }
        if (plan != null) {
            return Optional.of("Auto-map is already running. /argus automap stop ends it.");
        }
        String dimension = AutoUploads.dimensionOf(client.world);
        if (!dimension.equals("overworld") && !dimension.equals("theend")) {
            return Optional.of("Auto-map only flies the Overworld and the End.");
        }
        Optional<MeteorElytra> elytra = MeteorElytra.find();
        if (elytra.isEmpty()) {
            return Optional.of("Auto-map needs Meteor Client's Elytra Fly module.");
        }
        if (!elytra.get().isVanillaMode()) {
            return Optional.of("Set Elytra Fly's mode to Vanilla first.");
        }
        if (!elytra.get().isActive()) {
            return Optional.of("Turn on Elytra Fly first.");
        }
        if (!player.isGliding()) {
            return Optional.of("Take off and start gliding first.");
        }
        return elytraProblem(player);
    }

    private static Optional<String> elytraProblem(ClientPlayerEntity player) {
        ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);
        if (chest.isOf(Items.ELYTRA) && chest.getMaxDamage() - chest.getDamage() < MIN_ELYTRA_DURABILITY) {
            return Optional.of("Your elytra is nearly broken.");
        }
        return Optional.empty();
    }

    private static AutoMapController.Settings settingsFor(MinecraftClient client, MeteorElytra elytra) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        double min = Math.max(0.3, config.autoMapMinSpeed);
        double max = Math.max(min, config.autoMapMaxSpeed);
        LaneWidthModel model = LaneWidthModel.parse(config.autoMapWidthTable);
        double operating = config.autoMapSpeed > 0
                ? Math.max(min, Math.min(max, config.autoMapSpeed))
                : model.bestSpeed(min, max);
        int viewDistance = client.options.getViewDistance().getValue();
        int half = config.autoMapHalfWidthChunks > 0
                ? config.autoMapHalfWidthChunks
                : Math.min(model.halfWidthChunks(operating), Math.max(1, viewDistance - 2));
        double vertical = elytra.verticalSpeed();
        double climb = Double.isNaN(vertical) ? 0.5 : Math.max(0.1, vertical * 0.5);
        int lag = FabricLoader.getInstance().isModLoaded("xaeroworldmap") ? 4 : 2;
        return new AutoMapController.Settings(half, min, operating, operating, config.autoMapCruiseY, 8, climb, lag, 2);
    }

    private static void begin(MinecraftClient client, MeteorElytra elytra, ChunkBox box,
                              AutoMapController.Settings settings, String dimension) {
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, "Auto-map didn't start: " + problem.get());
            return;
        }
        ClientPlayerEntity player = client.player;
        meteor = elytra;
        originalHorizontalSpeed = elytra.horizontalSpeed();
        lastSpeedSet = originalHorizontalSpeed;
        xaero = FabricLoader.getInstance().isModLoaded("xaeroworldmap") ? XaeroCoverage.open().orElse(null) : null;
        xaeroUnreliable = false;
        xaeroSeenWritten = false;
        lastHealth = player.getHealth();
        startedAtMillis = System.currentTimeMillis();
        ticksRunning = 0;
        calibrating = false;
        plan = new AutoMapController(box, settings, dimension, player.getX(), player.getZ(),
                AutoMapper::covered, AutoMapper::topY);
        holdUploads(UploadHold.forBox(box, dimension, HOLD_MARGIN_REGIONS));
        feedback(client, "Auto-map started" + (xaero == null
                ? " (Xaero's World Map couldn't be read, so it goes by loaded chunks)." : ".")
                + " Nothing in that area is uploaded until it finishes. /argus automap stop cancels it.");
    }

    private static void onTick(MinecraftClient client) {
        ChunkBox box = pendingBox;
        if (box != null) {
            pendingBox = null;
            requestStart(box, client.currentScreen);
        }
        String speeds = pendingCalibration;
        if (speeds != null) {
            pendingCalibration = null;
            requestCalibration(speeds);
        }
        FlightPlan current = plan;
        if (current == null) {
            return;
        }
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            end(client, "you left the world");
            return;
        }
        ticksRunning++;
        checkSafety(current, player);
        checkXaero(player);

        AutoMapController.Command command = current.tick(new AutoMapController.Frame(System.currentTimeMillis(),
                player.getX(), player.getY(), player.getZ(), player.isGliding(), AutoUploads.dimensionOf(world)));
        if (command == null) {
            end(client, null);
            return;
        }
        player.setYaw((float) command.yawDegrees());
        client.options.forwardKey.setPressed(command.forward());
        client.options.jumpKey.setPressed(command.vertical() == AutoMapController.Vertical.UP);
        client.options.sneakKey.setPressed(command.vertical() == AutoMapController.Vertical.DOWN);
        if (Math.abs(command.horizontalSpeed() - lastSpeedSet) > 0.005) {
            meteor.setHorizontalSpeed(command.horizontalSpeed());
            lastSpeedSet = command.horizontalSpeed();
        }
    }

    private static void checkSafety(FlightPlan current, ClientPlayerEntity player) {
        if (player.getHealth() < lastHealth - 0.01f) {
            current.abort("you took damage");
        }
        lastHealth = player.getHealth();
        elytraProblem(player).ifPresent(current::abort);
        if (!meteor.isActive() || !meteor.isVanillaMode()) {
            current.abort("Elytra Fly was turned off or changed mode");
        }
    }

    private static void checkXaero(ClientPlayerEntity player) {
        if (xaero == null || xaeroUnreliable) {
            return;
        }
        xaero.refreshLayer();
        if (!calibrating && !xaeroSeenWritten) {
            xaeroSeenWritten = xaero.written(player.getBlockX() >> 4, player.getBlockZ() >> 4);
            if (!xaeroSeenWritten && ticksRunning > XAERO_GRACE_TICKS) {
                xaeroUnreliable = true;
                feedback(MinecraftClient.getInstance(),
                        "Xaero isn't recording chunks under you; going by loaded chunks instead.");
            }
        }
    }

    private static boolean covered(int chunkX, int chunkZ) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return false;
        }
        if (xaero != null && !xaeroUnreliable) {
            return xaero.written(chunkX, chunkZ);
        }
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    private static OptionalInt topY(int blockX, int blockZ) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null || !world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(world.getTopY(Heightmap.Type.MOTION_BLOCKING, blockX, blockZ));
    }

    private static void holdUploads(UploadHold.Area area) {
        UploadHold.hold(List.of(area));
        AutoUploads.cancelLiveRun();
    }

    private static void end(MinecraftClient client, String reason) {
        FlightPlan finished = plan;
        plan = null;
        UploadHold.release();
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        if (meteor != null && !Double.isNaN(originalHorizontalSpeed)) {
            meteor.setHorizontalSpeed(originalHorizontalSpeed);
        }
        if (finished == null) {
            return;
        }
        if (reason != null) {
            finished.abort(reason);
        }
        long minutes = Math.max(1, (System.currentTimeMillis() - startedAtMillis) / 60_000L);
        String label = calibrating ? "Calibration" : "Auto-map";
        String result = finished.resultLine() + " " + minutes + " min.";
        if (finished.state() == AutoMapController.State.ABORTED) {
            feedback(client, label + " stopped: " + finished.abortReason() + ". " + result);
        } else {
            feedback(client, label + " finished. " + result);
        }
        if (finished instanceof CalibrationController run) {
            writeCalibration(client, run);
        }
        calibrating = false;
    }

    // ------------------------------------------------------------ calibration

    public static void requestCalibrationFromCommand(String speeds) {
        pendingCalibration = speeds == null ? "" : speeds;
    }

    private static void requestCalibration(String speedsCsv) {
        MinecraftClient client = MinecraftClient.getInstance();
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, problem.get());
            return;
        }
        MeteorElytra elytra = MeteorElytra.find().orElseThrow();
        ClientPlayerEntity player = client.player;
        String dimension = AutoUploads.dimensionOf(client.world);
        CalibrationSchedule schedule = CalibrationSchedule.parse(speedsCsv);
        double lengthBlocks = 0;
        StringBuilder speeds = new StringBuilder();
        for (int stage = 0; stage < schedule.stages(); stage++) {
            lengthBlocks += schedule.speed(stage) * 20.0 * schedule.stageMillis() / 1000.0;
            speeds.append(stage == 0 ? "" : ", ").append(schedule.speed(stage));
        }
        List<int[]> mapped = mappedRegions(dimension);
        int preferred = CalibrationController.cardinalFromYaw(player.getYaw());
        int cardinal = mapped == null ? preferred
                : CorridorOverlap.leastMapped(mapped, player.getX(), player.getZ(), preferred, lengthBlocks);
        int overlap = mapped == null ? -1
                : CorridorOverlap.count(mapped, player.getX(), player.getZ(), cardinal, lengthBlocks);
        String overlapText = overlap < 0 ? "Couldn't check for existing map files on that line."
                : overlap == 0 ? "No map files exist on that line."
                : overlap + " region file(s) on that line already exist, which flatters the results.";

        String body = "Flies straight " + CalibrationController.cardinalName(cardinal) + " for about "
                + Math.max(1, Math.round(schedule.totalMillis() / 60_000.0)) + " min (roughly "
                + Math.round(lengthBlocks) + " blocks) at " + schedule.stages() + " speeds (" + speeds
                + " blocks/tick), recording how many chunks load and how many Xaero maps at each. "
                + overlapText + "\n\nIt writes a report to argus-mapper-calibration in the game folder. "
                + "Same stops as auto-map (teleport, dimension change, damage, not gliding, Elytra Fly off); "
                + "/argus automap stop ends it.";
        final int chosen = cardinal;
        final int overlapFinal = overlap;
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    client.setScreen(null);
                    if (confirmed) {
                        beginCalibration(client, elytra, schedule, chosen, dimension, overlapFinal);
                    }
                },
                Text.literal("Run a calibration flight?"), Text.literal(body),
                Text.literal("Start"), Text.literal("Cancel")));
    }

    private static void beginCalibration(MinecraftClient client, MeteorElytra elytra, CalibrationSchedule schedule,
                                         int cardinal, String dimension, int overlap) {
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, "Calibration didn't start: " + problem.get());
            return;
        }
        ClientPlayerEntity player = client.player;
        ArgusConfig config = ArgusUploaderClientMod.config();
        meteor = elytra;
        originalHorizontalSpeed = elytra.horizontalSpeed();
        lastSpeedSet = originalHorizontalSpeed;
        xaero = FabricLoader.getInstance().isModLoaded("xaeroworldmap") ? XaeroCoverage.open().orElse(null) : null;
        xaeroUnreliable = false;
        lastHealth = player.getHealth();
        startedAtMillis = System.currentTimeMillis();
        ticksRunning = 0;
        calibrating = true;
        double vertical = elytra.verticalSpeed();
        double climb = Double.isNaN(vertical) ? 0.5 : Math.max(0.1, vertical * 0.5);
        calibrationEnvironment = environmentLines(client, elytra, overlap);
        holdUploads(UploadHold.forLine(dimension, player.getX(), player.getZ(), cardinal,
                schedule.lengthBlocks() + 512, HOLD_MARGIN_REGIONS));
        plan = new CalibrationController(schedule, player.getX(), player.getZ(), cardinal, dimension,
                client.options.getViewDistance().getValue(), AutoMapper::loaded, AutoMapper::writtenRaw,
                AutoMapper::topY, config.autoMapCruiseY, 8, climb, AutoMapper::extras);
        feedback(client, "Calibration started, flying " + CalibrationController.cardinalName(cardinal)
                + (xaero == null ? " (Xaero's World Map couldn't be read, so only loaded chunks are recorded)." : ".")
                + " /argus automap stop cancels it.");
    }

    private static List<int[]> mappedRegions(String dimension) {
        try {
            RegionResolver.Resolved resolved = RegionResolver.resolve(dimension);
            if (resolved.isError()) {
                return null;
            }
            List<int[]> regions = new ArrayList<>();
            resolved.regions().forEach(region -> regions.add(new int[]{region.regionX(), region.regionZ()}));
            return regions;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean loaded(int chunkX, int chunkZ) {
        ClientWorld world = MinecraftClient.getInstance().world;
        return world != null && world.isChunkLoaded(chunkX, chunkZ);
    }

    private static boolean writtenRaw(int chunkX, int chunkZ) {
        return xaero != null && xaero.written(chunkX, chunkZ);
    }

    private static CalibrationController.Extras extras() {
        MinecraftClient client = MinecraftClient.getInstance();
        double ping = -1;
        try {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (entry != null) {
                ping = entry.getLatency();
            }
        } catch (RuntimeException ignored) {
            // Ping is only context for the log.
        }
        String render = "";
        try {
            render = client.worldRenderer.getChunksDebugString();
        } catch (RuntimeException | LinkageError ignored) {
            // The renderer's own count is only context for the log.
        }
        return new CalibrationController.Extras(client.getCurrentFps(), ping, render == null ? "" : render);
    }

    private static List<String> environmentLines(MinecraftClient client, MeteorElytra elytra, int overlap) {
        FabricLoader loader = FabricLoader.getInstance();
        Path config = loader.getConfigDir();
        String fastMapping = configValue(config.resolve("xaeroplus.txt"), "[XP] Fast Mapping");
        calibrationFastMapping = "true".equalsIgnoreCase(fastMapping) ? "on" : "false".equalsIgnoreCase(fastMapping) ? "off" : "unknown";
        List<String> lines = new ArrayList<>();
        lines.add("started: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        lines.add("minecraft: " + version(loader, "minecraft") + ", argus-mapper: " + version(loader, "argus-mapper"));
        lines.add("client view distance: " + client.options.getViewDistance().getValue()
                + ", simulation distance: " + client.options.getSimulationDistance().getValue()
                + ", max fps: " + client.options.getMaxFps().getValue());
        lines.add("xaero world map: " + version(loader, "xaeroworldmap") + ", tiles readable: " + (xaero != null)
                + ", map writing distance: " + configValue(config.resolve("xaero/world-map/profiles/default.cfg"), "map_writing_distance"));
        lines.add("xaeroplus: " + version(loader, "xaeroplus") + ", fast mapping: " + fastMapping
                + ", delay: " + configValue(config.resolve("xaeroplus.txt"), "[XP] Fast Mapping Delay")
                + ", rate limit: " + configValue(config.resolve("xaeroplus.txt"), "[XP] Fast Mapping Rate Limit")
                + " (as saved in config/xaeroplus.txt)");
        lines.add("sodium: " + version(loader, "sodium") + ", voxy: " + version(loader, "voxy"));
        lines.add("meteor elytra fly (before): horizontal " + originalHorizontalSpeed + ", vertical " + elytra.verticalSpeed());
        lines.add("existing region files on the line: " + (overlap < 0 ? "unknown" : overlap));
        return lines;
    }

    private static String version(FabricLoader loader, String modId) {
        return loader.getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
    }

    private static String configValue(Path file, String key) {
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith(key)) {
                    continue;
                }
                String rest = trimmed.substring(key.length()).stripLeading();
                if (rest.startsWith(":") || rest.startsWith("=")) {
                    return rest.substring(1).trim();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Not there or unreadable: reported as unknown.
        }
        return "unknown";
    }

    private static void writeCalibration(MinecraftClient client, CalibrationController run) {
        Path directory = FabricLoader.getInstance().getGameDir().resolve("argus-mapper-calibration")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + "-fastmapping-" + calibrationFastMapping);
        String summary = CalibrationReport.summary(run, calibrationEnvironment);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("summary.txt"), summary);
            Files.writeString(directory.resolve("samples.csv"), run.samplesCsv());
            Files.writeString(directory.resolve("rows.csv"), CalibrationReport.rowsCsv(run));
            feedback(client, "Calibration report saved to " + directory);
            summary.lines().filter(line -> line.startsWith("autoMapWidthTable=") || line.startsWith("Best speed"))
                    .forEach(line -> feedback(client, line));
        } catch (IOException e) {
            feedback(client, "Couldn't save the calibration report: " + e.getMessage());
        }
    }

    private static void feedback(MinecraftClient client, String message) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[ARGUS] " + message), false);
            }
        });
    }
}
