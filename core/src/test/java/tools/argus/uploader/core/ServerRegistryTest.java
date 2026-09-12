package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerRegistryTest {

    @Test
    void seedsA6b6tDefaultOnFirstLoad(@TempDir Path dir) throws IOException {
        ServerRegistry registry = ServerRegistry.load(dir.resolve("servers.properties"));
        assertTrue(registry.byName("6b6t").isPresent());
        assertEquals("shallowplague", registry.byName("6b6t").get().layer());
    }

    @Test
    void matchesAreCaseInsensitiveSubstrings() {
        ServerProfile profile = new ServerProfile("6b6t", "shallowplague", List.of("6b6t"));
        assertTrue(profile.matches("play.6b6t.org"));
        assertTrue(profile.matches("PLAY.6B6T.ORG"));
        assertTrue(profile.matches("6b6t.org:25565"));
        assertTrue(profile.matches("6b6t.org"));
    }

    @Test
    void doesNotMatchUnrelatedAddresses() {
        ServerProfile profile = new ServerProfile("6b6t", "shallowplague", List.of("6b6t"));
        assertEquals(false, profile.matches("hypixel.net"));
        assertEquals(false, profile.matches(""));
        assertEquals(false, profile.matches(null));
    }

    @Test
    void addOrReplacePersistsAcrossReload(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("servers.properties");
        ServerRegistry registry = ServerRegistry.load(file);
        registry.addOrReplace(new ServerProfile("2b2t", "some-other-layer", List.of("2b2t", "2builders2tools")));

        ServerRegistry reloaded = ServerRegistry.load(file);
        assertTrue(reloaded.byName("2b2t").isPresent());
        assertEquals("some-other-layer", reloaded.byName("2b2t").get().layer());
        assertEquals(List.of("2b2t", "2builders2tools"), reloaded.byName("2b2t").get().addressMatches());
        // the seeded default should still be there too
        assertTrue(reloaded.byName("6b6t").isPresent());
    }

    @Test
    void matchingReturnsEveryProfileThatMatchesForAmbiguityDetection(@TempDir Path dir) throws IOException {
        ServerRegistry registry = ServerRegistry.load(dir.resolve("servers.properties"));
        registry.addOrReplace(new ServerProfile("overlap", "some-layer", List.of("6b6t")));

        List<ServerProfile> matches = registry.matching("play.6b6t.org");
        assertEquals(2, matches.size());
    }

    @Test
    void emptyRegistryNeverThrowsOnSave() throws IOException {
        ServerRegistry registry = ServerRegistry.empty();
        registry.save();
        registry.addOrReplace(new ServerProfile("x", "y", List.of("z")));
        assertTrue(registry.byName("x").isPresent());
    }
}
