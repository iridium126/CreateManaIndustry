package com.iridium126.createmanaindustry.mixin.allaystorm.create;

import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftTransferContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractContraptionEntity.class)
public abstract class RiftContraptionStopMixin {
    @WrapOperation(method="remove",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/contraptions/Contraption;stop(Lnet/minecraft/world/level/Level;)V"))
    private void cmi$keepActors(Contraption contraption,Level level,Operation<Void> original) {
        if(!RiftTransferContext.contains(((AbstractContraptionEntity)(Object)this).getUUID()))original.call(contraption,level);
    }
}
