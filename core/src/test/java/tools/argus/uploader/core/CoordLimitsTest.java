package tools.argus.uploader.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordLimitsTest {

    @AfterEach
    void restoreDefault() {
        CoordLimits.setLifted(false);
    }

    @Test
    void theDefaultIsTwoHundredRegionsEitherWay() {
        assertEquals(200, CoordLimits.MAX_ABS_REGION);
    }

    @Test
    void boundaryValuesAreInRange() {
        assertTrue(CoordLimits.inRange(200));
        assertTrue(CoordLimits.inRange(-200));
        assertTrue(CoordLimits.inRange(0));
    }

    @Test
    void justOverBoundaryIsOutOfRange() {
        assertFalse(CoordLimits.inRange(201));
        assertFalse(CoordLimits.inRange(-201));
    }

    @Test
    void bothAxesMustBeInRange() {
        assertTrue(CoordLimits.inRange(150, -150));
        assertFalse(CoordLimits.inRange(201, 0));
        assertFalse(CoordLimits.inRange(0, -201));
        assertFalse(CoordLimits.inRange(500, 500));
    }

    @Test
    void anOldStyleFiveDigitRegionNumberIsNoLongerAccepted() {
        assertFalse(CoordLimits.inRange(99_999, 0));
    }

    @Test
    void liftingRemovesTheLimitAndRestoringPutsItBack() {
        assertFalse(CoordLimits.isLifted());
        CoordLimits.setLifted(true);
        assertTrue(CoordLimits.inRange(50_000, -50_000));
        CoordLimits.setLifted(false);
        assertFalse(CoordLimits.inRange(50_000, -50_000));
    }
}
