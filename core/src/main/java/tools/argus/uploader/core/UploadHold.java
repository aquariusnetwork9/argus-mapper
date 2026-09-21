package tools.argus.uploader.core;

import tools.argus.uploader.core.automap.ChunkBox;

import java.util.List;

/**
 * Regions that must not be uploaded because an auto-map flight is still writing them: a half-mapped
 * region would replace a fuller copy on the server. Session-only, never saved, and set and cleared only
 * by the flight itself. Enforced where uploads are chosen ({@link UploadRunner}) and again in the
 * network call ({@link ArgusUploadClient}), the same double check blackzones and {@link CoordLimits} get.
 */
public final class UploadHold {

    private static final int REGION_BLOCKS = 512;
    private static final int CHUNKS_PER_REGION = 32;

    public record Area(String dimension, int minRegionX, int minRegionZ, int maxRegionX, int maxRegionZ) {

        boolean contains(String dimension, int regionX, int regionZ) {
            return this.dimension.equals(dimension) && regionX >= minRegionX && regionX <= maxRegionX
                    && regionZ >= minRegionZ && regionZ <= maxRegionZ;
        }
    }

    private static volatile List<Area> areas = List.of();

    private UploadHold() {
    }

    public static void hold(List<Area> held) {
        areas = List.copyOf(held);
    }

    public static void release() {
        areas = List.of();
    }

    public static boolean isActive() {
        return !areas.isEmpty();
    }

    public static boolean isHeld(String dimension, int regionX, int regionZ) {
        for (Area area : areas) {
            if (area.contains(dimension, regionX, regionZ)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHeld(RegionFile region) {
        return isHeld(region.dimension(), region.regionX(), region.regionZ());
    }

    /** The regions a flight over {@code box} writes to, plus {@code marginRegions} around it for the loaded ring past its edge. */
    public static Area forBox(ChunkBox box, String dimension, int marginRegions) {
        return new Area(dimension,
                Math.floorDiv(box.minX(), CHUNKS_PER_REGION) - marginRegions, Math.floorDiv(box.minZ(), CHUNKS_PER_REGION) - marginRegions,
                Math.floorDiv(box.maxX(), CHUNKS_PER_REGION) + marginRegions, Math.floorDiv(box.maxZ(), CHUNKS_PER_REGION) + marginRegions);
    }

    /** The regions along a straight flight from the start point, {@code lengthBlocks} long, plus {@code marginRegions} around it. */
    public static Area forLine(String dimension, double startX, double startZ, int cardinal, double lengthBlocks,
                               int marginRegions) {
        double endX = startX + (cardinal == 0 ? lengthBlocks : cardinal == 2 ? -lengthBlocks : 0);
        double endZ = startZ + (cardinal == 1 ? lengthBlocks : cardinal == 3 ? -lengthBlocks : 0);
        return new Area(dimension,
                Math.floorDiv((int) Math.floor(Math.min(startX, endX)), REGION_BLOCKS) - marginRegions,
                Math.floorDiv((int) Math.floor(Math.min(startZ, endZ)), REGION_BLOCKS) - marginRegions,
                Math.floorDiv((int) Math.floor(Math.max(startX, endX)), REGION_BLOCKS) + marginRegions,
                Math.floorDiv((int) Math.floor(Math.max(startZ, endZ)), REGION_BLOCKS) + marginRegions);
    }
}
