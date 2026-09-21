package tools.argus.uploader.core.automap;

/** The speeds a calibration flight steps through, each held for a settling period and then a measured one. */
public final class CalibrationSchedule {

    public static final double[] DEFAULT_SPEEDS = {1.0, 1.25, 1.6, 2.0, 2.5, 3.0, 4.0, 4.61, 5.99};
    public static final long DEFAULT_SETTLE_MILLIS = 8_000L;
    public static final long DEFAULT_MEASURE_MILLIS = 22_000L;

    private static final int MAX_STAGES = 16;

    private final double[] speeds;
    private final long settleMillis;
    private final long measureMillis;

    public CalibrationSchedule(double[] speeds, long settleMillis, long measureMillis) {
        this.speeds = speeds.clone();
        this.settleMillis = settleMillis;
        this.measureMillis = measureMillis;
    }

    public static CalibrationSchedule defaults() {
        return new CalibrationSchedule(DEFAULT_SPEEDS, DEFAULT_SETTLE_MILLIS, DEFAULT_MEASURE_MILLIS);
    }

    /** {@code "1.25,2,3"} in blocks per tick; blank or unusable input gives the defaults. */
    public static CalibrationSchedule parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return defaults();
        }
        String[] parts = csv.split("[,\\s]+");
        if (parts.length > MAX_STAGES) {
            return defaults();
        }
        double[] speeds = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                speeds[i] = Double.parseDouble(parts[i]);
                if (!(speeds[i] >= 0.2 && speeds[i] <= 10)) {
                    return defaults();
                }
            }
        } catch (NumberFormatException e) {
            return defaults();
        }
        return new CalibrationSchedule(speeds, DEFAULT_SETTLE_MILLIS, DEFAULT_MEASURE_MILLIS);
    }

    public int stages() {
        return speeds.length;
    }

    public double speed(int stage) {
        return speeds[stage];
    }

    public long stageMillis() {
        return settleMillis + measureMillis;
    }

    /** Roughly how far a straight flight through every stage goes, in blocks. */
    public double lengthBlocks() {
        double total = 0;
        for (double speed : speeds) {
            total += speed * 20.0 * stageMillis() / 1000.0;
        }
        return total;
    }

    public long totalMillis() {
        return stageMillis() * speeds.length;
    }

    /** The stage running at this point, or -1 once every stage is done. */
    public int stageAt(long elapsedMillis) {
        if (elapsedMillis < 0 || elapsedMillis >= totalMillis()) {
            return -1;
        }
        return (int) (elapsedMillis / stageMillis());
    }

    public boolean isSettling(long elapsedMillis) {
        return elapsedMillis % stageMillis() < settleMillis;
    }
}
