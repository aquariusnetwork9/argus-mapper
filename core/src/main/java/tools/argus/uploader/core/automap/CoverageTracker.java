package tools.argus.uploader.core.automap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Remembers which chunks of a {@link ChunkBox} have been seen mapped. */
public final class CoverageTracker {

    public static final long MAX_CHUNKS = 16_000_000L;

    public record Chunk(int x, int z) {
    }

    private final ChunkBox box;
    private final BitSet covered;
    private int coveredCount;

    public CoverageTracker(ChunkBox box) {
        if (box.chunkCount() > MAX_CHUNKS) {
            throw new IllegalArgumentException("box too large");
        }
        this.box = box;
        this.covered = new BitSet((int) box.chunkCount());
    }

    /** Checks the not-yet-covered chunks within {@code radius} of the given chunk. */
    public void observe(int centerX, int centerZ, int radius, ChunkProbe probe) {
        int x1 = Math.max(box.minX(), centerX - radius);
        int x2 = Math.min(box.maxX(), centerX + radius);
        int z1 = Math.max(box.minZ(), centerZ - radius);
        int z2 = Math.min(box.maxZ(), centerZ + radius);
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                int bit = bit(x, z);
                if (!covered.get(bit) && probe.test(x, z)) {
                    covered.set(bit);
                    coveredCount++;
                }
            }
        }
    }

    public boolean isCovered(int chunkX, int chunkZ) {
        return !box.contains(chunkX, chunkZ) || covered.get(bit(chunkX, chunkZ));
    }

    public long coveredCount() {
        return coveredCount;
    }

    public long missingCount() {
        return box.chunkCount() - coveredCount;
    }

    public double fraction() {
        return (double) coveredCount / box.chunkCount();
    }

    public List<Chunk> missing() {
        List<Chunk> out = new ArrayList<>();
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                if (!covered.get(bit(x, z))) {
                    out.add(new Chunk(x, z));
                }
            }
        }
        return out;
    }

    private int bit(int chunkX, int chunkZ) {
        return (chunkZ - box.minZ()) * box.widthChunks() + (chunkX - box.minX());
    }
}
