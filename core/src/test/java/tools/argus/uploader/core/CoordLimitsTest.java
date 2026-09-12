package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordLimitsTest {

    @Test
    void boundaryValuesAreInRange() {
        assertTrue(CoordLimits.inRange(99_999));
        assertTrue(CoordLimits.inRange(-99_999));
        assertTrue(CoordLimits.inRange(0));
    }

    @Test
    void justOverBoundaryIsOutOfRange() {
        assertFalse(CoordLimits.inRange(100_000));
        assertFalse(CoordLimits.inRange(-100_000));
    }

    @Test
    void bothAxesMustBeInRange() {
        assertTrue(CoordLimits.inRange(1000, -1000));
        assertFalse(CoordLimits.inRange(100_000, 0));
        assertFalse(CoordLimits.inRange(0, -100_000));
        assertFalse(CoordLimits.inRange(100_000, 100_000));
    }
}
