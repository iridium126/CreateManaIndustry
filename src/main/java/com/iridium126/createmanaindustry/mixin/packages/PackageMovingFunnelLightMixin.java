package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.logistics.funnel.FunnelMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FunnelMovementBehaviour.class)
public abstract class PackageMovingFunnelLightMixin {
    @Inject(method="succ",at=@At("TAIL"))
    private void cmi$lightCollect(MovementContext context,BlockPos pos,CallbackInfo ci){if(!(context.world instanceof ServerLevel level))return;var filter=context.getFilterFromBE();for(var entry:PackageAuthorityManager.queryLight(level,new AABB(pos))){var box=entry.box(level);if(!filter.test(level,box))continue;var remainder=ItemHandlerHelper.insertItemStacked(context.contraption.getStorage().getAllItems(),box.copy(),false);if(remainder.isEmpty())PackageAuthorityManager.consumeLight(level,entry);}}
}
