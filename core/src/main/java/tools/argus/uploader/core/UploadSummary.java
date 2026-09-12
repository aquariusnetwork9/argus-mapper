package tools.argus.uploader.core;

/** Result of one /argus upload run, handed to add-ons via ArgusMapperEvents.UPLOAD_COMPLETED. */
public record UploadSummary(int succeeded, int failed) {
}
