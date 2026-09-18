package tools.argus.uploader.fabric.mixin.xaero;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 1.21.8's copy of 1.21.11's ArgusXaeroMixinPlugin - gates {@code argus-mapper-xaero.mixins.json}
 * on Xaero's World Map actually being installed. No Minecraft-version-specific code at all (pure
 * FabricLoader/Mixin API), so this is an unchanged copy; see the 1.21.11 file's own javadoc for
 * the full rationale.
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
