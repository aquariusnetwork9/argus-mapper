package tools.argus.uploader.core.automap;

/**
 * Picks the horizontal speed (blocks per tick). It backs off whenever the map isn't keeping up and
 * creeps back up after a stable stretch, never past a ceiling that drops each time the server
 * rubberbands the player.
 */
public final class SpeedGovernor {

    private static final long CHECK_MILLIS = 1_000L;
    private static final long RAISE_AFTER_MILLIS = 5_000L;
    private static final long RUBBERBAND_HOLD_MILLIS = 15_000L;
    private static final double RAISE_STEP = 0.1;
    private static final double RUBBERBAND_BACKOFF = 0.3;
    private static final double RUBBERBAND_MARGIN = 0.1;

    private final double floor;
    private double ceiling;
    private double speed;
    private long lastChange;
    private long healthySince = -1;
    private long lastRubberband = Long.MIN_VALUE / 2;

    public SpeedGovernor(double floor, double cap, double start) {
        this.floor = floor;
        this.ceiling = Math.max(floor, cap);
        this.speed = Math.max(floor, Math.min(ceiling, start));
    }

    public double speed() {
        return speed;
    }

    public double ceiling() {
        return ceiling;
    }

    public void onRubberband(long now) {
        lastRubberband = now;
        ceiling = Math.max(floor, Math.min(ceiling, speed - RUBBERBAND_MARGIN));
        speed = Math.max(floor, Math.min(ceiling, speed - RUBBERBAND_BACKOFF));
        lastChange = now;
        healthySince = now;
    }

    /** @param keepingUp whether the map is covering the lane as fast as it is being flown */
    public void update(long now, boolean keepingUp) {
        if (healthySince < 0) {
            healthySince = now;
            lastChange = now;
        }
        if (now - lastChange < CHECK_MILLIS) {
            return;
        }
        if (!keepingUp) {
            speed = Math.max(floor, speed - Math.max(0.2, speed * 0.08));
            lastChange = now;
            healthySince = now;
        } else if (now - healthySince >= RAISE_AFTER_MILLIS
                && now - lastRubberband >= RUBBERBAND_HOLD_MILLIS && speed < ceiling) {
            speed = Math.min(ceiling, speed + RAISE_STEP);
            lastChange = now;
        }
    }

    public void limitTo(double maxSpeed) {
        speed = Math.max(floor, Math.min(speed, maxSpeed));
    }
}
