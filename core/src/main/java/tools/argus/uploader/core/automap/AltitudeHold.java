package tools.argus.uploader.core.automap;

import java.util.OptionalInt;

/** Holds cruise altitude, climbing early over terrain ahead and asking for a slower pace when it can't climb in time. */
public final class AltitudeHold {

    private static final int LOOK_AHEAD_BLOCKS = 160;

    public record Result(AutoMapController.Vertical vertical, double speedLimit) {
    }

    private final int cruiseY;
    private final int clearance;
    private final double climbPerTick;
    private final AutoMapController.Terrain terrain;
    private AutoMapController.Vertical vertical = AutoMapController.Vertical.HOLD;

    public AltitudeHold(int cruiseY, int clearance, double climbPerTick, AutoMapController.Terrain terrain) {
        this.cruiseY = cruiseY;
        this.clearance = clearance;
        this.climbPerTick = climbPerTick;
        this.terrain = terrain;
    }

    /** @return the vertical push, and the fastest horizontal speed that still clears what is ahead (infinite if any) */
    public Result update(double x, double y, double z, double yawDegrees, double blocksPerTick) {
        double dy = targetAltitude(x, z, yawDegrees) - y;
        vertical = switch (vertical) {
            case HOLD -> dy > 2 ? AutoMapController.Vertical.UP : dy < -4 ? AutoMapController.Vertical.DOWN : AutoMapController.Vertical.HOLD;
            case UP -> dy < 0.5 ? AutoMapController.Vertical.HOLD : AutoMapController.Vertical.UP;
            case DOWN -> dy > -1 ? AutoMapController.Vertical.HOLD : AutoMapController.Vertical.DOWN;
        };
        double speedLimit = Double.POSITIVE_INFINITY;
        if (dy > 2) {
            double ticksToObstacle = LOOK_AHEAD_BLOCKS / Math.max(blocksPerTick, 0.1);
            if (dy / climbPerTick > ticksToObstacle) {
                speedLimit = LOOK_AHEAD_BLOCKS * climbPerTick / dy;
            }
        }
        return new Result(vertical, speedLimit);
    }

    private double targetAltitude(double x, double z, double yawDegrees) {
        double radians = Math.toRadians(yawDegrees);
        double headingX = -Math.sin(radians);
        double headingZ = Math.cos(radians);
        int highest = Integer.MIN_VALUE;
        for (int d = 0; d <= LOOK_AHEAD_BLOCKS; d += 16) {
            OptionalInt top = terrain.topY((int) Math.floor(x + headingX * d), (int) Math.floor(z + headingZ * d));
            if (top.isPresent()) {
                highest = Math.max(highest, top.getAsInt());
            }
        }
        return highest == Integer.MIN_VALUE ? cruiseY : Math.max(cruiseY, highest + clearance);
    }
}
