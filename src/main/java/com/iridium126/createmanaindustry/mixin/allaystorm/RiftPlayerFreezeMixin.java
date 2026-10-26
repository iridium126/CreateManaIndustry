package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftTransfers;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RiftPlayerFreezeMixin {
    @Shadow public ServerPlayer player;
    @Inject(method={"handleMovePlayer","handleMoveVehicle","handlePlayerAction","handleUseItemOn","handleUseItem","handleInteract","handleContainerClick"},at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",shift=At.Shift.AFTER),cancellable=true)
    private void cmi$locked(CallbackInfo ci){if(StormRiftTransfers.frozen(player.getUUID()))ci.cancel();}
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;doTick()V"))
    private void cmi$playerTick(ServerPlayer player,Operation<Void> original){if(!StormRiftTransfers.frozen(player.getUUID()))original.call(player);}
}
