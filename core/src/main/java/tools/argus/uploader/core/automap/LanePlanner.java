package tools.argus.uploader.core.automap;

import java.util.ArrayList;
import java.util.List;

/**
 * Back-and-forth lanes over a {@link ChunkBox}, starting at whichever corner is nearest the player.
 * Lanes run along the longer side (fewer turns) and are spaced evenly so that every chunk in the
 * box is within {@code halfWidthChunks} of one.
 */
public final class LanePlanner {

    private LanePlanner() {
    }

    /** The waypoints of the whole path, beginning with the transit to the first lane's start. */
    public static List<Waypoint> plan(ChunkBox box, double startX, double startZ, int halfWidthChunks) {
        double halfWidthBlocks = Math.max(1, halfWidthChunks) * 16.0;
        boolean alongX = box.maxBlockX() - box.minBlockX() >= box.maxBlockZ() - box.minBlockZ();

        double acrossMin = alongX ? box.minBlockZ() : box.minBlockX();
        double acrossMax = alongX ? box.maxBlockZ() : box.maxBlockX();
        double alongMin = alongX ? box.minBlockX() : box.minBlockZ();
        double alongMax = alongX ? box.maxBlockX() : box.maxBlockZ();

        double lo = acrossMin + 8;
        double hi = acrossMax - 8;
        int laneCount = Math.max(1, (int) Math.ceil((hi - lo) / (2 * halfWidthBlocks)));
        double[] centers = new double[laneCount];
        double spacing = laneCount == 1 ? 0 : (hi - lo) / laneCount;
        for (int i = 0; i < laneCount; i++) {
            centers[i] = laneCount == 1 ? (lo + hi) / 2 : lo + spacing * (i + 0.5);
        }

        double startAcross = alongX ? startZ : startX;
        double startAlong = alongX ? startX : startZ;
        boolean firstLaneNearest = laneCount == 1
                || Math.abs(startAcross - centers[0]) <= Math.abs(startAcross - centers[laneCount - 1]);
        double nearestLane = firstLaneNearest ? centers[0] : centers[laneCount - 1];
        boolean forward = Math.hypot(startAlong - alongMin, startAcross - nearestLane)
                <= Math.hypot(startAlong - alongMax, startAcross - nearestLane);

        List<Waypoint> path = new ArrayList<>();
        for (int n = 0; n < laneCount; n++) {
            double across = firstLaneNearest ? centers[n] : centers[laneCount - 1 - n];
            double from = forward ? alongMin : alongMax;
            double to = forward ? alongMax : alongMin;
            path.add(waypoint(alongX, from, across, false));
            path.add(waypoint(alongX, to, across, true));
            forward = !forward;
        }
        return path;
    }

    public static double length(List<Waypoint> path, double startX, double startZ) {
        double total = 0;
        double x = startX;
        double z = startZ;
        for (Waypoint point : path) {
            total += Math.hypot(point.x() - x, point.z() - z);
            x = point.x();
            z = point.z();
        }
        return total;
    }

    private static Waypoint waypoint(boolean alongX, double along, double across, boolean onLane) {
        return alongX ? new Waypoint(along, across, onLane) : new Waypoint(across, along, onLane);
    }
}
