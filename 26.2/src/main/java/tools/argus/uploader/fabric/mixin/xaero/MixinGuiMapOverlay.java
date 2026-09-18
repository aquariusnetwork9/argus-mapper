package tools.argus.uploader.fabric.mixin.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tools.argus.uploader.fabric.ArgusUploaderClientMod;
import tools.argus.uploader.fabric.RegionOverlayState;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;

/**
 * 26.2's copy of 1.21.11's MixinGuiMapOverlay - colors each visible Xaero region tile by
 * {@link RegionOverlayState}'s category. See that file's own javadoc for the full rationale.
 *
 * <p>Verified via javap against the real installed jars (xaeroworldmap-fabric-26.2-1.46.1.jar and
 * the 26.2 Mojang-mapped client jar): {@code cameraX}/{@code cameraZ}/{@code scale}/
 * {@code screenScale} kept their names on GuiMap. The real change is the render-context type
 * itself - what Yarn (and 1.21.x's port) calls {@code DrawContext} is
 * {@code net.minecraft.client.gui.GuiGraphicsExtractor} under 26.2's Mojang mappings (a genuinely
 * different name than the classic Mojang "GuiGraphics" from earlier versions - checked, not
 * assumed by analogy) - and {@link Minecraft#getWindow()}'s scaled-size accessors are
 * {@code getGuiScaledWidth()}/{@code getGuiScaledHeight()}, not {@code getScaledWidth()}/
 * {@code getScaledHeight()}. {@code fill(int, int, int, int, int)} kept the same signature.
 *
 * <p>Injects into {@code renderPreDropdown}, not {@code render}, for the same reason as 1.21.x's
 * port: Xaero's own method, not vanilla, so it needs no refmap.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinGuiMapOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger("argus-mapper-overlay");

    private static final int BLACKZONE_BORDER = 0xFFFF5555;
    private static final int UPLOADING_FILL = 0x50FFA500;
    private static final int UPLOADED_FILL = 0x4000FF66;
    private static final int BORDER_THICKNESS = 2;
    private static final int REGION_BLOCKS = 512;
    private static final int MAX_REGIONS_PER_AXIS = 128;

    @Shadow
    private MapProcessor mapProcessor;

    @Shadow
    private double cameraX;

    @Shadow
    private double cameraZ;

    @Shadow
    private double scale;

    @Shadow
    private double screenScale;

    @Unique
    private boolean argusMapper$loggedOverlayError;

    @Inject(method = "renderPreDropdown", at = @At("HEAD"), remap = false)
    private void argusMapper$renderRegionOverlay(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        try {
            argusMapper$doRenderOverlay(context);
        } catch (Throwable t) {
            if (!argusMapper$loggedOverlayError) {
                argusMapper$loggedOverlayError = true;
                LOGGER.error("ARGUS map overlay failed to render - disabling it for this session", t);
            }
        }
    }

    @Unique
    private void argusMapper$doRenderOverlay(GuiGraphicsExtractor context) {
        String dimension = argusMapper$currentDimension();
        String layer = ArgusUploaderClientMod.config().layer;
        if (dimension == null || layer == null || layer.isBlank()) {
            return;
        }
        double pixelsPerBlock = scale / screenScale;
        if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0.0) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        double halfW = width / 2.0;
        double halfH = height / 2.0;

        double worldLeft = cameraX - halfW / pixelsPerBlock;
        double worldRight = cameraX + halfW / pixelsPerBlock;
        double worldTop = cameraZ - halfH / pixelsPerBlock;
        double worldBottom = cameraZ + halfH / pixelsPerBlock;
        if (!Double.isFinite(worldLeft) || !Double.isFinite(worldRight)
                || !Double.isFinite(worldTop) || !Double.isFinite(worldBottom)) {
            return;
        }

        int regionMinX = Math.floorDiv((int) Math.floor(worldLeft), REGION_BLOCKS);
        int regionMaxX = Math.floorDiv((int) Math.ceil(worldRight), REGION_BLOCKS);
        int regionMinZ = Math.floorDiv((int) Math.floor(worldTop), REGION_BLOCKS);
        int regionMaxZ = Math.floorDiv((int) Math.ceil(worldBottom), REGION_BLOCKS);
        if (regionMaxX - regionMinX > MAX_REGIONS_PER_AXIS || regionMaxZ - regionMinZ > MAX_REGIONS_PER_AXIS) {
            return;
        }

        for (int regionX = regionMinX; regionX <= regionMaxX; regionX++) {
            for (int regionZ = regionMinZ; regionZ <= regionMaxZ; regionZ++) {
                RegionOverlayState.Category category = RegionOverlayState.classify(dimension, layer, regionX, regionZ);
                if (category == RegionOverlayState.Category.NONE) {
                    continue;
                }
                int screenLeft = (int) Math.round(halfW + (regionX * (long) REGION_BLOCKS - cameraX) * pixelsPerBlock);
                int screenRight = (int) Math.round(halfW + ((regionX + 1) * (long) REGION_BLOCKS - cameraX) * pixelsPerBlock);
                int screenTop = (int) Math.round(halfH + (regionZ * (long) REGION_BLOCKS - cameraZ) * pixelsPerBlock);
                int screenBottom = (int) Math.round(halfH + ((regionZ + 1) * (long) REGION_BLOCKS - cameraZ) * pixelsPerBlock);

                screenLeft = Math.max(0, Math.min(width, screenLeft));
                screenRight = Math.max(0, Math.min(width, screenRight));
                screenTop = Math.max(0, Math.min(height, screenTop));
                screenBottom = Math.max(0, Math.min(height, screenBottom));
                if (screenRight <= screenLeft || screenBottom <= screenTop) {
                    continue;
                }

                switch (category) {
                    case BLACKZONE -> argusMapper$drawBorder(context, screenLeft, screenTop, screenRight, screenBottom, BLACKZONE_BORDER);
                    case UPLOADING -> context.fill(screenLeft, screenTop, screenRight, screenBottom, UPLOADING_FILL);
                    case UPLOADED -> context.fill(screenLeft, screenTop, screenRight, screenBottom, UPLOADED_FILL);
                    case NONE -> {
                        // unreachable - filtered out above
                    }
                }
            }
        }
    }

    @Unique
    private void argusMapper$drawBorder(GuiGraphicsExtractor context, int left, int top, int right, int bottom, int color) {
        int thickness = Math.min(BORDER_THICKNESS, Math.min(right - left, bottom - top));
        if (thickness <= 0) {
            return;
        }
        context.fill(left, top, right, top + thickness, color);
        context.fill(left, bottom - thickness, right, bottom, color);
        context.fill(left, top, left + thickness, bottom, color);
        context.fill(right - thickness, top, right, bottom, color);
    }

    @Unique
    private String argusMapper$currentDimension() {
        if (mapProcessor == null || mapProcessor.getMapWorld() == null) {
            return null;
        }
        ResourceKey<Level> dimId = mapProcessor.getMapWorld().getCurrentDimensionId();
        if (dimId == Level.OVERWORLD) {
            return "overworld";
        }
        if (dimId == Level.NETHER) {
            return "the_nether";
        }
        if (dimId == Level.END) {
            return "theend";
        }
        return null;
    }
}
