package tools.argus.uploader.core.bounty;

/**
 * When to ask for the list again: once soon after it becomes wanted, then every interval, backing
 * off after failures. Pure timing so it can be tested; the caller supplies the clock and says
 * whether asking is allowed right now at all.
 */
public final class BountyPoller {

    private static final long MAX_BACKOFF_MILLIS = 10 * 60_000L;

    private final long intervalMillis;
    private final long minGapMillis;
    private long nextDue;
    private long lastStart = Long.MIN_VALUE / 2;
    private int failures;
    private boolean inFlight;

    public BountyPoller(long intervalMillis, long minGapMillis) {
        this.intervalMillis = intervalMillis;
        this.minGapMillis = minGapMillis;
    }

    public synchronized boolean isDue(long now, boolean allowed) {
        return allowed && !inFlight && now >= nextDue && now - lastStart >= minGapMillis;
    }

    public synchronized void started(long now) {
        inFlight = true;
        lastStart = now;
    }

    public synchronized void succeeded(long now) {
        inFlight = false;
        failures = 0;
        nextDue = now + intervalMillis;
    }

    public synchronized void failed(long now) {
        inFlight = false;
        long backoff = intervalMillis;
        for (int i = 0; i < failures && backoff < MAX_BACKOFF_MILLIS; i++) {
            backoff *= 2;
        }
        failures++;
        nextDue = now + Math.min(backoff, MAX_BACKOFF_MILLIS);
    }

    /** Ask again as soon as the minimum gap allows (a manual refresh, or coming back to somewhere polling is allowed). */
    public synchronized void askSoon() {
        nextDue = 0;
    }

    /** Forget everything, e.g. on disconnect; an answer still in flight is ignored by the caller. */
    public synchronized void reset() {
        nextDue = 0;
        failures = 0;
        inFlight = false;
    }

    public synchronized boolean inFlight() {
        return inFlight;
    }
}
