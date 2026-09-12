package tools.argus.uploader.core;

import java.util.List;

/**
 * Pure geometry backing the nether-region upload gate: "does this Xaero region come near an
 * ARD-recognized highway?" Deliberately independent of ARD's own {@code net.Geo} class (which
 * lives in the bundled Aquarius Road Department code and does live network fetching) - this
 * class takes plain, already-decided data, so it can be unit tested with no network, no
 * Minecraft, and no ARD dependency at all. The integration layer that HAS a live ARD {@code Geo}
 * object is responsible for converting its roads into {@link Segment}s using
 * {@link #isRoadEligible}.
 */
public final class HighwayProximity {

    private HighwayProximity() {
    }

    public record Segment(int x1, int z1, int x2, int z2) {
    }

    /**
     * ARD's road-set policy (PROTOCOL.md §3), applied per-road rather than per-point: an
     * {@code axis} road is allowed everywhere (it runs to the world border by design). A
     * non-axis road (ring/diamond/grid) is allowed only where {@code max(|x|,|z|) <=
     * nearSpawnRadius} - since every point on a ring segment sits at exactly the ring's own
     * {@code radius}, and every point on a diamond/grid segment sits at or inside its declared
     * {@code radius}, {@code radius <= nearSpawnRadius} is a safe (never over-inclusive) stand-in
     * for the point-by-point policy check: it can only be WRONG in the conservative direction
     * (treating a road as fully ineligible when only part of it technically crosses back inside
     * the near-spawn radius, e.g. a diamond whose radius is just over the threshold) - which is
     * the correct default for a privacy filter, exclude when unsure, never include.
     */
    public static boolean isRoadEligible(String category, Integer radius, int nearSpawnRadius) {
        if ("axis".equals(category)) {
            return true;
        }
        return radius != null && radius <= nearSpawnRadius;
    }

    /**
     * True if any point in the inclusive block box {@code [minX,maxX] x [minZ,maxZ]} comes
     * within {@code toleranceBlocks} of any of the given (already policy-filtered / "eligible")
     * segments. Approximates the true Euclidean-distance test by expanding the box by
     * {@code toleranceBlocks} on every side and testing for segment intersection - exact along
     * every edge, and off by at most {@code toleranceBlocks} right at a box corner (rounding a
     * square corner instead of a circular one). For a tolerance of a few blocks against a
     * 512-block Xaero region this is negligible, and the error only ever makes the box very
     * slightly EASIER to match (i.e. a hair more permissive at a corner), never harder - it does
     * not weaken the "excluded means truly excluded" guarantee for the common case of a road
     * actually crossing through or alongside the region.
     */
    public static boolean boxNearAnySegment(int minX, int minZ, int maxX, int maxZ,
                                             List<Segment> eligibleSegments, double toleranceBlocks) {
        double expMinX = minX - toleranceBlocks;
        double expMaxX = maxX + toleranceBlocks;
        double expMinZ = minZ - toleranceBlocks;
        double expMaxZ = maxZ + toleranceBlocks;
        for (Segment s : eligibleSegments) {
            if (segmentIntersectsBox(s.x1(), s.z1(), s.x2(), s.z2(), expMinX, expMinZ, expMaxX, expMaxZ)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Liang-Barsky parametric line-clipping test: true if the segment (x1,z1)-(x2,z2) intersects
     * or touches the axis-aligned box [minX,maxX] x [minZ,maxZ] (a fully-inside segment counts).
     */
    static boolean segmentIntersectsBox(double x1, double z1, double x2, double z2,
                                         double minX, double minZ, double maxX, double maxZ) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double tEnter = 0.0;
        double tExit = 1.0;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {x1 - minX, maxX - x1, z1 - minZ, maxZ - z1};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0) {
                if (q[i] < 0.0) {
                    return false; // parallel to this boundary and entirely on the outside of it
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0.0) {
                    if (t > tExit) {
                        return false;
                    }
                    if (t > tEnter) {
                        tEnter = t;
                    }
                } else {
                    if (t < tEnter) {
                        return false;
                    }
                    if (t < tExit) {
                        tExit = t;
                    }
                }
            }
        }
        return tEnter <= tExit;
    }
}
