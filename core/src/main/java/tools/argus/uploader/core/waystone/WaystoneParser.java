package tools.argus.uploader.core.waystone;

import tools.argus.uploader.core.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the waystone API's JSON. Entries it can't make sense of are skipped rather than failing the lot. */
public final class WaystoneParser {

    private static final Set<String> DIMENSIONS = Set.of("overworld", "the_nether", "the_end");
    private static final int MAX_COORDINATE = 30_000_000;
    private static final int MAX_WAYSTONES = 500;

    private WaystoneParser() {
    }

    public record Started(String id, String waystone, String ign, int balance) {
    }

    /** @throws IllegalArgumentException if the text isn't JSON or has no {@code waystones} list */
    public static List<Waystone> waystones(String json) {
        if (!(object(json).get("waystones") instanceof List<?> list)) {
            throw new IllegalArgumentException("the reply has no 'waystones' list");
        }
        List<Waystone> out = new ArrayList<>();
        for (Object item : list) {
            if (out.size() >= MAX_WAYSTONES) {
                break;
            }
            if (!(item instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || name.isBlank()) {
                continue;
            }
            Integer x = intOf(m.get("x"));
            Integer z = intOf(m.get("z"));
            if (x == null || z == null || Math.abs(x) > MAX_COORDINATE || Math.abs(z) > MAX_COORDINATE) {
                continue;
            }
            Integer y = intOf(m.get("y"));
            String dim = m.get("dim") instanceof String d && DIMENSIONS.contains(d) ? d : "overworld";
            String label = m.get("label") instanceof String l ? l.trim() : "";
            out.add(new Waystone(name, label, x, y == null ? 64 : y, z, dim));
        }
        return out;
    }

    public static SystemStatus systemStatus(String json) {
        Map<?, ?> m = object(json);
        return new SystemStatus(Boolean.TRUE.equals(m.get("online")), Boolean.TRUE.equals(m.get("busy")),
                orZero(intOf(m.get("queued"))), orZero(intOf(m.get("secondsUntilReady"))));
    }

    public static TokenInfo tokenInfo(String json) {
        Map<?, ?> m = object(json);
        if (!(m.get("ign") instanceof String ign)) {
            throw new IllegalArgumentException("the reply has no 'ign'");
        }
        return new TokenInfo(ign, orZero(intOf(m.get("balance"))), orZero(intOf(m.get("earned"))),
                orZero(intOf(m.get("spent"))), orZero(intOf(m.get("regions"))), orZero(intOf(m.get("regionsPerToken"))));
    }

    public static Started started(String json) {
        Map<?, ?> m = object(json);
        if (!(m.get("id") instanceof String id) || id.isBlank()) {
            throw new IllegalArgumentException("the reply has no request id");
        }
        return new Started(id, m.get("waystone") instanceof String w ? w : "", m.get("ign") instanceof String i ? i : "",
                orZero(intOf(m.get("balance"))));
    }

    public static TeleportStatus teleportStatus(String json) {
        Map<?, ?> m = object(json);
        if (!(m.get("status") instanceof String status) || status.isBlank()) {
            throw new IllegalArgumentException("the reply has no status");
        }
        String bot = "";
        for (String key : new String[]{"botIgn", "bot", "botName"}) {
            if (m.get(key) instanceof String s && !s.isBlank()) {
                bot = s.trim();
                break;
            }
        }
        return new TeleportStatus(status, m.get("waystone") instanceof String w ? w : "", orZero(intOf(m.get("position"))),
                m.get("error") instanceof String e ? e : null, bot);
    }

    /** The {@code error} code of an error reply, or null if the body isn't one. */
    public static String errorCode(String json) {
        try {
            return object(json).get("error") instanceof String e ? e : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The {@code balance} an insufficient-tokens reply carries, or -1. */
    public static int balanceOf(String json) {
        try {
            Integer balance = intOf(object(json).get("balance"));
            return balance == null ? -1 : balance;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static Map<?, ?> object(String json) {
        if (json == null || !(MiniJson.parse(json) instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("the reply isn't a JSON object");
        }
        return map;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static Integer intOf(Object value) {
        if (value instanceof Double d && !d.isNaN() && !d.isInfinite() && Math.abs(d) <= Integer.MAX_VALUE) {
            return (int) Math.round(d);
        }
        return null;
    }
}
