package com.iridium126.createmanaindustry.client.particles.engine;

import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual NeoForge dispatch ordering and the lifecycle boundary, without bootstrapping the game. */
class ParticleRenderLifecycleContractTest {
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
        assertTrue(calls(scope,ENGINE+"CMIParticleEngine","enterLevelFrame"));
        assertTrue(calls(scope,ENGINE+"CMIParticleEngine","leaveLevelFrame"));
        assertTrue(scope.tryCatchBlocks.stream().anyMatch(b->b.type==null),"Scope must close on exceptions too");
        var capture=method(mixin,"cmi$particleLevelFrame");
        assertTrue(calls(capture,ENGINE+"CMIParticleEngine","captureLevelFrame"));
        assertFalse(calls(capture,ENGINE+"CMIParticleEngine","leaveLevelFrame"),"Returning from LevelRenderer must not disarm AFTER_LEVEL");
    }
    @Test void everyLateMaterialRebindsItsInputsAndHexSelectsItsOwnProgram() throws IOException {
        var draws=read(ENGINE+"CMIParticleEngine$DrawPipeline");
        assertTrue(calls(method(draws,"drawPass"),ENGINE+"ParticleBuffers","bindBillboardDrawInputs"));
        assertTrue(calls(method(draws,"drawModels"),ENGINE+"ParticleBuffers","bindModelDrawInputs"));
        var compute=method(read(ENGINE+"CMIParticleEngine"),"runCompute");
        for(String flag:new String[]{"hookModelsDrawn","hookPackagesDrawn"}) {
            boolean cleared=false;
            for(var insn:compute.instructions)if(insn instanceof FieldInsnNode field&&field.name.equals(flag)&&field.getOpcode()==org.objectweb.asm.Opcodes.PUTFIELD) {
                var value=insn.getPrevious();while(value!=null&&value.getOpcode()<0)value=value.getPrevious();
                cleared=value!=null&&value.getOpcode()==org.objectweb.asm.Opcodes.ICONST_0;break;
            }
            assertTrue(cleared,"Previous frame arbitration survived an aborted draw: "+flag);
        }
        assertFalse(draws.methods.stream().anyMatch(m->m.name.equals("drawHexPatterns")),"Legacy pool Hex renderer returned");
    }
    @Test void aPublicationCanOnlyFollowAllResourceCommits() throws IOException {
        String coordinator="com/iridium126/createmanaindustry/client/gpu/frame/GpuFrameCoordinator";
        var compute=method(read(ENGINE+"CMIParticleEngine"),"runCompute");
        var phases=new ArrayList<String>();int swap=-1,population=-1,storm=-1,publish=-1,arm=-1;
        for(int i=0;i<compute.instructions.size();i++){
            var instruction=compute.instructions.get(i);
            if(instruction instanceof MethodInsnNode call){
                if(call.owner.equals(coordinator)){
                    if(!call.name.equals("abort"))phases.add(call.name);
                    if(call.name.equals("publish"))publish=i;
                }
                if(call.owner.equals(ENGINE+"ParticleBuffers")&&call.name.equals("swap"))swap=i;
                if(call.owner.equals(ENGINE+"ParticleBuffers")&&call.name.equals("commitPopulation"))population=i;
                if(call.owner.endsWith("/AllayStormRuntime")&&call.name.equals("commitStarfall"))storm=i;
            }
            if(instruction instanceof FieldInsnNode field&&field.name.equals("frameArmed")&&field.getOpcode()==org.objectweb.asm.Opcodes.PUTFIELD){
                var value=instruction.getPrevious();while(value!=null&&value.getOpcode()<0)value=value.getPrevious();
                if(value!=null&&value.getOpcode()==org.objectweb.asm.Opcodes.ICONST_1)arm=i;
            }
        }
        assertEquals(java.util.List.of("begin","simulate","generate","cullSort","publish"),phases);
        assertTrue(swap>=0&&population>swap&&storm>population&&publish>storm&&arm>publish,
                "A draw must not observe independently promoted resource generations");
        var engine=read(ENGINE+"CMIParticleEngine");
        assertTrue(calls(method(engine,"currentFrameReady"),coordinator,"publication"));
        assertTrue(calls(method(engine,"committedCounterBuffer"),coordinator,"publication"));
        for(String entry:new String[]{"computeFrame","prepareCurrentFrame","close"})
            assertTrue(calls(method(engine,entry),coordinator,"abort"),"Failed/retired frame retained publication: "+entry);
    }

    @Test void lateSharedFateHeaderIsUploadedBeforeItsFirstGpuDispatch() throws IOException {
        var generate=method(read("com/iridium126/createmanaindustry/client/greattrick/sharedfate/SharedFateGpu"),"generate");
        int allocation=-1,upload=-1,dispatch=-1;
        for(int i=0;i<generate.instructions.size();i++) {
            if(!(generate.instructions.get(i) instanceof MethodInsnNode call))continue;
            if(call.owner.equals(ENGINE+"ParticleAppendTarget")&&call.name.equals("emitter"))allocation=i;
            if(call.owner.equals(ENGINE+"ParticleAppendTarget")&&call.name.equals("flushHeaders"))upload=i;
            if(call.owner.equals("org/lwjgl/opengl/GL43")&&call.name.equals("glDispatchCompute")){dispatch=i;break;}
        }
        assertTrue(allocation>=0&&upload>allocation&&dispatch>upload,
                "Shared Fate allocates after the ordinary header upload; its first frame must upload the new header before dispatch");
        assertTrue(calls(method(read(ENGINE+"ParticleAppendTarget"),"flushHeaders"),ENGINE+"ParticleBuffers","uploadDirtyEmitters"));
        var output=method(read(ENGINE+"ParticleAppendTarget"),"bind");
        assertTrue(calls(output,ENGINE+"ParticleBuffers","bindCounter"));
        assertTrue(calls(output,ENGINE+"ParticleBuffers","bindParticleWrite"));
    }
    @Test void sharedFateRestoresPhaseInputsBeforePublishingItsDrawWork() throws IOException {
        var compute=method(read(ENGINE+"CMIParticleEngine"),"runCompute");
        int shared=-1,restore=-1,publication=-1;
        for(int i=0;i<compute.instructions.size();i++) {
            if(!(compute.instructions.get(i) instanceof MethodInsnNode call))continue;
            if(call.owner.equals("com/iridium126/createmanaindustry/client/greattrick/sharedfate/SharedFateGpu")&&call.name.equals("generate"))shared=i;
            if(shared>=0&&call.owner.equals(ENGINE+"ParticleBuffers")&&call.name.equals("bindDispatchInputs"))restore=i;
            if(shared>=0&&call.owner.equals(ENGINE+"ParticleDispatch")&&call.name.equals("prepare")){publication=i;break;}
        }
        assertTrue(shared>=0&&restore>shared&&publication>restore,
                "Publishing with Shared Fate's SSBOs still bound reads wire positions as particle counts and can dispatch zero draw work");
        var binding=method(read(ENGINE+"ParticleBuffers"),"bindDispatchInputs");
        for(String input:new String[]{"bindDispatch","bindIndirect","bindCounter","bindPrevCounter"})
            assertTrue(calls(binding,ENGINE+"ParticleBuffers",input),"Phase publication omitted "+input);
    }
}
