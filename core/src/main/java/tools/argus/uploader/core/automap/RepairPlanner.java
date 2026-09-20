package tools.argus.uploader.core.automap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns leftover holes into stops to fly to, one per 8x8-chunk cell, nearest first. */
public final class RepairPlanner {

    private static final int CELL_SHIFT = 3;

    private RepairPlanner() {
    }

    /** @param chunks the missing chunks this stop is meant to fill */
    public record Stop(double x, double z, List<CoverageTracker.Chunk> chunks) {
    }

    public static List<Stop> plan(List<CoverageTracker.Chunk> missing, double fromX, double fromZ) {
        Map<Long, List<CoverageTracker.Chunk>> cells = new HashMap<>();
        for (CoverageTracker.Chunk chunk : missing) {
            long key = ((long) (chunk.x() >> CELL_SHIFT) << 32) | ((chunk.z() >> CELL_SHIFT) & 0xFFFFFFFFL);
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }
        List<Stop> remaining = new ArrayList<>();
        for (List<CoverageTracker.Chunk> cell : cells.values()) {
            double sumX = 0;
            double sumZ = 0;
            for (CoverageTracker.Chunk chunk : cell) {
                sumX += chunk.x() * 16.0 + 8;
                sumZ += chunk.z() * 16.0 + 8;
            }
            remaining.add(new Stop(sumX / cell.size(), sumZ / cell.size(), cell));
        }
        List<Stop> ordered = new ArrayList<>();
        double x = fromX;
        double z = fromZ;
        while (!remaining.isEmpty()) {
            Stop nearest = remaining.get(0);
            double best = Double.MAX_VALUE;
            for (Stop stop : remaining) {
                double distance = Math.hypot(stop.x() - x, stop.z() - z);
                if (distance < best) {
                    best = distance;
                    nearest = stop;
                }
            }
            remaining.remove(nearest);
            ordered.add(nearest);
            x = nearest.x();
            z = nearest.z();
        }
        return ordered;
    }
}
