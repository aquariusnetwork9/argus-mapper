package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
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
import tools.argus.uploader.core.bounty.BountyRegion;

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
 * 26.2's copy of fabric-common's AutoMapper - see that file for the full feature design (flies a
 * box of the map by steering Meteor Client's Elytra Fly, pacing it to the map, with the same
 * safety stops: damage, teleport, dimension change, not gliding, Elytra Fly off, disconnect, game
 * closing).
 *
 * <p>Every vanilla touchpoint re-verified via javap against the real 26.2 client jar, not carried
 * over from fabric-common by assumption. The already-established facts from {@code NOTES.md}
 * applied here: {@code MinecraftClient} -&gt; {@code Minecraft}, {@code mc.world} -&gt;
 * {@code mc.level}, {@code ClientPlayerEntity} -&gt; {@code LocalPlayer}, {@code ClientWorld} -&gt;
 * {@code ClientLevel}, {@code Text}/{@code ConfirmScreen}/{@code Screen} -&gt; {@code Component}/
 * {@code ConfirmScreen}/{@code Screen} (package pluralized to {@code
 * net.minecraft.client.gui.screens}), {@code client.currentScreen} -&gt;
 * {@code Minecraft.gui.screen()}.
 *
 * <p>New facts needed for this file specifically, all confirmed via javap against the real jar
 * (unzipped from {@code minecraft-merged-043a8b3edf-26.2.jar}):
 * <ul>
 *   <li>{@code Heightmap.Types.MOTION_BLOCKING} (Yarn's {@code Heightmap.Type.MOTION_BLOCKING}) -
 *       the enum is literally named {@code Types}, plural, confirmed on the class itself.
 *   <li>{@code EquipmentSlot.CHEST} - unchanged.
 *   <li>{@code Items.ELYTRA} - unchanged.
 *   <li>{@code LivingEntity.getItemBySlot(EquipmentSlot)} replaces Yarn's
 *       {@code getEquippedStack(EquipmentSlot)} - inherited by {@code LocalPlayer}, not redeclared
 *       there.
 *   <li>{@code ItemStack} has no {@code isOf(Item)} method here; {@code Item.builtInRegistryHolder()}
 *       plus {@code ItemStack.is(Predicate<Holder<Item>>)} is the real shape, but the simplest
 *       equivalent that exists on both mapping schemes is comparing {@code stack.getItem()} to the
 *       target {@code Item} directly, which is what this file does instead of guessing at a
 *       predicate-based {@code is(...)} overload that doesn't exist for a bare {@code Item}.
 *   <li><b>{@code Options} has no {@code forwardKey}/{@code jumpKey}/{@code sneakKey} fields at
 *       all</b> - checked the complete field list. The real names are {@code keyUp} (forward),
 *       {@code keyJump}, and {@code keyShift} (sneak), all {@code KeyMapping}.
 *   <li><b>{@code KeyMapping} has no {@code setPressed(boolean)} method</b> - Yarn's
 *       {@code KeyBinding.setPressed} is Mojang's {@code KeyMapping.setDown(boolean)} (there's also
 *       a static {@code KeyMapping.set(InputConstants.Key, boolean)}, not what's needed here since
 *       this drives specific mappings, not raw keys).
 *   <li><b>{@code Options.getViewDistance()}/{@code getSimulationDistance()}/{@code getMaxFps()}
 *       don't exist</b> - the real accessors are {@code renderDistance()}/{@code
 *       simulationDistance()}/{@code framerateLimit()}, each returning {@code OptionInstance<Integer>}
 *       (read with {@code .get()}, not {@code .getValue()} - {@code OptionInstance} has no
 *       {@code getValue()} method, just {@code get()}/{@code set(T)}).
 *   <li>{@code ClientLevel.hasChunk(int, int)} replaces Yarn's {@code World.isChunkLoaded(int, int)}
 *       (inherited from {@code Level}/{@code ChunkSource} plumbing - confirmed directly on
 *       {@code ClientLevel}).
 *   <li>{@code Level.getHeight(Heightmap.Types, int, int)} replaces Yarn's
 *       {@code World.getTopY(Heightmap.Type, int, int)} - same argument shape, new name.
 *   <li>{@code Minecraft.getConnection().getPlayerInfo(UUID)} replaces Yarn's
 *       {@code ClientPlayNetworkHandler.getPlayerListEntry(UUID)}; Mojang's {@code PlayerInfo}
 *       (Yarn's {@code PlayerListEntry}) keeps {@code getLatency()} unchanged.
 *   <li><b>{@code LevelRenderer.getChunksDebugString()} does not exist in 26.2 at all</b> - checked
 *       the complete method list of the real {@code LevelRenderer} class, which was rewritten
 *       around a frame-graph renderer (matches the Vulkan/{@code dynamicRendering} evidence already
 *       noted elsewhere in this file's siblings). This is diagnostic-only text for the calibration
 *       report, not a safety condition, so it's simply reported as empty here rather than guessed
 *       at; the existing defensive {@code catch (RuntimeException | LinkageError)} around it is kept
 *       as a guard against any future signature drift, matching the spirit of fabric-common's own
 *       defensive read.
 *   <li>{@code Minecraft.getFps()} replaces Yarn's {@code MinecraftClient.getCurrentFps()}.
 *   <li>{@code Minecraft.levelRenderer} (public field) replaces Yarn's {@code worldRenderer}.
 *   <li>{@code Entity.isFallFlying()} is Mojang's name for the gliding state Yarn calls
 *       {@code ClientPlayerEntity.isGliding()} - same boolean, confirmed on {@code LivingEntity}.
 * </ul>
 */
public final class AutoMapper {

    private static final int MIN_ELYTRA_DURABILITY = 20;
    private static final int XAERO_GRACE_TICKS = 160;
    private static final int HOLD_MARGIN_REGIONS = 1;

    private static volatile ChunkBox pendingBox;
    private static volatile BountyRegion pendingBounty;
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

    /** Chat-command and GUI entry point for a bounty flight: the popup opens on the next tick. */
    public static void requestBountyFromCommand(BountyRegion region) {
        pendingBounty = region;
    }

    public static void stop(String reason) {
        if (plan != null) {
            end(Minecraft.getInstance(), reason);
        }
    }

    public static void requestStart(ChunkBox box, Screen previous) {
        Minecraft client = Minecraft.getInstance();
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, problem.get());
            return;
        }
        MeteorElytra elytra = MeteorElytra.find().orElseThrow();
        AutoMapController.Settings settings = settingsFor(client, elytra, false);
        LocalPlayer player = client.player;
        List<Waypoint> path = LanePlanner.plan(box, player.getX(), player.getZ(), settings.halfWidthChunks());
        double minutes = LanePlanner.length(path, player.getX(), player.getZ()) / (settings.startSpeed() * 20) / 60;
        String dimension = AutoUploads.dimensionOf(client.level);

        String body = "Area: " + box.widthChunks() + " x " + box.depthChunks() + " chunks, about "
                + path.size() / 2 + " lanes, roughly " + Math.max(1, Math.round(minutes)) + " min at "
                + String.format("%.2f", settings.startSpeed()) + " blocks/tick.\n\n"
                + "This steers your character and changes Meteor's Elytra Fly speed while it runs, at about Y "
                + settings.cruiseY() + ". It slows down when the map can't keep up and stops if you teleport, "
                + "change dimension, take damage, stop gliding or turn Elytra Fly off. "
                + "/argus automap stop cancels it.";
        client.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    client.setScreenAndShow(confirmed ? null : previous);
                    if (confirmed) {
                        begin(client, elytra, box, settings, dimension, "Auto-map");
                    }
                },
                Component.literal("Auto-map this area?"), Component.literal(body),
                Component.literal("Start"), Component.literal("Cancel")));
    }

    /** Asks before flying to a bounty region at top speed, then mapping it once there. */
    public static void requestBounty(BountyRegion region, Screen previous) {
        Minecraft client = Minecraft.getInstance();
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, problem.get());
            return;
        }
        String dimension = AutoUploads.dimensionOf(client.level);
        if (!dimension.equals("overworld")) {
            feedback(client, "Bounty regions are in the overworld; go there first.");
            return;
        }
        MeteorElytra elytra = MeteorElytra.find().orElseThrow();
        AutoMapController.Settings settings = settingsFor(client, elytra, true);
        ChunkBox box = new ChunkBox(Math.floorDiv(region.blockX0(), 16), Math.floorDiv(region.blockZ0(), 16),
                Math.floorDiv(region.blockX1(), 16) - 1, Math.floorDiv(region.blockZ1(), 16) - 1);
        LocalPlayer player = client.player;
        double[] corner = LanePlanner.nearestCorner(box, player.getX(), player.getZ());
        double distance = Math.hypot(corner[0] - player.getX(), corner[1] - player.getZ());
        double transitSeconds = distance / (settings.transitSpeed() * 20);
        List<Waypoint> path = LanePlanner.plan(box, corner[0], corner[1], settings.halfWidthChunks());
        double mapMinutes = LanePlanner.length(path, corner[0], corner[1]) / (settings.startSpeed() * 20) / 60;

        String body = (region.bounty() ? "Today's 2x bounty region" : "Bounty region") + " at " + region.centerX() + ", "
                + region.centerZ() + ", " + Math.round(distance) + " blocks to its nearest corner.\n\n"
                + "Flies there at Meteor's top speed (" + String.format("%.2f", settings.transitSpeed()) + " blocks/tick, about "
                + formatDuration(transitSeconds) + "), and on reaching the corner slows within a couple of seconds to "
                + String.format("%.2f", settings.startSpeed()) + " blocks/tick (about "
                + Math.round(settings.startSpeed() * 20) + " blocks/s) and maps the region in lanes, roughly "
                + Math.max(1, Math.round(mapMinutes)) + " min, at about Y " + settings.cruiseY() + ".\n\n"
                + "It steers your character and changes Meteor's Elytra Fly speed while it runs, and stops if you teleport, "
                + "change dimension, take damage, stop gliding or turn Elytra Fly off. /argus automap stop cancels it. "
                + "Uploads of the region are held until it finishes.";
        client.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    client.setScreenAndShow(confirmed ? null : previous);
                    if (confirmed) {
                        begin(client, elytra, box, settings, dimension, "Bounty flight");
                    }
                },
                Component.literal("Fly to this bounty region and map it?"), Component.literal(body),
                Component.literal("Yes"), Component.literal("No")));
    }

    private static String formatDuration(double seconds) {
        return seconds < 90 ? Math.max(1, Math.round(seconds)) + " s" : Math.round(seconds / 60.0) + " min";
    }

    /** Why a flight couldn't start right now, if it couldn't - for the GUI's live status. */
    public static Optional<String> readinessProblem() {
        return checkCanStart(Minecraft.getInstance());
    }

    private static Optional<String> checkCanStart(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return Optional.of("Join a world first.");
        }
        if (plan != null) {
            return Optional.of("Auto-map is already running. /argus automap stop ends it.");
        }
        String dimension = AutoUploads.dimensionOf(client.level);
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
        if (!player.isFallFlying()) {
            return Optional.of("Take off and start gliding first.");
        }
        return elytraProblem(player);
    }

    private static Optional<String> elytraProblem(LocalPlayer player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() == Items.ELYTRA && chest.getMaxDamage() - chest.getDamageValue() < MIN_ELYTRA_DURABILITY) {
            return Optional.of("Your elytra is nearly broken.");
        }
        return Optional.empty();
    }

    private static AutoMapController.Settings settingsFor(Minecraft client, MeteorElytra elytra, boolean transit) {
        ArgusConfig config = ArgusUploaderClientMod.config();
        double min = Math.max(0.3, config.autoMapMinSpeed);
        double max = Math.max(min, config.autoMapMaxSpeed);
        LaneWidthModel model = LaneWidthModel.parse(config.autoMapWidthTable);
        double operating = config.autoMapSpeed > 0
                ? Math.max(min, Math.min(max, config.autoMapSpeed))
                : model.bestSpeed(min, max);
        int viewDistance = client.options.renderDistance().get();
        int half = config.autoMapHalfWidthChunks > 0
                ? config.autoMapHalfWidthChunks
                : Math.min(model.halfWidthChunks(operating), Math.max(1, viewDistance - 2));
        double vertical = elytra.verticalSpeed();
        double climb = Double.isNaN(vertical) ? 0.5 : Math.max(0.1, vertical * 0.5);
        int lag = FabricLoader.getInstance().isModLoaded("xaeroworldmap") ? 4 : 2;
        return new AutoMapController.Settings(half, min, operating, operating, config.autoMapCruiseY, 8, climb, lag, 2,
                transit ? max : 0);
    }

    private static void begin(Minecraft client, MeteorElytra elytra, ChunkBox box,
                              AutoMapController.Settings settings, String dimension, String label) {
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, label + " didn't start: " + problem.get());
            return;
        }
        LocalPlayer player = client.player;
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
        feedback(client, label + " started" + (xaero == null
                ? " (Xaero's World Map couldn't be read, so it goes by loaded chunks)." : ".")
                + " Nothing in that area is uploaded until it finishes. /argus automap stop cancels it.");
    }

    private static void onTick(Minecraft client) {
        ChunkBox box = pendingBox;
        if (box != null) {
            pendingBox = null;
            requestStart(box, client.gui.screen());
        }
        BountyRegion bounty = pendingBounty;
        if (bounty != null) {
            pendingBounty = null;
            requestBounty(bounty, client.gui.screen());
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
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (player == null || world == null) {
            end(client, "you left the world");
            return;
        }
        ticksRunning++;
        checkSafety(current, player);
        checkXaero(player);

        AutoMapController.Command command = current.tick(new AutoMapController.Frame(System.currentTimeMillis(),
                player.getX(), player.getY(), player.getZ(), player.isFallFlying(), AutoUploads.dimensionOf(world)));
        if (command == null) {
            end(client, null);
            return;
        }
        player.setYRot((float) command.yawDegrees());
        client.options.keyUp.setDown(command.forward());
        client.options.keyJump.setDown(command.vertical() == AutoMapController.Vertical.UP);
        client.options.keyShift.setDown(command.vertical() == AutoMapController.Vertical.DOWN);
        if (Math.abs(command.horizontalSpeed() - lastSpeedSet) > 0.005) {
            meteor.setHorizontalSpeed(command.horizontalSpeed());
            lastSpeedSet = command.horizontalSpeed();
        }
    }

    private static void checkSafety(FlightPlan current, LocalPlayer player) {
        if (player.getHealth() < lastHealth - 0.01f) {
            current.abort("you took damage");
        }
        lastHealth = player.getHealth();
        elytraProblem(player).ifPresent(current::abort);
        if (!meteor.isActive() || !meteor.isVanillaMode()) {
            current.abort("Elytra Fly was turned off or changed mode");
        }
    }

    private static void checkXaero(LocalPlayer player) {
        if (xaero == null || xaeroUnreliable) {
            return;
        }
        xaero.refreshLayer();
        if (!calibrating && !xaeroSeenWritten) {
            xaeroSeenWritten = xaero.written(player.getBlockX() >> 4, player.getBlockZ() >> 4);
            if (!xaeroSeenWritten && ticksRunning > XAERO_GRACE_TICKS) {
                xaeroUnreliable = true;
                feedback(Minecraft.getInstance(),
                        "Xaero isn't recording chunks under you; going by loaded chunks instead.");
            }
        }
    }

    private static boolean covered(int chunkX, int chunkZ) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return false;
        }
        if (xaero != null && !xaeroUnreliable) {
            return xaero.written(chunkX, chunkZ);
        }
        return world.hasChunk(chunkX, chunkZ);
    }

    private static OptionalInt topY(int blockX, int blockZ) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null || !world.hasChunk(blockX >> 4, blockZ >> 4)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(world.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ));
    }

    private static void holdUploads(UploadHold.Area area) {
        UploadHold.hold(List.of(area));
        AutoUploads.cancelLiveRun();
    }

    private static void end(Minecraft client, String reason) {
        FlightPlan finished = plan;
        plan = null;
        UploadHold.release();
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        if (meteor != null && !Double.isNaN(originalHorizontalSpeed)) {
            meteor.setHorizontalSpeed(originalHorizontalSpeed);
        }
        if (finished == null) {
            return;
        }
        if (reason != null) {
            finished.abort(reason);
        }
        double elapsedSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000.0;
        String label = calibrating ? "Calibration" : "Auto-map";
        String result = finished.resultLine() + " " + formatDuration(elapsedSeconds) + ".";
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
        Minecraft client = Minecraft.getInstance();
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, problem.get());
            return;
        }
        MeteorElytra elytra = MeteorElytra.find().orElseThrow();
        LocalPlayer player = client.player;
        String dimension = AutoUploads.dimensionOf(client.level);
        CalibrationSchedule schedule = CalibrationSchedule.parse(speedsCsv);
        double lengthBlocks = 0;
        StringBuilder speeds = new StringBuilder();
        for (int stage = 0; stage < schedule.stages(); stage++) {
            lengthBlocks += schedule.speed(stage) * 20.0 * schedule.stageMillis() / 1000.0;
            speeds.append(stage == 0 ? "" : ", ").append(schedule.speed(stage));
        }
        List<int[]> mapped = mappedRegions(dimension);
        int preferred = CalibrationController.cardinalFromYaw(player.getYRot());
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
        client.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    client.setScreenAndShow(null);
                    if (confirmed) {
                        beginCalibration(client, elytra, schedule, chosen, dimension, overlapFinal);
                    }
                },
                Component.literal("Run a calibration flight?"), Component.literal(body),
                Component.literal("Start"), Component.literal("Cancel")));
    }

    private static void beginCalibration(Minecraft client, MeteorElytra elytra, CalibrationSchedule schedule,
                                         int cardinal, String dimension, int overlap) {
        Optional<String> problem = checkCanStart(client);
        if (problem.isPresent()) {
            feedback(client, "Calibration didn't start: " + problem.get());
            return;
        }
        LocalPlayer player = client.player;
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
                client.options.renderDistance().get(), AutoMapper::loaded, AutoMapper::writtenRaw,
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
        ClientLevel world = Minecraft.getInstance().level;
        return world != null && world.hasChunk(chunkX, chunkZ);
    }

    private static boolean writtenRaw(int chunkX, int chunkZ) {
        return xaero != null && xaero.written(chunkX, chunkZ);
    }

    private static CalibrationController.Extras extras() {
        Minecraft client = Minecraft.getInstance();
        double ping = -1;
        try {
            var entry = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (entry != null) {
                ping = entry.getLatency();
            }
        } catch (RuntimeException ignored) {
            // Ping is only context for the log.
        }
        // LevelRenderer.getChunksDebugString() doesn't exist on this version's frame-graph-based
        // LevelRenderer at all (see this class's own javadoc) - reported as empty rather than guessed.
        return new CalibrationController.Extras(client.getFps(), ping, "");
    }

    private static List<String> environmentLines(Minecraft client, MeteorElytra elytra, int overlap) {
        FabricLoader loader = FabricLoader.getInstance();
        Path config = loader.getConfigDir();
        String fastMapping = configValue(config.resolve("xaeroplus.txt"), "[XP] Fast Mapping");
        calibrationFastMapping = "true".equalsIgnoreCase(fastMapping) ? "on" : "false".equalsIgnoreCase(fastMapping) ? "off" : "unknown";
        List<String> lines = new ArrayList<>();
        lines.add("started: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        lines.add("minecraft: " + version(loader, "minecraft") + ", argus-mapper: " + version(loader, "argus-mapper"));
        lines.add("client view distance: " + client.options.renderDistance().get()
                + ", simulation distance: " + client.options.simulationDistance().get()
                + ", max fps: " + client.options.framerateLimit().get());
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

    private static void writeCalibration(Minecraft client, CalibrationController run) {
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

    private static void feedback(Minecraft client, String message) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("[ARGUS] " + message));
            }
        });
    }
}
