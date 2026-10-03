package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageOutputHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.logistics.chute.ChuteBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChuteBlockEntity.class)
public abstract class PackageChuteOutputMixin {
    @WrapOperation(method="handleDownwardOutput",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean cmi$downward(Level world,Entity item,Operation<Boolean> original) {
        return original.call(world,PackageOutputHooks.prepare(world,item,((ChuteBlockEntity)(Object)this).getBlockPos(),Direction.DOWN));
    }
    @WrapOperation(method="handleUpwardOutput",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean cmi$upward(Level world,Entity item,Operation<Boolean> original) {
        return original.call(world,PackageOutputHooks.prepare(world,item,((ChuteBlockEntity)(Object)this).getBlockPos(),Direction.UP));
    }
}
