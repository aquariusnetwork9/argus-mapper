package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.waystone.BotName;
import tools.argus.uploader.core.waystone.SystemStatus;
import tools.argus.uploader.core.waystone.TeleportStatus;
import tools.argus.uploader.core.waystone.TokenInfo;
import tools.argus.uploader.core.waystone.Waystone;
import tools.argus.uploader.core.waystone.WaystoneClient;
import tools.argus.uploader.core.waystone.WaystoneParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Waystone teleports: lists ARGUS's approved waystones, optionally shows them on Xaero's World Map,
 * and after a confirmation spends a Waystone token to have ARGUS's delivery bot travel to the chosen
 * one. When the bot reports it is ready, the mod types {@code /tpa <bot>} for the player.
 *
 * <p>The public waystone list and the bot's status are only fetched while the GUI's Waystones tab is
 * open (or the map markers are switched on); the player's balance and the teleport itself use the
 * upload token and only happen from the tab or after the player asks for a teleport.
 */
public final class Waystones {

    private static final long LIST_MILLIS = 60_000L;
    private static final long STATUS_MILLIS = 5_000L;
    private static final long TOKEN_INFO_MILLIS = 30_000L;
    private static final long STALE_LIST_MILLIS = 30_000L;
    private static final long FLOW_MILLIS = 2_500L;
    private static final long TAB_ALIVE_MILLIS = 2_000L;
    private static final long FLOW_GIVE_UP_MILLIS = 15 * 60_000L;
    private static final long RESEND_TPA_MILLIS = 25_000L;
    private static final int MAX_TPA_SENDS = 2;
    private static final int MAX_FLOW_FAILURES = 5;

    private static final ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "argus-waystones");
        t.setDaemon(true);
        return t;
    });

    private static WaystoneClient api;
    private static String apiUrl = "";

    private static volatile List<Waystone> list = List.of();
    private static volatile SystemStatus botStatus;
    private static volatile long botStatusAt;
    private static volatile TokenInfo tokenInfo;
    private static volatile String listProblem = "";
    private static volatile String botProblem = "";
    private static volatile String tokenProblem = "";
    private static volatile int version;
    private static volatile long tabSeenAt;

    private static long nextList;
    private static long listFetchedAt;
    private static long nextStatus;
    private static long nextToken;
    private static boolean listBusy;
    private static boolean statusBusy;
    private static boolean tokenBusy;
    private static int ticks;
    private static int generation;

    private static int markersVersion = -1;
    private static String markersDimension = "";
    private static String markersStatus = "";

    private static boolean starting;
    private static String activeId;
    private static Waystone activeWaystone;
    private static String flowText = "";
    private static String lastFlowStatus = "";
    private static long flowStartedAt;
    private static long nextFlow;
    private static boolean flowBusy;
    private static int flowFailures;
    private static int tpaSends;
    private static long lastTpaAt;
    private static boolean warnedNoBot;

    private Waystones() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Waystones::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> onDisconnect());
    }

    // ------------------------------------------------------------ what the GUI shows

    /** The GUI calls this every frame the Waystones tab is showing; polling only happens while it does. */
    public static void touchTab() {
        long now = System.currentTimeMillis();
        if (now - tabSeenAt > TAB_ALIVE_MILLIS) {
            nextToken = 0;
            nextStatus = 0;
            if (now - listFetchedAt > STALE_LIST_MILLIS) {
                nextList = 0;
            }
        }
        tabSeenAt = now;
    }

    public static int version() {
        return version;
    }

    /** Waystones in your dimension first, nearest first, then the rest by name. */
    public static List<Waystone> sortedFor(MinecraftClient mc) {
        List<Waystone> sorted = new ArrayList<>(list);
        if (mc.player == null || mc.world == null) {
            sorted.sort(Comparator.comparing(Waystone::displayName, String.CASE_INSENSITIVE_ORDER));
            return sorted;
        }
        String here = WaystoneMarkers.nameOf(mc.world.getRegistryKey());
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        sorted.sort(Comparator
                .comparing((Waystone w) -> !w.dimension().equals(here))
                .thenComparingDouble(w -> w.dimension().equals(here) ? Math.hypot(w.x() - px, w.z() - pz) : 0)
                .thenComparing(Waystone::displayName, String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    public static double distanceTo(MinecraftClient mc, Waystone waystone) {
        if (mc.player == null || mc.world == null
                || !waystone.dimension().equals(WaystoneMarkers.nameOf(mc.world.getRegistryKey()))) {
            return -1;
        }
        return Math.hypot(waystone.x() - mc.player.getX(), waystone.z() - mc.player.getZ());
    }

    public static String accountLine() {
        if (config().token.isBlank()) {
            return "Set your token on the Token tab first.";
        }
        TokenInfo info = tokenInfo;
        if (info == null) {
            return tokenProblem.isEmpty() ? "Checking your account..." : tokenProblem;
        }
        return info.ign() + " - " + info.balance() + " teleport" + (info.balance() == 1 ? "" : "s") + " available"
                + (info.regionsPerToken() > 0 ? " (1 per " + info.regionsPerToken() + " regions uploaded)" : "");
    }

    public static String botLine() {
        SystemStatus status = botStatus;
        if (status == null) {
            return botProblem.isEmpty() ? "Bot: checking..." : "Bot: " + botProblem;
        }
        if (!status.online()) {
            return "Bot: offline";
        }
        long seconds = Math.max(0, status.secondsUntilReady() - (System.currentTimeMillis() - botStatusAt) / 1000);
        return "Bot: " + (status.busy() ? "busy, " : "") + (seconds == 0 ? "ready now" : "ready in " + seconds + "s")
                + (status.queued() > 0 ? ", " + status.queued() + " in line" : "");
    }

    public static String flowLine() {
        return flowText;
    }

    public static String listLine() {
        if (!listProblem.isEmpty()) {
            return listProblem;
        }
        return list.isEmpty() ? "No waystones loaded yet." : list.size() + " waystone(s).";
    }

    public static String markersLine() {
        return markersStatus;
    }

    public static boolean isBusy() {
        return starting || activeId != null;
    }

    // ------------------------------------------------------------ switches and actions

    public static boolean markersEnabled() {
        return config().waystoneMarkers;
    }

    public static void setMarkersEnabled(boolean on) {
        ArgusConfig cfg = config();
        cfg.waystoneMarkers = on;
        try {
            cfg.save(ArgusUploaderClientMod.configPath());
        } catch (IOException ignored) {
            // the choice still holds for this session
        }
        markersVersion = -1;
        markersDimension = "";
        if (on) {
            nextList = 0;
            markersStatus = "Starting";
        } else {
            WaystoneMarkers.removeAll();
            markersStatus = "";
        }
    }

    public static void refreshNow() {
        nextList = 0;
        nextStatus = 0;
        nextToken = 0;
    }

    /** Stops watching the current teleport. It stays queued on ARGUS's side; this only ends the mod's part. */
    public static void cancelFlow() {
        if (activeId != null) {
            String label = activeWaystone == null ? "the waystone" : activeWaystone.displayName();
            clearFlow("Stopped watching the teleport to " + label + ". It's still queued with ARGUS.");
        }
    }

    /** Asks for confirmation, then starts a teleport to this waystone. */
    public static void requestTeleport(Waystone waystone, Screen previous) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String problem = teleportProblem(mc);
        if (problem != null) {
            feedback(mc, problem);
            return;
        }
        TokenInfo info = tokenInfo;
        String balance = info == null ? "" : " You have " + info.balance() + ".";
        String body = waystone.displayName() + " (" + waystone.dimensionLabel() + ", " + waystone.x() + ", " + waystone.y()
                + ", " + waystone.z() + ")\n\nThis spends 1 Waystone token." + balance + " ARGUS's delivery bot travels to the "
                + "waystone first; when it is ready, ARGUS Mapper sends /tpa to it for you and you're teleported "
                + "once it accepts.\n\n" + botLine() + ".";
        mc.setScreen(new ConfirmScreen(
                confirmed -> {
                    mc.setScreen(confirmed ? null : previous);
                    if (confirmed) {
                        start(mc, waystone);
                    }
                },
                Text.literal("Teleport to this waystone?"), Text.literal(body),
                Text.literal("Yes"), Text.literal("No")));
    }

    private static String teleportProblem(MinecraftClient mc) {
        if (config().token.isBlank()) {
            return "Set your token on the Token tab first.";
        }
        if (mc.player == null || mc.world == null) {
            return "Join a world first.";
        }
        if (mc.getCurrentServerEntry() == null
                || ArgusUploaderClientMod.registry().matching(GameWorldContext.currentWorldToken(mc)).isEmpty()) {
            return "Join the ARGUS server first - the delivery bot is on it.";
        }
        if (isBusy()) {
            return "A teleport is already in progress.";
        }
        SystemStatus status = botStatus;
        if (status != null && !status.online()) {
            return "The delivery bot is offline right now.";
        }
        return null;
    }

    // ------------------------------------------------------------ the teleport itself

    private static void start(MinecraftClient mc, Waystone waystone) {
        String problem = teleportProblem(mc);
        if (problem != null) {
            feedback(mc, problem);
            return;
        }
        starting = true;
        flowText = "Asking ARGUS for a teleport to " + waystone.displayName() + "...";
        String token = config().token;
        int startedIn = generation;
        fetcher.execute(() -> {
            WaystoneClient.Reply<WaystoneParser.Started> reply = client().requestTeleport(token, waystone.name());
            mc.execute(() -> onStarted(startedIn, waystone, reply));
        });
    }

    private static void onStarted(int startedIn, Waystone waystone, WaystoneClient.Reply<WaystoneParser.Started> reply) {
        starting = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (startedIn != generation) {
            flowText = "";
            return;
        }
        if (!reply.ok()) {
            flowText = reply.message();
            feedback(mc, "Teleport not started: " + reply.message());
            nextToken = 0;
            return;
        }
        WaystoneParser.Started started = reply.value();
        activeId = started.id();
        activeWaystone = waystone;
        lastFlowStatus = "";
        flowStartedAt = System.currentTimeMillis();
        nextFlow = flowStartedAt + FLOW_MILLIS;
        flowFailures = 0;
        tpaSends = 0;
        lastTpaAt = 0;
        warnedNoBot = false;
        TokenInfo info = tokenInfo;
        if (info != null) {
            tokenInfo = new TokenInfo(info.ign(), started.balance(), info.earned(), info.spent() + 1,
                    info.regions(), info.regionsPerToken());
        }
        flowText = "Queued for " + waystone.displayName() + ".";
        feedback(mc, "Teleport to " + waystone.displayName() + " queued (" + started.balance() + " left). I'll send /tpa when the bot is ready.");
        version++;
    }

    private static void pollFlow(MinecraftClient mc, long now) {
        if (activeId == null || flowBusy || now < nextFlow) {
            return;
        }
        if (now - flowStartedAt > FLOW_GIVE_UP_MILLIS) {
            clearFlow("Stopped watching the teleport after 15 minutes. Check map.argus.tools.");
            return;
        }
        flowBusy = true;
        String id = activeId;
        String token = config().token;
        fetcher.execute(() -> {
            WaystoneClient.Reply<TeleportStatus> reply = client().teleportStatus(token, id);
            mc.execute(() -> onFlowReply(id, reply));
        });
    }

    private static void onFlowReply(String id, WaystoneClient.Reply<TeleportStatus> reply) {
        flowBusy = false;
        if (!id.equals(activeId)) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.currentTimeMillis();
        nextFlow = now + FLOW_MILLIS;
        if (!reply.ok()) {
            if (reply.statusCode() == 404) {
                clearFlow("ARGUS no longer knows about this teleport. Check map.argus.tools.");
            } else if (++flowFailures >= MAX_FLOW_FAILURES) {
                clearFlow("Lost contact with ARGUS while waiting: " + reply.message());
            }
            return;
        }
        flowFailures = 0;
        TeleportStatus status = reply.value();
        String label = activeWaystone == null ? status.waystone() : activeWaystone.displayName();
        boolean changed = !status.status().equals(lastFlowStatus);
        lastFlowStatus = status.status();
        switch (status.status()) {
            case "queued" -> {
                flowText = status.position() <= 0 ? "Next in line for " + label + "."
                        : status.position() + " ahead of you for " + label + ".";
                if (changed) {
                    feedback(mc, flowText);
                }
            }
            case "homing" -> {
                flowText = "The bot is travelling to " + label + ".";
                if (changed) {
                    feedback(mc, flowText);
                }
            }
            case "ready" -> onReady(mc, status, label, now, changed);
            case "delivered" -> {
                clearFlow("Delivered to " + label + ".");
                feedback(mc, "Delivered to " + label + ".");
            }
            case "failed" -> {
                String reason = "The teleport to " + label + " failed" + (status.error() == null ? "." : ": " + status.error());
                clearFlow(reason);
                feedback(mc, reason);
            }
            case "expired" -> {
                String reason = "The teleport to " + label + " expired before it could be delivered.";
                clearFlow(reason);
                feedback(mc, reason);
            }
            default -> flowText = "Teleport to " + label + ": " + status.status() + ".";
        }
    }

    private static void onReady(MinecraftClient mc, TeleportStatus status, String label, long now, boolean changed) {
        String bot = BotName.validate(status.bot()).or(() -> BotName.validate(config().waystoneBotName)).orElse(null);
        if (bot == null) {
            flowText = "The bot is ready at " + label + " - send it a /tpa yourself.";
            if (!warnedNoBot) {
                warnedNoBot = true;
                feedback(mc, "The bot is ready at " + label + ", but I don't know its name. Send it a /tpa yourself "
                        + "(or set waystoneBotName in the config).");
            }
            return;
        }
        boolean firstSend = tpaSends == 0;
        boolean resend = tpaSends < MAX_TPA_SENDS && now - lastTpaAt >= RESEND_TPA_MILLIS;
        if ((firstSend || resend) && mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendChatCommand("tpa " + bot);
            tpaSends++;
            lastTpaAt = now;
            feedback(mc, (firstSend ? "The bot is ready. Sent /tpa " : "Still waiting; sent /tpa again to ") + bot + ".");
        }
        flowText = "Sent /tpa " + bot + " - waiting for it to accept.";
    }

    private static void clearFlow(String text) {
        activeId = null;
        activeWaystone = null;
        flowText = text;
        flowBusy = false;
        nextToken = 0;
        version++;
    }

    private static void onDisconnect() {
        generation++;
        starting = false;
        activeId = null;
        activeWaystone = null;
        flowText = "";
        flowBusy = false;
        WaystoneMarkers.forget();
        markersVersion = -1;
        markersDimension = "";
        markersStatus = "";
    }

    // ------------------------------------------------------------ polling

    private static void onTick(MinecraftClient mc) {
        if (++ticks % 10 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        ArgusConfig cfg = config();
        boolean tabOpen = now - tabSeenAt < TAB_ALIVE_MILLIS;
        boolean markers = cfg.waystoneMarkers && markersBlockedReason(mc) == null;

        if ((tabOpen || markers) && !listBusy && now >= nextList) {
            fetchList(mc, now);
        }
        if ((tabOpen || activeId != null) && !statusBusy && now >= nextStatus) {
            fetchStatus(mc, now);
        }
        if (tabOpen && !tokenBusy && now >= nextToken && !cfg.token.isBlank()) {
            fetchTokenInfo(mc, now, cfg.token);
        }
        pollFlow(mc, now);

        if (cfg.waystoneMarkers) {
            syncMarkers(mc);
        } else if (!WaystoneMarkers.isEmpty()) {
            WaystoneMarkers.removeAll();
            markersStatus = "";
        }
    }

    private static void fetchList(MinecraftClient mc, long now) {
        listBusy = true;
        listFetchedAt = now;
        nextList = now + LIST_MILLIS;
        int startedIn = generation;
        fetcher.execute(() -> {
            WaystoneClient.Reply<List<Waystone>> reply = client().waystones();
            mc.execute(() -> {
                listBusy = false;
                if (startedIn != generation) {
                    return;
                }
                if (reply.ok()) {
                    listProblem = "";
                    if (!reply.value().equals(list)) {
                        list = List.copyOf(reply.value());
                        version++;
                    }
                } else {
                    listProblem = "Couldn't load the waystone list: " + reply.message();
                    nextList = System.currentTimeMillis() + 15_000L;
                }
            });
        });
    }

    private static void fetchStatus(MinecraftClient mc, long now) {
        statusBusy = true;
        nextStatus = now + STATUS_MILLIS;
        fetcher.execute(() -> {
            WaystoneClient.Reply<SystemStatus> reply = client().systemStatus();
            mc.execute(() -> {
                statusBusy = false;
                if (reply.ok()) {
                    botProblem = "";
                    botStatus = reply.value();
                    botStatusAt = System.currentTimeMillis();
                } else {
                    botProblem = reply.message();
                }
            });
        });
    }

    private static void fetchTokenInfo(MinecraftClient mc, long now, String token) {
        tokenBusy = true;
        nextToken = now + TOKEN_INFO_MILLIS;
        fetcher.execute(() -> {
            WaystoneClient.Reply<TokenInfo> reply = client().tokenInfo(token);
            mc.execute(() -> {
                tokenBusy = false;
                if (reply.ok()) {
                    tokenProblem = "";
                    tokenInfo = reply.value();
                } else {
                    tokenProblem = reply.message();
                }
            });
        });
    }

    // ------------------------------------------------------------ map markers

    private static String markersBlockedReason(MinecraftClient mc) {
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
        return null;
    }

    private static void syncMarkers(MinecraftClient mc) {
        String blocked = markersBlockedReason(mc);
        if (blocked != null) {
            markersStatus = "Waiting: " + blocked;
            return;
        }
        String dimension = WaystoneMarkers.nameOf(mc.world.getRegistryKey());
        if (markersVersion == version && dimension.equals(markersDimension) && !WaystoneMarkers.isEmpty()) {
            return;
        }
        if (list.isEmpty()) {
            markersStatus = listProblem.isEmpty() ? "Waiting for the waystone list" : listProblem;
            return;
        }
        WaystoneMarkers.Outcome outcome = WaystoneMarkers.apply(list, mc.world.getRegistryKey());
        markersVersion = version;
        markersDimension = dimension;
        markersStatus = outcome.ok() ? outcome.count() + " waystone(s) on the map" : "Waiting: " + outcome.problem();
        if (!outcome.ok()) {
            markersVersion = -1;
        }
    }

    /** The waystone behind a waypoint on the World Map, or null if it isn't one of ours. */
    public static Waystone waystoneForMarker(Object minimapWaypoint) {
        return WaystoneMarkers.waystoneFor(minimapWaypoint);
    }

    // ------------------------------------------------------------ helpers

    private static ArgusConfig config() {
        return ArgusUploaderClientMod.config();
    }

    private static synchronized WaystoneClient client() {
        String url = config().waystoneBaseUrl;
        if (api == null || !url.equals(apiUrl)) {
            api = new WaystoneClient(url);
            apiUrl = url;
        }
        return api;
    }

    private static void feedback(MinecraftClient mc, String message) {
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("[ARGUS] " + message), false);
            }
        });
    }
}
