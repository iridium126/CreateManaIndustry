package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Standard game-time synchronization stays intact; the target context owns signed solar time. */
@Mixin(ClientPacketListener.class)
public abstract class AllayTimePacketMixin {
    @WrapOperation(method="handleSetTime",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;setDayTime(J)V"))
    private void cmi$solarTime(ClientLevel level,long value,Operation<Void> original) {
        if(!AllvrDimensions.isAllay(level))original.call(level,value);
    }
}
