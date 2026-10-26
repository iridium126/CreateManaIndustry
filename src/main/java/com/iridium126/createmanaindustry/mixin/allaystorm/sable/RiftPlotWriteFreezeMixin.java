package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftWorldAccess;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableFreeze;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Level.class)
public abstract class RiftPlotWriteFreezeMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
    private void cmi$write(BlockPos pos,BlockState state,int flags,int recursion,CallbackInfoReturnable<Boolean> ci){if(!RiftWorldAccess.allowed()&&RiftSableFreeze.at((Level)(Object)this,pos))ci.setReturnValue(false);}
    @Inject(method="getBlockEntity",at=@At("HEAD"),cancellable=true)
    private void cmi$inventory(BlockPos pos,CallbackInfoReturnable<BlockEntity> ci){if(!RiftWorldAccess.allowed()&&RiftSableFreeze.at((Level)(Object)this,pos))ci.setReturnValue(null);}
}
