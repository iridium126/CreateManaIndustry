package com.iridium126.createmanaindustry.content.allaystorm.rift;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Checks resolved Minecraft/NeoForge bytecode, separately from the real-client apply result. */
class RiftMixinAbiTest {
    ClassNode read(String name)throws Exception {
        try(var in=getClass().getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(in,name);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    @Test void travelApprovalWrapMatchesOneHookInEachConcreteVanillaImplementation()throws Exception {
        for(String name: new String[]{"net/minecraft/world/entity/Entity","net/minecraft/server/level/ServerPlayer"}) {
            var node=read(name);var method=node.methods.stream().filter(m->m.name.equals("changeDimension")&&m.desc.equals("(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;")).findFirst().orElseThrow();
            assertEquals(1,Arrays.stream(method.instructions.toArray()).filter(i->i instanceof MethodInsnNode m&&m.owner.equals("net/neoforged/neoforge/common/CommonHooks")&&m.name.equals("onTravelToDimension")&&m.desc.equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceKey;)Z")).count());
        }
    }
    @Test void tickFreezeAndSingleMemberSerializationTargetsExist()throws Exception {
        var level=read("net/minecraft/server/level/ServerLevel");
        assertEquals(1,level.methods.stream().filter(m->m.name.equals("tickNonPassenger")&&m.desc.equals("(Lnet/minecraft/world/entity/Entity;)V")).count());
        assertEquals(1,level.methods.stream().filter(m->m.name.equals("tickPassenger")&&m.desc.equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V")).count());
        var entity=read("net/minecraft/world/entity/Entity");var save=entity.methods.stream().filter(m->m.name.equals("saveWithoutId")).findFirst().orElseThrow();
        assertEquals(1,Arrays.stream(save.instructions.toArray()).filter(i->i instanceof MethodInsnNode m&&m.owner.equals(entity.name)&&m.name.equals("isVehicle")&&m.desc.equals("()Z")).count());
    }
    @Test void vanillaSkyAndCloudTargetsAreExact()throws Exception {
        var level=read("net/minecraft/client/renderer/LevelRenderer");
        assertEquals(1,level.methods.stream().filter(m->m.name.equals("renderSky")&&m.desc.equals("(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V")).count());
        assertEquals(1,level.methods.stream().filter(m->m.name.equals("renderClouds")&&m.desc.equals("(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V")).count());
        assertEquals(1,read("net/minecraft/client/renderer/GameRenderer").methods.stream().filter(m->m.name.equals("getRendertypeCloudsShader")&&m.desc.equals("()Lnet/minecraft/client/renderer/ShaderInstance;")).count());
    }
    @Test void independentCloudCacheAccessorsMatchActualVanillaFieldTypes()throws Exception {
        var level=read("net/minecraft/client/renderer/LevelRenderer");
        var accessor=read("com/iridium126/createmanaindustry/mixin/allaystorm/RiftCloudCacheAccess");
        var builder=accessor.methods.stream().filter(m->m.name.equals("cmi$buildClouds")).findFirst().orElseThrow();
        assertEquals(1,level.methods.stream().filter(m->m.name.equals("buildClouds")&&m.desc.equals(builder.desc)).count());
        for(var method:accessor.methods){
            if(!method.name.startsWith("cmi$")||method.name.equals("cmi$buildClouds"))continue;
            String field=method.name.substring(4);if(field.equals("cloudTicks"))field="ticks";
            String type=method.desc.startsWith("()")==true?method.desc.substring(2):method.desc.substring(1,method.desc.indexOf(')'));
            String expectedField=field;
            assertEquals(1,level.fields.stream().filter(f->f.name.equals(expectedField)&&f.desc.equals(type)).count(),field+" ABI");
        }
    }
    @Test void optionalCloudTimingWrapMatchesOneActualNativeStage()throws Exception {
        var nativeStage=read("net/irisshaders/iris/pipeline/CompositeRenderer");
        assertEquals(1,nativeStage.methods.stream().filter(m->m.name.equals("renderAll")&&m.desc.equals("()V")).count());
        var target=read("com/iridium126/createmanaindustry/client/allaystorm/rift/TargetVanillaClouds");
        assertFalse(target.methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).anyMatch(i2->i2 instanceof MethodInsnNode m&&m.owner.contains("sodium")),"target cloud replay must not link optional private Sodium renderer");
    }
    @Test void chunkPreparationDoesNotRequestBlockingFutures()throws Exception {
        for(String name:new String[]{"RiftLandingSearch","RiftGroupLanding","RiftChunkTickets","RiftTransferCoordinator$Operation","RiftRecoveryTask","RiftNativeCheckpoint"}){var search=read("com/iridium126/createmanaindustry/content/allaystorm/rift/"+name);
        assertFalse(search.methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).anyMatch(i->i instanceof MethodInsnNode m&&(m.name.equals("getChunkFuture")||m.name.equals("join")||m.owner.equals("java/util/concurrent/CompletableFuture")&&m.name.equals("get"))),name);}
    }
    @Test void nativeSkyIrisHooksMatchTheResolvedAbi()throws Exception {
        var pipeline=read("net/irisshaders/iris/pipeline/IrisRenderingPipeline");assertEquals(1,pipeline.methods.stream().filter(m->m.name.equals("<init>")&&m.desc.equals("(Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;)V")).count());
        var uniforms=read("net/irisshaders/iris/uniforms/CommonUniforms");assertEquals(1,uniforms.methods.stream().filter(m->m.name.equals("addNonDynamicUniforms")&&m.desc.equals("(Lnet/irisshaders/iris/gl/uniform/UniformHolder;Lnet/irisshaders/iris/shaderpack/IdMap;Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;Lnet/irisshaders/iris/uniforms/FrameUpdateNotifier;)V")).count());
        assertEquals(1,uniforms.methods.stream().filter(m->m.name.equals("addDynamicUniforms")&&m.desc.equals("(Lnet/irisshaders/iris/gl/uniform/DynamicUniformHolder;Lnet/irisshaders/iris/gl/state/FogMode;)V")).count());
        var pack=read("net/irisshaders/iris/shaderpack/ShaderPack");assertEquals(1,pack.methods.stream().filter(m->m.name.equals("getProgramSet")&&m.desc.equals("(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;")).count());
    }
    @Test void targetDomeCannotReuseTheIncomingTerrainShader()throws Exception {
        for(String name:new String[]{"com/iridium126/createmanaindustry/client/render/shaderpack/NativeSkyView","com/iridium126/createmanaindustry/client/allaystorm/rift/VanillaSkyView"}){
            var draw=read(name).methods.stream().filter(m->m.name.equals("draw")).findFirst().orElseThrow();
            int enter=-1,bind=-1,sky=-1,index=0;
            for(var instruction:draw.instructions){
                if(instruction instanceof MethodInsnNode call){
                    if(call.owner.endsWith("SkyRenderScope")&&call.name.equals("enter"))enter=index;
                    if(call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")&&call.name.equals("setShader")&&sky<0)bind=index;
                    if(call.owner.equals("net/minecraft/client/renderer/LevelRenderer")&&call.name.equals("renderSky"))sky=index;
                }
                index++;
            }
            assertTrue(enter>=0&&bind>enter&&sky>bind,"target scope must bind its own shader before vanilla's initial dome: "+name);
        }
    }
    @Test void stageSevenTimeAndNativeViewTargetsMatchInstalledBinaries()throws Exception {
        var server=read("net/minecraft/server/MinecraftServer");
        var create=server.methods.stream().filter(m->m.name.equals("createLevels")).findFirst().orElseThrow();
        assertEquals(2,Arrays.stream(create.instructions.toArray()).filter(i->i instanceof TypeInsnNode t&&t.getOpcode()==org.objectweb.asm.Opcodes.NEW&&t.desc.equals("net/minecraft/server/level/ServerLevel")).count());
        var client=read("net/minecraft/client/multiplayer/ClientLevel");
        assertTrue(client.fields.stream().anyMatch(f->f.name.equals("clientLevelData")&&f.desc.equals("Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;")));
        var source=read("net/irisshaders/iris/shaderpack/programs/ProgramSource");
        assertTrue(source.methods.stream().anyMatch(m->m.name.equals("<init>")&&m.desc.equals("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/properties/ProgramDirectives;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;)V")));
        var pipeline=read("net/irisshaders/iris/pipeline/IrisRenderingPipeline");
        for(String field:new String[]{"renderTargets","customTextureManager","shadowTargetsSupplier","customImages","shaderStorageBufferHolder","flippedAfterPrepare"})assertTrue(pipeline.fields.stream().anyMatch(f->f.name.equals(field)),field);
        for(String method:new String[]{"beginTranslucents","destroy"})assertTrue(pipeline.methods.stream().anyMatch(m->m.name.equals(method)&&m.desc.equals("()V")),method);
        var builder=read("net/irisshaders/iris/gl/program/ProgramBuilder");assertTrue(builder.methods.stream().anyMatch(m->m.name.equals("build")&&m.desc.equals("()Lnet/irisshaders/iris/gl/program/Program;")));
        assertEquals(1,builder.methods.stream().filter(m->m.name.equals("buildShader")&&m.desc.equals("(Lnet/irisshaders/iris/gl/shader/ShaderType;Ljava/lang/String;Ljava/lang/String;)Lnet/irisshaders/iris/gl/shader/GlShader;")&&(m.access&org.objectweb.asm.Opcodes.ACC_STATIC)!=0).count());
    }
}
