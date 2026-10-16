package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extend only the block's local count, including its native scheduled release checks. */
@Mixin(BasePressurePlateBlock.class)
public abstract class PackagePressurePlateMixin implements PackagePressurePlateAccess {
    @Invoker("getSignalForState") public abstract int cmi$signal(BlockState state);
    @Invoker("checkPressed") public abstract void cmi$checkPressed(Entity source,Level level,BlockPos pos,BlockState state,int signal);
    @Inject(method="getEntityCount",at=@At("RETURN"),cancellable=true)
    private static void cmi$countRecords(Level level,AABB bounds,Class<? extends Entity> type,CallbackInfoReturnable<Integer> cir) {
        if(level instanceof ServerLevel server&&type.isAssignableFrom(PackageEntity.class))
            cir.setReturnValue(cir.getReturnValueI()+PackageAuthorityManager.queryLight(server,bounds).size());
    }
}
