package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionCollision;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Set;

@Mixin(ServerPlayer.class)
public abstract class PartitionPlayerTeleportMixin {
    @Inject(method="teleportTo(DDD)V",at=@At("HEAD"),cancellable=true)
    private void cmi$localTeleport(double x,double y,double z,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(!PartitionCollision.teleportAllowed((ServerPlayer)(Object)this,new Vec3(x,y,z)))ci.cancel();
    }
    @Inject(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",at=@At("HEAD"),cancellable=true)
    private void cmi$teleport(ServerLevel level,double x,double y,double z,Set<RelativeMovement> relative,float yaw,float pitch,CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player=(ServerPlayer)(Object)this;
        if(level==player.level()&&!PartitionCollision.teleportAllowed(player,new Vec3(x,y,z)))cir.setReturnValue(false);
    }
}
