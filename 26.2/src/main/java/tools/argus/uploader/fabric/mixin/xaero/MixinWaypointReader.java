package tools.argus.uploader.fabric.mixin.xaero;

import net.minecraft.client.gui.screens.Screen;
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
 * 26.2's copy of 1.21.11's MixinWaypointReader - adds "Teleport to this Waystone (ARGUS)" to the
 * right-click menu for a waypoint on Xaero's World Map, but only for waypoints this mod placed for
 * waystones (see {@link Waystones#waystoneForMarker}). Verified via javap against the real
 * xaeros-world-map-fabric-26.2-1.46.1.jar: {@code WaypointReader.getRightClickOptions(Waypoint,
 * IRightClickableElement)} returns {@code ArrayList<RightClickOption>}, and {@code Waypoint.getOriginal()}
 * returns {@code Object} - the exact same shape as 1.21.11's copy of this class, since Xaero's own
 * API doesn't change across the Yarn/Mojang mapping switch. The one real vanilla touchpoint,
 * {@code RightClickOption.onAction(Screen)}, already takes {@code net.minecraft.client.gui.screens.Screen}
 * here (confirmed via javap) - the same type {@link Waystones#requestTeleport} takes, so no
 * conversion is needed.
 *
 * <p>{@code remap = false} throughout, same reasoning as {@link MixinGuiMap}: Xaero's classes
 * aren't part of Minecraft's own mappings.
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
