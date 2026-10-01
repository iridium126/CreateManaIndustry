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
}
