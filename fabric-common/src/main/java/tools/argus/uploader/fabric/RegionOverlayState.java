package tools.argus.uploader.fabric;

import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.RegionFile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadTracker;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * What color (if any) a single region file should show as on Xaero's World Map - the data side
 * of the map overlay Mixin (see {@code MixinGuiMapOverlay} in each version's {@code gui-src}).
 * Kept out of the Mixin class itself so it isn't tied to any one Minecraft/Mixin version, and so
 * a bug here can only mean "wrong/missing color", never a broken render call.
 *
 * <p>Every lookup here is meant to run every visible region, every frame the world map is open -
 * so the one disk read (the upload manifest) is cached rather than reloaded each call, and
 * {@link #classify} itself never throws (a cosmetic overlay is never worth risking the map
 * screen over).
 */
public final class RegionOverlayState {

    public enum Category {
        NONE, BLACKZONE, UPLOADING, UPLOADED
    }

    private static volatile Set<String> cachedUploadedKeys;

    private RegionOverlayState() {
    }

    /** Call after any upload run completes (both {@code /argus upload} and a Xaero map-selection
     *  upload funnel through {@code ArgusCommand.onUploadRunComplete}, so this one call covers
     *  both) - without it, a region uploaded this session would never show as UPLOADED on the map
     *  until the next full game restart re-read the manifest from disk. */
    public static void invalidateUploadedCache() {
        cachedUploadedKeys = null;
    }

    public static Category classify(String dimension, String layer, int regionX, int regionZ) {
        try {
            if (dimension == null || layer == null || layer.isBlank()) {
                return Category.NONE;
            }
            for (BlackZone zone : ArgusUploaderClientMod.blackzoneStore().forServer(dimension, layer)) {
                if (zone.bounds().contains(regionX, regionZ)) {
                    return Category.BLACKZONE;
                }
            }
            UploadTracker tracker = ArgusUploaderClientMod.activeUpload();
            if (tracker != null) {
                for (UploadTracker.RowState row : tracker.rows()) {
                    RegionFile region = row.region();
                    if (region.regionX() == regionX && region.regionZ() == regionZ && region.dimension().equals(dimension)) {
                        switch (row.status()) {
                            case UPLOADING, QUEUED -> {
                                return Category.UPLOADING;
                            }
                            case DONE -> {
                                return Category.UPLOADED;
                            }
                            default -> {
                                // FAILED - fall through to the persisted-manifest check below
                                // rather than claim a color either way for it.
                            }
                        }
                    }
                }
            }
            // The manifest key is dimension|<actual filename> - XaeroScanner (and therefore every
            // real upload) only ever produces a .zip filename, see its own comment on
            // REGION_FILE for why .xwmc is deliberately not included here too.
            if (uploadedKeys().contains(dimension + "|" + regionX + "_" + regionZ + ".zip")) {
                return Category.UPLOADED;
            }
        } catch (Throwable t) {
            // Cosmetic overlay only - a lookup bug here must never propagate into the map's own
            // render loop, so fail closed (no color) rather than risk breaking the screen.
            return Category.NONE;
        }
        return Category.NONE;
    }

    private static Set<String> uploadedKeys() {
        Set<String> keys = cachedUploadedKeys;
        if (keys == null) {
            try {
                keys = new HashSet<>(UploadManifest.load(ArgusUploaderClientMod.manifestPath()).snapshot());
            } catch (IOException e) {
                keys = Set.of();
            }
            cachedUploadedKeys = keys;
        }
        return keys;
    }
}
