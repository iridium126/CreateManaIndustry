package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate;
import at.petrak.hexcasting.api.utils.TreeList;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Reuses the same immutable tail in FrameEvaluate's emptiness check and continuation construction. */
@Mixin(value = FrameEvaluate.class, remap = false)
public abstract class FrameEvaluateMixin {
    @Unique private TreeList<Iota> cmi$cachedTail;

    @WrapOperation(method = "evaluate", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/utils/TreeList;tail()Lat/petrak/hexcasting/api/utils/TreeList;"))
    private TreeList<Iota> cmi$reuseImmutableTail(TreeList<Iota> list, Operation<TreeList<Iota>> original) {
        if (!ServerConfig.hexJitReuseFrameTail || ServerConfig.hexJitMode != ServerConfig.HexJitMode.AUTO
                || !HexJitRuntime.onServerThread() || !JitCompatibility.frameTailCacheReady()) {
            return original.call(list);
        }
        if (cmi$cachedTail != null) return cmi$cachedTail;
        cmi$cachedTail = original.call(list);
        return cmi$cachedTail;
    }
}
