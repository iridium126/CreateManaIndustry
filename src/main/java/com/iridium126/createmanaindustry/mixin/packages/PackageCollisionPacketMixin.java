package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RETURN is after PacketUtils.ensureRunningOnSameThread; the networking-thread reschedule
 * throws before reaching it, so no mutable client world is read from a Netty thread. */
@Mixin(ClientPacketListener.class)
public abstract class PackageCollisionPacketMixin {
    /** BlockEntity update packets can change a modded collision shape while BlockState stays identical.
     * RETURN is after Minecraft's network-thread guard and after the client applied the update tag. */
    @Inject(method="handleBlockEntityData",at=@At("RETURN"))
    private void cmi$blockEntityCollision(ClientboundBlockEntityDataPacket packet,CallbackInfo ci) {
        var level=Minecraft.getInstance().level;
        if(level!=null)PackageCollisionRuntime.blockEntityChanged(level,packet.getPos());
    }
}
