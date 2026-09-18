package tools.argus.uploader.fabric.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.UploadSummary;

/**
 * The public extension point for other Fabric mods - everything in this {@code api} package is
 * the stable surface third-party mods are meant to depend on directly, following the same
 * pattern Aquarius Road Department's own {@code api.LocalHazardEvents} uses. A consumer adds
 * this mod's jar as a normal Gradle dependency and registers a listener:
 *
 * <pre>{@code
 * ArgusMapperEvents.UPLOAD_COMPLETED.register(summary ->
 *     log.info(summary.succeeded() + " regions uploaded"));
 * ArgusMapperEvents.STATS_CHANGED.register(stats ->
 *     hud.update(stats.distanceTraveledBlocks()));
 * }</pre>
 *
 * <p>Both events fire on the client thread, never off-thread - a listener can safely touch
 * world/HUD state directly. Everything outside this package is internal and can change without
 * notice.
 */
public final class ArgusMapperEvents {

    private ArgusMapperEvents() {
    }

    /** Fires once an /argus upload run finishes (including one that uploaded nothing). */
    public static final Event<UploadCompleted> UPLOAD_COMPLETED = EventFactory.createArrayBacked(UploadCompleted.class,
            listeners -> summary -> {
                for (UploadCompleted listener : listeners) {
                    listener.onUploadCompleted(summary);
                }
            });

    /** Fires whenever contribution stats change - after an upload run, and once at startup with
     *  whatever was already on disk, so a fresh listener doesn't have to wait for an upload to
     *  get an initial value. */
    public static final Event<StatsChanged> STATS_CHANGED = EventFactory.createArrayBacked(StatsChanged.class,
            listeners -> stats -> {
                for (StatsChanged listener : listeners) {
                    listener.onStatsChanged(stats);
                }
            });

    @FunctionalInterface
    public interface UploadCompleted {
        void onUploadCompleted(UploadSummary summary);
    }

    @FunctionalInterface
    public interface StatsChanged {
        void onStatsChanged(MapperStats stats);
    }
}
