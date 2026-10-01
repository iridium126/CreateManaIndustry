package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityManager;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageNativeDownlink;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Per recipient native motion only. Other observers, metadata and gameplay keep Create's
 * original packets. The tracker retains rebase state even after authority runtime shutdown. */
@Mixin(targets="net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class PackageNativeDownlinkMixin {
    @Shadow @Final Entity entity;
    @Unique private PackageNativeDownlink<ServerPlayerConnection> cmi$downlink;

    @WrapOperation(method="broadcast",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerPlayerConnection;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void cmi$motionRecipient(ServerPlayerConnection recipient,Packet<?> packet,Operation<Void> original) {
        if(!(entity instanceof PackageEntity box)){original.call(recipient,packet);return;}
        var kind=PackageNativeDownlink.Kind.OTHER;
        if(packet instanceof ClientboundMoveEntityPacket move&&move.getEntity(entity.level())==entity)
            kind=move.hasPosition()?PackageNativeDownlink.Kind.RELATIVE_POSITION:PackageNativeDownlink.Kind.ROTATION;
        else if(packet instanceof ClientboundTeleportEntityPacket teleport&&teleport.getId()==entity.getId())
            kind=PackageNativeDownlink.Kind.ABSOLUTE_POSITION;
        else if(packet instanceof ClientboundSetEntityMotionPacket motion&&motion.getId()==entity.getId())
            kind=PackageNativeDownlink.Kind.VELOCITY;
        if(kind==PackageNativeDownlink.Kind.OTHER){original.call(recipient,packet);return;}
        boolean owned=PackageAuthorityManager.nativeMotionOwned(box,recipient);
        if(cmi$downlink==null){if(!owned){original.call(recipient,packet);return;}cmi$downlink=new PackageNativeDownlink<>();}
        switch(cmi$downlink.route(recipient,kind,owned)) {
            case SEND -> {
                original.call(recipient,packet);
                if(kind==PackageNativeDownlink.Kind.ABSOLUTE_POSITION)cmi$downlink.rebased(recipient);
            }
            case DROP -> { }
            // ServerEntity advances its shared position codec AFTER this broadcast. Sending
            // the current absolute tracking position aligns this one recipient with that base.
            case REBASE -> {
                original.call(recipient,new ClientboundTeleportEntityPacket(entity));
                cmi$downlink.rebased(recipient);
            }
        }
    }
    @WrapOperation(method={"removePlayer","updatePlayer","broadcastRemoved"},at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerEntity;removePairing(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void cmi$discardRecipient(ServerEntity tracker,ServerPlayer player,Operation<Void> original) {
        if(cmi$downlink!=null)cmi$downlink.unpaired(player.connection);
        try {if(entity instanceof PackageEntity box)PackageAuthorityManager.nativeUnpaired(box,player.connection);}
        finally {original.call(tracker,player);}
    }
}
