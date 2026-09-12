package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import tools.argus.uploader.core.StatsStore;

/**
 * Accumulates player movement for the "distance traveled" leaderboard stat.
 * Flushes to disk periodically rather than every tick to avoid hammering the
 * filesystem, and discards any single-tick jump bigger than a real player
 * could ever move (teleports, /home, etc.) so those can't inflate the number.
 */
final class PlayerDistanceTracker {

    private static final double MAX_SANE_BLOCKS_PER_TICK = 50.0;
    private static final long FLUSH_INTERVAL_MS = 10_000L;

    private static Vec3d lastPos;
    private static double pendingBlocks;
    private static long lastFlush = System.currentTimeMillis();

    private PlayerDistanceTracker() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PlayerDistanceTracker::onTick);
    }

    private static void onTick(MinecraftClient client) {
        if (client.player == null) {
            lastPos = null;
            return;
        }
        Vec3d pos = client.player.getPos();
        if (lastPos != null) {
            double delta = lastPos.distanceTo(pos);
            if (delta < MAX_SANE_BLOCKS_PER_TICK) {
                pendingBlocks += delta;
            }
        }
        lastPos = pos;

        long now = System.currentTimeMillis();
        if (now - lastFlush >= FLUSH_INTERVAL_MS && pendingBlocks > 0) {
            flush();
            lastFlush = now;
        }
    }

    private static void flush() {
        StatsStore stats = ArgusUploaderClientMod.stats();
        double toFlush = pendingBlocks;
        pendingBlocks = 0;
        try {
            stats.addDistance(toFlush);
        } catch (Exception e) {
            // Non-fatal: worst case a leaderboard stat is a bit behind. Put it
            // back so it isn't silently lost on a transient disk error.
            pendingBlocks += toFlush;
        }
    }
}
