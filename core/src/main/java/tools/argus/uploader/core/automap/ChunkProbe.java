package tools.argus.uploader.core.automap;

@FunctionalInterface
public interface ChunkProbe {

    boolean test(int chunkX, int chunkZ);
}
