package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link UploadRunner#filterRegions}, the pure filtering step - deliberately not testing
 * the networked {@link UploadRunner#start} path here (that needs a real or fake server; see
 * {@link ArgusUploadClientTest} for how the CoordLimits guard alone is verified without one).
 */
class UploadRunnerTest {

    private static RegionFile region(String dimension, int x, int z, long sizeBytes) {
        String filename = x + "_" + z + ".zip";
        return new RegionFile(Path.of(filename), filename, x, z, dimension, sizeBytes);
    }

    @Test
    void splitsRegionsByReasonInPriorityOrder(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile alreadyUploaded = region("overworld", 0, 0, 10);
        manifest.markUploaded(alreadyUploaded);

        RegionFile tooLarge = region("overworld", 1, 0, 999);
        RegionFile blackzoned = region("overworld", 2, 0, 10);
        RegionFile clean = region("overworld", 3, 0, 10);

        UploadRunner.FilterResult result = UploadRunner.filterRegions(
                List.of(alreadyUploaded, tooLarge, blackzoned, clean),
                manifest,
                100,
                r -> !r.equals(blackzoned),
                false);

        assertEquals(1, result.alreadyUploaded());
        assertEquals(1, result.tooLarge());
        assertEquals(1, result.excludedByBlackzone());
        assertEquals(List.of(clean), result.toUpload());
    }

    @Test
    void manifestCheckTakesPriorityOverBlackzoneForTheSameRegion(@TempDir Path dir) throws IOException {
        // A region that's both already-uploaded and (now) blackzoned should count once, as
        // already-uploaded - it's already on the server regardless, so that's the more accurate
        // status; re-litigating it as "excluded by blackzone" would undercount alreadyUploaded.
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile region = region("overworld", 0, 0, 10);
        manifest.markUploaded(region);

        UploadRunner.FilterResult result = UploadRunner.filterRegions(
                List.of(region), manifest, 100, r -> false, false);

        assertEquals(1, result.alreadyUploaded());
        assertEquals(0, result.excludedByBlackzone());
    }

    @Test
    void emptyInputProducesEmptyResult(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        UploadRunner.FilterResult result = UploadRunner.filterRegions(List.of(), manifest, 100, r -> true, false);
        assertTrue(result.toUpload().isEmpty());
        assertEquals(0, result.alreadyUploaded() + result.tooLarge() + result.excludedByBlackzone());
    }

    private static RegionFile modified(String dimension, int x, int z, long sizeBytes, long mtime) {
        String filename = x + "_" + z + ".zip";
        return new RegionFile(Path.of(filename), filename, x, z, dimension, sizeBytes, mtime);
    }

    @Test
    void changedRegionIsReuploadedOnlyWhenEnabled(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        manifest.markUploaded(modified("overworld", 0, 0, 10, 1_000));
        RegionFile newer = modified("overworld", 0, 0, 10, 2_000);

        UploadRunner.FilterResult off = UploadRunner.filterRegions(List.of(newer), manifest, 100, r -> true, false);
        assertEquals(1, off.alreadyUploaded());
        assertTrue(off.toUpload().isEmpty());

        UploadRunner.FilterResult on = UploadRunner.filterRegions(List.of(newer), manifest, 100, r -> true, true);
        assertEquals(0, on.alreadyUploaded());
        assertEquals(List.of(newer), on.toUpload());
    }

    @Test
    void unchangedRegionStaysAlreadyUploadedEvenWhenReuploadIsEnabled(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile region = modified("overworld", 0, 0, 10, 1_000);
        manifest.markUploaded(region);

        UploadRunner.FilterResult result = UploadRunner.filterRegions(List.of(region), manifest, 100, r -> true, true);

        assertEquals(1, result.alreadyUploaded());
        assertTrue(result.toUpload().isEmpty());
    }

    @Test
    void changedRegionInsideABlackzoneIsStillExcluded(@TempDir Path dir) throws IOException {
        // Reupload only relaxes "already uploaded" - a blackzone drawn over a region after it was
        // first uploaded must still stop every later version of it from being sent.
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        manifest.markUploaded(modified("overworld", 0, 0, 10, 1_000));
        RegionFile newer = modified("overworld", 0, 0, 10, 2_000);

        UploadRunner.FilterResult result = UploadRunner.filterRegions(List.of(newer), manifest, 100, r -> false, true);

        assertEquals(1, result.excludedByBlackzone());
        assertEquals(0, result.alreadyUploaded());
        assertTrue(result.toUpload().isEmpty());
    }

    @Test
    void changedRegionOverTheSizeLimitIsStillExcluded(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        manifest.markUploaded(modified("overworld", 0, 0, 10, 1_000));
        RegionFile newerAndHuge = modified("overworld", 0, 0, 999, 2_000);

        UploadRunner.FilterResult result = UploadRunner.filterRegions(List.of(newerAndHuge), manifest, 100, r -> true, true);

        assertEquals(1, result.tooLarge());
        assertTrue(result.toUpload().isEmpty());
    }
}
