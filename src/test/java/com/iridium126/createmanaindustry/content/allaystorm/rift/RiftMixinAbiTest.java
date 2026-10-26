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
    @Test void chunkPreparationDoesNotRequestBlockingFutures()throws Exception {
        for(String name:new String[]{"RiftLandingSearch","RiftGroupLanding","RiftChunkTickets","RiftTransferCoordinator$Operation","RiftRecoveryTask","RiftNativeCheckpoint"}){var search=read("com/iridium126/createmanaindustry/content/allaystorm/rift/"+name);
        assertFalse(search.methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).anyMatch(i->i instanceof MethodInsnNode m&&(m.name.equals("getChunkFuture")||m.name.equals("join")||m.owner.equals("java/util/concurrent/CompletableFuture")&&m.name.equals("get"))),name);}
    }
    @Test void nativeSkyIrisHooksMatchTheResolvedAbi()throws Exception {
        var pipeline=read("net/irisshaders/iris/pipeline/IrisRenderingPipeline");assertEquals(1,pipeline.methods.stream().filter(m->m.name.equals("<init>")&&m.desc.equals("(Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;)V")).count());
        var uniforms=read("net/irisshaders/iris/uniforms/CommonUniforms");assertEquals(1,uniforms.methods.stream().filter(m->m.name.equals("addNonDynamicUniforms")&&m.desc.equals("(Lnet/irisshaders/iris/gl/uniform/UniformHolder;Lnet/irisshaders/iris/shaderpack/IdMap;Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;Lnet/irisshaders/iris/uniforms/FrameUpdateNotifier;)V")).count());
        var pack=read("net/irisshaders/iris/shaderpack/ShaderPack");assertEquals(1,pack.methods.stream().filter(m->m.name.equals("getProgramSet")&&m.desc.equals("(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;")).count());
    }
}
