package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.phys.Vec3;

@Mixin(value = CastingEnvironment.class, remap = false)
public abstract class HexInvertedRangeMixin {
    @Inject(method = "isVecInRange", at = @At("RETURN"), cancellable = true)
    private void cmi$invert(Vec3 pos, CallbackInfoReturnable<Boolean> cir) {
        CastingEnvironment env=(CastingEnvironment)(Object)this;
        if(com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager.crosses(env.getWorld(), env.mishapSprayPos(), pos))cir.setReturnValue(false);
        if (((CastingEnvironment)(Object)this).getCastingEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && CastChains.invertedFor(player.getUUID())) cir.setReturnValue(!cir.getReturnValue());
    }
}
