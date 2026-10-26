package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableFreeze;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SubLevelPhysicsSystem.class)
public abstract class RiftPhysicsFreezeMixin {
    @WrapOperation(method="tickPipelinePhysics",at=@At(value="INVOKE",target="Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;physicsTick(D)V"))
    private void cmi$pin(PhysicsPipeline pipeline,double step,Operation<Void> original){RiftSableFreeze.pin(pipeline);original.call(pipeline,step);RiftSableFreeze.pin(pipeline);}
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Ldev/ryanhcode/sable/api/block/BlockEntitySubLevelActor;sable$tick(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V"))
    private void cmi$actors(BlockEntitySubLevelActor actor,ServerSubLevel sub,Operation<Void> original){if(!RiftSableFreeze.frozen(sub))original.call(actor,sub);}
    @Inject(method="updatePose",at=@At("HEAD"),cancellable=true)
    private void cmi$pose(ServerSubLevel sub,CallbackInfo ci){if(RiftSableFreeze.frozen(sub))ci.cancel();}
}
