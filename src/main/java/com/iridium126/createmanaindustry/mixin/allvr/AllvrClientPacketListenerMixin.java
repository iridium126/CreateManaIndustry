package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

/**
 * Allay terrain is transported as cube payloads.  Vanilla column packets are
 * deliberately rejected at the packet boundary so ClientChunkCache never
 * allocates or publishes an empty LevelChunk for this dimension.
 */
@Mixin(ClientPacketListener.class)
public abstract class AllvrClientPacketListenerMixin {

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"), cancellable = true)
    private void allvr$ignoreVanillaChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        if (self.getLevel() != null && self.getLevel().dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), cancellable = true)
    private void allvr$ignoreVanillaChunkForget(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        if (self.getLevel() != null && self.getLevel().dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }
}
