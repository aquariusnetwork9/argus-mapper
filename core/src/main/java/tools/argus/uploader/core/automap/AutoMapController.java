package tools.argus.uploader.core.automap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;

/**
 * Flies a {@link ChunkBox} for the map: lanes first, then a few passes over whatever the map still
 * lacks. It only decides - a yaw, whether to push forward, whether to climb or sink, and a speed -
 * and the caller applies that once a tick. Positions, chunk state and time all come in through
 * {@link Frame} and the probes, so it is unit-testable without a client.
 */
public final class AutoMapController {

    public record Settings(int halfWidthChunks, double minSpeed, double maxSpeed, double startSpeed, int cruiseY,
                           int clearance, double climbPerTick, int lagChunks, int maxRepairRounds) {
    }

    public enum Vertical { UP, DOWN, HOLD }

    public record Command(double yawDegrees, boolean forward, Vertical vertical, double horizontalSpeed) {
    }

    public record Frame(long nowMillis, double x, double y, double z, boolean gliding, String dimension) {
    }

    @FunctionalInterface
    public interface Terrain {
        OptionalInt topY(int blockX, int blockZ);
    }

    public enum State { RUNNING, FINISHED, ABORTED }

    private static final int TELEPORT_BLOCKS = 128;
    private static final int NOT_GLIDING_TICKS = 30;
    private static final int OBSERVE_EVERY_TICKS = 2;
    private static final int MIN_LANE_BLOCKS_BEFORE_MEASURING = 160;
    private static final int LOOK_AHEAD_BLOCKS = 160;
    private static final long HOVER_MILLIS = 4_000L;

    private final ChunkBox box;
    private final Settings settings;
    private final String dimension;
    private final ChunkProbe covered;
    private final Terrain terrain;
    private final CoverageTracker tracker;
    private final SpeedGovernor governor;
    private final RubberbandDetector rubberband = new RubberbandDetector();
    private final PathFollower lanes;

    private State state = State.RUNNING;
    private String abortReason = "";
    private boolean repairing;
    private int repairRound;
    private final Deque<RepairPlanner.Stop> stops = new ArrayDeque<>();
    private RepairPlanner.Stop stop;
    private PathFollower toStop;
    private long hoverUntil;

    private long ticks;
    private int notGliding;
    private boolean hasLast;
    private double lastX;
    private double lastZ;
    private double laneStartX;
    private double laneStartZ;
    private int lastLaneIndex = -1;
    private double lastYaw;
    private boolean lastForward;
    private Vertical vertical = Vertical.HOLD;
    private int rubberbands;

    public AutoMapController(ChunkBox box, Settings settings, String dimension, double startX, double startZ,
                             ChunkProbe covered, Terrain terrain) {
        this.box = box;
        this.settings = settings;
        this.dimension = dimension;
        this.covered = covered;
        this.terrain = terrain;
        this.tracker = new CoverageTracker(box);
        this.governor = new SpeedGovernor(settings.minSpeed(), settings.maxSpeed(), settings.startSpeed());
        this.lanes = new PathFollower(LanePlanner.plan(box, startX, startZ, settings.halfWidthChunks()));
    }

    public State state() {
        return state;
    }

    public String abortReason() {
        return abortReason;
    }

    public double speed() {
        return governor.speed();
    }

    public int rubberbands() {
        return rubberbands;
    }

    public long missingChunks() {
        return tracker.missingCount();
    }

    public double progress() {
        return tracker.fraction();
    }

    public String statusLine() {
        return String.format("%s %.0f%% mapped, %.2f blocks/tick, %d rubberband(s)",
                repairing ? "Filling gaps:" : "Flying lanes:", tracker.fraction() * 100, governor.speed(), rubberbands);
    }

    public void abort(String reason) {
        if (state == State.RUNNING) {
            state = State.ABORTED;
            abortReason = reason;
        }
    }

    /** The command for this tick, or null once the run has finished or been aborted. */
    public Command tick(Frame frame) {
        if (state != State.RUNNING) {
            return null;
        }
        ticks++;
        long now = frame.nowMillis();
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
            return new Command(lastYaw, false, Vertical.HOLD, governor.speed());
        }
        notGliding = 0;

        if (!firstTick && rubberband.observe(frame.x(), frame.z(), lastYaw, lastForward)) {
            rubberbands++;
            governor.onRubberband(now);
        }

        int chunkX = Math.floorDiv((int) Math.floor(frame.x()), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(frame.z()), 16);
        if (ticks % OBSERVE_EVERY_TICKS == 0) {
            tracker.observe(chunkX, chunkZ, settings.halfWidthChunks() + 1, covered);
        }

        double blocksPerTick = governor.speed();
        boolean forward = true;
        double yaw = lastYaw;

        if (!repairing) {
            PathFollower.Steer steer = lanes.steer(frame.x(), frame.z(), blocksPerTick);
            yaw = steer.yawDegrees();
            trackLane(frame);
            updateSpeed(frame, yaw, now);
            if (steer.finished()) {
                beginRepair(frame);
                if (state != State.RUNNING) {
                    return null;
                }
            }
        }
        if (repairing) {
            if (stop == null && !stops.isEmpty()) {
                stop = stops.poll();
                toStop = new PathFollower(List.of(new Waypoint(stop.x(), stop.z(), false)));
                hoverUntil = 0;
            }
            if (stop == null) {
                beginRepair(frame);
                if (state != State.RUNNING) {
                    return null;
                }
            } else {
                PathFollower.Steer steer = toStop.steer(frame.x(), frame.z(), blocksPerTick);
                yaw = steer.yawDegrees();
                if (steer.finished()) {
                    forward = false;
                    if (hoverUntil == 0) {
                        hoverUntil = now + HOVER_MILLIS;
                    }
                    if (now >= hoverUntil || stop.chunks().stream().allMatch(c -> tracker.isCovered(c.x(), c.z()))) {
                        stop = null;
                    }
                }
            }
        }

        double targetY = targetAltitude(frame, yaw);
        updateVertical(frame.y(), targetY, blocksPerTick);
        lastYaw = yaw;
        lastForward = forward;
        return new Command(yaw, forward, vertical, governor.speed());
    }

    private void trackLane(Frame frame) {
        int index = lanes.index();
        if (index != lastLaneIndex) {
            lastLaneIndex = index;
            laneStartX = frame.x();
            laneStartZ = frame.z();
        }
    }

    private void updateSpeed(Frame frame, double yaw, long now) {
        boolean keepingUp = true;
        if (lanes.onLane()
                && Math.hypot(frame.x() - laneStartX, frame.z() - laneStartZ) >= MIN_LANE_BLOCKS_BEFORE_MEASURING) {
            int width = LateralWidth.measure(frame.x(), frame.z(), yaw, settings.lagChunks(),
                    settings.halfWidthChunks() + 1, covered);
            keepingUp = width >= Math.max(1, settings.halfWidthChunks() - 1);
        }
        governor.update(now, keepingUp);
    }

    private void beginRepair(Frame frame) {
        List<CoverageTracker.Chunk> missing = tracker.missing();
        if (missing.isEmpty() || repairRound >= settings.maxRepairRounds()) {
            state = State.FINISHED;
            return;
        }
        repairRound++;
        repairing = true;
        stops.clear();
        stops.addAll(RepairPlanner.plan(missing, frame.x(), frame.z()));
        stop = null;
    }

    private double targetAltitude(Frame frame, double yaw) {
        double radians = Math.toRadians(yaw);
        double headingX = -Math.sin(radians);
        double headingZ = Math.cos(radians);
        int highest = Integer.MIN_VALUE;
        for (int d = 0; d <= LOOK_AHEAD_BLOCKS; d += 16) {
            OptionalInt top = terrain.topY((int) Math.floor(frame.x() + headingX * d),
                    (int) Math.floor(frame.z() + headingZ * d));
            if (top.isPresent()) {
                highest = Math.max(highest, top.getAsInt());
            }
        }
        return highest == Integer.MIN_VALUE ? settings.cruiseY() : Math.max(settings.cruiseY(), highest + settings.clearance());
    }

    private void updateVertical(double y, double targetY, double blocksPerTick) {
        double dy = targetY - y;
        vertical = switch (vertical) {
            case HOLD -> dy > 2 ? Vertical.UP : dy < -4 ? Vertical.DOWN : Vertical.HOLD;
            case UP -> dy < 0.5 ? Vertical.HOLD : Vertical.UP;
            case DOWN -> dy > -1 ? Vertical.HOLD : Vertical.DOWN;
        };
        if (dy > 2) {
            double ticksToObstacle = LOOK_AHEAD_BLOCKS / Math.max(blocksPerTick, 0.1);
            if (dy / settings.climbPerTick() > ticksToObstacle) {
                governor.limitTo(LOOK_AHEAD_BLOCKS * settings.climbPerTick() / dy);
            }
        }
    }
}
