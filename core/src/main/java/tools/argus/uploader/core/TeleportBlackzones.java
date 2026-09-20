package tools.argus.uploader.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The blackzones live upload sets up when the player lands outside the default upload area: a
 * square {@link #RADIUS_REGIONS} regions out from the landing spot in the dimension they arrived
 * in, plus the matching spot in the other of Overworld/Nether (coordinates scale 8:1) since a base
 * usually has a portal side. The End has no counterpart.
 */
public final class TeleportBlackzones {

    public static final int RADIUS_REGIONS = 25;
    private static final String OVERWORLD = "overworld";
    private static final String NETHER = "the_nether";

    private TeleportBlackzones() {
    }

    public static boolean isOutsideDefaultLimit(double x, double z) {
        return !CoordLimits.withinDefaultLimit(regionOf(x), regionOf(z));
    }

    /** Empty for a dimension that isn't one of the three a blackzone can name. */
    public static List<BlackZone> plan(String dimension, double x, double z, String layer, String idPrefix) {
        List<BlackZone> zones = new ArrayList<>();
        switch (dimension) {
            case OVERWORLD -> {
                zones.add(zone(OVERWORLD, x, z, layer, idPrefix));
                zones.add(zone(NETHER, x / 8, z / 8, layer, idPrefix));
            }
            case NETHER -> {
                zones.add(zone(NETHER, x, z, layer, idPrefix));
                zones.add(zone(OVERWORLD, x * 8, z * 8, layer, idPrefix));
            }
            case "theend" -> zones.add(zone("theend", x, z, layer, idPrefix));
            default -> {
            }
        }
        return zones;
    }

    private static BlackZone zone(String dimension, double x, double z, String layer, String idPrefix) {
        int regionX = regionOf(x);
        int regionZ = regionOf(z);
        RegionBounds bounds = RegionBounds.ofCorners(regionX - RADIUS_REGIONS, regionZ - RADIUS_REGIONS,
                regionX + RADIUS_REGIONS, regionZ + RADIUS_REGIONS);
        return new BlackZone(idPrefix + "-" + dimension, dimension, layer, bounds, "teleport arrival");
    }

    private static int regionOf(double blockCoord) {
        return (int) Math.floor(blockCoord) >> 9;
    }
}
