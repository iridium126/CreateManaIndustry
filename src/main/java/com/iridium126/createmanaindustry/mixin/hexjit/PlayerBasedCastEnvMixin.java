package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.addldata.ADMediaHolder;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastHexOPMediaPool;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Skips inventory discovery only when HexOP's first-priority pool alone covers this extraction. */
@Mixin(value = PlayerBasedCastEnv.class, remap = false)
public abstract class PlayerBasedCastEnvMixin {
    @WrapMethod(method = "extractMediaFromInventory(JZZ)J")
    private long cmi$personalMediaPoolOnly(long costLeft, boolean allowOvercast, boolean simulate,
                                           Operation<Long> original) throws Throwable {
        CastingEnvironment env = (CastingEnvironment) (Object) this;
        long directRemaining = FastHexOPMediaPool.extractPreparedPersonalPool(env, costLeft, simulate);
        if (directRemaining != Long.MIN_VALUE) {
            FastHexOPMediaPool.finishDirectExtraction(env, costLeft, directRemaining);
            return directRemaining;
        }
        FastHexOPMediaPool.beginExtraction(env, costLeft, simulate);
        long remaining;
        try {
            remaining = original.call(costLeft, allowOvercast, simulate);
        } catch (Throwable failure) {
            FastHexOPMediaPool.endExtractionFailure(env);
            throw failure;
        }
        FastHexOPMediaPool.endExtraction(env, costLeft, simulate, remaining);
        return remaining;
    }

    @WrapOperation(method = "extractMediaFromInventory(JZZ)J", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/utils/MediaHelper;scanPlayerForMediaStuff(" +
                    "Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/List;"))
    private List<ADMediaHolder> cmi$reusePoolOnlyScan(ServerPlayer player,
                                                       Operation<List<ADMediaHolder>> original) {
        List<ADMediaHolder> forced = FastHexOPMediaPool.forcedSources(
                (CastingEnvironment) (Object) this, player);
        if (forced != null) return forced;
        List<ADMediaHolder> scanned = original.call(player);
        if (ServerConfig.hexJitReuseTickMediaScan)
            FastHexOPMediaPool.rememberScannedSources((CastingEnvironment) (Object) this, player, scanned);
        return scanned;
    }
}
