package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.*;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Sequential submission sections; object-category labels do not imply exclusive GPU ownership. */
@Mixin(LevelRenderer.class)
public abstract class WorldFrameTimingMixin {
    @Unique private ParticleFrameStages.Scope cmi$section;
    @WrapMethod(method="renderLevel")
    private void cmi$level(DeltaTracker delta,boolean outline,Camera camera,GameRenderer game,LightTexture light,Matrix4f view,Matrix4f projection,Operation<Void> original){
        var saved=cmi$section;cmi$section=null;boolean success=false;
        try{original.call(delta,outline,camera,game,light,view,projection);success=true;}
        finally{if(cmi$section!=null)cmi$section.end(success);cmi$section=saved;}
    }
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
    private void cmi$section(ProfilerFiller profiler,String name,Operation<Void> original){
        if(cmi$section!=null)cmi$section.close();cmi$section=null;
        original.call(profiler,name);
        cmi$section=ParticleFrameStages.begin("world.section."+name);
    }
    @WrapMethod(method="renderSectionLayer")
    private void cmi$terrain(RenderType type,double x,double y,double z,Matrix4f view,Matrix4f projection,Operation<Void> original){
        String layer=type==RenderType.solid()?"solid":type==RenderType.cutoutMipped()?"cutout_mipped":type==RenderType.cutout()?"cutout":type==RenderType.translucent()?"translucent":type==RenderType.tripwire()?"tripwire":"other";
        var scope=ParticleFrameStages.begin("world.terrain."+layer);boolean success=false;
        try{original.call(type,x,y,z,view,projection);success=true;}finally{if(scope!=null)scope.end(success);}
    }
}
