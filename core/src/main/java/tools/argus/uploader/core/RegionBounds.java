package tools.argus.uploader.core;

/**
 * An inclusive rectangle in Xaero region-file coordinates (the same integer unit
 * {@link RegionFile#regionX()}/{@link RegionFile#regionZ()} already use, i.e. the numbers
 * encoded in a region filename like {@code 3_-1.zip} - not block or chunk coordinates).
 * Used both for a saved {@link BlackZone}'s shape and, later, for a live map-drag selection.
 */
public record RegionBounds(int minRegionX, int minRegionZ, int maxRegionX, int maxRegionZ) {

    public RegionBounds {
        if (minRegionX > maxRegionX || minRegionZ > maxRegionZ) {
            throw new IllegalArgumentException("min must not exceed max: " + minRegionX + ".." + maxRegionX
                    + ", " + minRegionZ + ".." + maxRegionZ);
        }
    }

    /** Normalizes two arbitrary corners (e.g. a drag that went up-left instead of down-right) into a valid bounds. */
    public static RegionBounds ofCorners(int regionX1, int regionZ1, int regionX2, int regionZ2) {
        return new RegionBounds(
                Math.min(regionX1, regionX2), Math.min(regionZ1, regionZ2),
                Math.max(regionX1, regionX2), Math.max(regionZ1, regionZ2));
    }

    public boolean contains(int regionX, int regionZ) {
        return regionX >= minRegionX && regionX <= maxRegionX && regionZ >= minRegionZ && regionZ <= maxRegionZ;
    }

    public boolean contains(RegionFile region) {
        return contains(region.regionX(), region.regionZ());
    }

    public boolean intersects(RegionBounds other) {
        return minRegionX <= other.maxRegionX && maxRegionX >= other.minRegionX
                && minRegionZ <= other.maxRegionZ && maxRegionZ >= other.minRegionZ;
    }
}
