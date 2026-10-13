package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import com.iridium126.createmanaindustry.compat.trickster.*;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CastingEnvironment.class, remap = false)
public abstract class HexInvertedPaymentMixin {
    @WrapOperation(method = "extractMedia", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;extractMediaEnvironment(JZ)J"))
    private long cmi$exchange(CastingEnvironment environment, long cost, boolean simulate, Operation<Long> original) {
        if (!(environment.getCastingEntity() instanceof ServerPlayer player) || !CastChains.invertedFor(player.getUUID()))
            return original.call(environment, cost, simulate);
        return InvertedPayments.spendMana(player, cost, simulate) ? 0 : cost;
    }
}
