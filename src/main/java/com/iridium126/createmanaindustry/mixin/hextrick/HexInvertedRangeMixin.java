package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CastingEnvironment.class, remap = false)
public abstract class HexInvertedRangeMixin {
    @Inject(method = "isVecInRange", at = @At("RETURN"), cancellable = true)
    private void cmi$invert(Vec3 pos, CallbackInfoReturnable<Boolean> cir) {
        CastingEnvironment env=(CastingEnvironment)(Object)this;
        if(PartitionManager.crosses(env.getWorld(), env.mishapSprayPos(), pos))cir.setReturnValue(false);
        if (((CastingEnvironment)(Object)this).getCastingEntity() instanceof ServerPlayer player
                && CastChains.invertedFor(player.getUUID())) cir.setReturnValue(!cir.getReturnValue());
    }
}
