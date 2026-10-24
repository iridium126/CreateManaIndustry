package com.iridium126.createmanaindustry.client.particles.shaderpack;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Inspect the resolved Iris jar without bootstrapping Iris or NeoForge.
 * Checks ABI/injection locations; actual mixin application still requires the game. */
class PackageIrisContractTest {
    private static final String IRIS="net/irisshaders/iris/",MIXIN="com/iridium126/createmanaindustry/mixin/iris/";
    private static ClassNode read(String name) throws IOException {
        try(var resource=PackageIrisContractTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(resource,"Missing resolved class: "+name);
            var node=new ClassNode();new ClassReader(resource).accept(node,0);return node;
        }
    }
    private static void field(ClassNode node,String name,String descriptor,boolean immutable) {
        var f=node.fields.stream().filter(v->v.name.equals(name)).findFirst().orElseThrow();
        assertEquals(descriptor,f.desc);assertEquals(immutable,(f.access&Opcodes.ACC_FINAL)!=0);
    }
    @Test void lateWorldEffectsUseThePublishedIrisVeilFramebufferApi() throws IOException {
        var accessor = read("top/leonx/irisveil/accessors/IrisRenderingPipelineAccessor");
        assertTrue(accessor.methods.stream().anyMatch(m -> m.name.equals("irisveil$bindCompatGbufferFramebuffer")
                && m.desc.equals("([I)V")));
        var level = read("net/minecraft/client/renderer/LevelRenderer");
        var render = level.methods.stream().filter(m -> m.name.equals("renderLevel")).findFirst().orElseThrow();
        // Fabulous and normal transparency branches each contain one call; only one branch executes per frame.
        assertEquals(2, java.util.Arrays.stream(render.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals(level.name) && call.name.equals("renderWorldBorder")
                && call.desc.equals("(Lnet/minecraft/client/Camera;)V")).count());
        var effects = read("com/iridium126/createmanaindustry/client/render/shaderpack/IrisWorldEffects");
        assertTrue(effects.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(accessor.name)
                        && call.name.equals("irisveil$bindCompatGbufferFramebuffer") && call.desc.equals("([I)V")));
    }
    @Test void shadowHookRunsAfterTheEntityFrustumHasBeenPrepared() throws IOException {
        var shadow=read(IRIS+"shadows/ShadowRenderer");
        field(shadow,"shouldRenderEntities","Z",true);field(shadow,"shouldRenderBlockEntities","Z",true);
        field(shadow,"entityFrustumHolder","L"+IRIS+"shadows/frustum/FrustumHolder;",false);
        var render=shadow.methods.stream().filter(m->m.name.equals("renderShadows")).findFirst().orElseThrow();
        assertEquals("(L"+IRIS+"mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V",render.desc);
        boolean prepared=false,matched=false;
        for(var instruction:render.instructions) {
            if(instruction instanceof MethodInsnNode call && call.name.equals("prepare")
                    && call.desc.equals("(DDD)V"))prepared=true;
            if(instruction instanceof LdcInsnNode constant && constant.cst.equals("draw entities")) {
                assertTrue(prepared,"Package shadow draw would precede frustum preparation");
                var next=instruction.getNext();while(next!=null && next.getOpcode()<0)next=next.getNext();
                assertInstanceOf(MethodInsnNode.class,next);
                var call=(MethodInsnNode)next;assertEquals("net/minecraft/util/profiling/ProfilerFiller",call.owner);
                assertEquals("popPush",call.name);assertEquals("(Ljava/lang/String;)V",call.desc);matched=true;
            }
        }
        assertTrue(matched,"Missing shadow injection boundary");
    }
    @Test void cullingAccessorsMatchActualFrustumLayouts() throws IOException {
        var advanced=read(IRIS+"shadows/frustum/advanced/AdvancedShadowCullingFrustum");
        field(advanced,"planes","[[F",true);field(advanced,"planeCount","I",false);
        field(advanced,"boxCuller","L"+IRIS+"shadows/frustum/BoxCuller;",true);
        field(read(IRIS+"shadows/frustum/advanced/SafeZoneCullingFrustum"),"distanceCuller","L"+IRIS+"shadows/frustum/BoxCuller;",true);
        field(read(IRIS+"shadows/frustum/fallback/BoxCullingFrustum"),"boxCuller","L"+IRIS+"shadows/frustum/BoxCuller;",true);
        field(read(IRIS+"shadows/frustum/BoxCuller"),"maxDistance","D",true);
    }
    @Test void nativeCompilerInvokersAndLifetimeFieldsMatch() throws IOException {
        var pipeline=read(IRIS+"pipeline/IrisRenderingPipeline");var bridge=read(MIXIN+"PackageIrisPipelineMixin");
        field(pipeline,"loadedShaders","Ljava/util/Set;",true);field(pipeline,"destroyed","Z",false);
        field(pipeline,"shadowRenderTargets","L"+IRIS+"shadows/ShadowRenderTargets;",false);
        for(var method:bridge.methods)if(method.name.startsWith("cmi$createPackage")) {
            String target=method.name.contains("Shadow")?"createShadowShader":"createShader";
            assertTrue(pipeline.methods.stream().anyMatch(m->m.name.equals(target) && m.desc.equals(method.desc)),target+method.desc);
        }
        assertTrue(pipeline.methods.stream().anyMatch(m->m.name.equals("<init>") && m.desc.equals("(L"+IRIS+"shaderpack/programs/ProgramSet;)V")));
        var set=read(IRIS+"shaderpack/programs/ProgramSet");
        assertTrue(set.methods.stream().anyMatch(m->m.name.equals("<init>") && m.desc.equals("(L"+IRIS+"shaderpack/include/AbsolutePackPath;Ljava/util/function/Function;L"+IRIS+"shaderpack/properties/ShaderProperties;L"+IRIS+"shaderpack/ShaderPack;)V")));
    }
    @Test void currentFrameWrapperMatchesMainCallAndPipelinePrecedesShadowCamera() throws IOException {
        var game=read("net/minecraft/client/renderer/GameRenderer");
        var render=game.methods.stream().filter(m->m.name.equals("renderLevel")&&m.desc.equals("(Lnet/minecraft/client/DeltaTracker;)V")).findFirst().orElseThrow();
        String descriptor="(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V";
        int calls=0;for(var instruction:render.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/client/renderer/LevelRenderer")&&call.name.equals("renderLevel")){assertEquals(descriptor,call.desc);calls++;}
        assertEquals(1,calls,"Frame wrapper would match multiple main render calls");
        var pipeline=read(IRIS+"pipeline/IrisRenderingPipeline");var shadows=pipeline.methods.stream().filter(m->m.name.equals("renderShadows")).findFirst().orElseThrow();
        assertEquals("(L"+IRIS+"mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V",shadows.desc);
        assertTrue(java.util.Arrays.stream(shadows.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals(IRIS+"shadows/ShadowRenderer")&&call.name.equals("renderShadows")));
        var callback=read("com/iridium126/createmanaindustry/mixin/irisveil/MixinIrisShadowRenderer");
        assertTrue(callback.methods.stream().anyMatch(m->m.name.equals("createmanaindustry$drawShadowModels")&&m.desc.equals("(L"+IRIS+"mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")));
    }

}
