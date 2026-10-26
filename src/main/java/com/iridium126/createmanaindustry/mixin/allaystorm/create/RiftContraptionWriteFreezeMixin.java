package com.iridium126.createmanaindustry.mixin.allaystorm.create;
import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AbstractContraptionEntity.class)
public abstract class RiftContraptionWriteFreezeMixin {
    @Inject(method="disassemble",at=@At("HEAD"),cancellable=true)
    private void cmi$locked(CallbackInfo ci){var self=(AbstractContraptionEntity)(Object)this;if(!self.level().isClientSide&&!RiftWorldAccess.allowed()&&StormRiftTransfers.frozen(self.getUUID()))ci.cancel();}
}
