package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionBoundsTest {

    @Test
    void containsIsInclusiveOnAllFourEdges() {
        RegionBounds bounds = new RegionBounds(-2, -2, 2, 2);
        assertTrue(bounds.contains(-2, -2));
        assertTrue(bounds.contains(2, 2));
        assertTrue(bounds.contains(0, 0));
        assertFalse(bounds.contains(-3, 0));
        assertFalse(bounds.contains(0, 3));
    }

    @Test
    void containsRegionFileDelegatesToCoordinates() {
        RegionBounds bounds = new RegionBounds(0, 0, 5, 5);
        RegionFile inside = new RegionFile(Path.of("3_3.zip"), "3_3.zip", 3, 3, "overworld", 10);
        RegionFile outside = new RegionFile(Path.of("9_9.zip"), "9_9.zip", 9, 9, "overworld", 10);
        assertTrue(bounds.contains(inside));
        assertFalse(bounds.contains(outside));
    }

    @Test
    void ofCornersNormalizesRegardlessOfDragDirection() {
        RegionBounds draggedDownRight = RegionBounds.ofCorners(-1, -1, 3, 4);
        RegionBounds draggedUpLeft = RegionBounds.ofCorners(3, 4, -1, -1);
        assertEquals(draggedDownRight, draggedUpLeft);
        assertEquals(new RegionBounds(-1, -1, 3, 4), draggedDownRight);
    }

    @Test
    void rejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(5, 0, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(0, 5, 5, 0));
    }
}
