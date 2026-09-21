package tools.argus.uploader.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps another {@link UploadProgressListener} (typically one that posts chat feedback) and, in
 * addition, keeps a live per-region status table for a torrent-style progress view: every region
 * in the run shows up immediately (as {@link Status#QUEUED}) once {@link #onQueueBuilt} fires,
 * then flips to {@link Status#UPLOADING}/{@link Status#DONE}/{@link Status#FAILED} in place as
 * the run works through them - so a GUI can render the whole list up front instead of rows
 * appearing one at a time.
 *
 * <p>Framework-agnostic on purpose (no Fabric event dependency here): a UI layer reads
 * {@link #rows()} and calls {@link #addListener} to be told when to repaint, then bridges that to
 * whatever event system it uses.
 */
public final class UploadTracker implements UploadProgressListener {

    public enum Status { QUEUED, UPLOADING, DONE, FAILED }

    /**
     * @param startedAtMillis  when this region's request began (0 if still QUEUED and never
     *                         started)
     * @param finishedAtMillis when it resolved, DONE or FAILED (0 while still QUEUED/UPLOADING)
     */
    public record RowState(RegionFile region, Status status, String error, long startedAtMillis, long finishedAtMillis) {

        /** Time in flight so far - still-growing for the currently UPLOADING row, frozen at
         *  however long it actually took once DONE/FAILED, 0 for a row that hasn't started. */
        public long elapsedMillis() {
            if (startedAtMillis <= 0) {
                return 0;
            }
            long end = finishedAtMillis > 0 ? finishedAtMillis : System.currentTimeMillis();
            return Math.max(0, end - startedAtMillis);
        }
    }

    private final UploadProgressListener delegate;
    private final Map<RegionFile, RowState> rows = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile int excludedByBlackzone;

    public UploadTracker(UploadProgressListener delegate) {
        this.delegate = delegate;
    }

    public void addListener(Runnable onChange) {
        listeners.add(onChange);
    }

    public List<RowState> rows() {
        synchronized (rows) {
            return List.copyOf(rows.values());
        }
    }

    public int excludedByBlackzone() {
        return excludedByBlackzone;
    }

    @Override
    public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload) {
        this.excludedByBlackzone = excludedByBlackzone;
        delegate.onSummary(totalFound, alreadyUploaded, tooLarge, excludedByBlackzone, toUpload);
        fireChanged();
    }

    @Override
    public void onHeldForMapping(int held) {
        delegate.onHeldForMapping(held);
    }

    @Override
    public void onQueueBuilt(List<RegionFile> toUpload) {
        synchronized (rows) {
            rows.clear();
            for (RegionFile region : toUpload) {
                rows.put(region, new RowState(region, Status.QUEUED, null, 0L, 0L));
            }
        }
        delegate.onQueueBuilt(toUpload);
        fireChanged();
    }

    @Override
    public void onRegionStarted(RegionFile region) {
        synchronized (rows) {
            rows.put(region, new RowState(region, Status.UPLOADING, null, System.currentTimeMillis(), 0L));
        }
        delegate.onRegionStarted(region);
        fireChanged();
    }

    @Override
    public void onRegionRequeued(RegionFile region) {
        synchronized (rows) {
            rows.put(region, new RowState(region, Status.QUEUED, null, 0L, 0L));
        }
        delegate.onRegionRequeued(region);
        fireChanged();
    }

    @Override
    public void onRegionDeferred(RegionFile region) {
        synchronized (rows) {
            rows.remove(region);
        }
        delegate.onRegionDeferred(region);
        fireChanged();
    }

    @Override
    public void onRegionUploaded(RegionFile region, int done, int total) {
        synchronized (rows) {
            rows.put(region, new RowState(region, Status.DONE, null, startedAtOrNow(region), System.currentTimeMillis()));
        }
        delegate.onRegionUploaded(region, done, total);
        fireChanged();
    }

    @Override
    public void onRegionFailed(RegionFile region, String reason, int done, int total) {
        synchronized (rows) {
            rows.put(region, new RowState(region, Status.FAILED, reason, startedAtOrNow(region), System.currentTimeMillis()));
        }
        delegate.onRegionFailed(region, reason, done, total);
        fireChanged();
    }

    /** Must be called while holding the {@code rows} lock. Falls back to "now" (0 elapsed)
     *  rather than throwing if a region resolves without ever having been marked started -
     *  shouldn't happen, but this is purely cosmetic state and isn't worth risking a run over. */
    private long startedAtOrNow(RegionFile region) {
        RowState existing = rows.get(region);
        return existing != null && existing.startedAtMillis() > 0 ? existing.startedAtMillis() : System.currentTimeMillis();
    }

    @Override
    public void onNotice(String message) {
        delegate.onNotice(message);
    }

    @Override
    public void onFatalError(String message) {
        delegate.onFatalError(message);
        fireChanged();
    }

    @Override
    public void onComplete(int succeeded, int failed) {
        delegate.onComplete(succeeded, failed);
        fireChanged();
    }

    private void fireChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
