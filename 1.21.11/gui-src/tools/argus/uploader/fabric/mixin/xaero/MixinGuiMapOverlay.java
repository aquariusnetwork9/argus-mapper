package tools.argus.uploader.fabric.mixin.xaero;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
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
 * Colors each visible Xaero region tile by {@link RegionOverlayState}'s category - red outline
 * for a blackzone, translucent amber fill while it's uploading this run, translucent green once
 * it's uploaded (this run or a past one) - mirroring how mods like NewerNewChunks color chunks by
 * render version. Deliberately a separate {@code @Mixin} class from {@link MixinGuiMap} (see this
 * package's javadoc there) so a bug in this newer, less-proven code can never take the existing
 * right-click blackzone/upload integration down with it - {@code required: false} in
 * argus-mapper-xaero.mixins.json already isolates a failed mixin per-class, but there's no reason
 * to risk a working feature on an unrelated one anyway.
 *
 * <p>The world-to-screen transform below ({@code screenX = width/2 + (blockX - cameraX) *
 * (scale/screenScale)}) is not guessed or decompiled - {@code cameraX}/{@code cameraZ}/
 * {@code scale}/{@code screenScale} are real fields GuiMap already keeps (confirmed via javap
 * against the actual installed 1.40.16 jar), but the actual pixel math they feed into is inlined
 * directly in GuiMap's own ~6,000-instruction render method with no public helper and no
 * available decompiler in this environment to read it as source. Fitted instead from real
 * (mouseX, mouseY) &lt;-&gt; (mouseBlockPosX, mouseBlockPosZ) sample pairs logged live across three
 * different zoom levels (a now-removed diagnostic build of this same class) and cross-checked
 * against multiple independent samples at each zoom level before being trusted here.
 *
 * <p>Injects into {@code renderPreDropdown}, not {@code render} itself: {@code render} is
 * inherited from vanilla Screen, so on a real launch it's compiled under its Fabric intermediary
 * name (confirmed live: an earlier attempt targeting the literal name "render" compiled fine but
 * failed to apply at runtime with "could not find any targets matching 'render' ... No refMap
 * loaded", since this mixins.json - unlike vanilla-targeting ones - was never given a refmap).
 * {@code renderPreDropdown} is Xaero/lib's own {@code ScreenBase} method, not vanilla, so its
 * literal source name resolves directly with no refmap needed - same pattern as
 * {@code getRightClickOptions} in {@link MixinGuiMap} - and by the time it runs, the map's own
 * tiles have already been drawn (so our fill isn't painted over) but Xaero's right-click dropdown
 * and tooltips have not (so those still render on top of, not under, our overlay).
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinGuiMapOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger("argus-mapper-overlay");

    private static final int BLACKZONE_BORDER = 0xFFFF5555;
    private static final int UPLOADING_FILL = 0x50FFA500;
    private static final int UPLOADED_FILL = 0x4000FF66;
    private static final int BORDER_THICKNESS = 2;
    private static final int REGION_BLOCKS = 512;
    // Defensive cap on how many region tiles a single frame will ever test/draw - protects
    // against a pathological frame hang if the player zooms out far enough that the visible
    // world area would otherwise span an enormous region-coordinate range.
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
    private void argusMapper$renderRegionOverlay(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        try {
            argusMapper$doRenderOverlay(context);
        } catch (Throwable t) {
            // A cosmetic overlay must never take the map screen down with it. Logged once per
            // game session (not every frame) so a real bug is still discoverable, not silent.
            if (!argusMapper$loggedOverlayError) {
                argusMapper$loggedOverlayError = true;
                LOGGER.error("ARGUS map overlay failed to render - disabling it for this session (see MANUAL_TEST_PLAN.md)", t);
            }
        }
    }

    @Unique
    private void argusMapper$doRenderOverlay(DrawContext context) {
        String dimension = argusMapper$currentDimension();
        String layer = ArgusUploaderClientMod.config().layer;
        if (dimension == null || layer == null || layer.isBlank()) {
            return;
        }
        double pixelsPerBlock = scale / screenScale;
        if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0.0) {
            return;
        }
        // Deliberately not @Shadow-ing Screen's own width/height fields here: those are declared
        // on vanilla Screen, not on GuiMap itself, so a remap=false shadow through a Mixin
        // targeting this unmapped third-party class risks the exact same "literal name doesn't
        // match runtime bytecode" failure worked around above for renderPreDropdown/render - and
        // unlike that method, there's no simple literal fallback since the field isn't even
        // declared on GuiMap's own class file. The window's scaled size is exactly what Screen's
        // width/height are set to (see Screen.resize/init) and is a plain, fully-mapped accessor
        // with no shadow/refmap involved at all.
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
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
    private void argusMapper$drawBorder(DrawContext context, int left, int top, int right, int bottom, int color) {
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
        RegistryKey<World> dimId = mapProcessor.getMapWorld().getCurrentDimensionId();
        if (dimId == World.OVERWORLD) {
            return "overworld";
        }
        if (dimId == World.NETHER) {
            return "the_nether";
        }
        if (dimId == World.END) {
            return "theend";
        }
        return null;
    }
}
