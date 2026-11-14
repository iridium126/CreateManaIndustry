package com.iridium126.createmanaindustry.mixin;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitMixinChecks;
import com.iridium126.createmanaindustry.infrastructure.config.ExperimentalStartup;
import java.util.List;
import java.util.Set;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Both manifests use the same side, dependency and startup rules. */
public final class CMIMixinPlugin implements IMixinConfigPlugin {
    private HexJitMixinChecks hexJit;

    @Override
    public void onLoad(String mixinPackage) {
        if (mixinPackage.equals(MixinGroups.ROOT + "hexjit")
                && enabled(MixinGroups.HEX_JIT)) {
            hexJit = new HexJitMixinChecks();
            hexJit.initialize();
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        var group = MixinGroups.of(mixinClassName);
        if (group == MixinGroups.HEX_JIT) {
            return hexJit != null && hexJit.supportsTarget(targetClassName, mixinClassName);
        }
        return group != null && enabled(group);
    }

    private static boolean enabled(MixinGroups group) {
        return group.enabled(FMLLoader.getDist() == Dist.CLIENT, ExperimentalStartup.settings(),
                mod -> FMLLoader.getLoadingModList().getModFileById(mod) != null);
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (hexJit != null) hexJit.preApply(targetClassName, targetClass);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (hexJit != null) hexJit.postApply(mixinClassName);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
}
