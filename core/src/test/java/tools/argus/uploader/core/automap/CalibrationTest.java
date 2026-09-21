package tools.argus.uploader.core.automap;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.DoubleToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationTest {

    private static final int VIEW = 9;

    /** Flying east from the origin: chunks near the player load, and the ones within a speed-dependent half-width get mapped. */
    private static final class Sim {
        double x;
        double z = 8;
        long now;
        boolean gliding = true;
        String dimension = "overworld";
        double speed = 1.0;
        final Set<Long> mapped = new HashSet<>();
        DoubleToIntFunction mappedHalfWidth = s -> s <= 1.5 ? 7 : s <= 3.5 ? 3 : 1;
        DoubleToIntFunction mappedBefore = s -> 0;

        static long key(int cx, int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }

        ChunkProbe loaded() {
            return (cx, cz) -> Math.abs(cx - Math.floorDiv((int) Math.floor(x), 16)) <= VIEW
                    && Math.abs(cz - Math.floorDiv((int) Math.floor(z), 16)) <= VIEW;
        }

        ChunkProbe written() {
            return (cx, cz) -> mapped.contains(key(cx, cz));
        }

        void mapAroundPlayer() {
            int pcx = Math.floorDiv((int) Math.floor(x), 16);
            int pcz = Math.floorDiv((int) Math.floor(z), 16);
            int half = mappedHalfWidth.applyAsInt(speed);
            for (int cx = pcx - VIEW; cx <= pcx + VIEW; cx++) {
                for (int cz = pcz - half; cz <= pcz + half; cz++) {
                    mapped.add(key(cx, cz));
                }
            }
        }
    }

    private static CalibrationController controller(Sim sim, CalibrationSchedule schedule) {
        return new CalibrationController(schedule, sim.x, sim.z, 0, "overworld", VIEW, sim.loaded(), sim.written(),
                (bx, bz) -> OptionalInt.empty(), 300, 8, 0.8,
                () -> new CalibrationController.Extras(60, 40, "C: 100/200"));
    }

    private static void fly(Sim sim, CalibrationController controller, int maxTicks) {
        for (int tick = 0; tick < maxTicks; tick++) {
            AutoMapController.Command command = controller.tick(new AutoMapController.Frame(
                    sim.now, sim.x, 300, sim.z, sim.gliding, sim.dimension));
            if (command == null) {
                return;
            }
            sim.speed = command.horizontalSpeed();
            double radians = Math.toRadians(command.yawDegrees());
            sim.x += -Math.sin(radians) * command.horizontalSpeed();
            sim.z += Math.cos(radians) * command.horizontalSpeed();
            sim.mapAroundPlayer();
            sim.now += 50;
        }
    }

    @Test
    void headingSnapsToTheNearestCardinal() {
        assertEquals(1, CalibrationController.cardinalFromYaw(10), "south");
        assertEquals(2, CalibrationController.cardinalFromYaw(100), "west");
        assertEquals(3, CalibrationController.cardinalFromYaw(170), "north");
        assertEquals(0, CalibrationController.cardinalFromYaw(-80), "east");
        assertEquals(3, CalibrationController.cardinalFromYaw(-175), "north from the other side");
    }

    @Test
    void scheduleStepsThroughItsSpeedsAndSettlesFirst() {
        CalibrationSchedule schedule = new CalibrationSchedule(new double[]{1, 2, 3}, 1_000, 4_000);

        assertEquals(15_000, schedule.totalMillis());
        assertEquals(0, schedule.stageAt(0));
        assertEquals(1, schedule.stageAt(5_000));
        assertEquals(2, schedule.stageAt(14_999));
        assertEquals(-1, schedule.stageAt(15_000));
        assertTrue(schedule.isSettling(5_500));
        assertTrue(!schedule.isSettling(6_500));
    }

    @Test
    void scheduleParsingFallsBackToTheDefaultsOnBadInput() {
        assertEquals(3, CalibrationSchedule.parse("1.25, 2 3").stages());
        assertEquals(CalibrationSchedule.DEFAULT_SPEEDS.length, CalibrationSchedule.parse("fast").stages());
        assertEquals(CalibrationSchedule.DEFAULT_SPEEDS.length, CalibrationSchedule.parse("99").stages());
        assertEquals(CalibrationSchedule.DEFAULT_SPEEDS.length, CalibrationSchedule.parse("").stages());
    }

    @Test
    void recordsHowWideAStripWasMappedAtEachSpeed() {
        Sim sim = new Sim();
        CalibrationSchedule schedule = new CalibrationSchedule(new double[]{1.25, 3.0, 5.0}, 2_000, 8_000);
        CalibrationController controller = controller(sim, schedule);

        fly(sim, controller, 5_000);

        assertEquals(AutoMapController.State.FINISHED, controller.state());
        CorridorRecorder.StageResult slow = controller.result(0);
        CorridorRecorder.StageResult medium = controller.result(1);
        assertTrue(slow.cells() > 5 && medium.cells() > 10, "cells " + slow.cells() + " / " + medium.cells());
        assertEquals(15, slow.widths().written20());
        assertEquals(7, medium.widths().written20());
        assertEquals(15, slow.widths().written6());
        assertTrue(slow.widths().loaded() >= 17, "everything within view loads");
        assertEquals(1.25, controller.measuredSpeed(0), 0.01);
        assertEquals(3.0, controller.measuredSpeed(1), 0.01);
    }

    @Test
    void theSummaryListsEachStageAndSuggestsAWidthTable() {
        Sim sim = new Sim();
        CalibrationSchedule schedule = new CalibrationSchedule(new double[]{1.25, 3.0, 5.0}, 2_000, 8_000);
        CalibrationController controller = controller(sim, schedule);
        fly(sim, controller, 5_000);

        String summary = CalibrationReport.summary(controller, List.of("fast mapping: off"));

        assertTrue(summary.contains("fast mapping: off"));
        assertTrue(summary.contains("heading: east (+X)"));
        assertTrue(summary.contains("autoMapWidthTable=1.25:15,3.00:7"), summary);
        assertTrue(summary.contains("Best speed by that table"));
        String rows = CalibrationReport.rowsCsv(controller);
        assertTrue(rows.startsWith("stage,set_speed,measured_speed,row,loaded,mapped_lag6,mapped_lag20"));
        assertTrue(rows.lines().count() > 33);
        assertTrue(controller.samplesCsv().lines().count() > 100);
        assertTrue(controller.samplesCsv().startsWith("t_ms,stage,set_speed,measured_speed"));
    }

    @Test
    void abortsLikeTheMapperDoes() {
        Sim sim = new Sim();
        CalibrationController portal = controller(sim, CalibrationSchedule.defaults());
        assertNotNull(portal.tick(new AutoMapController.Frame(0, 0, 300, 0, true, "overworld")));
        assertNull(portal.tick(new AutoMapController.Frame(50, 0, 300, 0, true, "the_nether")));
        assertTrue(portal.abortReason().contains("dimension"));

        CalibrationController teleported = controller(sim, CalibrationSchedule.defaults());
        teleported.tick(new AutoMapController.Frame(0, 0, 300, 0, true, "overworld"));
        assertNull(teleported.tick(new AutoMapController.Frame(50, 5_000, 300, 0, true, "overworld")));
        assertTrue(teleported.abortReason().contains("teleported"));

        CalibrationController grounded = controller(sim, CalibrationSchedule.defaults());
        for (int i = 0; i < 30; i++) {
            assertNotNull(grounded.tick(new AutoMapController.Frame(i * 50L, 0, 300, 0, false, "overworld")));
        }
        assertNull(grounded.tick(new AutoMapController.Frame(2_000, 0, 300, 0, false, "overworld")));
        assertTrue(grounded.abortReason().contains("gliding"));
    }

    @Test
    void countsTheMappedRegionsAlongAStraightLine() {
        List<int[]> regions = List.of(new int[]{1, 0}, new int[]{3, 1}, new int[]{3, 5}, new int[]{-2, 0}, new int[]{0, 0});

        assertEquals(3, CorridorOverlap.count(regions, 100, 100, 0, 2_000), "east: regions (0,0), (1,0), (3,1)");
        assertEquals(2, CorridorOverlap.count(regions, 100, 100, 2, 2_000), "west: (0,0) and (-2,0)");
        assertEquals(2, CorridorOverlap.count(regions, 100, 100, 3, 2_000), "north: (0,0) and (1,0), one region either side");
    }

    @Test
    void picksTheCleanestDirectionAndKeepsTheOneYouWereFacingOnATie() {
        List<int[]> regions = List.of(new int[]{1, 0}, new int[]{2, 0}, new int[]{0, 1});

        assertEquals(2, CorridorOverlap.leastMapped(regions, 100, 100, 0, 3_000));
        assertEquals(3, CorridorOverlap.leastMapped(List.of(), 100, 100, 3, 3_000), "nothing mapped: no reason to turn");
    }
}
