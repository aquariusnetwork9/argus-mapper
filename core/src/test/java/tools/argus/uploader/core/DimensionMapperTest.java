package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionMapperTest {

    @Test
    void netherFolderIsClassifiedAsNether() {
        assertEquals("the_nether", DimensionMapper.classify(Path.of("DIM-1", "-1_2.zip")));
    }

    @Test
    void endFolderIsClassifiedAsEnd() {
        assertEquals("theend", DimensionMapper.classify(Path.of("DIM1", "-1_2.zip")));
    }

    @Test
    void everythingElseIsOverworld() {
        assertEquals("overworld", DimensionMapper.classify(Path.of("-1_2.zip")));
        assertEquals("overworld", DimensionMapper.classify(Path.of("mw", "-1_2.zip")));
    }

    @Test
    void dimMinus1DoesNotFalsePositiveAsDim1() {
        // "DIM1" must not match inside "DIM-1" or vice versa, and neither should
        // match a longer numeric folder like "DIM-10" or "DIM10".
        assertEquals("the_nether", DimensionMapper.classify(Path.of("DIM-1", "0_0.zip")));
        assertEquals("theend", DimensionMapper.classify(Path.of("DIM1", "0_0.zip")));
        assertEquals("overworld", DimensionMapper.classify(Path.of("DIM-10", "0_0.zip")));
        assertEquals("overworld", DimensionMapper.classify(Path.of("DIM10", "0_0.zip")));
    }

    @Test
    void cavesBranchIsDetected() {
        assertTrue(DimensionMapper.isCaves(Path.of("caves", "-2147483648", "0_0.zip")));
        assertFalse(DimensionMapper.isCaves(Path.of("mw", "0_0.zip")));
    }
}
