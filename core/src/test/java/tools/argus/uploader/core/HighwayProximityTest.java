package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighwayProximityTest {

    // --- isRoadEligible: PROTOCOL.md §3 policy, applied per-road ---

    @Test
    void axisRoadsAreAlwaysEligibleRegardlessOfRadius() {
        assertTrue(HighwayProximity.isRoadEligible("axis", null, 100_000));
        assertTrue(HighwayProximity.isRoadEligible("axis", 5_000_000, 100_000));
    }

    @Test
    void ringRoadEligibleOnlyWithinNearSpawnRadius() {
        assertTrue(HighwayProximity.isRoadEligible("ring", 5_000, 100_000));
        assertTrue(HighwayProximity.isRoadEligible("ring", 100_000, 100_000));
        assertFalse(HighwayProximity.isRoadEligible("ring", 125_000, 100_000));
        assertFalse(HighwayProximity.isRoadEligible("ring", 3_750_000, 100_000));
    }

    @Test
    void nonAxisRoadWithNullRadiusIsNeverEligible() {
        assertFalse(HighwayProximity.isRoadEligible("grid", null, 100_000));
    }

    // --- segmentIntersectsBox: Liang-Barsky clipping ---

    @Test
    void horizontalSegmentCrossingBoxIntersects() {
        assertTrue(HighwayProximity.segmentIntersectsBox(-1000, 0, 1000, 0, -10, -10, 10, 10));
    }

    @Test
    void verticalSegmentCrossingBoxIntersects() {
        assertTrue(HighwayProximity.segmentIntersectsBox(0, -1000, 0, 1000, -10, -10, 10, 10));
    }

    @Test
    void diagonalSegmentCrossingBoxIntersects() {
        // A diamond-style 45-degree segment from (0,-2500) to (2500,0) - does it cross a small
        // box straddling its midpoint (1250,-1250)?
        assertTrue(HighwayProximity.segmentIntersectsBox(0, -2500, 2500, 0, 1240, -1260, 1260, -1240));
    }

    @Test
    void segmentFarFromBoxDoesNotIntersect() {
        assertFalse(HighwayProximity.segmentIntersectsBox(-1000, 5000, 1000, 5000, -10, -10, 10, 10));
    }

    @Test
    void segmentFullyInsideBoxIntersects() {
        assertTrue(HighwayProximity.segmentIntersectsBox(-1, -1, 1, 1, -100, -100, 100, 100));
    }

    @Test
    void boxFullyInsideSegmentsSpanIntersects() {
        // segment spans way past the box on both ends - box sits astride it
        assertTrue(HighwayProximity.segmentIntersectsBox(-1_000_000, 0, 1_000_000, 0, -5, -5, 5, 5));
    }

    @Test
    void collinearSegmentOutsideBoxRangeDoesNotIntersect() {
        // horizontal segment at z=0, but entirely to the right of the box
        assertFalse(HighwayProximity.segmentIntersectsBox(100, 0, 200, 0, -10, -10, 10, 10));
    }

    @Test
    void segmentTouchingBoxEdgeExactlyIntersects() {
        assertTrue(HighwayProximity.segmentIntersectsBox(10, -5, 10, 5, -10, -10, 10, 10));
    }

    // --- boxNearAnySegment: the actual per-region decision, including tolerance expansion ---

    @Test
    void regionTouchingAxisRoadIsAllowed() {
        // The east-west axis at z=0 (like ARD's real "Cardinals... dug" road), a region box that
        // straddles it.
        List<HighwayProximity.Segment> segs = List.of(new HighwayProximity.Segment(-30_000_000, 0, 30_000_000, 0));
        assertTrue(HighwayProximity.boxNearAnySegment(-54272, -300, -53761, 300, segs, 3.0));
    }

    @Test
    void regionFarFromAnyRoadIsRejected() {
        List<HighwayProximity.Segment> segs = List.of(new HighwayProximity.Segment(-30_000_000, 0, 30_000_000, 0));
        // A region 5000 blocks off the axis - nowhere near tolerance.
        assertFalse(HighwayProximity.boxNearAnySegment(-54272, 4744, -53761, 5255, segs, 3.0));
    }

    @Test
    void toleranceExpandsTheEffectiveHitZoneButOnlyByThatMuch() {
        // Road runs along x=0. A region just barely within tolerance of it should hit; one
        // further out should not.
        List<HighwayProximity.Segment> segs = List.of(new HighwayProximity.Segment(0, -1000, 0, 1000));
        assertTrue(HighwayProximity.boxNearAnySegment(2, 0, 10, 10, segs, 3.0));
        assertFalse(HighwayProximity.boxNearAnySegment(10, 0, 20, 10, segs, 3.0));
    }

    @Test
    void emptySegmentListNeverMatches() {
        assertFalse(HighwayProximity.boxNearAnySegment(-10, -10, 10, 10, List.of(), 3.0));
    }
}
