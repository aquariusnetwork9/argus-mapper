package tools.argus.uploader.core.automap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanePlannerTest {

    @Test
    void startsAtTheCornerNearestThePlayer() {
        ChunkBox box = ChunkBox.ofRegions(0, 0, 1, 1);

        List<Waypoint> fromSouthEast = LanePlanner.plan(box, 5_000, 5_000, 6);
        assertEquals(1_024, fromSouthEast.get(0).x(), 0.001, "east edge, the near end of the lane");
        assertTrue(fromSouthEast.get(0).z() > 512, "the lane on the south side");

        List<Waypoint> fromNorthWest = LanePlanner.plan(box, -500, -500, 6);
        assertEquals(0, fromNorthWest.get(0).x(), 0.001);
        assertTrue(fromNorthWest.get(0).z() < 512);
    }

    @Test
    void lanesAlternateDirectionAndShiftSidewaysBetweenThem() {
        List<Waypoint> path = LanePlanner.plan(ChunkBox.ofRegions(0, 0, 0, 0), -100, -100, 6);

        assertEquals(6, path.size(), "three lanes, a start and an end each");
        assertEquals(0, path.get(0).x(), 0.001);
        assertEquals(512, path.get(1).x(), 0.001);
        assertEquals(512, path.get(2).x(), 0.001, "the next lane starts where this one ended");
        assertEquals(0, path.get(3).x(), 0.001);
        assertEquals(path.get(0).z(), path.get(1).z(), 0.001, "a lane is straight");
        for (int i = 0; i < path.size(); i++) {
            assertEquals(i % 2 == 1, path.get(i).onLane());
        }
    }

    @Test
    void lanesRunAlongTheLongerSide() {
        List<Waypoint> tall = LanePlanner.plan(ChunkBox.ofRegions(0, 0, 0, 3), 0, -100, 6);

        assertEquals(tall.get(0).x(), tall.get(1).x(), 0.001, "constant x: the lane runs north-south");
        assertTrue(Math.abs(tall.get(1).z() - tall.get(0).z()) > 1_000);
    }

    @Test
    void aBoxNarrowerThanOneLaneGetsOneLaneDownItsMiddle() {
        List<Waypoint> path = LanePlanner.plan(new ChunkBox(0, 0, 3, 40), 0, 0, 6);

        assertEquals(2, path.size());
        assertEquals(32, path.get(0).x(), 0.001);
    }

    @Test
    void everyChunkCenterIsWithinReachOfSomeLane() {
        Random random = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            int x1 = random.nextInt(200) - 100;
            int z1 = random.nextInt(200) - 100;
            ChunkBox box = ChunkBox.ofCorners(x1, z1, x1 + random.nextInt(90), z1 + random.nextInt(90));
            int half = 2 + random.nextInt(9);
            List<Waypoint> path = LanePlanner.plan(box, random.nextInt(4_000) - 2_000, random.nextInt(4_000) - 2_000, half);

            for (int cx = box.minX(); cx <= box.maxX(); cx++) {
                for (int cz = box.minZ(); cz <= box.maxZ(); cz++) {
                    assertTrue(distanceToNearestLane(path, cx * 16 + 8, cz * 16 + 8) <= half * 16 + 0.001,
                            "chunk " + cx + "," + cz + " in " + box + " half " + half);
                }
            }
        }
    }

    private static double distanceToNearestLane(List<Waypoint> path, double x, double z) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < path.size(); i += 2) {
            Waypoint a = path.get(i - 1);
            Waypoint b = path.get(i);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double t = Math.max(0, Math.min(1, ((x - a.x()) * dx + (z - a.z()) * dz) / (dx * dx + dz * dz)));
            best = Math.min(best, Math.hypot(x - (a.x() + dx * t), z - (a.z() + dz * t)));
        }
        return best;
    }
}
