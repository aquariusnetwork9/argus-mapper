package tools.argus.uploader.core;

/** Snapshot of contribution stats, shared between the GUI, Discord reporting, and the add-on API. */
public record MapperStats(long regionsContributed, long chunksContributedApprox, double distanceTraveledBlocks) {

    public static MapperStats of(long regionsContributed, double distanceTraveledBlocks) {
        return new MapperStats(regionsContributed, regionsContributed * 1024L, distanceTraveledBlocks);
    }
}
