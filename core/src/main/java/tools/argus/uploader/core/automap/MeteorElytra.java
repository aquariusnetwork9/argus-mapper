package tools.argus.uploader.core.automap;

import java.util.Optional;

/**
 * Reads and adjusts Meteor Client's Elytra Fly module by reflection, so Meteor stays an optional
 * runtime companion rather than a build dependency. Anything unexpected just means "not available".
 */
public final class MeteorElytra {

    private final Object module;
    private final Object mode;
    private final Object horizontalSpeed;
    private final Object verticalSpeed;

    private MeteorElytra(Object module, Object mode, Object horizontalSpeed, Object verticalSpeed) {
        this.module = module;
        this.mode = mode;
        this.horizontalSpeed = horizontalSpeed;
        this.verticalSpeed = verticalSpeed;
    }

    public static Optional<MeteorElytra> find() {
        try {
            Class<?> modules = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
            Object registry = modules.getMethod("get").invoke(null);
            Object module = modules.getMethod("get", String.class).invoke(registry, "elytra-fly");
            if (module == null) {
                return Optional.empty();
            }
            Object settings = module.getClass().getField("settings").get(module);
            var byName = settings.getClass().getMethod("get", String.class);
            Object mode = byName.invoke(settings, "mode");
            Object horizontal = byName.invoke(settings, "horizontal-speed");
            Object vertical = byName.invoke(settings, "vertical-speed");
            if (mode == null || horizontal == null || vertical == null) {
                return Optional.empty();
            }
            return Optional.of(new MeteorElytra(module, mode, horizontal, vertical));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    public boolean isActive() {
        try {
            return (boolean) module.getClass().getMethod("isActive").invoke(module);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public boolean isVanillaMode() {
        try {
            return "Vanilla".equalsIgnoreCase(String.valueOf(mode.getClass().getMethod("get").invoke(mode)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public double horizontalSpeed() {
        return read(horizontalSpeed);
    }

    public double verticalSpeed() {
        return read(verticalSpeed);
    }

    public void setHorizontalSpeed(double value) {
        write(horizontalSpeed, value);
    }

    public void setVerticalSpeed(double value) {
        write(verticalSpeed, value);
    }

    private static double read(Object setting) {
        try {
            return ((Number) setting.getClass().getMethod("get").invoke(setting)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Double.NaN;
        }
    }

    private static void write(Object setting, double value) {
        try {
            setting.getClass().getMethod("set", Object.class).invoke(setting, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The next tick tries again.
        }
    }
}
