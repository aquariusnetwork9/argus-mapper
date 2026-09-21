package tools.argus.uploader.core.automap;

/**
 * A point on the flight path, in block coordinates.
 *
 * @param onLane true if the leg that arrives here is a mapping lane, false for a transit or a
 *               sideways shift between lanes
 */
public record Waypoint(double x, double z, boolean onLane) {
}
