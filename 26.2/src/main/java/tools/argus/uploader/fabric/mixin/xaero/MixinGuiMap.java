package tools.argus.uploader.fabric.mixin.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.RegionBounds;
import tools.argus.uploader.fabric.ArgusUploaderClientMod;
import tools.argus.uploader.fabric.MapUploadTrigger;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.MapTileSelection;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.io.IOException;
import java.util.ArrayList;

/**
 * 26.2's copy of 1.21.11's MixinGuiMap - adds a "Mark Blackzone" entry to Xaero's World Map
 * right-click menu, covering whatever area is currently drag-selected.
 *
 * <p>Verified via javap against the real jar installed in the 26.2 test instance
 * (xaeroworldmap-fabric-26.2-1.46.1.jar, paired with XaeroPlus). {@code cameraX}/{@code cameraZ}/
 * {@code scale}/{@code screenScale}/{@code mapTileSelection}/{@code mapProcessor}/
 * {@code getRightClickOptions()} kept the exact same names Xaero used on 1.21.x - Xaero's own API
 * is stable across the Yarn/Mojang mapping switch, since Xaero isn't part of Minecraft's own
 * mappings either way. What DID change, because it's real vanilla-touching surface: {@code
 * MapWorld.getCurrentDimensionId()} now returns {@code ResourceKey<Level>} (was {@code
 * RegistryKey<World>}), {@code World.OVERWORLD/NETHER/END} became {@code Level.OVERWORLD/NETHER/
 * END}, and {@code RightClickOption.onAction(Screen)}'s Screen is now {@code
 * net.minecraft.client.gui.screens.Screen} (package pluralized to "screens").
 *
 * <p>{@code remap = false} throughout: Xaero's classes aren't part of Minecraft's own mappings
 * (Mojang's or otherwise), so Mixin must not try to remap references into them. This still
 * applies unchanged in the unobfuscated era - remap was always about vanilla-mapped names, never
 * about the game being obfuscated as such.
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

    @Unique
    private void argusMapper$feedback(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[ARGUS] " + message));
        }
    }
}
