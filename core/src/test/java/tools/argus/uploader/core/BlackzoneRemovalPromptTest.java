package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackzoneRemovalPromptTest {

    private static BlackZone zone(String id, String label) {
        return new BlackZone(id, "overworld", "shallowplague", new RegionBounds(-1, 2, 3, 4), label);
    }

    @Test
    void singleZoneTitleUsesTheLabelWhenThereIsOne() {
        assertEquals("Remove blackzone \"main base\"?", BlackzoneRemovalPrompt.title(List.of(zone("map-1", "main base"))));
    }

    @Test
    void singleZoneTitleFallsBackToTheIdWhenUnlabeled() {
        assertEquals("Remove blackzone \"map-1\"?", BlackzoneRemovalPrompt.title(List.of(zone("map-1", ""))));
    }

    @Test
    void multipleZonesTitleCountsThem() {
        assertEquals("Remove 2 blackzones?", BlackzoneRemovalPrompt.title(List.of(zone("a", ""), zone("b", ""))));
    }

    @Test
    void bodyNamesTheDimensionAndBoundsAndStatesTheConsequence() {
        String body = BlackzoneRemovalPrompt.body(List.of(zone("map-1", "main base")));
        assertTrue(body.contains("main base: overworld, region -1..3, 2..4"));
        assertTrue(body.contains("can be uploaded again"));
        assertTrue(body.contains("only changes this device"));
    }

    @Test
    void bodyListsAtMostThreeZonesAndCountsTheRest() {
        List<BlackZone> zones = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            zones.add(zone("z" + i, ""));
        }
        String body = BlackzoneRemovalPrompt.body(zones);
        assertTrue(body.contains("z2: "));
        assertFalse(body.contains("z3: "));
        assertTrue(body.contains("...and 2 more"));
    }
}
