package tools.argus.uploader.core.automap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneWidthModelTest {

    @Test
    void slowFlightMapsAboutFourteenAcross() {
        assertEquals(14, LaneWidthModel.widthChunks(1.25), 0.0001);
        assertEquals(14, LaneWidthModel.widthChunks(0.5), 0.0001);
        assertEquals(6, LaneWidthModel.halfWidthChunks(1.25));
    }

    @Test
    void theTopSpeedNarrowsItToEightAndYourUsualSpeedToAboutTen() {
        assertEquals(8, LaneWidthModel.widthChunks(5.99), 0.0001);
        assertEquals(8, LaneWidthModel.widthChunks(9.0), 0.0001);
        double atUsualSpeed = LaneWidthModel.widthChunks(4.61);
        assertTrue(atUsualSpeed > 9 && atUsualSpeed < 10.5, "was " + atUsualSpeed);
        assertEquals(3, LaneWidthModel.halfWidthChunks(5.99));
        assertEquals(4, LaneWidthModel.halfWidthChunks(4.61));
    }

    @Test
    void staysAtEightAcrossBeyondTheTopSpeed() {
        assertEquals(3, LaneWidthModel.halfWidthChunks(50));
    }
}
