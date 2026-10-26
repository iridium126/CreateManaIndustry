package com.iridium126.createmanaindustry.mixin.allaystorm.create;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftTransferContext;
import com.simibubi.create.content.contraptions.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** OrientedContraptionEntity.stopRiding otherwise disassembles the machine even when it is already a root. */
@Mixin(OrientedContraptionEntity.class)
public abstract class RiftOrientedDismountMixin extends AbstractContraptionEntity {
    protected RiftOrientedDismountMixin(EntityType<?> type,Level level){super(type,level);}
    @Inject(method="stopRiding",at=@At("HEAD"),cancellable=true)
    private void cmi$transportDetach(CallbackInfo ci){if(RiftTransferContext.contains(getUUID())){super.stopRiding();ci.cancel();}}
}
