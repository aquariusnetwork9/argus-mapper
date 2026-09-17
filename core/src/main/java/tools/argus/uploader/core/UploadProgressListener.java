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

    /** Fired right before the blocking network call for this region starts. Runs are sequential
     *  (one region in flight at a time), so at most one region is ever "started" and not yet
     *  resolved. */
    default void onRegionStarted(RegionFile region) {
    }

    void onRegionUploaded(RegionFile region, int done, int total);

    void onRegionFailed(RegionFile region, String reason, int done, int total);

    void onFatalError(String message);

    void onComplete(int succeeded, int failed);
}
