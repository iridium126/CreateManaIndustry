package com.iridium126.createmanaindustry.mixin.allaystorm.create;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftEntitySnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.entity.Entity;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
// Sable merges an overwrite at 1000. Mixin's injector guard requires a strictly higher priority.
@Mixin(value=AbstractContraptionEntity.class,priority=1100)
public abstract class RiftContraptionSnapshotMixin {
    @WrapOperation(method="saveWithoutId",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;getPassengers()Ljava/util/List;"))
    private List<Entity> cmi$noPassengerMutation(AbstractContraptionEntity entity,Operation<List<Entity>> original){return RiftEntitySnapshot.singleMember(entity)||com.iridium126.createmanaindustry.content.allaystorm.rift.RiftMountPersistence.managed(entity)?List.of():original.call(entity);}
}
