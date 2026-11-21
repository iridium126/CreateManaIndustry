package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Check actual resolved dependency bytecode; cannot substitute for applying mixins in a client. */
class ShaderPackIntegrationAbiTest {
    static ClassNode read(String path)throws Exception{try(var in=ShaderPackIntegrationAbiTest.class.getClassLoader().getResourceAsStream(path+".class")){assertNotNull(in,path);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;}}
    @Test void loadingAndRecoveryInjectionTargetsMatchResolvedIris()throws Exception{
        var pack=read("net/irisshaders/iris/shaderpack/ShaderPack");
        assertTrue(pack.methods.stream().anyMatch(m->m.name.equals("<init>")&&m.desc.equals("(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V")));
        var iris=read("net/irisshaders/iris/Iris");assertTrue(iris.methods.stream().anyMatch(m->m.name.equals("loadExternalShaderpack")&&m.desc.equals("(Ljava/lang/String;)Z")));
        assertTrue(iris.methods.stream().anyMatch(m->m.name.equals("createPipeline")&&m.desc.equals("(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;")));
    }
    @Test void baselineWindowIsAfterLevelEventAndBeforeOptionalHandRendering()throws Exception{
        var renderer=read("net/minecraft/client/renderer/GameRenderer");var render=renderer.methods.stream().filter(m->m.name.equals("renderLevel")&&m.desc.equals("(Lnet/minecraft/client/DeltaTracker;)V")).findFirst().orElseThrow();
        int event=-1,hand=-1,index=0;for(var i:render.instructions){if(i instanceof MethodInsnNode m&&m.name.equals("dispatchRenderStage"))event=index;if(i instanceof FieldInsnNode f&&f.name.equals("renderHand"))hand=index;index++;}
        assertTrue(event>=0&&hand>event,"world effects must follow AFTER_LEVEL and run even with the hand disabled");
    }
    @Test void dlssCutPrecedesDispatchAndHistoryRestoreFollowsFinalDraw() throws Exception {
        var composite = read("net/irisshaders/iris/pipeline/CompositeRenderer");
        var renderAll = composite.methods.stream().filter(m -> m.name.equals("renderAll")).findFirst().orElseThrow();
        int lookup = call(renderAll, "com/google/common/collect/ImmutableList", "get", "(I)Ljava/lang/Object;");
        int dispatch = call(renderAll, "net/irisshaders/iris/gl/program/ComputeProgram", "dispatch", "(FF)V");
        assertTrue(lookup < dispatch, "DLSS must switch grids before the selected pass dispatches work");
        var finals = read("net/irisshaders/iris/pipeline/FinalPassRenderer");
        var renderFinal = finals.methods.stream().filter(m -> m.name.equals("renderFinalPass")).findFirst().orElseThrow();
        int draw = call(renderFinal, "net/irisshaders/iris/pathways/FullScreenQuadRenderer", "renderQuad", "()V");
        int restore = call(renderFinal, "net/irisshaders/iris/targets/RenderTargets", "getRenderTargetCount", "()I");
        int swap = call(renderFinal, "com/google/common/collect/ImmutableList", "iterator",
                "()Lcom/google/common/collect/UnmodifiableIterator;");
        assertTrue(draw < restore && restore < swap, "History must return to the low grid before Iris swaps buffers");
        var constructor = finals.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        assertEquals("Lcom/google/common/collect/ImmutableSet;", Type.getArgumentTypes(constructor.desc)[6].getDescriptor());
    }
    private static int call(MethodNode method, String owner, String name, String descriptor) {
        var positions = new java.util.ArrayList<Integer>();
        for (int i = 0; i < method.instructions.size(); i++)
            if (method.instructions.get(i) instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) positions.add(i);
        assertEquals(1, positions.size(), method.name + " " + owner + "." + name);
        return positions.getFirst();
    }
    @Test void sessionConstructorHooksSelectOneOverloadAndAreSafeBeforeInitialization()throws Exception{
        var pack=read("net/irisshaders/iris/shaderpack/ShaderPack");
        var mixin=read("com/iridium126/createmanaindustry/mixin/iris/ShaderPackSessionMixin");
        String selectedConstructor=null;int hooks=0;
        for(var hook:mixin.methods){
            if(hook.visibleAnnotations==null)continue;
            for(var annotation:hook.visibleAnnotations){
                if(!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))continue;
                var selectors=(java.util.List<?>)annotationValue(annotation,"method");
                assertEquals(1,selectors.size());String selector=(String)selectors.getFirst();
                var targets=pack.methods.stream().filter(m->selector.equals(m.name+m.desc)).toList();
                assertEquals(1,targets.size(),"constructor injection must select exactly one actual overload");
                var target=targets.getFirst();assertEquals("<init>",target.name);
                if(selectedConstructor==null)selectedConstructor=selector;else assertEquals(selectedConstructor,selector,"begin/options must wrap the same construction");
                Type[] targetArgs=Type.getArgumentTypes(target.desc),handlerArgs=Type.getArgumentTypes(hook.desc);
                assertEquals(targetArgs.length+1,handlerArgs.length);
                for(int i=0;i<targetArgs.length;i++)assertEquals(targetArgs[i],handlerArgs[i]);
                assertEquals("org.spongepowered.asm.mixin.injection.callback.CallbackInfo",handlerArgs[handlerArgs.length-1].getClassName());
                var points=(java.util.List<?>)annotationValue(annotation,"at");
                for(var point:points)if("HEAD".equals(annotationValue((AnnotationNode)point,"value"))){
                    assertNotEquals(0,hook.access&Opcodes.ACC_STATIC,"constructor HEAD handlers must not access uninitialized this");
                }
                hooks++;
            }
        }
        assertEquals(2,hooks);
    }
    private static Object annotationValue(AnnotationNode annotation,String key){
        for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i)))return annotation.values.get(i+1);
        throw new AssertionError("missing annotation value: "+key);
    }
    @Test void visualFramePublicationFollowsSuccessfulParticleCommit()throws Exception{
        var scene=read("com/iridium126/createmanaindustry/client/render/scene/ClientGpuScene");boolean found=false;
        for(var method:scene.methods){boolean committed=false;for(var i:method.instructions)if(i instanceof MethodInsnNode m){if(m.name.equals("commit"))committed=true;if(m.owner.endsWith("/StormVisualFrame")&&m.name.equals("publish")){assertTrue(committed);found=true;}}}
        assertTrue(found);
    }
    @Test void explicitCameraUsesVec3AndAvoidsVeilGlobalUnbind()throws Exception{
        var scene=read("com/iridium126/createmanaindustry/client/render/scene/ClientGpuScene");var method=scene.methods.stream().filter(m->m.name.equals("applyWorldEffectCamera")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(method.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode m&&m.name.equals("setVector")&&m.desc.equals("(FFF)V")));
        var renderer=read("com/iridium126/createmanaindustry/client/render/shaderpack/WorldEffectRenderer");
        assertFalse(renderer.methods.stream().flatMap(m->java.util.Arrays.stream(m.instructions.toArray())).anyMatch(i->i instanceof MethodInsnNode m&&m.owner.startsWith("foundry/veil/")&&(m.name.equals("bind")||m.name.equals("unbind"))));
    }
    @Test void mistSunDirectionUploadsVec3AndShadowLifetimeTargetsExist()throws Exception{
        var handler=read("com/iridium126/createmanaindustry/client/render/mist/MistClientHandler");
        var uniforms=handler.methods.stream().filter(m->m.name.equals("applyMistUniforms")).findFirst().orElseThrow();
        var setters=java.util.Arrays.stream(uniforms.instructions.toArray()).filter(i->i instanceof MethodInsnNode m&&m.name.equals("setVector")).map(i->((MethodInsnNode)i).desc).toList();
        assertFalse(setters.isEmpty());assertTrue(setters.stream().allMatch("(FFF)V"::equals),"Veil silently ignores vec4 uploads to a vec3 uniform");
        var shadows=read("net/irisshaders/iris/shadows/ShadowRenderer");
        assertTrue(shadows.methods.stream().anyMatch(m->m.name.equals("renderShadows")));
        assertTrue(shadows.methods.stream().anyMatch(m->m.name.equals("destroy")&&m.desc.equals("()V")));
    }
}
