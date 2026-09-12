package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapperStatsTest {

    @Test
    void chunksApproximatedAt1024PerRegion() {
        MapperStats stats = MapperStats.of(10, 500.0);
        assertEquals(10, stats.regionsContributed());
        assertEquals(10 * 1024L, stats.chunksContributedApprox());
        assertEquals(500.0, stats.distanceTraveledBlocks());
    }

    @Test
    void zeroRegionsGivesZeroChunks() {
        assertEquals(0, MapperStats.of(0, 0).chunksContributedApprox());
    }
}
