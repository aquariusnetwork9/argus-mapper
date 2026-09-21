package tools.argus.uploader.core.bounty;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BountyParserTest {

    private static final String SAMPLE = """
            {
              "dimension": "overworld",
              "regionSizeBlocks": 512,
              "cellSizeRegions": 2,
              "bounty": { "regionX0":14, "regionZ0":20, "blockX0":7168, "blockZ0":10240, "blockX1":8192, "blockZ1":11264 },
              "needed": [
                { "cellX":-10, "cellZ":10, "ring":10,
                  "regionX0":-20, "regionZ0":20, "regionX1":-19, "regionZ1":21,
                  "blockX0":-10240, "blockZ0":10240, "blockX1":-9216, "blockZ1":11264,
                  "isBounty": false },
                { "cellX":7, "cellZ":10, "ring":11,
                  "blockX0":7168, "blockZ0":10240, "blockX1":8192, "blockZ1":11264,
                  "isBounty": true }
              ]
            }
            """;

    @Test
    void readsTheDocumentedReply() {
        BountyResponse response = BountyParser.parse(SAMPLE);

        assertEquals("overworld", response.dimension());
        assertEquals(2, response.needed().size());
        BountyRegion first = response.needed().get(0);
        assertEquals(-10, first.cellX());
        assertEquals(10, first.ring());
        assertEquals(-10240, first.blockX0());
        assertEquals(11264, first.blockZ1());
        assertFalse(first.bounty());
        assertTrue(response.needed().get(1).bounty());
        assertEquals(7168, response.bounty().blockX0());
        assertTrue(response.bounty().bounty());
    }

    @Test
    void aRegionsCenterIsTheMiddleOfItsBox() {
        BountyRegion region = BountyParser.parse(SAMPLE).needed().get(0);

        assertEquals(-9728, region.centerX());
        assertEquals(10752, region.centerZ());
    }

    @Test
    void theBountyCellIsMarkedOnceAndFlaggedWhetherOrNotTheListHasIt() {
        List<BountyRegion> listed = BountyParser.parse(SAMPLE).markers();
        assertEquals(2, listed.size());
        assertTrue(listed.get(1).bounty());

        String withoutIt = SAMPLE.replace("\"isBounty\": true", "\"isBounty\": false")
                .replace("\"blockX0\":7168, \"blockZ0\":10240, \"blockX1\":8192, \"blockZ1\":11264,\n", "\"blockX0\":9000, \"blockZ0\":10240, \"blockX1\":10024, \"blockZ1\":11264,\n");
        List<BountyRegion> added = BountyParser.parse(withoutIt).markers();
        assertEquals(3, added.size());
        assertTrue(added.get(0).bounty(), "a bounty cell missing from the list is added, first");
    }

    @Test
    void aReplyWithoutABountyIsFine() {
        BountyResponse response = BountyParser.parse("{\"needed\":[{\"blockX0\":0,\"blockZ0\":0,\"blockX1\":1024,\"blockZ1\":1024}]}");

        assertNull(response.bounty());
        assertEquals("overworld", response.dimension());
        assertEquals(1, response.markers().size());
    }

    @Test
    void entriesItCannotUseAreSkippedNotFatal() {
        String json = """
                {"needed":[
                  {"blockX0":0,"blockZ0":0,"blockX1":1024,"blockZ1":1024},
                  {"blockX0":"a","blockZ0":0,"blockX1":1024,"blockZ1":1024},
                  {"blockX0":10,"blockZ0":0,"blockX1":5,"blockZ1":1024},
                  {"blockX0":999999999,"blockZ0":0,"blockX1":1000000999,"blockZ1":1024},
                  "junk", 7, null,
                  {"blockX0":2048,"blockZ0":0,"blockX1":3072,"blockZ1":1024}
                ]}""";

        assertEquals(2, BountyParser.parse(json).needed().size());
    }

    @Test
    void rejectsRepliesThatAreNotTheList() {
        assertThrows(IllegalArgumentException.class, () -> BountyParser.parse("not json"));
        assertThrows(IllegalArgumentException.class, () -> BountyParser.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> BountyParser.parse("{\"error\":\"nope\"}"));
        assertThrows(IllegalArgumentException.class, () -> BountyParser.parse("{\"needed\":[}"));
        assertThrows(IllegalArgumentException.class, () -> BountyParser.parse("{\"needed\":[]} trailing"));
    }

    @Test
    void neverReturnsMoreThanTheCap() {
        StringBuilder json = new StringBuilder("{\"needed\":[");
        for (int i = 0; i < BountyParser.MAX_REGIONS + 50; i++) {
            json.append(i == 0 ? "" : ",").append("{\"blockX0\":").append(i * 1024).append(",\"blockZ0\":0,\"blockX1\":")
                    .append(i * 1024 + 1024).append(",\"blockZ1\":1024}");
        }
        json.append("]}");

        assertEquals(BountyParser.MAX_REGIONS, BountyParser.parse(json.toString()).needed().size());
    }

    @Test
    void handlesEscapesAndDeepNesting() {
        assertEquals("a\"b\\c\ndé", ((java.util.Map<?, ?>) MiniJson.parse("{\"k\":\"a\\\"b\\\\c\\nd\\u00e9\"}")).get("k"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[".repeat(100) + "]".repeat(100)));
    }
}
