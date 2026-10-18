package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionCollision;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({Entity.class,ServerPlayer.class})
public abstract class PartitionDimensionTeleportMixin {
    @WrapMethod(method="changeDimension")
    private Entity cmi$transition(DimensionTransition transition,Operation<Entity> original) {
        Entity entity=(Entity)(Object)this;
        if(entity.level()==transition.newLevel()&&!PartitionCollision.teleportAllowed(entity,transition.pos()))return null;
        try(var ignored=PartitionCollision.transit()){return original.call(transition);}
    }
}
