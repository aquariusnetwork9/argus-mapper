package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackzoneStoreTest {

    private static RegionFile region(String dimension, int x, int z) {
        String filename = x + "_" + z + ".zip";
        return new RegionFile(Path.of(filename), filename, x, z, dimension, 10);
    }

    @Test
    void freshStoreExcludesNothing() {
        BlackzoneStore store = BlackzoneStore.empty();
        assertFalse(store.isBlackzoned("overworld", "shallowplague", region("overworld", 0, 0)));
    }

    @Test
    void regionInsideBoundsOnMatchingDimensionAndLayerIsBlackzoned() throws IOException {
        BlackzoneStore store = BlackzoneStore.empty();
        store.addOrReplace(new BlackZone("home", "overworld", "shallowplague",
                new RegionBounds(-1, -1, 1, 1), "main base"));

        assertTrue(store.isBlackzoned("overworld", "shallowplague", region("overworld", 0, 0)));
        assertFalse(store.isBlackzoned("overworld", "shallowplague", region("overworld", 5, 5)),
                "outside the declared bounds");
        assertFalse(store.isBlackzoned("the_nether", "shallowplague", region("the_nether", 0, 0)),
                "same coordinates but a different dimension");
        assertFalse(store.isBlackzoned("overworld", "some-other-server", region("overworld", 0, 0)),
                "same coordinates but a different server/layer");
    }

    @Test
    void survivesReloadFromDisk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("blackzones.properties");
        BlackzoneStore store = BlackzoneStore.load(file);
        store.addOrReplace(new BlackZone("home", "overworld", "shallowplague",
                new RegionBounds(0, 0, 2, 2), "main base"));

        BlackzoneStore reloaded = BlackzoneStore.load(file);
        assertTrue(reloaded.isBlackzoned("overworld", "shallowplague", region("overworld", 1, 1)));
        assertEquals(1, reloaded.all().size());
        assertEquals("main base", reloaded.all().get(0).label());
    }

    @Test
    void removeDropsAZoneAndPersists(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("blackzones.properties");
        BlackzoneStore store = BlackzoneStore.load(file);
        store.addOrReplace(new BlackZone("home", "overworld", "shallowplague", new RegionBounds(0, 0, 0, 0), ""));

        assertTrue(store.remove("home"));
        assertFalse(store.remove("home"), "removing twice reports false, doesn't error");
        assertTrue(store.all().isEmpty());

        assertTrue(BlackzoneStore.load(file).all().isEmpty(), "removal persisted to disk");
    }

    @Test
    void forServerFiltersByDimensionAndLayer() throws IOException {
        BlackzoneStore store = BlackzoneStore.empty();
        store.addOrReplace(new BlackZone("a", "overworld", "shallowplague", new RegionBounds(0, 0, 0, 0), ""));
        store.addOrReplace(new BlackZone("b", "the_nether", "shallowplague", new RegionBounds(0, 0, 0, 0), ""));
        store.addOrReplace(new BlackZone("c", "overworld", "other-server", new RegionBounds(0, 0, 0, 0), ""));

        List<BlackZone> onlyOverworldShallowplague = store.forServer("overworld", "shallowplague");
        assertEquals(1, onlyOverworldShallowplague.size());
        assertEquals("a", onlyOverworldShallowplague.get(0).id());
    }
}
