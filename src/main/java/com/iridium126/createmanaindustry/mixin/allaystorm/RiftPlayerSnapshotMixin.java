package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftEntitySnapshot;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftMountPersistence;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.nbt.CompoundTag;
@Mixin(ServerPlayer.class)
public abstract class RiftPlayerSnapshotMixin {
    @WrapOperation(method="addAdditionalSaveData",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;getRootVehicle()Lnet/minecraft/world/entity/Entity;"))
    private Entity cmi$noRecursiveVehicle(ServerPlayer player,Operation<Entity> original){return RiftEntitySnapshot.singleMember(player)||RiftMountPersistence.separate(player)?player:original.call(player);}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void cmi$writeMount(CompoundTag tag,CallbackInfo ci){RiftMountPersistence.write((ServerPlayer)(Object)this,tag);}
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void cmi$readMount(CompoundTag tag,CallbackInfo ci){RiftMountPersistence.read((ServerPlayer)(Object)this,tag);}
}
