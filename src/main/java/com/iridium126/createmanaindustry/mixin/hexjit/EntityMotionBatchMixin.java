package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Defers only the repeated Vec3 allocations made by Hexcasting's exact Add Motion rendered spell. */
@Mixin(Entity.class)
public abstract class EntityMotionBatchMixin {
    @Shadow public boolean hasImpulse;

    @WrapMethod(method = "push(DDD)V")
    private void cmi$batchPush(double x, double y, double z, Operation<Void> original) {
        if (!ServerConfig.hexJitBatchAddMotion) {
            original.call(x, y, z);
            return;
        }
        ExecutionScope scope = ExecutionScope.current();
        if (scope == null || !scope.batchMotionEnabled() || !scope.inAddMotionEffect()) {
            original.call(x, y, z);
            return;
        }
        scope.accumulateMotion((Entity) (Object) this, x, y, z);
        this.hasImpulse = true;
    }

    @WrapMethod(method = "getDeltaMovement()Lnet/minecraft/world/phys/Vec3;")
    private Vec3 cmi$readBatchedMotion(Operation<Vec3> original) {
        if (ServerConfig.hexJitBatchAddMotion) {
            ExecutionScope scope = ExecutionScope.current();
            if (scope != null && scope.batchMotionEnabled()) {
                Vec3 pending = scope.pendingMotion((Entity) (Object) this);
                if (pending != null) return pending;
            }
        }
        return original.call();
    }

    @WrapMethod(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V")
    private void cmi$commitBeforeVelocitySet(Vec3 motion, Operation<Void> original) {
        if (ServerConfig.hexJitBatchAddMotion) {
            ExecutionScope scope = ExecutionScope.current();
            if (scope != null && scope.batchMotionEnabled()) scope.flushMotion((Entity) (Object) this);
        }
        original.call(motion);
    }

    @WrapMethod(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V")
    private void cmi$commitBeforeMove(MoverType type, Vec3 movement, Operation<Void> original) {
        if (ServerConfig.hexJitBatchAddMotion) {
            ExecutionScope scope = ExecutionScope.current();
            if (scope != null && scope.batchMotionEnabled()) scope.flushMotion((Entity) (Object) this);
        }
        original.call(type, movement);
    }
}
