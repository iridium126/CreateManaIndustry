package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastHexOPMediaPool;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Omits the overcast advancement lookup only when HexOP's pool covers the complete request. */
@Mixin(value = StaffCastEnv.class, remap = false)
public abstract class StaffCastEnvMixin {
    @WrapOperation(method = "extractMediaEnvironment(JZ)J", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/env/StaffCastEnv;canOvercast()Z"))
    private boolean cmi$skipOvercastCheckWhenCovered(StaffCastEnv env, Operation<Boolean> original,
                                                     @Local(argsOnly = true) long cost) {
        return FastHexOPMediaPool.skipOvercastCheckWhenCovered((CastingEnvironment) env, cost)
                || original.call(env);
    }
}
