package tools.argus.uploader.core.automap;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.function.DoubleToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMapControllerTest {

    private static final AutoMapController.Settings SETTINGS =
            new AutoMapController.Settings(6, 1.5, 5.99, 4.6, 300, 8, 0.8, 4, 2);

    /** A one-player world: it moves exactly as commanded, and chunks within a radius of it are covered. */
    private static final class Sim {
        double x;
        double y = 300;
        double z;
        long now;
        boolean gliding = true;
        String dimension = "overworld";
        AutoMapController.Terrain terrain = (bx, bz) -> OptionalInt.empty();
        DoubleToIntFunction radiusAtSpeed = speed -> 8;
        double currentSpeed = 4.6;

        ChunkProbe probe() {
            return (cx, cz) -> {
                int pcx = Math.floorDiv((int) Math.floor(x), 16);
                int pcz = Math.floorDiv((int) Math.floor(z), 16);
                return Math.abs(cx - pcx) <= radiusAtSpeed.applyAsInt(currentSpeed)
                        && Math.abs(cz - pcz) <= radiusAtSpeed.applyAsInt(currentSpeed);
            };
        }

        AutoMapController.Frame frame() {
            return new AutoMapController.Frame(now, x, y, z, gliding, dimension);
        }

        void apply(AutoMapController.Command command) {
            currentSpeed = command.horizontalSpeed();
            if (command.forward()) {
                double radians = Math.toRadians(command.yawDegrees());
                x += -Math.sin(radians) * command.horizontalSpeed();
                z += Math.cos(radians) * command.horizontalSpeed();
            }
            switch (command.vertical()) {
                case UP -> y += 0.8;
                case DOWN -> y -= 0.8;
                case HOLD -> {
                }
            }
            now += 50;
        }
    }

    private static AutoMapController controllerFor(Sim sim, ChunkBox box) {
        return new AutoMapController(box, SETTINGS, "overworld", sim.x, sim.z, sim.probe(), (bx, bz) -> sim.terrain.topY(bx, bz));
    }

    private static int run(Sim sim, AutoMapController controller, int maxTicks) {
        for (int tick = 0; tick < maxTicks; tick++) {
            AutoMapController.Command command = controller.tick(sim.frame());
            if (command == null) {
                return tick;
            }
            sim.apply(command);
        }
        return maxTicks;
    }

    @Test
    void mapsAWholeRegionFromAnOutsideStartWithNoGaps() {
        Sim sim = new Sim();
        sim.x = -900;
        sim.z = -900;
        AutoMapController controller = controllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));

        run(sim, controller, 20_000);

        assertEquals(AutoMapController.State.FINISHED, controller.state());
        assertEquals(0, controller.missingChunks());
        assertEquals(1.0, controller.progress(), 0.0001);
    }

    @Test
    void mapsSeveralRegionsAndEndsOnTheLastLane() {
        Sim sim = new Sim();
        AutoMapController controller = controllerFor(sim, ChunkBox.ofRegions(0, 0, 2, 1));

        run(sim, controller, 40_000);

        assertEquals(AutoMapController.State.FINISHED, controller.state());
        assertEquals(0, controller.missingChunks());
    }

    @Test
    void slowsDownWhenFasterFlightLosesTheSides() {
        Sim sim = new Sim();
        sim.radiusAtSpeed = speed -> (int) Math.round(8 - 2 * Math.max(0, speed - 3));
        AutoMapController.Settings flatOut = new AutoMapController.Settings(6, 1.5, 5.99, 5.99, 300, 8, 0.8, 4, 2);
        AutoMapController controller = new AutoMapController(ChunkBox.ofRegions(0, 0, 1, 1), flatOut, "overworld",
                sim.x, sim.z, sim.probe(), (bx, bz) -> OptionalInt.empty());

        run(sim, controller, 60_000);

        assertEquals(AutoMapController.State.FINISHED, controller.state());
        assertTrue(controller.speed() < 5.0, "settled at " + controller.speed());
        assertTrue(controller.progress() > 0.99, "covered " + controller.progress());
    }

    @Test
    void fillsHolesTheLanesLeftBehind() {
        Sim sim = new Sim();
        int holeChunkX = 20;
        int holeChunkZ = 20;
        ChunkProbe base = sim.probe();
        boolean[] hasFlownRepair = new boolean[1];
        ChunkProbe probe = (cx, cz) -> !(cx == holeChunkX && cz == holeChunkZ && !hasFlownRepair[0]) && base.test(cx, cz);
        AutoMapController controller = new AutoMapController(ChunkBox.ofRegions(0, 0, 0, 0), SETTINGS, "overworld",
                sim.x, sim.z, probe, (bx, bz) -> OptionalInt.empty());

        for (int tick = 0; tick < 30_000; tick++) {
            AutoMapController.Command command = controller.tick(sim.frame());
            if (command == null) {
                break;
            }
            if (!command.forward()) {
                hasFlownRepair[0] = true;
            }
            sim.apply(command);
        }

        assertEquals(AutoMapController.State.FINISHED, controller.state());
        assertEquals(0, controller.missingChunks());
    }

    @Test
    void givesUpOnChunksThatNeverLoadAndSaysSo() {
        Sim sim = new Sim();
        ChunkProbe base = sim.probe();
        ChunkProbe probe = (cx, cz) -> !(cx == 5 && cz == 5) && base.test(cx, cz);
        AutoMapController controller = new AutoMapController(ChunkBox.ofRegions(0, 0, 0, 0), SETTINGS, "overworld",
                sim.x, sim.z, probe, (bx, bz) -> OptionalInt.empty());

        run(sim, controller, 40_000);

        assertEquals(AutoMapController.State.FINISHED, controller.state());
        assertEquals(1, controller.missingChunks());
    }

    @Test
    void climbsAboveTerrainAheadAndHoldsCruiseAltitudeOtherwise() {
        Sim sim = new Sim();
        sim.y = 100;
        AutoMapController controller = controllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));

        AutoMapController.Command command = controller.tick(sim.frame());
        assertEquals(AutoMapController.Vertical.UP, command.vertical(), "well under the cruise altitude");

        sim.y = 300;
        sim.terrain = (bx, bz) -> OptionalInt.of(340);
        command = controller.tick(sim.frame());
        assertEquals(AutoMapController.Vertical.UP, command.vertical(), "a tall ridge ahead");

        sim.terrain = (bx, bz) -> OptionalInt.of(100);
        controller = controllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));
        assertEquals(AutoMapController.Vertical.HOLD, controller.tick(sim.frame()).vertical());
    }

    @Test
    void slowsForAClimbItCannotMakeInTime() {
        Sim sim = new Sim();
        sim.terrain = (bx, bz) -> OptionalInt.of(500);
        AutoMapController controller = controllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));

        AutoMapController.Command command = controller.tick(sim.frame());

        assertTrue(command.horizontalSpeed() < 4.6, "was " + command.horizontalSpeed());
        assertTrue(command.horizontalSpeed() >= 1.5);
    }

    @Test
    void aRubberbandLowersTheSpeedCeiling() {
        Sim sim = new Sim();
        AutoMapController controller = controllerFor(sim, ChunkBox.ofRegions(0, 0, 3, 3));
        for (int i = 0; i < 40; i++) {
            sim.apply(controller.tick(sim.frame()));
        }
        double before = controller.speed();

        sim.x -= 60;
        sim.z -= 60;
        for (int i = 0; i < 5; i++) {
            sim.apply(controller.tick(sim.frame()));
        }

        assertEquals(1, controller.rubberbands());
        assertTrue(controller.speed() < before);
    }

    @Test
    void abortsWhenTeleportedChangingDimensionOrOutOfTheAir() {
        Sim sim = new Sim();
        AutoMapController teleported = controllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));
        assertNotNull(teleported.tick(sim.frame()));
        sim.x += 5_000;
        assertNull(teleported.tick(sim.frame()));
        assertEquals(AutoMapController.State.ABORTED, teleported.state());
        assertTrue(teleported.abortReason().contains("teleported"));

        sim = new Sim();
        AutoMapController portal = controllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));
        sim.dimension = "the_nether";
        assertNull(portal.tick(sim.frame()));
        assertTrue(portal.abortReason().contains("dimension"));

        sim = new Sim();
        AutoMapController grounded = controllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));
        sim.gliding = false;
        for (int i = 0; i < 30; i++) {
            AutoMapController.Command command = grounded.tick(sim.frame());
            assertNotNull(command);
            assertEquals(false, command.forward(), "no pushing forward while not gliding");
        }
        assertNull(grounded.tick(sim.frame()));
        assertTrue(grounded.abortReason().contains("gliding"));
    }

    @Test
    void anAbortStaysAborted() {
        Sim sim = new Sim();
        AutoMapController controller = controllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));
        controller.abort("stopped by you");

        assertNull(controller.tick(sim.frame()));
        assertEquals("stopped by you", controller.abortReason());
        controller.abort("something else");
        assertEquals("stopped by you", controller.abortReason());
    }

    private static final AutoMapController.Settings TRANSIT =
            new AutoMapController.Settings(6, 1.0, 1.5, 1.25, 300, 8, 0.8, 4, 2, 5.99);

    private static AutoMapController transitControllerFor(Sim sim, ChunkBox box) {
        return new AutoMapController(box, TRANSIT, "overworld", sim.x, sim.z, sim.probe(), (bx, bz) -> sim.terrain.topY(bx, bz));
    }

    @Test
    void fliesFlatOutToTheNearestCornerThenBrakesToTheMappingSpeed() {
        Sim sim = new Sim();
        sim.x = -6000;
        sim.z = 250;
        AutoMapController controller = transitControllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));

        int tick = 0;
        double slowestBeforeCorner = Double.MAX_VALUE;
        while (tick < 3000 && sim.x < -60) {
            AutoMapController.Command command = controller.tick(sim.frame());
            assertNotNull(command);
            slowestBeforeCorner = Math.min(slowestBeforeCorner, command.horizontalSpeed());
            sim.apply(command);
            tick++;
        }
        assertEquals(5.99, slowestBeforeCorner, 0.001, "top speed the whole way to the box");
        assertTrue(controller.statusLine().contains("Flying to the area"), controller.statusLine());

        double lastSpeed = 5.99;
        int ticksAfterCorner = 0;
        while (ticksAfterCorner < 200) {
            AutoMapController.Command command = controller.tick(sim.frame());
            assertNotNull(command);
            assertTrue(command.horizontalSpeed() <= lastSpeed + 1e-9, "only ever slows down after the corner");
            lastSpeed = command.horizontalSpeed();
            sim.apply(command);
            ticksAfterCorner++;
            if (lastSpeed <= 1.26) {
                break;
            }
        }
        assertTrue(lastSpeed <= 1.26, "should be down to the mapping speed within a few seconds, was " + lastSpeed);
        assertTrue(ticksAfterCorner <= 90, "braking should take about two seconds, took " + ticksAfterCorner + " ticks");
        assertTrue(sim.x >= 0 && sim.x < 200, "arrived at the west edge of the region, x=" + sim.x);
    }

    @Test
    void doesNotBotherWithAFastTransitWhenAlreadyCloseToTheBox() {
        Sim sim = new Sim();
        sim.x = -200;
        sim.z = 250;
        AutoMapController controller = transitControllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));

        AutoMapController.Command command = controller.tick(sim.frame());

        assertEquals(1.25, command.horizontalSpeed(), 0.001);
    }

    @Test
    void aRubberbandOnTheWayInSlowsTheTransitButNotTheMappingSpeed() {
        Sim sim = new Sim();
        sim.x = -6000;
        sim.z = 250;
        AutoMapController controller = transitControllerFor(sim, ChunkBox.ofRegions(0, 0, 0, 0));
        for (int i = 0; i < 20; i++) {
            sim.apply(controller.tick(sim.frame()));
        }

        sim.x -= 12;
        AutoMapController.Command afterRubberband = controller.tick(sim.frame());
        sim.apply(afterRubberband);

        assertEquals(1, controller.rubberbands());
        assertEquals(5.69, afterRubberband.horizontalSpeed(), 0.001);
        while (sim.x < 20) {
            sim.apply(controller.tick(sim.frame()));
        }
        double speed = 99;
        for (int i = 0; i < 80; i++) {
            AutoMapController.Command command = controller.tick(sim.frame());
            speed = command.horizontalSpeed();
            sim.apply(command);
        }
        assertTrue(speed >= 1.24 && speed <= 1.5, "the mapping speed wasn't lowered by the rubberband: " + speed);
    }

    @Test
    void theNearestCornerIsPulledInsideTheBox() {
        ChunkBox box = ChunkBox.ofRegions(0, 0, 1, 1);

        double[] fromWest = LanePlanner.nearestCorner(box, -5000, 900);
        double[] fromEastSouth = LanePlanner.nearestCorner(box, 9000, 9000);

        assertEquals(8, fromWest[0], 0.001);
        assertEquals(1016, fromWest[1], 0.001);
        assertEquals(1016, fromEastSouth[0], 0.001);
        assertEquals(1016, fromEastSouth[1], 0.001);
    }
}
