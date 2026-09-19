package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.argus.uploader.core.PauseButtonPlacement.Rect;

class PauseButtonPlacementTest {

    private static final int W = 640;
    private static final int H = 360;

    /** A vanilla-shaped pause stack: full-width rows, a half-width pair, then Disconnect. */
    private static List<Rect> vanillaStack() {
        List<Rect> rows = new ArrayList<>();
        rows.add(new Rect(218, 100, 204, 20));
        rows.add(new Rect(218, 124, 98, 20));
        rows.add(new Rect(324, 124, 98, 20));
        rows.add(new Rect(218, 148, 98, 20));
        rows.add(new Rect(324, 148, 98, 20));
        rows.add(new Rect(218, 172, 204, 20));
        return rows;
    }

    @Test
    void goesOneRowBelowTheStackAlignedToItsFullWidth() {
        Rect spot = PauseButtonPlacement.place(vanillaStack(), W, H);

        assertEquals(new Rect(218, 196, 204, 20), spot);
    }

    @Test
    void anotherModsBottomLeftIconButtonDoesNotHijackThePlacement() {
        List<Rect> widgets = vanillaStack();
        Rect iconInCorner = new Rect(4, H - 24, 20, 20);
        widgets.add(iconInCorner);

        Rect spot = PauseButtonPlacement.place(widgets, W, H);

        assertEquals(new Rect(218, 196, 204, 20), spot, "still under the real stack, at its width - not 20px wide");
        assertTrue(spot.width() > 90);
        assertFalse(spot.x() == iconInCorner.x() && spot.y() == iconInCorner.y(), "never on top of the icon");
    }

    @Test
    void aLowestRowOfTwoHalfWidthButtonsIsSpannedInFull() {
        List<Rect> widgets = new ArrayList<>();
        widgets.add(new Rect(218, 100, 204, 20));
        widgets.add(new Rect(218, 124, 98, 20));
        widgets.add(new Rect(324, 124, 98, 20));

        assertEquals(new Rect(218, 148, 204, 20), PauseButtonPlacement.place(widgets, W, H));
    }

    @Test
    void whenNothingFitsBelowItUsesABottomCornerThatIsFree() {
        List<Rect> tall = new ArrayList<>();
        tall.add(new Rect(218, H - 40, 204, 20));

        Rect spot = PauseButtonPlacement.place(tall, W, H);

        assertEquals(new Rect(4, H - 24, 100, 20), spot);
    }

    @Test
    void skipsACornerAnotherModAlreadyOccupies() {
        List<Rect> widgets = new ArrayList<>();
        widgets.add(new Rect(218, H - 40, 204, 20));
        widgets.add(new Rect(4, H - 24, 20, 20));

        Rect spot = PauseButtonPlacement.place(widgets, W, H);

        assertEquals(new Rect(W - 4 - 100, H - 24, 100, 20), spot);
    }

    @Test
    void neverOverlapsAnythingWhenACornerIsFree() {
        List<Rect> widgets = vanillaStack();
        widgets.add(new Rect(4, H - 24, 20, 20));
        widgets.add(new Rect(W - 24, 4, 20, 20));

        Rect spot = PauseButtonPlacement.place(widgets, W, H);

        for (Rect r : widgets) {
            assertFalse(rectsOverlap(spot, r), "overlaps " + r);
        }
    }

    @Test
    void widgetsAnotherModParkedOffScreenAreIgnored() {
        // ReplayMod hides the buttons it replaces by moving them to (-1000, -1000) and shifts the
        // rest of the stack up 24px, so the layout we see can hold off-screen full-size buttons.
        List<Rect> widgets = new ArrayList<>();
        widgets.add(new Rect(218, 100, 204, 20));
        widgets.add(new Rect(-1000, -1000, 98, 20));
        widgets.add(new Rect(-1000, -1000, 98, 20));
        widgets.add(new Rect(-1000, -1000, 204, 20));
        widgets.add(new Rect(218, 124, 204, 20));

        assertEquals(new Rect(218, 148, 204, 20), PauseButtonPlacement.place(widgets, W, H));
    }

    @Test
    void ifEverythingFullSizeIsOffScreenItStillFindsACorner() {
        List<Rect> widgets = List.of(new Rect(-1000, -1000, 204, 20));

        assertEquals(new Rect(4, H - 24, 100, 20), PauseButtonPlacement.place(widgets, W, H));
    }

    @Test
    void anEmptyScreenGetsTheBottomLeftCorner() {
        assertEquals(new Rect(4, H - 24, 100, 20), PauseButtonPlacement.place(List.of(), W, H));
    }

    @Test
    void whenEveryCornerIsTakenItFallsBackToTopLeft() {
        List<Rect> widgets = new ArrayList<>();
        widgets.add(new Rect(218, H - 40, 204, 20));
        widgets.add(new Rect(0, H - 30, 120, 30));
        widgets.add(new Rect(W - 120, H - 30, 120, 30));
        widgets.add(new Rect(0, 0, 120, 30));
        widgets.add(new Rect(W - 120, 0, 120, 30));

        assertEquals(new Rect(4, 4, 100, 20), PauseButtonPlacement.place(widgets, W, H));
    }

    private static boolean rectsOverlap(Rect a, Rect b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
                && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }
}
