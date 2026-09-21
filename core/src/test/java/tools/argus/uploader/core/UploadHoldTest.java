package tools.argus.uploader.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.argus.uploader.core.automap.ChunkBox;
import tools.argus.uploader.core.automap.CalibrationSchedule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadHoldTest {

    @AfterEach
    void release() {
        UploadHold.release();
    }

    private static RegionFile region(String dimension, int x, int z) {
        String filename = x + "_" + z + ".zip";
        return new RegionFile(Path.of(filename), filename, x, z, dimension, 10);
    }

    @Test
    void nothingIsHeldUnlessAFlightSaysSo() {
        assertFalse(UploadHold.isActive());
        assertFalse(UploadHold.isHeld(region("overworld", 0, 0)));
    }

    @Test
    void aBoxHoldsItsRegionsAndOneRegionAroundThemInThatDimensionOnly() {
        UploadHold.hold(List.of(UploadHold.forBox(ChunkBox.ofRegions(2, 3, 3, 3), "overworld", 1)));

        assertTrue(UploadHold.isActive());
        assertTrue(UploadHold.isHeld(region("overworld", 2, 3)));
        assertTrue(UploadHold.isHeld(region("overworld", 3, 3)));
        assertTrue(UploadHold.isHeld(region("overworld", 1, 2)), "the margin");
        assertTrue(UploadHold.isHeld(region("overworld", 4, 4)), "the margin");
        assertFalse(UploadHold.isHeld(region("overworld", 5, 3)));
        assertFalse(UploadHold.isHeld(region("overworld", 0, 3)));
        assertFalse(UploadHold.isHeld(region("the_nether", 2, 3)), "another dimension is untouched");
    }

    @Test
    void aLineHoldsTheRegionsItCrossesEastWestNorthAndSouth() {
        UploadHold.hold(List.of(UploadHold.forLine("overworld", 100, 100, 0, 3_000, 0)));
        assertTrue(UploadHold.isHeld(region("overworld", 0, 0)));
        assertTrue(UploadHold.isHeld(region("overworld", 5, 0)));
        assertFalse(UploadHold.isHeld(region("overworld", 7, 0)));
        assertFalse(UploadHold.isHeld(region("overworld", 3, 1)));
        assertFalse(UploadHold.isHeld(region("overworld", -1, 0)));

        UploadHold.hold(List.of(UploadHold.forLine("overworld", 100, 100, 3, 3_000, 1)));
        assertTrue(UploadHold.isHeld(region("overworld", 1, -5)));
        assertTrue(UploadHold.isHeld(region("overworld", -1, 0)));
        assertFalse(UploadHold.isHeld(region("overworld", 0, 1 + 1)));
    }

    @Test
    void releasingLetsEverythingThroughAgain() {
        UploadHold.hold(List.of(UploadHold.forBox(ChunkBox.ofRegions(0, 0, 0, 0), "overworld", 1)));
        UploadHold.release();

        assertFalse(UploadHold.isActive());
        assertFalse(UploadHold.isHeld(region("overworld", 0, 0)));
    }

    @Test
    void theRunnerLeavesHeldRegionsOutAndCountsThem(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        UploadHold.hold(List.of(UploadHold.forBox(ChunkBox.ofRegions(0, 0, 0, 0), "overworld", 0)));
        RegionFile held = region("overworld", 0, 0);
        RegionFile free = region("overworld", 9, 9);

        UploadRunner.FilterResult result = UploadRunner.filterRegions(List.of(held, free), manifest, 100, r -> true, false);

        assertEquals(List.of(free), result.toUpload());
        assertEquals(1, result.held());
        assertEquals(0, result.excludedByBlackzone());
    }

    @Test
    void theNetworkCallRefusesAHeldRegionWithoutMakingARequest() {
        ArgusConfig config = new ArgusConfig();
        config.apiBaseUrl = "http://127.0.0.1:1";
        config.token = "x";
        config.layer = "y";
        UploadHold.hold(List.of(UploadHold.forBox(ChunkBox.ofRegions(0, 0, 0, 0), "overworld", 0)));

        ArgusUploadClient.UploadResult result = new ArgusUploadClient(config).upload(region("overworld", 0, 0), "batch-1");

        assertFalse(result.success());
        assertTrue(result.ioError().getMessage().contains("still being auto-mapped"));
        assertEquals(-1, result.statusCode());
    }

    @Test
    void aCalibrationScheduleKnowsRoughlyHowFarItFlies() {
        CalibrationSchedule schedule = new CalibrationSchedule(new double[]{1, 2}, 1_000, 4_000);

        assertEquals((1 + 2) * 20.0 * 5, schedule.lengthBlocks(), 0.001);
    }
}
