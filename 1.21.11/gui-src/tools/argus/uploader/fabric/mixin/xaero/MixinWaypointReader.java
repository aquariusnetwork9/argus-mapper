package tools.argus.uploader.fabric.mixin.xaero;

import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tools.argus.uploader.core.waystone.Waystone;
import tools.argus.uploader.fabric.Waystones;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;
import xaero.map.mods.gui.WaypointReader;

import java.util.ArrayList;

/**
 * Adds "Teleport to this Waystone (ARGUS)" to the right-click menu Xaero's World Map shows for a
 * waypoint, but only for the waypoints this mod placed for waystones. Kept as its own mixin so a
 * change in Xaero's waypoint menu can only ever cost this one menu item; {@code required: false} in
 * the mixin config already isolates a class that fails to apply.
 */
@Mixin(value = WaypointReader.class, remap = false)
public abstract class MixinWaypointReader {

    @Inject(method = "getRightClickOptions(Lxaero/map/mods/gui/Waypoint;Lxaero/map/gui/IRightClickableElement;)Ljava/util/ArrayList;",
            at = @At("RETURN"), remap = false)
    private void argusMapper$addWaystoneOption(Waypoint waypoint, IRightClickableElement target,
                                                CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        try {
            Waystone waystone = Waystones.waystoneForMarker(waypoint.getOriginal());
            ArrayList<RightClickOption> options = cir.getReturnValue();
            if (waystone == null || options == null) {
                return;
            }
            options.add(new RightClickOption("argus_mapper.gui.world_map.waystone_tp", options.size(), target) {
                @Override
                public void onAction(Screen screen) {
                    Waystones.requestTeleport(waystone, screen);
                }
            });
        } catch (Throwable ignored) {
            // a menu item must never break the map's own waypoint menu
        }
    }
}
