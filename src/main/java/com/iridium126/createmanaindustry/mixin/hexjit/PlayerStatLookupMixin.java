package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.mod.HexStatistics;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.*;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cache only immutable Stat resolution, preserving every award call, event, and scoreboard update. */
@Mixin(Player.class)
public abstract class PlayerStatLookupMixin {
    @WrapOperation(method = {"awardStat(Lnet/minecraft/resources/ResourceLocation;)V",
            "awardStat(Lnet/minecraft/resources/ResourceLocation;I)V"}, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/stats/StatType;get(Ljava/lang/Object;)Lnet/minecraft/stats/Stat;"))
    private Stat<?> cmi$lookupHexStat(StatType<?> type, Object value, Operation<Stat<?>> original) {
        if (type != Stats.CUSTOM || value != HexStatistics.SPELLS_CAST && value != HexStatistics.MEDIA_USED
                || ServerConfig.hexJitMode != ServerConfig.HexJitMode.AUTO || !HexJitRuntime.onServerThread()
                || !JitCompatibility.statLookupReady()) return original.call(type, value);
        ExecutionScope scope = ExecutionScope.currentOnServerThread();
        if (scope == null || !scope.loopSpecializationEnabled()) return original.call(type, value);
        Stat<?> cached = scope.cachedHexStat(value);
        if (cached != null) return cached;
        Stat<?> stat = original.call(type, value);
        scope.rememberHexStat(value, stat);
        return stat;
    }
}
