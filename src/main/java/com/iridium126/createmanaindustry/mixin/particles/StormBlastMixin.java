package com.iridium126.createmanaindustry.mixin.particles;

import com.iridium126.createmanaindustry.content.allaystorm.StormBlast;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
abstract class StormBlastMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void createmanaindustry$beginStormRays(CallbackInfo ci) {
        StormBlast.beginRays((Explosion) (Object) this);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
    private BlockPos createmanaindustry$stormRayCell(double x,double y,double z) {
        return StormBlast.rayCell((Explosion) (Object) this,x,y,z);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState createmanaindustry$stormRayBlock(Level level,BlockPos pos) {
        return StormBlast.rayBlock(level,pos);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState createmanaindustry$stormRayFluid(Level level,BlockPos pos) {
        return StormBlast.rayFluid(level,pos);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getBlockExplosionResistance(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"))
    private Optional<Float> createmanaindustry$stormRayResistance(ExplosionDamageCalculator calculator,Explosion explosion,
                                                                  BlockGetter level,BlockPos pos,BlockState block,FluidState fluid) {
        return StormBlast.rayResistance(calculator,explosion,level,pos,block,fluid);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private boolean createmanaindustry$stormCandidate(Set<BlockPos> blocks,Object value) {
        return StormBlast.candidate(blocks,(BlockPos)value);
    }

}
