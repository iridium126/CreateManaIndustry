package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Entity.class)
public abstract class RiftMountStorageMixin {
    @Inject(method="shouldBeSaved",at=@At("HEAD"),cancellable=true)
    private void cmi$nativeRegion(CallbackInfoReturnable<Boolean> ci){Entity e=(Entity)(Object)this;if(!(e instanceof ServerPlayer)&&!e.isRemoved()&&!e.isPassenger()&&RiftMountPersistence.managed(e))ci.setReturnValue(true);}
    @Inject(method="saveWithoutId",at=@At("RETURN"))
    private void cmi$exactPose(CompoundTag tag,CallbackInfoReturnable<CompoundTag> ci){Entity e=(Entity)(Object)this;if(RiftMountPersistence.managed(e)){ListTag pos=new ListTag();pos.add(DoubleTag.valueOf(e.getX()));pos.add(DoubleTag.valueOf(e.getY()));pos.add(DoubleTag.valueOf(e.getZ()));ci.getReturnValue().put("Pos",pos);}}
}
