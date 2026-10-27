package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.google.common.collect.*;
import com.iridium126.createmanaindustry.client.allaystorm.rift.*;
import com.iridium126.createmanaindustry.mixin.iris.NativeSkyPipelineAccess;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.irisshaders.iris.pipeline.*;
import net.irisshaders.iris.pipeline.programs.*;
import net.irisshaders.iris.shaderpack.loading.*;
import net.irisshaders.iris.shaderpack.programs.*;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.*;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.uniforms.*;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import java.io.IOException;
import java.util.*;

/** Native sky-only replay with its own uniform graph, LUTs and histories. No second ClientLevel. */
public final class NativeSkyView {
    private static NativeSkyView active;
    private final String family;
    private final ProgramSet programs;
    private final IrisRenderingPipeline pipeline;
    private final FrameUpdateNotifier notifier=new FrameUpdateNotifier();
    private final Map<ShaderKey,ShaderInstance> skyShaders=new EnumMap<>(ShaderKey.class);
    private final List<CompositeRenderer> renderers=new ArrayList<>();
    private record Clear(int buffer,boolean main,GlFramebuffer framebuffer,boolean integer,boolean unsigned,org.joml.Vector4f color) {}
    private final List<Clear> clears=new ArrayList<>();
    private record History(int buffer,GlFramebuffer source) {}
    private final List<History> histories=new ArrayList<>();
    private final Matrix4f view=new Matrix4f(),projection=new Matrix4f();
    private final Set<Integer> writes=new HashSet<>();
    private final Set<Integer> skyWrites=new HashSet<>();
    private final ImmutableSet<Integer> skyFlips;
    private NativeSkyTargets targets;
    private CustomUniforms uniforms;
    private DepthTexture depth;
    private GlFramebuffer skyFramebuffer;
    private final int output;
    private final ImmutableSet<Integer> flipped;
    private CenterDepthSampler centerDepth;
    private int width,height,depthVersion;
    private boolean initialized,rendered;

    public static ShaderInstance override(ShaderKey key){return active==null?null:active.skyShaders.get(key);}
    public boolean ready(){return rendered;}
    public int texture(){var target=targets.getOrCreate(output);return flipped.contains(output)?target.getAltTexture():target.getMainTexture();}
    public String report(){return "native "+family+" isolated sky; buffers="+writes+"; "+(rendered?"rendered":"prepared");}

    public NativeSkyView(IrisRenderingPipeline pipeline,ProgramSet programs,NativeRiftPatchPlan contract) throws IOException {
        this.programs=programs;this.pipeline=pipeline;family=contract.family();
        var access=(NativeSkyPipelineAccess)pipeline;
        skyFlips=access.cmi$sourceFlips();
        var mc=Minecraft.getInstance();width=mc.getMainRenderTarget().width;height=mc.getMainRenderTarget().height;
        var resolver=new ProgramFallbackResolver(programs);
        var selected=new EnumMap<ProgramArrayId,ProgramSource[]>(ProgramArrayId.class);
        ProgramSource main=null;
        for(var requirement:contract.programs()) {
            var array=ProgramArrayId.valueOf(requirement.array());var originals=programs.getComposite(array);
            var source=originals[requirement.index()];
            if(source==null||!source.isValid())continue;
            if(requirement.required())main=source;
            var sources=selected.computeIfAbsent(array,k->new ProgramSource[originals.length]);
            sources[requirement.index()]=NativeSkySources.isolate(source,family);
            for(int buffer:source.getDirectives().getDrawBuffers())writes.add(buffer);
        }
        if(main==null)throw new IOException("No effective native sky program");
        output=main.getDirectives().getDrawBuffers()[0];
        var keys=List.of(ShaderKey.SKY_BASIC,ShaderKey.SKY_BASIC_COLOR,ShaderKey.SKY_TEXTURED,ShaderKey.SKY_TEXTURED_COLOR);
        for(var key:keys)for(int buffer:resolver.resolve(key.getProgram()).orElseThrow(()->new IOException("Missing native "+key)).getDirectives().getDrawBuffers()){writes.add(buffer);skyWrites.add(buffer);}
        var flipper=new BufferFlipper();
        skyFlips.forEach(flipper::flip);
        try(var state=new SkyRenderState()) {
        depth=new DepthTexture("cmi_target_sky_depth",width,height,DepthBufferFormat.DEPTH24);
        targets=new NativeSkyTargets(width,height,depth.getTextureId(),programs.getPackDirectives(),access.cmi$targets(),access.cmi$sourceFlips(),writes);
        centerDepth=new CenterDepthSampler(targets::getDepthTexture,programs.getPackDirectives().getCenterDepthHalfLife());
        uniforms=programs.getPack().customUniforms.build(holder->CommonUniforms.addNonDynamicUniforms(holder,programs.getPack().getIdMap(),programs.getPackDirectives(),notifier));
        skyFramebuffer=targets.createFramebufferWritingToMain(new int[]{0});
        NativeSkyBuildScope.enter(access.cmi$targets(),targets);
            for(var key:keys) {
                var source=NativeSkySources.isolate(resolver.resolve(key.getProgram()).orElseThrow(),family);
                var framebuffer=targets.createGbufferFramebuffer(skyFlips,source.getDirectives().getDrawBuffers());
                var format=key.getVertexFormat();boolean fullbright=key.shouldIgnoreLightmap();
                var inputs=new ShaderAttributeInputs(format,fullbright,false,false,false,false);
                var shader=ShaderCreator.create(pipeline,"cmi_target_"+key.getName(),source,key.getProgram(),framebuffer,framebuffer,
                        key.getAlphaTest(),format,inputs,notifier,pipeline,()->skyFlips,key.getFogMode(),key.isIntensity(),fullbright,false,false,uniforms);
                skyShaders.put(key,shader);
            }
            for(var array:List.of(ProgramArrayId.Deferred,ProgramArrayId.Composite)) {
                var sources=selected.get(array);if(sources==null)continue;
                var stage=array==ProgramArrayId.Deferred?TextureStage.DEFERRED:TextureStage.COMPOSITE_AND_FINAL;
                renderers.add(new CompositeRenderer(pipeline,array==ProgramArrayId.Deferred?CompositePass.DEFERRED:CompositePass.COMPOSITE,
                        programs.getPackDirectives(),sources,new ComputeSource[0][],targets,null,
                        access.cmi$textures().getNoiseTexture(),notifier,centerDepth,flipper,access.cmi$shadows(),stage,
                        access.cmi$textures().getCustomTextureIdMap().getOrDefault(stage,Object2ObjectMaps.emptyMap()),
                        access.cmi$textures().getIrisCustomTextures(),access.cmi$images(),programs.getPackDirectives().getExplicitFlips(array.getSourcePrefix()+"_pre"),uniforms));
            }
            for(int buffer:writes) {
                var format=targets.getOrCreate(buffer).getInternalFormat();boolean integer=format.getPixelFormat().isInteger(),unsigned=format.name().endsWith("UI");
                var clearColor=programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings().get(buffer).getClearColor().orElseGet(()->buffer==1?new org.joml.Vector4f(1):buffer==0?new org.joml.Vector4f(0,0,0,1):new org.joml.Vector4f());
                clears.add(new Clear(buffer,true,targets.createClearFramebuffer(false,new int[]{buffer}),integer,unsigned,clearColor));
                clears.add(new Clear(buffer,false,targets.createClearFramebuffer(true,new int[]{buffer}),integer,unsigned,clearColor));
            }
            flipped=flipper.snapshot();
            for(int buffer:flipped)if(writes.contains(buffer)&&!programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings().get(buffer).shouldClear())
                histories.add(new History(buffer,targets.createColorFramebuffer(ImmutableSet.of(),new int[]{buffer})));
            uniforms.optimise();
        } catch(RuntimeException|IOException error) {
            destroy();throw error;
        } finally {NativeSkyBuildScope.leave();}
    }

    public void draw(float partial) {
        var mc=Minecraft.getInstance();var snapshot=AllaySkyProvider.rift();
        if(snapshot==null||snapshot.state()!=com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftSnapshot.OPEN)return;
        var captured=CapturedRenderingState.INSTANCE;view.set(captured.getGbufferModelView());projection.set(captured.getGbufferProjection());
        int framebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        var shader=RenderSystem.getShader();
        boolean depthTest=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        var phase=pipeline.getPhase();
        float[] shaderColor=RenderSystem.getShaderColor().clone();
        try(var state=new SkyRenderState()) {
            state.prepare();
            resize();
            {
                for(var clear:clears) {
                    if(initialized&&(!skyWrites.contains(clear.buffer())||clear.main()==skyFlips.contains(clear.buffer())))continue;
                    clear.framebuffer().bind();
                    var c=clear.color();
                    if(clear.unsigned())GL30.glClearBufferuiv(GL11.GL_COLOR,0,new int[]{(int)c.x,(int)c.y,(int)c.z,(int)c.w});
                    else if(clear.integer())GL30.glClearBufferiv(GL11.GL_COLOR,0,new int[]{(int)c.x,(int)c.y,(int)c.z,(int)c.w});
                    else GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{c.x,c.y,c.z,c.w});
                }
                initialized=true;
            }
            skyFramebuffer.bind();RenderSystem.viewport(0,0,width,height);RenderSystem.depthMask(true);GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});
            SkyRenderScope.enter(snapshot.sky(),Math.max(0,mc.level.getGameTime()-snapshot.sky().gameTime()));
            notifier.onNewFrame();uniforms.update();active=this;
            mc.levelRenderer.renderSky(view,projection,partial,mc.gameRenderer.getMainCamera(),false,()->{});
            active=null;
            pipeline.setPhase(phase);
            targets.copyPreHandDepth();targets.copyPreTranslucentDepth();
            for(var renderer:renderers)renderer.renderAll();
            // Iris final-pass semantics: persistent flipped results become next frame's main history.
            for(var history:histories) {
                var target=targets.getOrCreate(history.buffer());history.source().bind();
                RenderSystem.bindTexture(target.getMainTexture());
                GlStateManager._glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,0,0,target.getWidth(),target.getHeight());
            }
            rendered=true;
        } finally {
            active=null;SkyRenderScope.leave();
            pipeline.setPhase(phase);
            net.irisshaders.iris.gl.program.ProgramUniforms.clearActiveUniforms();
            net.irisshaders.iris.gl.program.ProgramSamplers.clearActiveSamplers();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.setShader(()->shader);GlStateManager._glUseProgram(program);RenderSystem.setShaderColor(shaderColor[0],shaderColor[1],shaderColor[2],shaderColor[3]);
            RenderSystem.depthMask(mask);RenderSystem.depthFunc(depthFunction);
            if(depthTest)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
        }
    }
    private void resize() {
        var main=Minecraft.getInstance().getMainRenderTarget();if(width==main.width&&height==main.height)return;
        width=main.width;height=main.height;
        var old=depth;depth=new DepthTexture("cmi_target_sky_depth",width,height,DepthBufferFormat.DEPTH24);
        targets.resizeIfNeeded(++depthVersion,depth.getTextureId(),width,height,DepthBufferFormat.DEPTH24,programs.getPackDirectives());old.destroy();
        renderers.forEach(CompositeRenderer::recalculateSizes);initialized=rendered=false;
    }
    public void destroy(){renderers.forEach(CompositeRenderer::destroy);renderers.clear();skyShaders.values().forEach(ShaderInstance::close);skyShaders.clear();if(centerDepth!=null){centerDepth.destroy();centerDepth=null;}if(targets!=null){targets.destroy();targets=null;}if(depth!=null){depth.destroy();depth=null;}rendered=false;}
}
