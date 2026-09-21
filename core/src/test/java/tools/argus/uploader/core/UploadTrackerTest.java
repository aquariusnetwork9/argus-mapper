package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UploadTrackerTest {

    private static RegionFile region(int x, int z) {
        String filename = x + "_" + z + ".zip";
        return new RegionFile(Path.of(filename), filename, x, z, "overworld", 10);
    }

    /** Records every call it receives, so tests can assert the wrapped listener still fires
     *  (UploadTracker must not swallow events, only observe them). */
    private static final class RecordingListener implements UploadProgressListener {
        final List<String> calls = new ArrayList<>();

        @Override
        public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload) {
            calls.add("summary");
        }

        @Override
        public void onQueueBuilt(List<RegionFile> toUpload) {
            calls.add("queueBuilt");
        }

        @Override
        public void onRegionStarted(RegionFile region) {
            calls.add("started:" + region.filename());
        }

        @Override
        public void onRegionUploaded(RegionFile region, int done, int total) {
            calls.add("uploaded:" + region.filename());
        }

        @Override
        public void onRegionFailed(RegionFile region, String reason, int done, int total) {
            calls.add("failed:" + region.filename());
        }

        @Override
        public void onFatalError(String message) {
            calls.add("fatal");
        }

        @Override
        public void onComplete(int succeeded, int failed) {
            calls.add("complete");
        }
    }

    @Test
    void queueBuiltSeedsAllRowsAsQueued() {
        RecordingListener delegate = new RecordingListener();
        UploadTracker tracker = new UploadTracker(delegate);
        RegionFile a = region(0, 0);
        RegionFile b = region(1, 0);

        tracker.onQueueBuilt(List.of(a, b));

        assertEquals(2, tracker.rows().size());
        assertEquals(UploadTracker.Status.QUEUED, tracker.rows().get(0).status());
        assertEquals(UploadTracker.Status.QUEUED, tracker.rows().get(1).status());
        assertEquals(List.of("queueBuilt"), delegate.calls);
    }

    @Test
    void aDeferredRegionLeavesTheTable() {
        UploadTracker tracker = new UploadTracker(new RecordingListener());
        RegionFile a = region(0, 0);
        RegionFile b = region(1, 0);
        tracker.onQueueBuilt(List.of(a, b));

        tracker.onRegionDeferred(a);

        assertEquals(1, tracker.rows().size());
        assertEquals(b, tracker.rows().get(0).region());
    }

    @Test
    void rowsTransitionThroughStartedThenResolved() {
        UploadTracker tracker = new UploadTracker(new RecordingListener());
        RegionFile a = region(0, 0);
        RegionFile b = region(1, 0);
        tracker.onQueueBuilt(List.of(a, b));

        tracker.onRegionStarted(a);
        assertEquals(UploadTracker.Status.UPLOADING, statusOf(tracker, a));
        assertEquals(UploadTracker.Status.QUEUED, statusOf(tracker, b));

        tracker.onRegionUploaded(a, 1, 2);
        assertEquals(UploadTracker.Status.DONE, statusOf(tracker, a));

        tracker.onRegionStarted(b);
        tracker.onRegionFailed(b, "HTTP 500", 2, 2);
        assertEquals(UploadTracker.Status.FAILED, statusOf(tracker, b));
        assertEquals("HTTP 500", errorOf(tracker, b));
    }

    @Test
    void delegateStillReceivesEveryCall() {
        RecordingListener delegate = new RecordingListener();
        UploadTracker tracker = new UploadTracker(delegate);
        RegionFile a = region(0, 0);

        tracker.onSummary(5, 1, 1, 1, 2);
        tracker.onQueueBuilt(List.of(a));
        tracker.onRegionStarted(a);
        tracker.onRegionUploaded(a, 1, 1);
        tracker.onComplete(1, 0);

        assertEquals(List.of("summary", "queueBuilt", "started:0_0.zip", "uploaded:0_0.zip", "complete"), delegate.calls);
    }

    @Test
    void listenersAreNotifiedOnEveryChange() {
        UploadTracker tracker = new UploadTracker(new RecordingListener());
        AtomicInteger notifications = new AtomicInteger();
        tracker.addListener(notifications::incrementAndGet);

        RegionFile a = region(0, 0);
        tracker.onQueueBuilt(List.of(a));
        tracker.onRegionStarted(a);
        tracker.onRegionUploaded(a, 1, 1);

        assertEquals(3, notifications.get());
    }

    private static UploadTracker.Status statusOf(UploadTracker tracker, RegionFile region) {
        return tracker.rows().stream().filter(r -> r.region().equals(region)).findFirst().orElseThrow().status();
    }

    private static String errorOf(UploadTracker tracker, RegionFile region) {
        return tracker.rows().stream().filter(r -> r.region().equals(region)).findFirst().orElseThrow().error();
    }

    @Test
    void aRequeuedRegionGoesBackToQueued() {
        UploadTracker tracker = new UploadTracker(new RecordingListener());
        RegionFile a = region(0, 0);
        tracker.onQueueBuilt(List.of(a));
        tracker.onRegionStarted(a);
        assertEquals(UploadTracker.Status.UPLOADING, tracker.rows().get(0).status());

        tracker.onRegionRequeued(a);

        assertEquals(UploadTracker.Status.QUEUED, tracker.rows().get(0).status());
    }
}
