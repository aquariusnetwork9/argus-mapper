package tools.argus.uploader.fabric.mixin.xaero;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.RegionBounds;
import tools.argus.uploader.fabric.ArgusUploaderClientMod;
import tools.argus.uploader.fabric.BlackzoneRemoval;
import tools.argus.uploader.fabric.MapUploadTrigger;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.MapTileSelection;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.21.4's copy of 1.21.11's MixinGuiMap - adds a "Mark Blackzone" entry to Xaero's World Map
 * right-click menu, covering whatever area is currently drag-selected ({@code mapTileSelection} -
 * Xaero's own built-in rectangle-select tool).
 *
 * <p>Verified against the real 1.40.11 Xaero's World Map jar for Minecraft 1.21.4 (javap against
 * the exact jar installed in ARGUS-Mapper-Test-1.21.4, not guessed or assumed from the 1.21.11
 * port): every field and method this class shadows/injects into - {@code mapTileSelection},
 * {@code mapProcessor}, {@code getRightClickOptions()}, {@code MapTileSelection.getLeft/Top/
 * Right/Bottom()}, {@code RightClickOption}'s constructor, {@code IRightClickableElement},
 * {@code MapProcessor.getMapWorld()}, {@code MapWorld.getCurrentDimensionId()} - has the exact
 * same name and signature in 1.40.11 as in the 1.46.0 jar 1.21.11 targets. Xaero's own API here
 * turned out to be stable across those releases, so this file is otherwise an unchanged copy of
 * 1.21.11's version; see that file's own javadoc for the chunk/region math this class relies on
 * ({@code mapTileSelection} bounds are chunk coordinates, a region is 32x32 chunks).
 *
 * <p>{@code remap = false} throughout: Xaero's classes aren't part of Minecraft's own Yarn
 * mappings, so Mixin must not try to remap references into them.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinGuiMap {

    @Shadow
    private MapTileSelection mapTileSelection;

    @Shadow
    private MapProcessor mapProcessor;

    @Inject(method = "getRightClickOptions", at = @At("RETURN"), remap = false)
    private void argusMapper$addOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        MapTileSelection selection = this.mapTileSelection;
        if (selection == null) {
            return;
        }
        ArrayList<RightClickOption> options = cir.getReturnValue();
        IRightClickableElement self = (IRightClickableElement) (Object) this;

        options.add(new RightClickOption("argus_mapper.gui.world_map.upload_area", options.size(), self) {
            @Override
            public void onAction(Screen screen) {
                argusMapper$uploadSelectedArea(selection, screen);
            }
        });
        options.add(new RightClickOption("argus_mapper.gui.world_map.mark_blackzone", options.size(), self) {
            @Override
            public void onAction(Screen screen) {
                argusMapper$markBlackzone(selection);
            }
        });

        List<BlackZone> blackzonesInSelection = argusMapper$blackzonesIn(selection);
        if (!blackzonesInSelection.isEmpty()) {
            options.add(new RightClickOption("argus_mapper.gui.world_map.remove_blackzone", options.size(), self) {
                @Override
                public void onAction(Screen screen) {
                    BlackzoneRemoval.confirmAndRemove(screen, blackzonesInSelection);
                }
            });
        }
    }

    @Unique
    private List<BlackZone> argusMapper$blackzonesIn(MapTileSelection selection) {
        String dimension = argusMapper$currentDimension();
        String layer = ArgusUploaderClientMod.config().layer;
        if (dimension == null || layer.isBlank()) {
            return List.of();
        }
        RegionBounds bounds = RegionBounds.ofCorners(
                selection.getLeft() >> 5, selection.getTop() >> 5,
                selection.getRight() >> 5, selection.getBottom() >> 5);
        List<BlackZone> overlapping = new ArrayList<>();
        for (BlackZone zone : ArgusUploaderClientMod.blackzoneStore().forServer(dimension, layer)) {
            if (zone.bounds().intersects(bounds)) {
                overlapping.add(zone);
            }
        }
        return overlapping;
    }

    @Unique
    private void argusMapper$uploadSelectedArea(MapTileSelection selection, Screen screen) {
        String dimension = argusMapper$currentDimension();
        if (dimension == null) {
            argusMapper$feedback("Could not determine the current dimension - nothing to upload.");
            return;
        }
        RegionBounds bounds = new RegionBounds(
                selection.getLeft() >> 5, selection.getTop() >> 5,
                selection.getRight() >> 5, selection.getBottom() >> 5);
        MapUploadTrigger.Preview preview = MapUploadTrigger.preview(dimension, bounds);
        MapUploadTrigger.confirmAndUpload(screen, dimension, preview);
    }

    @Unique
    private void argusMapper$markBlackzone(MapTileSelection selection) {
        String dimension = argusMapper$currentDimension();
        if (dimension == null) {
            argusMapper$feedback("Could not determine the current dimension - blackzone not saved.");
            return;
        }
        var config = ArgusUploaderClientMod.config();
        if (config.layer.isBlank()) {
            argusMapper$feedback("Set a server layer first (/argus server use <id>) - a blackzone is per-server.");
            return;
        }
        RegionBounds bounds = new RegionBounds(
                selection.getLeft() >> 5, selection.getTop() >> 5,
                selection.getRight() >> 5, selection.getBottom() >> 5);
        String id = "map-" + System.currentTimeMillis();
        try {
            ArgusUploaderClientMod.blackzoneStore().addOrReplace(new BlackZone(id, dimension, config.layer, bounds, ""));
            argusMapper$feedback("Blackzone '" + id + "' saved - covers region " + bounds.minRegionX() + ".." + bounds.maxRegionX()
                    + ", " + bounds.minRegionZ() + ".." + bounds.maxRegionZ() + " (" + dimension
                    + "). Never uploaded, local only. See /argus blackzone list.");
        } catch (IOException e) {
            argusMapper$feedback("Failed to save blackzone: " + e.getMessage());
        }
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

    @Unique
    private void argusMapper$feedback(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[ARGUS] " + message), false);
        }
    }
}
