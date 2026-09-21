package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.bounty.BountyClient;
import tools.argus.uploader.core.bounty.BountyPoller;
import tools.argus.uploader.core.bounty.BountyRegion;
import tools.argus.uploader.core.bounty.BountyResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ARGUS bounty: while turned on, in the overworld on a known ARGUS server, fetches the public
 * list of regions the map wants next and shows them as waypoints on Xaero's World Map. Off by
 * default; nothing is fetched otherwise. The only request is a plain GET for the list - no token,
 * no player information.
 */
public final class Bounty {

    private static final long POLL_MILLIS = 120_000L;
    private static final long MIN_GAP_MILLIS = 15_000L;

    private static final BountyPoller poller = new BountyPoller(POLL_MILLIS, MIN_GAP_MILLIS);
    private static final BountyClient client = new BountyClient();
    private static final ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "argus-bounty");
        t.setDaemon(true);
        return t;
    });

    private static List<BountyRegion> shown = List.of();
    private static BountyRegion bountyCell;
    private static long updatedAt;
    private static String status = "Off";
    private static int ticks;
    private static int generation;

    private Bounty() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Bounty::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> onDisconnect());
    }

    public static boolean isEnabled() {
        return ArgusUploaderClientMod.config().bountyEnabled;
    }

    public static String statusLine() {
        if (!isEnabled()) {
            return "Off";
        }
        if (shown.isEmpty() || updatedAt == 0) {
            return status;
        }
        String cell = bountyCell == null ? "" : ", 2x cell near " + bountyCell.centerX() + ", " + bountyCell.centerZ();
        long seconds = Math.max(0, (System.currentTimeMillis() - updatedAt) / 1000);
        return shown.size() + " region(s) marked" + cell + " - updated " + (seconds < 90 ? seconds + "s" : seconds / 60 + "m") + " ago"
                + (status.startsWith("Couldn't") || status.startsWith("Waiting") ? " (" + status + ")" : "");
    }

    /** Turns the fetch on or off and remembers the choice; turning it off also removes the markers. */
    public static void setEnabled(boolean on) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        cfg.bountyEnabled = on;
        try {
            cfg.save(ArgusUploaderClientMod.configPath());
        } catch (IOException ignored) {
            // the choice still holds for this session
        }
        generation++;
        poller.reset();
        if (on) {
            status = "Starting";
        } else {
            clearMarkers();
            status = "Off";
        }
    }

    /** Asks for a fresh list as soon as allowed; returns what to tell the player. */
    public static String refreshNow() {
        if (!isEnabled()) {
            return "Bounty is off. Turn it on with /argus bounty on.";
        }
        poller.askSoon();
        return "Refreshing the bounty list.";
    }

    public static void clearMarkers() {
        BountyMarkers.removeAll();
        shown = List.of();
        bountyCell = null;
        updatedAt = 0;
    }

    private static void onDisconnect() {
        generation++;
        poller.reset();
        BountyMarkers.forget();
        shown = List.of();
        bountyCell = null;
        updatedAt = 0;
        status = isEnabled() ? "Waiting: not in a world" : "Off";
    }

    private static void onTick(MinecraftClient mc) {
        if (++ticks % 20 != 0) {
            return;
        }
        if (!isEnabled()) {
            if (!BountyMarkers.isEmpty()) {
                clearMarkers();
            }
            return;
        }
        String blocked = blockedReason(mc);
        if (blocked != null) {
            status = "Waiting: " + blocked;
            return;
        }
        if (poller.isDue(System.currentTimeMillis(), true)) {
            startFetch(mc);
        }
    }

    /** Why the list shouldn't be fetched right now, or null if it can be. */
    private static String blockedReason(MinecraftClient mc) {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("xaerominimap")) {
            return "Xaero's Minimap isn't installed";
        }
        if (!loader.isModLoaded("xaeroworldmap")) {
            return "Xaero's World Map isn't installed";
        }
        if (mc.player == null || mc.world == null) {
            return "not in a world";
        }
        if (mc.getCurrentServerEntry() == null
                || ArgusUploaderClientMod.registry().matching(GameWorldContext.currentWorldToken(mc)).isEmpty()) {
            return "not on a known ARGUS server";
        }
        if (!World.OVERWORLD.equals(mc.world.getRegistryKey())) {
            return "not in the overworld";
        }
        return null;
    }

    private static void startFetch(MinecraftClient mc) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        String url = cfg.bountyUrl;
        int limit = cfg.bountyLimit;
        int startedIn = generation;
        poller.started(System.currentTimeMillis());
        status = "Fetching";
        fetcher.execute(() -> {
            BountyClient.Result result = client.fetch(url, limit);
            mc.execute(() -> onResult(startedIn, result));
        });
    }

    private static void onResult(int startedIn, BountyClient.Result result) {
        if (startedIn != generation || !isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!result.ok()) {
            poller.failed(now);
            status = "Couldn't refresh (" + result.error() + "), will retry";
            return;
        }
        BountyResponse response = result.response();
        if (!"overworld".equals(response.dimension())) {
            clearMarkers();
            poller.succeeded(now);
            status = "The list is for " + response.dimension() + ", which this doesn't mark";
            return;
        }
        List<BountyRegion> regions = response.markers();
        if (regions.equals(shown) && !BountyMarkers.isEmpty()) {
            updatedAt = now;
            poller.succeeded(now);
            return;
        }
        BountyMarkers.Outcome outcome = BountyMarkers.apply(regions);
        if (!outcome.ok()) {
            poller.failed(now);
            status = "Waiting: " + outcome.problem();
            shown = List.of();
            updatedAt = 0;
            return;
        }
        shown = regions;
        bountyCell = response.bounty();
        updatedAt = now;
        poller.succeeded(now);
        status = regions.size() + " region(s) marked";
    }
}
