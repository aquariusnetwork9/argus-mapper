package tools.argus.uploader.core.automap;

/**
 * Spots a server rubberband: while moving, the player ends up well behind where the last tick's
 * heading should have taken them. Pass the heading the previous tick was flown with - a turn
 * changes the heading, not the movement that already happened.
 */
public final class RubberbandDetector {

    private static final double BACKWARD_BLOCKS = 1.5;

    private boolean hasLast;
    private double lastX;
    private double lastZ;

    public boolean observe(double x, double z, double previousHeadingYawDegrees, boolean wasMoving) {
        boolean rubberband = false;
        if (hasLast && wasMoving) {
            double radians = Math.toRadians(previousHeadingYawDegrees);
            double progress = (x - lastX) * -Math.sin(radians) + (z - lastZ) * Math.cos(radians);
            rubberband = progress < -BACKWARD_BLOCKS;
        }
        lastX = x;
        lastZ = z;
        hasLast = true;
        return rubberband;
    }

    public void reset() {
        hasLast = false;
    }
}
