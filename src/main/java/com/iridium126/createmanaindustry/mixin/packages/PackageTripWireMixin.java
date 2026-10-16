package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TripWireBlock.class)
public abstract class PackageTripWireMixin implements PackageTripWireAccess {
    @Invoker("checkPressed") public abstract void cmi$checkPressed(Level level,BlockPos pos);
    @Invoker("updateSource") protected abstract void cmi$updateSource(Level level,BlockPos pos,BlockState state);
    @Inject(method="checkPressed",at=@At("HEAD"),cancellable=true)
    private void cmi$records(Level level,BlockPos pos,CallbackInfo ci){
        if(!(level instanceof ServerLevel server))return;
        var state=level.getBlockState(pos);
        if(PackageAuthorityManager.queryLight(server,state.getShape(level,pos).bounds().move(pos)).isEmpty())return;
        if(!state.getValue(TripWireBlock.POWERED)){
            state=state.setValue(TripWireBlock.POWERED,true);level.setBlock(pos,state,3);cmi$updateSource(level,pos,state);
        }
        level.scheduleTick(pos,(TripWireBlock)(Object)this,10);ci.cancel();
    }
}
