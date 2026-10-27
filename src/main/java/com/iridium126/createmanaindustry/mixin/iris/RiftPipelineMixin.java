package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.RiftIrisPipeline;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=IrisRenderingPipeline.class,remap=false)
public abstract class RiftPipelineMixin implements RiftIrisPipeline {
    @Unique private long cmi$riftGeneration;
    @Unique private ProgramSet cmi$programs;
    @Unique private com.iridium126.createmanaindustry.client.render.shaderpack.NativeSkyView cmi$targetSky;
    @Unique private String cmi$skyFailure="";
    @Inject(method="<init>(Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;)V",at=@At("TAIL"))
    private void cmi$skyPrograms(ProgramSet programs,CallbackInfo ci) {
        var session=ShaderPackSession.current();session.verifyRiftPrograms(programs);cmi$riftGeneration=session.generation();cmi$programs=programs;
    }
    @Override public long cmi$riftGeneration(){return cmi$riftGeneration;}
    @Override public com.iridium126.createmanaindustry.client.render.shaderpack.NativeSkyView cmi$targetSky(){return cmi$targetSky;}
    @Override public String cmi$targetSkyReport(){return !cmi$skyFailure.isEmpty()?cmi$skyFailure:cmi$targetSky==null?"awaiting open rift":cmi$targetSky.report();}
    @Inject(method="beginTranslucents",at=@At("HEAD"))
    private void cmi$projection(CallbackInfo ci) {
        var state=net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE;
        com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaProjection.draw(state.getGbufferModelView(),state.getGbufferProjection(),state.getTickDelta());
        var snapshot=com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider.rift();
        if(com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider.dimension()||snapshot==null
                ||snapshot.state()!=com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftSnapshot.OPEN
                ||!ShaderPackSession.current().riftReady()||!cmi$skyFailure.isEmpty())return;
        try {
            if(cmi$targetSky==null)cmi$targetSky=new com.iridium126.createmanaindustry.client.render.shaderpack.NativeSkyView((IrisRenderingPipeline)(Object)this,cmi$programs,ShaderPackSession.current().rift());
            cmi$targetSky.draw(state.getTickDelta());
        } catch(Exception error) {
            if(cmi$targetSky!=null){cmi$targetSky.destroy();cmi$targetSky=null;}
            cmi$skyFailure="native target sky rejected: "+error.getMessage();
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI shaderpack] Target sky candidate rejected",error);
        }
    }
    @Inject(method="destroy",at=@At("HEAD"))
    private void cmi$destroySky(CallbackInfo ci){if(cmi$targetSky!=null){cmi$targetSky.destroy();cmi$targetSky=null;}}
}
