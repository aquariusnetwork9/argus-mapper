package tools.argus.uploader.fabric;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import tools.argus.uploader.core.bounty.BountyRegion;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 26.2 copy of fabric-common's BountyMarkers. Reflective against Xaero's own classes (unchanged
 * package names/API on 26.2 per NOTES.md), so the only real port here is {@code World.OVERWORLD}
 * -&gt; {@code Level.OVERWORLD} ({@code ResourceKey<Level>}), same as {@link WaystoneMarkers}.
 */
final class BountyMarkers {

    static final String NAME = "ARGUS Bounty Region";
    static final String BOUNTY_NAME = "ARGUS Bounty Region (2x)";
    private static final int Y = 64;

    record Outcome(int count, String problem) {

        boolean ok() {
            return problem == null;
        }
    }

    private record Placed(Object set, Object waypoint) {
    }

    private static final List<Placed> placed = new ArrayList<>();

    private BountyMarkers() {
    }

    static boolean isEmpty() {
        return placed.isEmpty();
    }

    /** Replaces whatever this placed before with one waypoint per region. */
    static Outcome apply(List<BountyRegion> regions) {
        removeAll();
        try {
            Object set = currentOverworldSet();
            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class<?> colorClass = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
            Class<?> purposeClass = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
            Class<?> visibilityClass = Class.forName("xaero.hud.minimap.waypoint.WaypointVisibilityType");
            Constructor<?> constructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class,
                    String.class, colorClass, purposeClass, boolean.class, boolean.class);
            Method setVisibility = waypointClass.getMethod("setVisibility", visibilityClass);
            Method add = set.getClass().getMethod("add", waypointClass, boolean.class);
            Object normal = purposeClass.getField("NORMAL").get(null);
            Object worldMapOnly = visibilityClass.getField("WORLD_MAP_LOCAL").get(null);
            Object regular = colorClass.getField("AQUA").get(null);
            Object gold = colorClass.getField("GOLD").get(null);

            for (BountyRegion region : regions) {
                Object waypoint = constructor.newInstance(region.centerX(), Y, region.centerZ(),
                        region.bounty() ? BOUNTY_NAME : NAME, region.bounty() ? "2x" : "A",
                        region.bounty() ? gold : regular, normal, true, false);
                setVisibility.invoke(waypoint, worldMapOnly);
                add.invoke(set, waypoint, false);
                placed.add(new Placed(set, waypoint));
            }
            return new Outcome(placed.size(), null);
        } catch (Unavailable e) {
            removeAll();
            return new Outcome(0, e.getMessage());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            removeAll();
            return new Outcome(0, "Xaero's Minimap waypoints aren't available on this version");
        }
    }

    static void removeAll() {
        for (Placed p : placed) {
            try {
                p.set().getClass().getMethod("remove", p.waypoint().getClass()).invoke(p.set(), p.waypoint());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Xaero's session may already be gone; temporary waypoints aren't saved anyway
            }
        }
        placed.clear();
    }

    /** Drops the references without touching Xaero, for when its session has already been torn down. */
    static void forget() {
        placed.clear();
    }

    private static Object currentOverworldSet() throws ReflectiveOperationException, Unavailable {
        Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
        Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
        if (session == null) {
            throw new Unavailable("Xaero's Minimap isn't running yet");
        }
        Object manager = session.getClass().getMethod("getWorldManager").invoke(session);
        Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
        if (world == null) {
            throw new Unavailable("Xaero's Minimap hasn't loaded this world yet");
        }
        if (!Level.OVERWORLD.equals(world.getClass().getMethod("getDimId").invoke(world))) {
            throw new Unavailable("Xaero's Minimap is on another dimension");
        }
        Object set = world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
        if (set == null) {
            throw new Unavailable("Xaero's Minimap has no waypoint set open");
        }
        return set;
    }

    private static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message);
        }
    }
}
