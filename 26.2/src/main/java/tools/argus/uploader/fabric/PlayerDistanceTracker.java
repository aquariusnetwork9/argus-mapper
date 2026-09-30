package tools.argus.uploader.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import tools.argus.uploader.core.StatsStore;

/**
 * 26.2 copy of fabric-common's tracker. Mojang-mapped equivalents verified via javap against the
 * real 26.2 client jar: Yarn's {@code Vec3d} is {@code net.minecraft.world.phys.Vec3} here, same
 * {@code Vec3(double, double, double)} constructor and {@code distanceTo(Vec3)} method.
 */
final class PlayerDistanceTracker {

    private static final double MAX_SANE_BLOCKS_PER_TICK = 50.0;
    private static final long FLUSH_INTERVAL_MS = 10_000L;

    private static Vec3 lastPos;
    private static double pendingBlocks;
    private static long lastFlush = System.currentTimeMillis();

    private PlayerDistanceTracker() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PlayerDistanceTracker::onTick);
    }

    private static void onTick(Minecraft client) {
        if (client.player == null) {
            lastPos = null;
            return;
        }
        Vec3 pos = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
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
