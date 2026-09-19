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

    private static RegionFile modified(String filename, long mtime) {
        return new RegionFile(Path.of(filename), filename, 0, 0, "overworld", 10, mtime);
    }

    @Test
    void detectsAFileThatIsNewerThanWhenItWasUploaded(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        manifest.markUploaded(modified("0_0.zip", 1_000));

        assertFalse(manifest.isChangedSinceUpload(modified("0_0.zip", 1_000)), "same mtime is not a change");
        assertFalse(manifest.isChangedSinceUpload(modified("0_0.zip", 500)), "an older file is not an update");
        assertTrue(manifest.isChangedSinceUpload(modified("0_0.zip", 1_001)));
        assertFalse(manifest.isChangedSinceUpload(modified("9_9.zip", 5_000)), "never uploaded is not 'changed'");
    }

    @Test
    void reuploadingUpdatesTheBaselineAndSurvivesReload(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("manifest.txt");
        UploadManifest manifest = UploadManifest.load(file);
        manifest.markUploaded(modified("0_0.zip", 1_000));
        manifest.markUploaded(modified("0_0.zip", 2_000));

        assertEquals(1, manifest.size(), "still one region, not two");
        assertFalse(manifest.isChangedSinceUpload(modified("0_0.zip", 2_000)));

        UploadManifest reloaded = UploadManifest.load(file);
        assertEquals(1, reloaded.size());
        assertFalse(reloaded.isChangedSinceUpload(modified("0_0.zip", 2_000)), "last recorded upload wins on reload");
        assertTrue(reloaded.isChangedSinceUpload(modified("0_0.zip", 2_001)));
    }

    @Test
    void entryFromBeforeModifiedTimesWereTrackedIsNeverChangedUntilABaselineIsAdopted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("manifest.txt");
        java.nio.file.Files.writeString(file, "overworld|0_0.zip\n");
        UploadManifest manifest = UploadManifest.load(file);
        RegionFile now = modified("0_0.zip", 5_000);

        assertTrue(manifest.isUploaded(now));
        assertFalse(manifest.isChangedSinceUpload(now), "no recorded mtime to compare against");

        manifest.adoptBaselines(List.of(now, modified("9_9.zip", 5_000)));

        assertFalse(manifest.isChangedSinceUpload(now));
        assertTrue(manifest.isChangedSinceUpload(modified("0_0.zip", 6_000)));
        assertEquals(1, manifest.size(), "adopting a baseline must not add regions that were never uploaded");
        assertFalse(manifest.isUploaded(modified("9_9.zip", 5_000)));
        assertTrue(UploadManifest.load(file).isChangedSinceUpload(modified("0_0.zip", 6_000)), "baseline is persisted");
    }

    @Test
    void needsUploadIsTrueForNewRegionsAndForChangedOnesOnlyWhenAsked(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        manifest.markUploaded(modified("0_0.zip", 1_000));

        assertTrue(manifest.needsUpload(modified("9_9.zip", 1_000), false), "never uploaded");
        assertFalse(manifest.needsUpload(modified("0_0.zip", 1_000), true), "unchanged");
        assertFalse(manifest.needsUpload(modified("0_0.zip", 2_000), false), "changed, but re-upload is off");
        assertTrue(manifest.needsUpload(modified("0_0.zip", 2_000), true), "changed, re-upload is on");
    }

    @Test
    void markingWithoutAKnownMtimeNeverErasesARecordedOne(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        manifest.markUploaded(modified("0_0.zip", 1_000));
        manifest.markUploaded(region("overworld", "0_0.zip"));

        assertTrue(manifest.isChangedSinceUpload(modified("0_0.zip", 1_001)));
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
