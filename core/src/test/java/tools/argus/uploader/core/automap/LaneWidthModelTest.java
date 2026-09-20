package tools.argus.uploader.core.automap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneWidthModelTest {

    private final LaneWidthModel model = LaneWidthModel.defaults();

    @Test
    void slowFlightMapsAboutFourteenAcross() {
        assertEquals(14, model.widthChunks(1.25), 0.0001);
        assertEquals(14, model.widthChunks(0.5), 0.0001, "flat below the first point");
        assertEquals(6, model.halfWidthChunks(1.25));
    }

    @Test
    void theMeasuredPointsAreReproduced() {
        assertEquals(6, model.widthChunks(2.0), 0.0001, "40 blocks/s");
        assertEquals(4, model.widthChunks(3.0), 0.0001, "60 blocks/s");
        assertEquals(2, model.widthChunks(4.6), 0.0001, "92 blocks/s");
        assertEquals(2, model.widthChunks(5.99), 0.0001);
        assertEquals(2, model.widthChunks(9), 0.0001, "flat beyond the last point");
    }

    @Test
    void planningNeverAssumesLessThanOneChunkEitherSide() {
        assertEquals(2, model.halfWidthChunks(2.0));
        assertEquals(1, model.halfWidthChunks(3.0));
        assertEquals(1, model.halfWidthChunks(4.61));
        assertEquals(1, model.halfWidthChunks(5.99));
    }

    @Test
    void widthFallsSteadilyBetweenThePoints() {
        double previous = model.widthChunks(1.25);
        for (double speed = 1.3; speed <= 5.99; speed += 0.1) {
            double width = model.widthChunks(speed);
            assertTrue(width <= previous + 1e-9, "at " + speed);
            previous = width;
        }
    }

    @Test
    void theQuickestWayOverAnAreaIsAboutTwentyFiveBlocksASecond() {
        double best = model.bestSpeed(1.0, 5.99);

        assertEquals(1.25, best, 0.06);
        double bestGround = best * (model.widthChunks(best) - 1);
        assertTrue(bestGround > 5.99 * (model.widthChunks(5.99) - 1) * 2, "far better than flat out");
    }

    @Test
    void theBestSpeedStaysInsideTheAllowedRange() {
        assertEquals(1.5, LaneWidthModel.parse("1.0:2,2.0:2").bestSpeed(1.5, 1.5), 0.0001);
        assertTrue(model.bestSpeed(4.0, 5.99) >= 4.0);
        assertTrue(model.bestSpeed(1.5, 5.99) >= 1.5);
    }

    @Test
    void aCustomTableIsSortedAndUsed() {
        LaneWidthModel custom = LaneWidthModel.parse("4:5, 1:15");

        assertEquals(15, custom.widthChunks(1), 0.0001);
        assertEquals(10, custom.widthChunks(2.5), 0.0001);
        assertEquals(5, custom.widthChunks(9), 0.0001);
    }

    @Test
    void anUnusableTableFallsBackToTheDefault() {
        for (String bad : new String[]{null, "", "3", "1:14", "a:b,c:d", "1:14,1:9", "1:0.5,2:3", "1:14,-2:3"}) {
            assertEquals(14, LaneWidthModel.parse(bad).widthChunks(1.25), 0.0001, "for " + bad);
        }
    }
}
