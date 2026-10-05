package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedSpiralPatternCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Avoids re-hashing the same immutable pattern for the spiral-pattern set during a hot cast. */
@Mixin(value = PlayerBasedSpiralPatternCastEnv.class, remap = false)
public abstract class SpiralPatternSetMixin {
    @Unique private HexPattern cmi$lastRecordedPattern;

    @WrapOperation(method = "postExecution", at = @At(value = "INVOKE", target =
            "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private boolean cmi$skipRepeatedPatternHash(Set<HexPattern> patterns, Object pattern,
                                                 Operation<Boolean> original) {
        if (pattern == cmi$lastRecordedPattern && pattern instanceof HexPattern
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO && HexJitRuntime.enabled()) {
            return false;
        }
        boolean added = original.call(patterns, pattern);
        if (pattern instanceof HexPattern hexPattern) cmi$lastRecordedPattern = hexPattern;
        return added;
    }

    @WrapMethod(method = "postCast")
    private void cmi$clearPatternCacheAfterCast(CastingImage image, Operation<Void> original) {
        try {
            original.call(image);
        } finally {
            cmi$lastRecordedPattern = null;
        }
    }
}
