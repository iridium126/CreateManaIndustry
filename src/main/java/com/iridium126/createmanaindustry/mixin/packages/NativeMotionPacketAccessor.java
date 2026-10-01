package com.iridium126.createmanaindustry.mixin.packages;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Copy exact wire shorts; no reflective access or CPU velocity re-quantization. */
@Mixin(ClientboundSetEntityMotionPacket.class)
public interface NativeMotionPacketAccessor {
    @Accessor("xa") int cmi$rawX();
    @Accessor("ya") int cmi$rawY();
    @Accessor("za") int cmi$rawZ();
}
