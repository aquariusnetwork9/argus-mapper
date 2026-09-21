package tools.argus.uploader.fabric;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Asks Xaero's World Map whether it has recorded a chunk yet, by reflection (see
 * {@link XaeroMapOpener} for why). Mapping only counts once Xaero has it, which is slower than the
 * chunk arriving.
 */
final class XaeroCoverage {

    private final Object processor;
    private final Method getMapTile;
    private final Method getCurrentCaveLayer;
    private int layer;

    private XaeroCoverage(Object processor, Method getMapTile, Method getCurrentCaveLayer) {
        this.processor = processor;
        this.getMapTile = getMapTile;
        this.getCurrentCaveLayer = getCurrentCaveLayer;
    }

    static Optional<XaeroCoverage> open() {
        try {
            Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
            Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null || !(boolean) sessionClass.getMethod("isUsable").invoke(session)) {
                return Optional.empty();
            }
            Object processor = sessionClass.getMethod("getMapProcessor").invoke(session);
            Class<?> processorClass = Class.forName("xaero.map.MapProcessor");
            XaeroCoverage coverage = new XaeroCoverage(processor,
                    processorClass.getMethod("getMapTile", int.class, int.class, int.class),
                    processorClass.getMethod("getCurrentCaveLayer"));
            coverage.refreshLayer();
            return Optional.of(coverage);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    void refreshLayer() {
        try {
            layer = (int) getCurrentCaveLayer.invoke(processor);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keeps the last layer.
        }
    }

    boolean written(int chunkX, int chunkZ) {
        try {
            return getMapTile.invoke(processor, layer, chunkX, chunkZ) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
