package com.iridium126.createmanaindustry.client.particles.engine;

import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual NeoForge dispatch ordering and the lifecycle boundary, without bootstrapping the game. */
class ParticleRenderLifecycleContractTest {
    private static final String SCENE="com/iridium126/createmanaindustry/client/render/scene/";
    private static final String ENGINE="com/iridium126/createmanaindustry/client/particles/engine/";
    private static ClassNode read(String name) throws IOException {
        try(var stream=ParticleRenderLifecycleContractTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(stream,name);var node=new ClassNode();new ClassReader(stream).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode type,String name) {
        return type.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
    }
    private static boolean calls(MethodNode method,String owner,String name) {
        for(var insn:method.instructions)if(insn instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))return true;
        return false;
    }
    @Test void irisShadowAccountingIncludesPreparationAndEveryDrawWithoutAddingTheDetailTwice()throws IOException {
        var shadow=method(read("com/iridium126/createmanaindustry/client/particles/shaderpack/CMIPackEntityMergeHook"),"renderShadow");
        int begin=-1,prepare=-1,lastDraw=-1,end=Integer.MAX_VALUE;
        for(int i=0;i<shadow.instructions.size();i++)if(shadow.instructions.get(i) instanceof MethodInsnNode call) {
            if(call.owner.endsWith("/GpuPassTelemetry")&&call.name.equals("begin"))begin=i;
            if(call.owner.endsWith("/ModelShadowGpu")&&call.name.equals("prepare"))prepare=i;
            if(call.owner.equals(ENGINE+"ParticleBuffers")&&call.name.startsWith("drawModel"))lastDraw=i;
            if(call.owner.endsWith("/GpuPassTelemetry")&&call.name.equals("end")) {
                end=Math.min(end,i);assertEquals("(Z)V",call.desc,"Failed shadow samples must be discarded");
            }
        }
        assertTrue(begin>=0&&prepare>begin&&lastDraw>prepare&&end>lastDraw);
    }
    @Test void neoForgeLateStageIsOutsideLevelRendererAndInsideGameRenderer() throws IOException {
        var render=method(read("net/minecraft/client/renderer/GameRenderer"),"renderLevel");
        int world=-1,stage=-1,dispatch=-1;
        for(int i=0;i<render.instructions.size();i++) {
            var insn=render.instructions.get(i);
            if(insn instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/client/renderer/LevelRenderer")&&call.name.equals("renderLevel"))world=i;
            if(insn instanceof FieldInsnNode field&&field.name.equals("AFTER_LEVEL"))stage=i;
            if(stage>=0&&insn instanceof MethodInsnNode call&&call.owner.equals("net/neoforged/neoforge/client/ClientHooks")&&call.name.equals("dispatchRenderStage")){dispatch=i;break;}
        }
        assertTrue(world>=0&&stage>world&&dispatch>stage,"Late material draws run after LevelRenderer returns");
        var mixin=read("com/iridium126/createmanaindustry/mixin/iris/ParticleLevelFrameMixin");
        var scope=method(mixin,"cmi$particleRenderScope");var annotations=new ArrayList<AnnotationNode>();
        if(scope.visibleAnnotations!=null)annotations.addAll(scope.visibleAnnotations);
        if(scope.invisibleAnnotations!=null)annotations.addAll(scope.invisibleAnnotations);
        var wrapper=annotations.stream().filter(a->a.desc.endsWith("/WrapMethod;")).findFirst().orElseThrow();
        assertTrue(wrapper.values.toString().contains("renderLevel(Lnet/minecraft/client/DeltaTracker;)V"));
        assertTrue(calls(scope,SCENE+"ClientGpuScene","enterLevelFrame"));
        assertTrue(calls(scope,SCENE+"ClientGpuScene","leaveLevelFrame"));
        assertTrue(scope.tryCatchBlocks.stream().anyMatch(b->b.type==null),"Scope must close on exceptions too");
        var capture=method(mixin,"cmi$particleLevelFrame");
        assertTrue(calls(capture,SCENE+"ClientGpuScene","captureLevelFrame"));
        assertFalse(calls(capture,SCENE+"ClientGpuScene","leaveLevelFrame"),"Returning from LevelRenderer must not disarm AFTER_LEVEL");
    }
    @Test void everyLateMaterialRebindsItsInputsAndHexSelectsItsOwnProgram() throws IOException {
        var draws=read(SCENE+"ParticleRenderer");
        assertTrue(calls(method(draws,"drawPass"),ENGINE+"ParticleBuffers","bindBillboardDrawInputs"));
        assertTrue(calls(method(draws,"drawModels"),ENGINE+"ParticleBuffers","bindModelDrawInputs"));
        var compute=method(read(ENGINE+"ParticleRuntime$FrameCandidate"),"input");
        assertTrue(calls(compute,SCENE+"ParticleRenderer","beginFrame"));
        assertTrue(calls(compute,"com/iridium126/createmanaindustry/client/packages/render/PackageRuntime","prepare"));
        for(var owner:java.util.Map.of(SCENE+"ParticleRenderer","beginFrame",
                "com/iridium126/createmanaindustry/client/packages/render/PackageRuntime","prepare").entrySet()) {
            boolean cleared=false;
            for(var insn:method(read(owner.getKey()),owner.getValue()).instructions)
                if(insn instanceof FieldInsnNode field&&field.name.startsWith("hook")&&field.getOpcode()==org.objectweb.asm.Opcodes.PUTFIELD) {
                    var value=insn.getPrevious();while(value!=null&&value.getOpcode()<0)value=value.getPrevious();
                    if(value!=null&&value.getOpcode()==org.objectweb.asm.Opcodes.ICONST_0)cleared=true;
                }
            assertTrue(cleared,"Previous arbitration survived an aborted draw: "+owner);
        }
        assertFalse(draws.methods.stream().anyMatch(m->m.name.equals("drawHexPatterns")),"Legacy pool Hex renderer returned");
    }
    @Test void aPublicationCanOnlyFollowPreflightAndAllResourceCommits() throws IOException {
        String coordinator="com/iridium126/createmanaindustry/client/gpu/frame/GpuFrameCoordinator";
        var scene=read(SCENE+"ClientGpuScene");var compute=method(scene,"computeFrame");
        var phases=new ArrayList<String>();int validation=-1,commit=-1,publish=-1,consume=-1;
        for(int i=0;i<compute.instructions.size();i++)if(compute.instructions.get(i) instanceof MethodInsnNode call){
            if(call.owner.equals(coordinator)&&!call.name.equals("abort"))phases.add(call.name);
            if(call.owner.equals(SCENE+"ScenePublicationPolicy")&&call.name.equals("validate"))validation=i;
            if(call.owner.equals(ENGINE+"ParticleRuntime$FrameCandidate")&&call.name.equals("commit"))commit=i;
            if(call.owner.equals(coordinator)&&call.name.equals("publish"))publish=i;
            if(call.owner.equals(ENGINE+"ParticleRuntime$FrameCandidate")&&call.name.equals("consumeInputs"))consume=i;
        }
        assertEquals(java.util.List.of("begin","simulate","generate","cullSort","publish"),phases);
        assertTrue(validation>=0&&commit>validation&&publish>commit&&consume>publish);
        var candidate=read(ENGINE+"ParticleRuntime$FrameCandidate");
        assertTrue(calls(method(candidate,"preparePublication"),ENGINE+"ParticleBuffers","copySnapshot"));
        assertFalse(calls(method(candidate,"preparePublication"),ENGINE+"ParticleBuffers","swap"));
        int swap=-1,population=-1,storm=-1;
        var promotion=method(candidate,"commit");
        for(int i=0;i<promotion.instructions.size();i++)if(promotion.instructions.get(i) instanceof MethodInsnNode call){
            if(call.owner.equals(ENGINE+"ParticleBuffers")&&call.name.equals("swap"))swap=i;
            if(call.owner.equals(ENGINE+"ParticleBuffers")&&call.name.equals("commitPopulation"))population=i;
            if(call.owner.endsWith("/AllayStormRuntime")&&call.name.equals("commitStarfall"))storm=i;
        }
        assertTrue(swap>=0&&population>swap&&storm>population);
        assertFalse(calls(promotion,"com/iridium126/createmanaindustry/client/particles/interaction/ModelInteractionRuntime","commitInputs"));
        assertTrue(calls(method(candidate,"consumeInputs"),"com/iridium126/createmanaindustry/client/particles/interaction/ModelInteractionRuntime","commitInputs"));
        assertTrue(calls(method(scene,"currentFrameReady"),coordinator,"publication"));
        for(String entry:new String[]{"computeFrame","prepareCurrentFrame","close"})
            assertTrue(calls(method(scene,entry),coordinator,"abort"),entry);
    }

    @Test void lateSharedFateHeaderIsUploadedBeforeItsFirstGpuDispatch() throws IOException {
        var generate=method(read("com/iridium126/createmanaindustry/client/greattrick/sharedfate/SharedFateGpu"),"generate");
        int allocation=-1,upload=-1,dispatch=-1;
        for(int i=0;i<generate.instructions.size();i++) {
            if(!(generate.instructions.get(i) instanceof MethodInsnNode call))continue;
            if(call.owner.equals(ENGINE+"ParticleAppendTarget")&&call.name.equals("ensureEmitter"))allocation=i;
            if(call.owner.equals(ENGINE+"ParticleAppendTarget")&&call.name.equals("flushHeaders"))upload=i;
            if(call.owner.equals("org/lwjgl/opengl/GL43")&&call.name.equals("glDispatchCompute")){dispatch=i;break;}
        }
        assertTrue(allocation>=0&&upload>allocation&&dispatch>upload,
                "Shared Fate allocates after the ordinary header upload; its first frame must upload the new header before dispatch");
        assertTrue(calls(method(read(ENGINE+"ParticleRuntime"),"flushHeaders"),ENGINE+"ParticleBuffers","uploadDirtyEmitters"));
        var output=method(read(ENGINE+"ParticleRuntime"),"bind");
        assertTrue(calls(output,ENGINE+"ParticleBuffers","bindCounter"));
        assertTrue(calls(output,ENGINE+"ParticleBuffers","bindParticleWrite"));
    }
    @Test void sharedFateRestoresPhaseInputsBeforePublishingItsDrawWork() throws IOException {
        var candidate=read(ENGINE+"ParticleRuntime$FrameCandidate");
        assertTrue(calls(method(candidate,"generate"),"com/iridium126/createmanaindustry/client/greattrick/sharedfate/SharedFateGpu","generate"));
        var cull=method(candidate,"cullSort");int restore=-1,publication=-1;
        for(int i=0;i<cull.instructions.size();i++)if(cull.instructions.get(i) instanceof MethodInsnNode call){
            if(call.owner.equals(ENGINE+"ParticleBuffers")&&call.name.equals("bindDispatchInputs"))restore=i;
            if(call.owner.equals(ENGINE+"ParticleDispatch")&&call.name.equals("prepare")){publication=i;break;}
        }
        assertTrue(restore>=0&&publication>restore,"Cull publication must restore every binding after generators");
        var binding=method(read(ENGINE+"ParticleBuffers"),"bindDispatchInputs");
        for(String input:new String[]{"bindDispatch","bindIndirect","bindCounter","bindPrevCounter"})
            assertTrue(calls(binding,ENGINE+"ParticleBuffers",input),"Phase publication omitted "+input);
    }
}
