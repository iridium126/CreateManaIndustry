package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionCollision;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla accepts small client collision discrepancies. Enforce analytic faces before that tolerance. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PartitionMovementPacketMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="handleMovePlayer",at=@At("HEAD"),cancellable=true)
    private void cmi$player(ServerboundMovePlayerPacket packet,CallbackInfo ci) {
        PacketUtils.ensureRunningOnSameThread(packet,(ServerGamePacketListenerImpl)(Object)this,player.serverLevel());
        if(player.isPassenger())return;
        Vec3 requested=new Vec3(packet.getX(player.getX()),packet.getY(player.getY()),packet.getZ(player.getZ())).subtract(player.position());
        if(Double.isFinite(requested.lengthSqr())&&PartitionCollision.clip(player,requested,player.getBoundingBox()).subtract(requested).lengthSqr()>1e-14) {
            player.connection.teleport(player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());ci.cancel();
        }
    }
    @Inject(method="handleMoveVehicle",at=@At("HEAD"),cancellable=true)
    private void cmi$vehicle(ServerboundMoveVehiclePacket packet,CallbackInfo ci) {
        PacketUtils.ensureRunningOnSameThread(packet,(ServerGamePacketListenerImpl)(Object)this,player.serverLevel());
        var vehicle=player.getRootVehicle();if(vehicle==player)return;
        Vec3 requested=new Vec3(packet.getX(),packet.getY(),packet.getZ()).subtract(vehicle.position());
        if(Double.isFinite(requested.lengthSqr())&&PartitionCollision.clip(vehicle,requested,vehicle.getBoundingBox()).subtract(requested).lengthSqr()>1e-14) {
            player.connection.send(new ClientboundMoveVehiclePacket(vehicle));ci.cancel();
        }
    }
}
