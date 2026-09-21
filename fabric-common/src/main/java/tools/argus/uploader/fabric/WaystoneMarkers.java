package tools.argus.uploader.fabric;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import tools.argus.uploader.core.waystone.Waystone;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts the waystones of the dimension you are in on Xaero's World Map as temporary, world-map-only
 * waypoints. Reflective for the same reasons as {@link BountyMarkers}: Xaero's Minimap isn't on this
 * build's compile path and its waypoint API differs between versions. Call only from the client thread.
 */
final class WaystoneMarkers {

    static final String NAME_PREFIX = "ARGUS Waystone: ";

    record Outcome(int count, String problem) {

        boolean ok() {
            return problem == null;
        }
    }

    private record Placed(Object set, Object waypoint, Waystone waystone) {
    }

    private static final List<Placed> placed = new ArrayList<>();

    private WaystoneMarkers() {
    }

    static boolean isEmpty() {
        return placed.isEmpty();
    }

    /** The waystone behind a waypoint Xaero hands back (the minimap's own waypoint object), or null if it isn't one of ours. */
    static Waystone waystoneFor(Object minimapWaypoint) {
        for (Placed p : placed) {
            if (p.waypoint() == minimapWaypoint) {
                return p.waystone();
            }
        }
        return null;
    }

    /** Replaces what this placed before with one waypoint per waystone in {@code dimension}. */
    static Outcome apply(List<Waystone> waystones, RegistryKey<World> dimension) {
        removeAll();
        try {
            Object set = currentSet(dimension);
            String dimensionName = nameOf(dimension);
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
            Object purple = colorClass.getField("PURPLE").get(null);

            for (Waystone waystone : waystones) {
                if (!waystone.dimension().equals(dimensionName)) {
                    continue;
                }
                Object waypoint = constructor.newInstance(waystone.x(), waystone.y(), waystone.z(),
                        NAME_PREFIX + waystone.displayName(), "W", purple, normal, true, true);
                setVisibility.invoke(waypoint, worldMapOnly);
                add.invoke(set, waypoint, false);
                placed.add(new Placed(set, waypoint, waystone));
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

    static String nameOf(RegistryKey<World> dimension) {
        if (World.NETHER.equals(dimension)) {
            return "the_nether";
        }
        if (World.END.equals(dimension)) {
            return "the_end";
        }
        return "overworld";
    }

    private static Object currentSet(RegistryKey<World> dimension) throws ReflectiveOperationException, Unavailable {
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
        if (!dimension.equals(world.getClass().getMethod("getDimId").invoke(world))) {
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
