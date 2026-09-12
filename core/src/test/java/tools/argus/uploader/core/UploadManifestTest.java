package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadManifestTest {

    private static RegionFile region(String dimension, String filename) {
        return new RegionFile(Path.of(filename), filename, 0, 0, dimension, 10);
    }

    @Test
    void tracksAndPersistsUploadedRegions(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("manifest.txt");
        UploadManifest manifest = UploadManifest.load(file);
        RegionFile r = region("overworld", "0_0.zip");

        assertFalse(manifest.isUploaded(r));
        manifest.markUploaded(r);
        assertTrue(manifest.isUploaded(r));

        UploadManifest reloaded = UploadManifest.load(file);
        assertTrue(reloaded.isUploaded(r), "should survive a reload from disk");
    }

    @Test
    void sameCoordinatesDifferentDimensionAreTrackedSeparately(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile overworld = region("overworld", "0_0.zip");
        RegionFile nether = region("the_nether", "0_0.zip");

        manifest.markUploaded(overworld);

        assertTrue(manifest.isUploaded(overworld));
        assertFalse(manifest.isUploaded(nether));
    }

    @Test
    void markingTwiceDoesNotDuplicateOrError(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile r = region("overworld", "0_0.zip");
        manifest.markUploaded(r);
        manifest.markUploaded(r);
        assertEquals(1, manifest.size());
        assertEquals(List.of(r.manifestKey()), manifest.snapshot());
    }
}
