package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageNativeObserverClient;
import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderOwnership;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RETURN is after PacketUtils.ensureRunningOnSameThread; the networking-thread reschedule
 * throws before reaching it, so no mutable client world is read from a Netty thread. */
@Mixin(ClientPacketListener.class)
public abstract class NativePackagePacketMixin {
    @Inject(method="handleAddEntity",at=@At("RETURN")) private void cmi$add(ClientboundAddEntityPacket p,CallbackInfo ci){PackageNativeObserverClient.added(p);}
    @Inject(method="handleMoveEntity",at=@At("RETURN")) private void cmi$move(ClientboundMoveEntityPacket p,CallbackInfo ci){PackageNativeObserverClient.moved(p);}
    @Inject(method="handleTeleportEntity",at=@At("RETURN")) private void cmi$teleport(ClientboundTeleportEntityPacket p,CallbackInfo ci){PackageRenderOwnership.recovered(p);PackageNativeObserverClient.teleported(p);}
    @Inject(method="handleSetEntityMotion",at=@At("RETURN")) private void cmi$motion(ClientboundSetEntityMotionPacket p,CallbackInfo ci){PackageNativeObserverClient.motion(p);}
    @Inject(method="handleRemoveEntities",at=@At("RETURN")) private void cmi$remove(ClientboundRemoveEntitiesPacket p,CallbackInfo ci){PackageNativeObserverClient.removed(p);}
    @Inject(method="handleSetEntityPassengersPacket",at=@At("RETURN")) private void cmi$vehicle(ClientboundSetPassengersPacket p,CallbackInfo ci){PackageNativeObserverClient.passengers(p);}
}
