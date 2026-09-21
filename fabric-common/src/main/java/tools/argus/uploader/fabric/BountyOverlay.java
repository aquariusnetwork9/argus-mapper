package tools.argus.uploader.fabric;

import net.minecraft.client.gui.DrawContext;
import tools.argus.uploader.core.bounty.BountyRegion;

import java.util.List;

/**
 * Draws the bounty boxes on Xaero's World Map: a border and a light wash over each cell, aqua for
 * a wanted cell and gold for today's 2x cell. The map mixins call this with the same world-to-screen
 * numbers they use for the region overlay.
 */
public final class BountyOverlay {

    private static final int BORDER = 0xFF3FD0FF;
    private static final int FILL = 0x243FD0FF;
    private static final int BOUNTY_BORDER = 0xFFFFC83D;
    private static final int BOUNTY_FILL = 0x30FFC83D;

    private BountyOverlay() {
    }

    public static void draw(DrawContext context, double cameraX, double cameraZ, double pixelsPerBlock, int width, int height) {
        List<BountyRegion> boxes = Bounty.boxes();
        if (boxes.isEmpty()) {
            return;
        }
        double halfW = width / 2.0;
        double halfH = height / 2.0;
        for (BountyRegion box : boxes) {
            int left = (int) Math.round(halfW + (box.blockX0() - cameraX) * pixelsPerBlock);
            int right = (int) Math.round(halfW + (box.blockX1() - cameraX) * pixelsPerBlock);
            int top = (int) Math.round(halfH + (box.blockZ0() - cameraZ) * pixelsPerBlock);
            int bottom = (int) Math.round(halfH + (box.blockZ1() - cameraZ) * pixelsPerBlock);
            if (right < 0 || left > width || bottom < 0 || top > height) {
                continue;
            }
            int border = box.bounty() ? BOUNTY_BORDER : BORDER;
            int thickness = Math.max(1, Math.min(box.bounty() ? 4 : 3, Math.min(right - left, bottom - top) / 2));
            rect(context, width, height, left, top, right, bottom, box.bounty() ? BOUNTY_FILL : FILL);
            rect(context, width, height, left, top, right, top + thickness, border);
            rect(context, width, height, left, bottom - thickness, right, bottom, border);
            rect(context, width, height, left, top, left + thickness, bottom, border);
            rect(context, width, height, right - thickness, top, right, bottom, border);
        }
    }

    private static void rect(DrawContext context, int width, int height, int x1, int y1, int x2, int y2, int color) {
        int left = Math.max(0, x1);
        int top = Math.max(0, y1);
        int right = Math.min(width, x2);
        int bottom = Math.min(height, y2);
        if (right > left && bottom > top) {
            context.fill(left, top, right, bottom, color);
        }
    }
}
