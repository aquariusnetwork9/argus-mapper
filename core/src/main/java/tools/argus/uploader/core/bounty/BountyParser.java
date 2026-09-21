package tools.argus.uploader.core.bounty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the {@code needed-regions} reply; entries it can't make sense of are skipped rather than failing the lot. */
public final class BountyParser {

    static final int MAX_REGIONS = 200;
    private static final int MAX_COORDINATE = 30_000_000;

    private BountyParser() {
    }

    /** @throws IllegalArgumentException if the text isn't JSON or has no {@code needed} list */
    public static BountyResponse parse(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("the reply isn't a JSON object");
        }
        if (!(map.get("needed") instanceof List<?> list)) {
            throw new IllegalArgumentException("the reply has no 'needed' list");
        }
        String dimension = map.get("dimension") instanceof String d && !d.isBlank() ? d : "overworld";
        List<BountyRegion> needed = new ArrayList<>();
        for (Object item : list) {
            if (needed.size() >= MAX_REGIONS) {
                break;
            }
            BountyRegion region = region(item, false);
            if (region != null) {
                needed.add(region);
            }
        }
        BountyRegion bounty = region(map.get("bounty"), true);
        return new BountyResponse(dimension, needed, bounty);
    }

    private static BountyRegion region(Object item, boolean isBounty) {
        if (!(item instanceof Map<?, ?> m)) {
            return null;
        }
        Integer x0 = intOf(m.get("blockX0"));
        Integer z0 = intOf(m.get("blockZ0"));
        Integer x1 = intOf(m.get("blockX1"));
        Integer z1 = intOf(m.get("blockZ1"));
        if (x0 == null || z0 == null || x1 == null || z1 == null || x1 <= x0 || z1 <= z0) {
            return null;
        }
        for (int coordinate : new int[]{x0, z0, x1, z1}) {
            if (Math.abs(coordinate) > MAX_COORDINATE) {
                return null;
            }
        }
        Integer cellX = intOf(m.get("cellX"));
        Integer cellZ = intOf(m.get("cellZ"));
        Integer ring = intOf(m.get("ring"));
        boolean flagged = isBounty || Boolean.TRUE.equals(m.get("isBounty"));
        return new BountyRegion(cellX == null ? 0 : cellX, cellZ == null ? 0 : cellZ, ring == null ? 0 : ring,
                x0, z0, x1, z1, flagged);
    }

    private static Integer intOf(Object value) {
        if (value instanceof Double d && !d.isNaN() && !d.isInfinite() && Math.abs(d) <= Integer.MAX_VALUE) {
            return (int) Math.round(d);
        }
        return null;
    }
}
