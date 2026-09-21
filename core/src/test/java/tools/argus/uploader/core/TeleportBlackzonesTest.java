package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportBlackzonesTest {

    private static final int R = TeleportBlackzones.RADIUS_REGIONS;

    @Test
    void anOverworldArrivalZonesBothTheOverworldAndTheMatchingNetherSpot() {
        // Region (300, -300) at 512 blocks each; the nether spot is 1/8 of it: region (37, -38).
        List<BlackZone> zones = TeleportBlackzones.plan("overworld", 300 * 512 + 10, -300 * 512 - 10, "6b6t", "auto-1");

        assertEquals(2, zones.size());
        BlackZone overworld = zones.get(0);
        assertEquals("overworld", overworld.dimension());
        assertEquals(new RegionBounds(300 - R, -301 - R, 300 + R, -301 + R), overworld.bounds());
        BlackZone nether = zones.get(1);
        assertEquals("the_nether", nether.dimension());
        assertEquals(new RegionBounds(37 - R, -38 - R, 37 + R, -38 + R), nether.bounds());
        assertEquals("6b6t", nether.layer());
        assertFalse(overworld.id().equals(nether.id()));
    }

    @Test
    void aNetherArrivalAlsoZonesTheOverworldSideEightTimesOut() {
        List<BlackZone> zones = TeleportBlackzones.plan("the_nether", 512 * 40, 512 * 40, "6b6t", "auto-1");

        assertEquals(List.of("the_nether", "overworld"), zones.stream().map(BlackZone::dimension).toList());
        assertEquals(new RegionBounds(40 - R, 40 - R, 40 + R, 40 + R), zones.get(0).bounds());
        assertEquals(new RegionBounds(320 - R, 320 - R, 320 + R, 320 + R), zones.get(1).bounds());
    }

    @Test
    void theEndHasNoOtherSide() {
        List<BlackZone> zones = TeleportBlackzones.plan("theend", 0, 0, "6b6t", "auto-1");

        assertEquals(1, zones.size());
        assertEquals("theend", zones.get(0).dimension());
    }

    @Test
    void anUnknownDimensionGetsNoZone() {
        assertTrue(TeleportBlackzones.plan("some_mod:dim", 0, 0, "6b6t", "auto-1").isEmpty());
    }

    @Test
    void outsideTheDefaultLimitStartsOneRegionPastTheEdge() {
        double lastInside = (CoordLimits.MAX_ABS_REGION + 1) * 512.0 - 1;
        assertFalse(TeleportBlackzones.isOutsideDefaultLimit(lastInside, 0));
        assertTrue(TeleportBlackzones.isOutsideDefaultLimit(lastInside + 1, 0));
        assertFalse(TeleportBlackzones.isOutsideDefaultLimit(-(CoordLimits.MAX_ABS_REGION * 512.0), 0));
        assertTrue(TeleportBlackzones.isOutsideDefaultLimit(0, -(CoordLimits.MAX_ABS_REGION + 1) * 512.0 + -1));
    }

    @Test
    void theLimitCheckIgnoresTheWholeMapSwitch() {
        CoordLimits.setLifted(true);
        try {
            assertTrue(TeleportBlackzones.isOutsideDefaultLimit(1_000_000, 0));
        } finally {
            CoordLimits.setLifted(false);
        }
    }
}
