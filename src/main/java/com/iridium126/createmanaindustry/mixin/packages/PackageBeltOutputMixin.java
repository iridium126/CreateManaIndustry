package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageOutputHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BeltInventory.class)
public abstract class PackageBeltOutputMixin {
    @Shadow @Final private BeltBlockEntity belt;
    @WrapOperation(method="eject",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean cmi$output(Level world,Entity item,Operation<Boolean> original,@Local(argsOnly=true) TransportedItemStack stack) {
        int offset=Math.clamp((int)Math.floor(stack.beltPosition),0,belt.beltLength-1);
        var direction=belt.getBeltChainDirection();
        // Inclined belts have both a horizontal and vertical chain component.
        // Their endpoint is the horizontal outlet; do not pick UP/DOWN on a tie.
        var outlet=direction.getX()!=0?(direction.getX()>0?Direction.EAST:Direction.WEST):
                direction.getZ()!=0?(direction.getZ()>0?Direction.SOUTH:Direction.NORTH):
                direction.getY()>0?Direction.UP:Direction.DOWN;
        return original.call(world,PackageOutputHooks.prepare(world,item,BeltHelper.getPositionForOffset(belt,offset),outlet));
    }
}
