package tools.argus.uploader.core.automap;

import java.util.List;
import java.util.function.Supplier;

/**
 * Flies one straight line at a series of speeds while recording, for each, how wide a strip of
 * chunks was loaded and how wide a strip Xaero mapped. The numbers to log come from the probes; what
 * it produces is the raw material for {@link CalibrationReport}.
 */
public final class CalibrationController implements FlightPlan {

    public record Extras(double fps, double pingMillis, String render) {
    }

    private static final int TELEPORT_BLOCKS = 128;
    private static final int NOT_GLIDING_TICKS = 30;
    private static final int SAMPLE_EVERY_TICKS = 5;
    private static final int SPEED_WINDOW_TICKS = 20;
    private static final String SAMPLE_HEADER = "t_ms,stage,set_speed,measured_speed,x,y,z,along_chunk,"
            + "loaded_left,loaded_right,loaded_ahead,loaded_in_view,written_left_lag6,written_right_lag6,"
            + "rubberband,fps,ping_ms,render";

    private final CalibrationSchedule schedule;
    private final String dimension;
    private final int cardinal;
    private final boolean alongX;
    private final int direction;
    private final int lineRow;
    private final int viewRadius;
    private final ChunkProbe loaded;
    private final ChunkProbe written;
    private final Supplier<Extras> extras;
    private final PathFollower line;
    private final AltitudeHold altitude;
    private final CorridorRecorder recorder;
    private final RubberbandDetector rubberband = new RubberbandDetector();
    private final double[] speedSum;
    private final int[] speedSamples;
    private final int[] rubberbands;
    private final StringBuilder samples = new StringBuilder(SAMPLE_HEADER).append('\n');
    private final double[] recentX = new double[SPEED_WINDOW_TICKS];
    private final double[] recentZ = new double[SPEED_WINDOW_TICKS];

    private AutoMapController.State state = AutoMapController.State.RUNNING;
    private String abortReason = "";
    private long startMillis = -1;
    private long ticks;
    private int notGliding;
    private boolean hasLast;
    private double lastX;
    private double lastZ;
    private double lastYaw;
    private boolean lastForward;
    private int currentStage;
    private long elapsedMillis;

    /** @param cardinal 0 east (+X), 1 south (+Z), 2 west (-X), 3 north (-Z) */
    public CalibrationController(CalibrationSchedule schedule, double startX, double startZ, int cardinal,
                                 String dimension, int viewRadius, ChunkProbe loaded, ChunkProbe written,
                                 AutoMapController.Terrain terrain, int cruiseY, int clearance,
                                 double climbPerTick, Supplier<Extras> extras) {
        this.schedule = schedule;
        this.dimension = dimension;
        this.cardinal = cardinal;
        this.alongX = cardinal % 2 == 0;
        this.direction = cardinal < 2 ? 1 : -1;
        this.viewRadius = viewRadius;
        this.loaded = loaded;
        this.written = written;
        this.extras = extras;
        this.lineRow = Math.floorDiv((int) Math.floor(alongX ? startZ : startX), 16);
        double far = 10_000_000.0 * direction;
        this.line = new PathFollower(List.of(new Waypoint(alongX ? startX + far : startX, alongX ? startZ : startZ + far, true)));
        this.altitude = new AltitudeHold(cruiseY, clearance, climbPerTick, terrain);
        this.recorder = new CorridorRecorder(alongX, direction, lineRow, viewRadius, schedule.stages());
        this.speedSum = new double[schedule.stages()];
        this.speedSamples = new int[schedule.stages()];
        this.rubberbands = new int[schedule.stages()];
    }

    public static int cardinalFromYaw(double yawDegrees) {
        double wrapped = ((yawDegrees % 360) + 360) % 360;
        return switch ((int) Math.round(wrapped / 90.0) % 4) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 3;
            default -> 0;
        };
    }

    public static String cardinalName(int cardinal) {
        return switch (cardinal) {
            case 0 -> "east (+X)";
            case 1 -> "south (+Z)";
            case 2 -> "west (-X)";
            default -> "north (-Z)";
        };
    }

    public CalibrationSchedule schedule() {
        return schedule;
    }

    public String heading() {
        return cardinalName(cardinal);
    }

    public int lineRow() {
        return lineRow;
    }

    public boolean alongX() {
        return alongX;
    }

    public long elapsedMillis() {
        return elapsedMillis;
    }

    public double measuredSpeed(int stage) {
        return speedSamples[stage] == 0 ? Double.NaN : speedSum[stage] / speedSamples[stage];
    }

    public int rubberbands(int stage) {
        return rubberbands[stage];
    }

    public CorridorRecorder.StageResult result(int stage) {
        return recorder.result(stage);
    }

    public String samplesCsv() {
        return samples.toString();
    }

    @Override
    public AutoMapController.State state() {
        return state;
    }

    @Override
    public String abortReason() {
        return abortReason;
    }

    @Override
    public void abort(String reason) {
        if (state == AutoMapController.State.RUNNING) {
            state = AutoMapController.State.ABORTED;
            abortReason = reason;
        }
    }

    @Override
    public String statusLine() {
        return String.format("Calibrating: stage %d of %d at %.2f blocks/tick", currentStage + 1, schedule.stages(),
                schedule.speed(Math.min(currentStage, schedule.stages() - 1)));
    }

    @Override
    public String resultLine() {
        return "Calibration flew " + (state == AutoMapController.State.FINISHED ? "all" : "part of")
                + " its " + schedule.stages() + " speeds.";
    }

    @Override
    public AutoMapController.Command tick(AutoMapController.Frame frame) {
        if (state != AutoMapController.State.RUNNING) {
            return null;
        }
        if (!frame.dimension().equals(dimension)) {
            abort("you changed dimension");
            return null;
        }
        if (hasLast && Math.hypot(frame.x() - lastX, frame.z() - lastZ) > TELEPORT_BLOCKS) {
            abort("you were teleported");
            return null;
        }
        boolean firstTick = !hasLast;
        hasLast = true;
        lastX = frame.x();
        lastZ = frame.z();
        if (!frame.gliding()) {
            if (++notGliding > NOT_GLIDING_TICKS) {
                abort("you stopped gliding");
                return null;
            }
            return new AutoMapController.Command(lastYaw, false, AutoMapController.Vertical.HOLD, 0.5);
        }
        notGliding = 0;
        if (startMillis < 0) {
            startMillis = frame.nowMillis();
        }
        elapsedMillis = frame.nowMillis() - startMillis;
        int stage = schedule.stageAt(elapsedMillis);
        if (stage < 0) {
            state = AutoMapController.State.FINISHED;
            return null;
        }
        currentStage = stage;
        boolean settling = schedule.isSettling(elapsedMillis);
        double setSpeed = schedule.speed(stage);

        boolean hit = !firstTick && rubberband.observe(frame.x(), frame.z(), lastYaw, lastForward);
        if (hit) {
            rubberbands[stage]++;
        }
        int slot = (int) (ticks % SPEED_WINDOW_TICKS);
        double measured = ticks >= SPEED_WINDOW_TICKS
                ? Math.hypot(frame.x() - recentX[slot], frame.z() - recentZ[slot]) / SPEED_WINDOW_TICKS
                : Double.NaN;
        recentX[slot] = frame.x();
        recentZ[slot] = frame.z();
        if (!settling && !Double.isNaN(measured)) {
            speedSum[stage] += measured;
            speedSamples[stage]++;
        }

        int along = Math.floorDiv((int) Math.floor(alongX ? frame.x() : frame.z()), 16);
        recorder.observe(along, stage, settling, loaded, written);
        if (ticks % SAMPLE_EVERY_TICKS == 0) {
            sample(frame, stage, setSpeed, measured, along, hit);
        }
        ticks++;

        double yaw = line.steer(frame.x(), frame.z(), setSpeed).yawDegrees();
        AltitudeHold.Result climb = altitude.update(frame.x(), frame.y(), frame.z(), yaw, setSpeed);
        lastYaw = yaw;
        lastForward = true;
        return new AutoMapController.Command(yaw, true, climb.vertical(), setSpeed);
    }

    private void sample(AutoMapController.Frame frame, int stage, double setSpeed, double measured, int along,
                        boolean rubberbandHit) {
        Extras info = extras.get();
        int nearAlong = along - direction * CorridorRecorder.LAG_NEAR;
        samples.append(elapsedMillis).append(',').append(stage).append(',').append(fmt(setSpeed)).append(',')
                .append(fmt(measured)).append(',').append(fmt(frame.x())).append(',').append(fmt(frame.y())).append(',')
                .append(fmt(frame.z())).append(',').append(along).append(',')
                .append(side(loaded, along, -1)).append(',').append(side(loaded, along, 1)).append(',')
                .append(ahead(along)).append(',').append(inView(frame)).append(',')
                .append(side(written, nearAlong, -1)).append(',').append(side(written, nearAlong, 1)).append(',')
                .append(rubberbandHit ? 1 : 0).append(',').append(fmt(info.fps())).append(',')
                .append(fmt(info.pingMillis())).append(",\"").append(info.render().replace("\"", "'")).append("\"\n");
    }

    private int side(ChunkProbe probe, int along, int sign) {
        int count = 0;
        for (int k = 1; k <= CorridorRecorder.ROWS; k++) {
            int across = lineRow + sign * k;
            if (!probe.test(alongX ? along : across, alongX ? across : along)) {
                break;
            }
            count++;
        }
        return count;
    }

    private int ahead(int along) {
        int count = 0;
        for (int k = 1; k <= viewRadius + 4; k++) {
            int a = along + direction * k;
            if (!loaded.test(alongX ? a : lineRow, alongX ? lineRow : a)) {
                break;
            }
            count++;
        }
        return count;
    }

    private int inView(AutoMapController.Frame frame) {
        int centerX = Math.floorDiv((int) Math.floor(frame.x()), 16);
        int centerZ = Math.floorDiv((int) Math.floor(frame.z()), 16);
        int count = 0;
        for (int cx = centerX - viewRadius; cx <= centerX + viewRadius; cx++) {
            for (int cz = centerZ - viewRadius; cz <= centerZ + viewRadius; cz++) {
                if (loaded.test(cx, cz)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "" : String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
