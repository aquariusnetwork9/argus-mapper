package tools.argus.uploader.fabric.mixin.xaero;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates {@code argus-mapper-xaero.mixins.json} on Xaero's World Map actually being installed -
 * blackzone/upload-selection map integration is optional compat, not a hard requirement (unlike
 * the rest of this mod, which works fine from Xaero's on-disk cache alone with no live API
 * dependency on Xaero's mod at all). {@code "required": false} in the mixin config is a second,
 * independent guard for the same thing (matches the double-enforcement habit used everywhere else
 * in this codebase - e.g. {@code CoordLimits}) - if this plugin's gate is ever wrong, the config's
 * own required:false should still stop a missing-target-class failure from being fatal.
 *
 * <p>Untested with an actual absent-Xaero game launch (no way to run the real game client in the
 * environment this was written in) - verify both the with-Xaero and without-Xaero cases in a real
 * client before shipping.
 */
public final class ArgusXaeroMixinPlugin implements IMixinConfigPlugin {

    private static final String XAERO_WORLD_MAP_MOD_ID = "xaeroworldmap";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FabricLoader.getInstance().isModLoaded(XAERO_WORLD_MAP_MOD_ID);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
