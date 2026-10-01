package com.iridium126.createmanaindustry.mixin.packages;

import java.util.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageForceHooks;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AirCurrent.class)
public abstract class PackageAirCurrentMixin {
    @Shadow protected List<Entity> caughtEntities;
    @Unique private final List<Entity> cmi$nativeEntities=new ArrayList<>();
    @Inject(method="tick",at=@At("RETURN"))
    private void cmi$captureForce(CallbackInfo ci){PackageForceHooks.fan((AirCurrent)(Object)this);}
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/kinetics/fan/AirCurrent;tickAffectedEntities(Lnet/minecraft/world/level/Level;)V"))
    private void cmi$forceOnGpu(AirCurrent self,Level world,Operation<Void> original){
        if(!PackageForceHooks.active(world)){original.call(self,world);return;}
        List<Entity> all=caughtEntities;cmi$nativeEntities.clear();boolean gpu=false;
        var probe=PackageForceHooks.fanProbe(self);
        // Segment boundaries are integer offsets in Create. Determine once per current whether
        // per-entity gameplay processing is needed; cap the scan for custom enormous currents.
        boolean processing=self.maxDistance>256;
        if(!processing)for(int offset=0;offset<=Math.ceil(self.maxDistance);offset++)
            if(self.getTypeAt(Math.min(offset,self.maxDistance))!=null){processing=true;break;}
        for(var iterator=all.iterator();iterator.hasNext();){Entity e=iterator.next();
            if(!e.isAlive()||!(probe==null?e.getBoundingBox():probe.bounds(e)).intersects(self.bounds)||AirCurrent.isPlayerCreativeFlying(e)){iterator.remove();continue;}
            if(!(e instanceof PackageEntity box)||!PackageForceHooks.simulated(box)){cmi$nativeEntities.add(e);continue;}
            gpu=true;
            // Processing/health/world callbacks still run through Create's original method.
            // Plain wind avoids its per-package distance, clamp and vector allocations entirely.
            if(processing&&self.getTypeAt((float)VecHelper.alignedDistanceToFace(probe==null?e.position():probe.position(e),self.source.getAirCurrentPos(),self.direction))!=null)
                cmi$nativeEntities.add(e);else e.fallDistance=0;
        }
        if(!gpu){cmi$nativeEntities.clear();original.call(self,world);return;}
        caughtEntities=cmi$nativeEntities;
        try{original.call(self,world);}finally{if(caughtEntities==cmi$nativeEntities)caughtEntities=all;cmi$nativeEntities.clear();}
    }
    @WrapOperation(method="tickAffectedEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void cmi$replaceWindVelocity(Entity entity,Vec3 velocity,Operation<Void> original){
        if(!(entity instanceof PackageEntity box)||!PackageForceHooks.simulated(box))original.call(entity,velocity);
    }
}
