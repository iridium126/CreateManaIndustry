package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.*;
import com.iridium126.createmanaindustry.dimension.cube.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(LevelTicks.class)
public abstract class AllvrLevelTicksMixin<T> implements AllvrTickAccess {
    @Unique private ServerLevel allvr$level;
    @Unique private boolean allvr$fluids;
    @Override public void allvr$bindTicks(ServerLevel level, boolean fluids) { allvr$level = level; allvr$fluids = fluids; }
    @Override public ServerLevel allvr$tickLevel() { return allvr$level; }
    @Unique private AllvrCubeMap allvr$map() { return allvr$level == null || !AllvrDimensions.isAllay(allvr$level) ? null : ((AllvrServerLevelDuck) allvr$level).allvr$getCubeMap(); }
    @Unique private boolean allvr$outside(BlockPos pos) { return allvr$level != null && AllvrDimensions.isAllay(allvr$level) && !AllvrDimensionLimits.isVanillaY(pos.getY()); }
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void allvr$schedule(ScheduledTick<T> tick, CallbackInfo ci) {
        if (!allvr$outside(tick.pos())) return;
        var map = allvr$map();
        if (allvr$fluids) map.scheduleFluidTick(tick.pos(), (Fluid) tick.type(), Math.max(0, tick.triggerTick() - allvr$level.getGameTime()), tick.priority(), tick.subTickOrder());
        else map.scheduleBlockTick(tick.pos(), (Block) tick.type(), Math.max(0, tick.triggerTick() - allvr$level.getGameTime()), tick.priority(), tick.subTickOrder());
        ci.cancel();
    }
    @Inject(method = "hasScheduledTick", at = @At("HEAD"), cancellable = true)
    private void allvr$has(BlockPos pos, T type, CallbackInfoReturnable<Boolean> cir) {
        if (!allvr$outside(pos)) return;
        var cube = allvr$map().peek(pos);
        cir.setReturnValue(cube != null && (allvr$fluids ? cube.fluidTicks().has(pos, (Fluid) type) : cube.blockTicks().has(pos, (Block) type)));
    }
    @Inject(method = "willTickThisTick", at = @At("HEAD"), cancellable = true)
    private void allvr$will(BlockPos pos, T type, CallbackInfoReturnable<Boolean> cir) {
        if (allvr$outside(pos)) cir.setReturnValue(allvr$map().ticking().willTick(pos, type));
    }
    @Inject(method = "clearArea", at = @At("HEAD"))
    private void allvr$clear(BoundingBox area, CallbackInfo ci) {
        if (allvr$map() != null) allvr$map().clearTicks(area, allvr$fluids);
    }
    @Inject(method = "copyAreaFrom", at = @At("HEAD"))
    private void allvr$copy(LevelTicks<T> source, BoundingBox area, Vec3i offset, CallbackInfo ci) {
        if (allvr$level != null && (Object) source instanceof AllvrTickAccess ticks && ticks.allvr$tickLevel() != null && AllvrDimensions.isAllay(ticks.allvr$tickLevel()))
            ((AllvrServerLevelDuck) ticks.allvr$tickLevel()).allvr$getCubeMap().copyTicksTo(allvr$level, area, offset, allvr$fluids);
    }
    @Inject(method = "count", at = @At("RETURN"), cancellable = true)
    private void allvr$count(CallbackInfoReturnable<Integer> cir) {
        if (allvr$map() != null) cir.setReturnValue(cir.getReturnValue() + allvr$map().tickCount(allvr$fluids));
    }

}
