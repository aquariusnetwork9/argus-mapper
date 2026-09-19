package tools.argus.uploader.core;

import java.util.List;

/**
 * Where the pause screen's "ARGUS Menu" button goes so it never lands on another mod's widget.
 * Pure geometry, so it's unit-testable without a Minecraft client.
 *
 * <p>Other mods add their own pause-screen widgets - e.g. an icon button in the bottom-left corner -
 * and an earlier version of this took the lowest widget of any size as "the stack". That icon
 * button then decided the new button's x and width (a 20px-wide button, unreadable) and, because
 * nothing fit below it, put it in the same corner on top of the icon.
 */
public final class PauseButtonPlacement {

    public static final int BUTTON_WIDTH = 100;
    public static final int BUTTON_HEIGHT = 20;
    private static final int MARGIN = 4;
    private static final int ROW_GAP = 4;
    // Vanilla's pause buttons are 98 or 204 wide; anything narrower is another mod's icon button and
    // must not decide where the stack ends or how wide this button is.
    private static final int MAIN_COLUMN_MIN_WIDTH = 90;

    private PauseButtonPlacement() {
    }

    public record Rect(int x, int y, int width, int height) {

        int bottom() {
            return y + height;
        }

        boolean intersects(Rect other) {
            return x < other.x + other.width && other.x < x + width
                    && y < other.y + other.height && other.y < y + height;
        }
    }

    public static Rect place(List<Rect> existing, int screenWidth, int screenHeight) {
        int lowestBottom = -1;
        for (Rect r : existing) {
            if (r.width() >= MAIN_COLUMN_MIN_WIDTH) {
                lowestBottom = Math.max(lowestBottom, r.bottom());
            }
        }
        if (lowestBottom >= 0) {
            int left = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            for (Rect r : existing) {
                if (r.width() >= MAIN_COLUMN_MIN_WIDTH && r.bottom() == lowestBottom) {
                    left = Math.min(left, r.x());
                    right = Math.max(right, r.x() + r.width());
                }
            }
            Rect below = new Rect(left, lowestBottom + ROW_GAP, right - left, BUTTON_HEIGHT);
            if (below.bottom() <= screenHeight - MARGIN && isFree(below, existing)) {
                return below;
            }
        }
        Rect bottomLeft = new Rect(MARGIN, screenHeight - MARGIN - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT);
        Rect bottomRight = new Rect(screenWidth - MARGIN - BUTTON_WIDTH, bottomLeft.y(), BUTTON_WIDTH, BUTTON_HEIGHT);
        Rect topLeft = new Rect(MARGIN, MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT);
        Rect topRight = new Rect(bottomRight.x(), MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT);
        for (Rect corner : new Rect[]{bottomLeft, bottomRight, topLeft, topRight}) {
            if (isFree(corner, existing)) {
                return corner;
            }
        }
        return topLeft;
    }

    private static boolean isFree(Rect spot, List<Rect> existing) {
        for (Rect r : existing) {
            if (spot.intersects(r)) {
                return false;
            }
        }
        return true;
    }
}
