package com.iridium126.createmanaindustry.mixin.packages.sable;

import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRuntime;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Direct Sable 2.0.5 hook: its LevelChunk bridge calls this after applying plot block changes. */
@Mixin(LevelPlot.class)
public abstract class PackageSableCollisionPlotMixin {
    @Shadow @Final private SubLevel subLevel;

    @Inject(method="onBlockChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",at=@At("TAIL"))
    private void cmi$invalidateMovingCollision(BlockPos position,BlockState state,CallbackInfo ci) {
        if(subLevel instanceof ClientSubLevel && subLevel.getLevel() instanceof ClientLevel level)
            PackageCollisionRuntime.movingBlockChanged(level,position);
    }
}
