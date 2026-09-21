package tools.argus.uploader.core.automap;

import java.util.List;

/**
 * Steers along a {@link Waypoint} path. It aims a little ahead of the point on the current leg
 * nearest the player, so drift (a rubberband, say) pulls back onto the line instead of orbiting a
 * waypoint. The first leg starts wherever the player is; later legs start at the previous waypoint.
 */
public final class PathFollower {

    /** @param yawDegrees Minecraft yaw: 0 is +Z (south), -90 is +X (east) */
    public record Steer(double yawDegrees, boolean finished) {
    }

    private final List<Waypoint> path;
    private int index;
    private boolean legStarted;
    private double legStartX;
    private double legStartZ;
    private double lastYaw;

    public PathFollower(List<Waypoint> path) {
        this.path = path;
    }

    public int index() {
        return index;
    }

    public boolean isFinished() {
        return index >= path.size();
    }

    public boolean onLane() {
        return !isFinished() && path.get(index).onLane();
    }

    public Steer steer(double x, double z, double blocksPerTick) {
        while (index < path.size()) {
            if (!legStarted) {
                legStartX = x;
                legStartZ = z;
                legStarted = true;
            }
            Waypoint target = path.get(index);
            double dx = target.x() - legStartX;
            double dz = target.z() - legStartZ;
            double length = Math.hypot(dx, dz);
            double ux = length == 0 ? 0 : dx / length;
            double uz = length == 0 ? 1 : dz / length;
            double along = (x - legStartX) * ux + (z - legStartZ) * uz;

            if (length - along <= Math.max(2.0, blocksPerTick * 0.75)) {
                legStartX = target.x();
                legStartZ = target.z();
                index++;
                continue;
            }
            double lookAhead = Math.max(24.0, blocksPerTick * 5);
            double aimAlong = Math.min(length, Math.max(along, 0) + lookAhead);
            lastYaw = yawTowards(legStartX + ux * aimAlong - x, legStartZ + uz * aimAlong - z);
            return new Steer(lastYaw, false);
        }
        return new Steer(lastYaw, true);
    }

    public static double yawTowards(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }
}
