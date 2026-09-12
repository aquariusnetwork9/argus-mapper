package tools.argus.uploader.core;

/** All callbacks may be invoked from a background thread. */
public interface UploadProgressListener {

    void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int toUpload);

    void onRegionUploaded(RegionFile region, int done, int total);

    void onRegionFailed(RegionFile region, String reason, int done, int total);

    void onFatalError(String message);

    void onComplete(int succeeded, int failed);
}
