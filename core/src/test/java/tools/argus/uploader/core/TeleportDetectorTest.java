package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportDetectorTest {

    @Test
    void firstObservationIsNeverAJump() {
        assertTrue(new TeleportDetector().observe("overworld", 5_000, 64, 5_000).isEmpty());
    }

    @Test
    void ordinaryMovementIsNotAJump() {
        TeleportDetector d = new TeleportDetector();
        d.observe("overworld", 0, 64, 0);
        assertTrue(d.observe("overworld", 3, 64, 4).isEmpty());
        assertTrue(d.observe("overworld", 40, 80, 40).isEmpty(), "fast elytra flight stays under the threshold");
    }

    @Test
    void moveOverTheThresholdInOneStepIsAJump() {
        TeleportDetector d = new TeleportDetector();
        d.observe("overworld", 0, 64, 0);
        TeleportDetector.Jump jump = d.observe("overworld", 300, 64, 400).orElseThrow();
        assertEquals(500.0, jump.blocksMoved(), 0.001);
        assertEquals(300, jump.x());
        assertEquals(400, jump.z());
    }

    @Test
    void exactlyTheThresholdIsNotAJumpButJustOverIs() {
        TeleportDetector d = new TeleportDetector();
        d.observe("overworld", 0, 64, 0);
        assertTrue(d.observe("overworld", 128, 64, 0).isEmpty());
        assertTrue(d.observe("overworld", 128 + 129, 64, 0).isPresent());
    }

    @Test
    void dimensionChangeIsAJumpRegardlessOfCoordinates() {
        TeleportDetector d = new TeleportDetector();
        d.observe("overworld", 100, 64, 100);
        TeleportDetector.Jump jump = d.observe("the_nether", 12, 64, 12).orElseThrow();
        assertTrue(jump.dimensionChanged());
    }

    @Test
    void resetForgetsTheBaseline() {
        TeleportDetector d = new TeleportDetector();
        d.observe("overworld", 0, 64, 0);
        d.reset();
        assertTrue(d.observe("overworld", 9_000, 64, 9_000).isEmpty(), "rejoining somewhere else isn't a teleport");
    }
}
