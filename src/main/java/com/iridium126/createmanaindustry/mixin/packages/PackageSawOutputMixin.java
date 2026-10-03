package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageOutputHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SawBlockEntity.class)
public abstract class PackageSawOutputMixin {
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean cmi$output(Level world,Entity item,Operation<Boolean> original) {
        var velocity=item.getDeltaMovement();
        var outlet=Direction.getNearest(velocity.x,0,velocity.z);
        return original.call(world,PackageOutputHooks.prepare(world,item,((SawBlockEntity)(Object)this).getBlockPos(),outlet));
    }
}
