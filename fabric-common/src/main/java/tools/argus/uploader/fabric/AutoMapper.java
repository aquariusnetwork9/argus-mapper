package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.world.Heightmap;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.automap.AutoMapController;
import tools.argus.uploader.core.automap.ChunkBox;
import tools.argus.uploader.core.automap.LanePlanner;
import tools.argus.uploader.core.automap.LaneWidthModel;
import tools.argus.uploader.core.automap.MeteorElytra;
import tools.argus.uploader.core.automap.Waypoint;

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

    private static volatile ChunkBox pendingBox;
    private static AutoMapController controller;
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
            if (controller != null) {
                end(client, "you disconnected");
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (controller != null) {
                end(client, "the game is closing");
            }
        });
    }

    public static boolean isRunning() {
        return controller != null;
    }

    public static String status() {
        AutoMapController current = controller;
        return current == null ? "Off" : current.statusLine();
    }

    /** Chat-command entry point: the popup opens on the next tick, after chat has closed. */
    public static void requestStartFromCommand(ChunkBox box) {
        pendingBox = box;
    }

    public static void stop(String reason) {
        if (controller != null) {
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

    private static Optional<String> checkCanStart(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Optional.of("Join a world first.");
        }
        if (controller != null) {
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
        double min = Math.max(0.5, config.autoMapMinSpeed);
        double max = Math.max(min, config.autoMapMaxSpeed);
        int viewDistance = client.options.getViewDistance().getValue();
        int half = config.autoMapHalfWidthChunks > 0
                ? config.autoMapHalfWidthChunks
                : Math.min(LaneWidthModel.halfWidthChunks(max), Math.max(2, viewDistance - 2));
        double current = elytra.horizontalSpeed();
        double start = Double.isNaN(current) ? min : Math.max(min, Math.min(max, current));
        double vertical = elytra.verticalSpeed();
        double climb = Double.isNaN(vertical) ? 0.5 : Math.max(0.1, vertical * 0.5);
        int lag = FabricLoader.getInstance().isModLoaded("xaeroworldmap") ? 4 : 2;
        return new AutoMapController.Settings(half, min, max, start, config.autoMapCruiseY, 8, climb, lag, 2);
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
        controller = new AutoMapController(box, settings, dimension, player.getX(), player.getZ(),
                AutoMapper::covered, AutoMapper::topY);
        feedback(client, "Auto-map started" + (xaero == null
                ? " (Xaero's World Map couldn't be read, so it goes by loaded chunks)." : ".")
                + " /argus automap stop cancels it.");
    }

    private static void onTick(MinecraftClient client) {
        ChunkBox box = pendingBox;
        if (box != null) {
            pendingBox = null;
            requestStart(box, client.currentScreen);
        }
        AutoMapController current = controller;
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

    private static void checkSafety(AutoMapController current, ClientPlayerEntity player) {
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
        if (!xaeroSeenWritten) {
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

    private static void end(MinecraftClient client, String reason) {
        AutoMapController finished = controller;
        controller = null;
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
        String result = String.format("%.1f%% of the area mapped, %d chunk(s) missing, %d min, %d rubberband(s).",
                finished.progress() * 100, finished.missingChunks(), minutes, finished.rubberbands());
        if (finished.state() == AutoMapController.State.ABORTED) {
            feedback(client, "Auto-map stopped: " + finished.abortReason() + ". " + result);
        } else {
            feedback(client, "Auto-map finished. " + result);
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
