package tools.argus.uploader.core;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.random.RandomGenerator;

/**
 * Session-only state machine behind live upload. Nothing here is ever read from or written to
 * disk, so live upload is off again after a restart or crash - the only way to get it is to turn
 * it on in the running game.
 *
 * <p>While on, a cycle is started after a random delay of {@link #MIN_DELAY_MILLIS} to
 * {@link #MAX_DELAY_MILLIS}: a compromise between streaming a player's path as it happens and
 * uploading by hand, so the backend never sees where a player is in real time. A teleport - the
 * server's countdown message, a jump over {@link TeleportDetector#THRESHOLD_BLOCKS} blocks, or a
 * dimension change - pauses it immediately and cancels any run in flight. The player is only asked
 * what to do once the teleport has settled ({@link #SETTLE_MILLIS} with no further jump, and no
 * further announced teleport pending), so the prompt never opens mid-load and always refers to
 * where they ended up; nothing resumes until {@link #resume} is called. All time is passed in, so
 * this is unit-testable without waiting.
 */
public final class AutoUploadCoordinator {

    public static final long MIN_DELAY_MILLIS = 45L * 60_000L;
    public static final long MAX_DELAY_MILLIS = 80L * 60_000L;
    public static final long SETTLE_MILLIS = 5_000L;
    private static final long ARRIVAL_GRACE_MILLIS = 20_000L;

    public enum Cause { ANNOUNCED, JUMP, DIMENSION_CHANGE }

    /** @param blocksMoved 0 when unknown (announced but no jump seen, or a dimension change) */
    public record Teleport(String dimension, double x, double z, double blocksMoved, Cause cause) {
    }

    public interface Host {
        boolean isUploadRunning();

        /** @param liveSinceMillis regions saved before this moment were not part of this live session */
        void startCycle(long liveSinceMillis);

        void cancelAutoRun();

        /** Ask the player what to do; answer with {@link #resume} or {@link #promptFinished}. */
        void promptTeleport(Teleport teleport);
    }

    private final Host host;
    private final RandomGenerator random;
    private final TeleportDetector detector = new TeleportDetector();

    private boolean live;
    private boolean paused;
    private boolean promptOutstanding;
    private long liveSinceMillis;
    private long nextRunAtMillis;
    private long awaitingArrivalUntilMillis;
    private long settleUntilMillis;
    private Cause settleCause;
    private double settleBlocks;

    private boolean hasPosition;
    private String lastDimension;
    private double lastX;
    private double lastZ;

    public AutoUploadCoordinator(Host host, RandomGenerator random) {
        this.host = host;
        this.random = random;
    }

    public synchronized boolean isLive() {
        return live;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized void setLive(boolean on, long now) {
        if (on == live) {
            return;
        }
        live = on;
        if (on) {
            liveSinceMillis = now;
            nextRunAtMillis = now + randomDelay();
        } else {
            clearRunState();
            host.cancelAutoRun();
        }
    }

    /** Turns live upload off and forgets any pause - used when leaving a world/server. */
    public synchronized void reset() {
        live = false;
        clearRunState();
        hasPosition = false;
        detector.reset();
        host.cancelAutoRun();
    }

    private void clearRunState() {
        paused = false;
        promptOutstanding = false;
        nextRunAtMillis = 0;
        awaitingArrivalUntilMillis = 0;
        settleUntilMillis = 0;
    }

    public synchronized void onChatMessage(String message, long now) {
        if (!live) {
            return;
        }
        OptionalInt seconds = TeleportChat.secondsUntilTeleport(message);
        if (seconds.isEmpty() || promptOutstanding) {
            return;
        }
        pause();
        long deadline = now + seconds.getAsInt() * 1000L + ARRIVAL_GRACE_MILLIS;
        awaitingArrivalUntilMillis = Math.max(awaitingArrivalUntilMillis, deadline);
    }

    public synchronized void onPlayerPosition(String dimension, double x, double y, double z, long now) {
        hasPosition = true;
        lastDimension = dimension;
        lastX = x;
        lastZ = z;
        Optional<TeleportDetector.Jump> jump = detector.observe(dimension, x, y, z);
        if (jump.isEmpty() || !live || promptOutstanding) {
            return;
        }
        TeleportDetector.Jump j = jump.get();
        pause();
        awaitingArrivalUntilMillis = 0;
        settleUntilMillis = now + SETTLE_MILLIS;
        settleCause = j.dimensionChanged() ? Cause.DIMENSION_CHANGE : Cause.JUMP;
        settleBlocks = j.blocksMoved();
    }

    /** The player is briefly absent (a loading screen, a respawn). The baseline is kept so a jump
     *  seen when they reappear still counts. */
    public synchronized void onPlayerGone() {
        hasPosition = false;
    }

    public synchronized void tick(long now) {
        if (!live) {
            return;
        }
        if (awaitingArrivalUntilMillis != 0 && now >= awaitingArrivalUntilMillis && !promptOutstanding) {
            awaitingArrivalUntilMillis = 0;
            if (settleUntilMillis == 0) {
                settleUntilMillis = now;
                settleCause = Cause.ANNOUNCED;
                settleBlocks = 0;
            }
        }
        if (settleUntilMillis != 0 && awaitingArrivalUntilMillis == 0 && now >= settleUntilMillis
                && hasPosition && !promptOutstanding) {
            settleUntilMillis = 0;
            prompt(new Teleport(lastDimension, lastX, lastZ, settleBlocks, settleCause));
            return;
        }
        if (paused || nextRunAtMillis == 0 || now < nextRunAtMillis) {
            return;
        }
        nextRunAtMillis = now + randomDelay();
        if (!host.isUploadRunning()) {
            host.startCycle(liveSinceMillis);
        }
    }

    /** The player confirmed: continue, with a fresh random delay so nothing sends right away. */
    public synchronized void resume(long now) {
        if (!paused) {
            return;
        }
        paused = false;
        promptOutstanding = false;
        awaitingArrivalUntilMillis = 0;
        settleUntilMillis = 0;
        nextRunAtMillis = now + randomDelay();
    }

    /** The player answered the prompt without resuming - stays paused until {@link #resume}. */
    public synchronized void promptFinished() {
        promptOutstanding = false;
    }

    public synchronized String statusLine(long now) {
        if (!live) {
            return "Off";
        }
        if (paused) {
            return "Paused - teleport detected";
        }
        long minutes = Math.max(1, (nextRunAtMillis - now + 59_999L) / 60_000L);
        return "On - next upload in about " + minutes + " min";
    }

    private void pause() {
        if (!paused) {
            paused = true;
            host.cancelAutoRun();
        }
    }

    private void prompt(Teleport teleport) {
        promptOutstanding = true;
        host.promptTeleport(teleport);
    }

    private long randomDelay() {
        return random.nextLong(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS + 1);
    }
}
