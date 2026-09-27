package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Reuses enum arrays only inside HexDir's verified rotation helpers; public values() keeps clone semantics. */
@Mixin(value = HexDir.class, remap = false)
public abstract class HexDirMixin {
    @Unique private static volatile HexDir[] cmi$directions;
    @Unique private static volatile HexAngle[] cmi$angles;

    @WrapOperation(method = "rotatedBy", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/math/HexDir;values()[Lat/petrak/hexcasting/api/casting/math/HexDir;"))
    private HexDir[] cmi$reuseDirections(Operation<HexDir[]> original) {
        if (!cmi$enabledForCast()) return original.call();
        HexDir[] values = cmi$directions;
        if (values == null) {
            values = original.call();
            cmi$directions = values;
        }
        return values;
    }

    @WrapOperation(method = "angleFrom", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/math/HexAngle;values()[Lat/petrak/hexcasting/api/casting/math/HexAngle;"))
    private HexAngle[] cmi$reuseAngles(Operation<HexAngle[]> original) {
        if (!cmi$enabledForCast()) return original.call();
        HexAngle[] values = cmi$angles;
        if (values == null) {
            values = original.call();
            cmi$angles = values;
        }
        return values;
    }

    @Unique private static boolean cmi$enabledForCast() {
        return ServerConfig.hexJitFastSpecialHandlerMath
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && HexJitRuntime.onServerThread()
                && JitCompatibility.specialHandlerMathReady();
    }
}
