package tools.argus.uploader.core.automap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallPartsTest {

    @Test
    void boxOfRegionsCoversWholeRegions() {
        ChunkBox box = ChunkBox.ofRegions(-1, 2, 0, 2);

        assertEquals(new ChunkBox(-32, 64, 31, 95), box);
        assertEquals(64L * 32, box.chunkCount());
        assertEquals(-512, box.minBlockX());
        assertEquals(512, box.maxBlockX());
    }

    @Test
    void boxIsClampedToTheRegionLimitOrDropped() {
        ChunkBox wide = ChunkBox.ofRegions(-300, 0, 300, 0);
        assertEquals(new ChunkBox(-200 * 32, 0, 200 * 32 + 31, 31), wide.clampedToRegions(200).orElseThrow());
        assertEquals(Optional.empty(), ChunkBox.ofRegions(250, 0, 260, 0).clampedToRegions(200));
    }

    @Test
    void yawFollowsMinecraftsConvention() {
        assertEquals(0, PathFollower.yawTowards(0, 10), 0.001, "south");
        assertEquals(-90, PathFollower.yawTowards(10, 0), 0.001, "east");
        assertEquals(90, PathFollower.yawTowards(-10, 0), 0.001, "west");
        assertEquals(180, Math.abs(PathFollower.yawTowards(0, -10)), 0.001, "north");
    }

    @Test
    void followerHeadsForTheWaypointThenFinishes() {
        PathFollower follower = new PathFollower(List.of(new Waypoint(100, 0, false), new Waypoint(100, 100, true)));

        assertEquals(-90, follower.steer(0, 0, 5).yawDegrees(), 0.001);
        assertFalse(follower.onLane());
        follower.steer(99, 0, 5);
        assertEquals(1, follower.index());
        assertTrue(follower.onLane());
        assertEquals(0, follower.steer(100, 20, 5).yawDegrees(), 1.0, "now south along the lane");
        assertTrue(follower.steer(100, 99, 5).finished());
        assertTrue(follower.isFinished());
    }

    @Test
    void followerPullsBackOntoALaneItDriftedOff() {
        PathFollower follower = new PathFollower(List.of(new Waypoint(0, 0, false), new Waypoint(1_000, 0, true)));
        follower.steer(0, 0, 5);
        follower.steer(0, 0, 5);

        double yaw = follower.steer(400, 60, 5).yawDegrees();

        assertTrue(yaw < -90 && yaw > -180, "heads east and back toward the line (north of it), was " + yaw);
    }

    @Test
    void aTurnAroundIsNotMistakenForARubberband() {
        RubberbandDetector detector = new RubberbandDetector();
        detector.observe(0, 0, -90, true);
        detector.observe(6, 0, -90, true);

        assertFalse(detector.observe(12, 0, -90, true));
        assertFalse(detector.observe(6, 0, 90, true), "the new heading is west and it moved west");
    }

    @Test
    void movingBackAgainstTheHeadingIsARubberband() {
        RubberbandDetector detector = new RubberbandDetector();
        detector.observe(0, 0, -90, true);
        detector.observe(6, 0, -90, true);

        assertTrue(detector.observe(-20, 0, -90, true));
        assertFalse(detector.observe(-30, 0, -90, false), "not moving on purpose: nothing to judge");
    }

    @Test
    void governorBacksOffWhenTheMapFallsBehindAndCreepsBackUp() {
        SpeedGovernor governor = new SpeedGovernor(1.5, 5.99, 5.0);

        governor.update(0, true);
        governor.update(1_000, false);
        double slowed = governor.speed();
        assertTrue(slowed < 5.0);

        long now = 1_000;
        for (int i = 0; i < 12; i++) {
            now += 1_000;
            governor.update(now, true);
        }
        assertTrue(governor.speed() > slowed);
        assertTrue(governor.speed() <= 5.99);
    }

    @Test
    void governorNeverGoesUnderTheFloorOrOverTheCap() {
        SpeedGovernor governor = new SpeedGovernor(1.5, 5.99, 6.5);
        assertEquals(5.99, governor.speed(), 0.0001);

        long now = 0;
        for (int i = 0; i < 100; i++) {
            now += 1_000;
            governor.update(now, false);
        }
        assertEquals(1.5, governor.speed(), 0.0001);
    }

    @Test
    void aRubberbandLowersTheCeilingForTheRestOfTheRun() {
        SpeedGovernor governor = new SpeedGovernor(1.5, 5.99, 5.99);
        governor.update(0, true);

        governor.onRubberband(2_000);
        double ceiling = governor.ceiling();
        assertEquals(5.89, ceiling, 0.0001);
        assertTrue(governor.speed() < ceiling);

        long now = 2_000;
        for (int i = 0; i < 300; i++) {
            now += 1_000;
            governor.update(now, true);
        }
        assertEquals(ceiling, governor.speed(), 0.0001);
    }

    @Test
    void coverageRemembersChunksOnceSeen() {
        CoverageTracker tracker = new CoverageTracker(new ChunkBox(0, 0, 9, 9));

        tracker.observe(2, 2, 2, (x, z) -> true);
        long after = tracker.coveredCount();
        tracker.observe(2, 2, 2, (x, z) -> false);

        assertEquals(25, after);
        assertEquals(25, tracker.coveredCount(), "a chunk that later unloads stays counted");
        assertEquals(75, tracker.missingCount());
        assertTrue(tracker.isCovered(0, 0));
        assertFalse(tracker.isCovered(9, 9));
        assertTrue(tracker.isCovered(500, 500), "outside the box never counts as missing");
        assertEquals(75, tracker.missing().size());
    }

    @Test
    void repairStopsAreOneEightByEightCellEachNearestFirst() {
        List<CoverageTracker.Chunk> missing = new ArrayList<>();
        missing.add(new CoverageTracker.Chunk(1, 1));
        missing.add(new CoverageTracker.Chunk(2, 1));
        missing.add(new CoverageTracker.Chunk(40, 1));
        missing.add(new CoverageTracker.Chunk(20, 1));

        List<RepairPlanner.Stop> stops = RepairPlanner.plan(missing, 0, 0);

        assertEquals(3, stops.size());
        assertEquals(2, stops.get(0).chunks().size());
        assertEquals(1.5 * 16 + 8, stops.get(0).x(), 0.001);
        assertEquals(20 * 16 + 8, stops.get(1).x(), 0.001);
        assertEquals(40 * 16 + 8, stops.get(2).x(), 0.001);
    }

    @Test
    void lateralWidthCountsCoveredChunksEitherSideAndReportsTheNarrowerOne() {
        // Flying east (yaw -90) along z=8; chunks are covered for |cz| <= 3 on the north and <= 5 south.
        ChunkProbe probe = (cx, cz) -> cz >= -3 && cz <= 5;

        assertEquals(3, LateralWidth.measure(200, 8, -90, 2, 10, probe));
        assertEquals(2, LateralWidth.measure(200, 8, -90, 2, 2, probe), "capped at the maximum");
    }
}
