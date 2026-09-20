package tools.argus.uploader.core.automap;

import java.util.Optional;

/** A rectangle of chunks, both corners inclusive. */
public record ChunkBox(int minX, int minZ, int maxX, int maxZ) {

    private static final int CHUNKS_PER_REGION = 32;

    public ChunkBox {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("empty box");
        }
    }

    public static ChunkBox ofCorners(int x1, int z1, int x2, int z2) {
        return new ChunkBox(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public static ChunkBox ofRegions(int regionX1, int regionZ1, int regionX2, int regionZ2) {
        int minRx = Math.min(regionX1, regionX2);
        int minRz = Math.min(regionZ1, regionZ2);
        int maxRx = Math.max(regionX1, regionX2);
        int maxRz = Math.max(regionZ1, regionZ2);
        return new ChunkBox(minRx * CHUNKS_PER_REGION, minRz * CHUNKS_PER_REGION,
                maxRx * CHUNKS_PER_REGION + CHUNKS_PER_REGION - 1, maxRz * CHUNKS_PER_REGION + CHUNKS_PER_REGION - 1);
    }

    public int widthChunks() {
        return maxX - minX + 1;
    }

    public int depthChunks() {
        return maxZ - minZ + 1;
    }

    public long chunkCount() {
        return (long) widthChunks() * depthChunks();
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
    }

    public int minBlockX() {
        return minX * 16;
    }

    public int minBlockZ() {
        return minZ * 16;
    }

    public int maxBlockX() {
        return (maxX + 1) * 16;
    }

    public int maxBlockZ() {
        return (maxZ + 1) * 16;
    }

    /** The part of this box inside +/-{@code maxAbsRegion} regions, if any of it is. */
    public Optional<ChunkBox> clampedToRegions(int maxAbsRegion) {
        int lo = -maxAbsRegion * CHUNKS_PER_REGION;
        int hi = maxAbsRegion * CHUNKS_PER_REGION + CHUNKS_PER_REGION - 1;
        int x1 = Math.max(minX, lo);
        int z1 = Math.max(minZ, lo);
        int x2 = Math.min(maxX, hi);
        int z2 = Math.min(maxZ, hi);
        return x1 > x2 || z1 > z2 ? Optional.empty() : Optional.of(new ChunkBox(x1, z1, x2, z2));
    }
}
