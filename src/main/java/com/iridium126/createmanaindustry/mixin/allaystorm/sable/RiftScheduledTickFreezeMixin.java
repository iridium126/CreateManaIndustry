package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftWorldAccess;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableFreeze;
import com.iridium126.createmanaindustry.dimension.cube.AllvrTickAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.world.ticks.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelTicks.class)
public abstract class RiftScheduledTickFreezeMixin<T> {
    @WrapOperation(method="sortContainersToTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/ticks/LevelChunkTicks;peek()Lnet/minecraft/world/ticks/ScheduledTick;"))
    private ScheduledTick<T> cmi$clock(LevelChunkTicks<T> queue,Operation<ScheduledTick<T>> original){var tick=original.call(queue);var level=((AllvrTickAccess)this).allvr$tickLevel();return level==null?tick:RiftSableFreeze.peek(level,tick);}
    @Inject(method="schedule",at=@At("HEAD"),cancellable=true)
    private void cmi$writes(ScheduledTick<T> tick,CallbackInfo ci){var level=((AllvrTickAccess)this).allvr$tickLevel();if(!RiftWorldAccess.allowed()&&level!=null&&RiftSableFreeze.at(level,tick.pos()))ci.cancel();}
}
