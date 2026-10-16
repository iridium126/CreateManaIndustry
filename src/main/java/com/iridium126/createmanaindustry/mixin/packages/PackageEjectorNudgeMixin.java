package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLightGameplay;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EjectorBlockEntity.class)
public abstract class PackageEjectorNudgeMixin {
    @Inject(method="nudgeEntities",at=@At("TAIL"))
    private void cmi$nudgeRecords(CallbackInfo ci){
        var blockEntity=(BlockEntity)(Object)this;
        if(blockEntity.getLevel() instanceof ServerLevel level)PackageLightGameplay.nudgeEjector(level,blockEntity.getBlockPos());
    }
}
