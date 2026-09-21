package tools.argus.uploader.core;

import java.util.List;

/** All callbacks may be invoked from a background thread. */
public interface UploadProgressListener {

    void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload);

    /**
     * Fired once, right after {@link #onSummary}, with the full ordered list of regions the run
     * is about to attempt - before any of them have actually been sent. A torrent-style tracker
     * uses this to render every row up front (as "queued"), then updates individual rows via
     * {@link #onRegionUploaded}/{@link #onRegionFailed} as each one resolves, rather than having
     * rows pop into existence one at a time as their turn comes up.
     */
    default void onQueueBuilt(List<RegionFile> toUpload) {
    }

    /** Fired right before this region's request starts. Several regions can be started and not yet
     *  resolved at once. */
    default void onRegionStarted(RegionFile region) {
    }

    /** Regions left out because an auto-map flight is still writing them; fired right after {@link #onSummary}. */
    default void onHeldForMapping(int held) {
    }

    /** A queued region the run dropped just before sending; it is not recorded, so a later run picks it up. */
    default void onRegionDeferred(RegionFile region) {
    }

    void onRegionUploaded(RegionFile region, int done, int total);

    void onRegionFailed(RegionFile region, String reason, int done, int total);

    /** Something worth telling the player that isn't a per-region result, e.g. the server asking us to slow down. */
    default void onNotice(String message) {
    }

    void onFatalError(String message);

    void onComplete(int succeeded, int failed);
}
