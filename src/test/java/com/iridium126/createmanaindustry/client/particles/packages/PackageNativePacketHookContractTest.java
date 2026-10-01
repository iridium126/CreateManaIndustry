package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.google.gson.JsonParser;

/** Inspect resolved target bytecode without initializing Minecraft registries or a client.
 * Does not replace a real game Mixin application/visual test. */
class PackageNativePacketHookContractTest {
    static ClassNode type(String name) throws IOException {
        try(var input=PackageNativePacketHookContractTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(input,name);var node=new ClassNode();new ClassReader(input).accept(node,ClassReader.SKIP_DEBUG);return node;
        }
    }
    @Test void nativeHandlersExistAndThreadGuardPrecedesReturnHooks() throws Exception {
        var listener=type("net/minecraft/client/multiplayer/ClientPacketListener");
        var handlers=Map.of("handleAddEntity","ClientboundAddEntityPacket","handleMoveEntity","ClientboundMoveEntityPacket",
            "handleTeleportEntity","ClientboundTeleportEntityPacket","handleSetEntityMotion","ClientboundSetEntityMotionPacket",
            "handleRemoveEntities","ClientboundRemoveEntitiesPacket","handleSetEntityPassengersPacket","ClientboundSetPassengersPacket",
            "handleBlockEntityData","ClientboundBlockEntityDataPacket");
        for(var entry:handlers.entrySet()) {
            var m=listener.methods.stream().filter(method->method.name.equals(entry.getKey())&&method.desc.equals("(Lnet/minecraft/network/protocol/game/"+entry.getValue()+";)V")).findFirst().orElseThrow();
            boolean guarded=false;int returns=0;
            for(var instruction:m.instructions){if(instruction instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/network/protocol/PacketUtils")&&call.name.equals("ensureRunningOnSameThread"))guarded=true;
                if(instruction.getOpcode()==Opcodes.RETURN){assertTrue(guarded,entry.getKey()+" lacks thread guard before RETURN");returns++;}}
            assertTrue(returns>0,entry.getKey());
        }
        var mixin=type("com/iridium126/createmanaindustry/mixin/packages/NativePackagePacketMixin");
        var invalidate=mixin.methods.stream().filter(method->method.name.equals("cmi$blockEntityCollision")).findFirst().orElseThrow();
        boolean invalidates=false,afterApply=false;
        for(var instruction:invalidate.instructions)if(instruction instanceof MethodInsnNode call) {
            if(call.owner.equals("com/iridium126/createmanaindustry/client/particles/packages/PackageCollisionRuntime")
                    && call.name.equals("blockChanged"))invalidates=true;
        }
        var annotation=invalidate.visibleAnnotations==null?List.<AnnotationNode>of():invalidate.visibleAnnotations;
        afterApply=annotation.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .anyMatch(a->annotationValueContains(a,"method","handleBlockEntityData")&&annotationAt(a,"RETURN"));
        assertTrue(invalidates&&afterApply,"applied block entity data must revoke same-block collision snapshots");
    }
    @Test void rawVelocityAccessorsAndCreateLifecycleMatchResolvedTargets() throws Exception {
        var motion=type("net/minecraft/network/protocol/game/ClientboundSetEntityMotionPacket");
        for(String name:List.of("xa","ya","za"))assertTrue(motion.fields.stream().anyMatch(f->f.name.equals(name)&&f.desc.equals("I")&&(f.access&Opcodes.ACC_FINAL)!=0),name);
        var entity=type("com/simibubi/create/content/logistics/box/PackageEntity");
        assertTrue(entity.methods.stream().anyMatch(m->m.name.equals("readSpawnData")&&m.desc.equals("(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V")));
        assertTrue(entity.methods.stream().anyMatch(m->m.name.equals("setBox")&&m.desc.equals("(Lnet/minecraft/world/item/ItemStack;)V")));
        assertTrue(entity.methods.stream().anyMatch(m->m.name.equals("decreaseInsertionTimer")&&m.desc.equals("(Lnet/minecraft/world/phys/Vec3;)Z")));
    }
    @Test void everyNativeHookIsClientOnlyInActualMixinConfiguration() throws Exception {
        try(var input=getClass().getClassLoader().getResourceAsStream("createmanaindustry.mixins.json")) {
            assertNotNull(input);var json=JsonParser.parseReader(new InputStreamReader(input)).getAsJsonObject();var clients=new HashSet<String>();var shared=new HashSet<String>();
            json.getAsJsonArray("client").forEach(v->clients.add(v.getAsString()));json.getAsJsonArray("mixins").forEach(v->shared.add(v.getAsString()));
            for(String name:List.of("packages.NativePackagePacketMixin","packages.NativeMotionPacketAccessor","packages.NativePackageLifecycleMixin","packages.PackageCollisionChunkMixin")){assertTrue(clients.contains(name));assertFalse(shared.contains(name));}
        }
    }
    @Test void successfulClientBlockEditsImmediatelyRevokeCollisionCoverage() throws Exception {
        var target=type("net/minecraft/world/level/chunk/LevelChunk");
        assertTrue(target.methods.stream().anyMatch(method->method.name.equals("setBlockState")
                &&method.desc.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;")),
                "resolved LevelChunk direct-write target changed");
        var level=type("net/minecraft/world/level/Level");
        var levelSetBlock=level.methods.stream().filter(method->method.name.equals("setBlock")
                &&method.desc.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"))
                .findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(levelSetBlock.instructions.spliterator(),false)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .anyMatch(call->call.owner.equals("net/minecraft/world/level/chunk/LevelChunk")&&call.name.equals("setBlockState")),
                "vanilla Level.setBlock must pass through the shared chunk invalidation hook");
        var mixin=type("com/iridium126/createmanaindustry/mixin/packages/PackageCollisionChunkMixin");
        var hook=mixin.methods.stream().filter(method->method.name.equals("cmi$packageCollisionBlockChanged")).findFirst().orElseThrow();
        var injection=hook.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
        assertTrue(annotationValueContains(injection,"method","setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"));
        assertTrue(annotationAt(injection,"RETURN"));
        boolean checksChangedEdit=false,getsOwningLevel=false,checksClientLevel=false,invalidates=false;
        for(var instruction:hook.instructions)if(instruction instanceof MethodInsnNode call) {
            if(call.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable")&&call.name.equals("getReturnValue"))checksChangedEdit=true;
            if(call.owner.equals("net/minecraft/world/level/chunk/LevelChunk")&&call.name.equals("getLevel"))getsOwningLevel=true;
            if(call.owner.equals("com/iridium126/createmanaindustry/client/particles/packages/PackageCollisionRuntime")&&call.name.equals("blockChanged"))invalidates=true;
        }
        for(var instruction:hook.instructions)if(instruction instanceof TypeInsnNode type&&type.desc.equals("net/minecraft/client/multiplayer/ClientLevel"))checksClientLevel=true;
        assertTrue(checksChangedEdit&&getsOwningLevel&&checksClientLevel&&invalidates,
                "only changed client-side LevelChunk writes should invalidate package collision coverage");

        var allvr=type("com/iridium126/createmanaindustry/mixin/allvr/AllvrClientLevelMixin");
        var cubeHook=allvr.methods.stream().filter(method->method.name.equals("allvr$clientSetBlock")).findFirst().orElseThrow();
        boolean writesCube=false,invalidatesCube=false;
        for(var instruction:cubeHook.instructions)if(instruction instanceof MethodInsnNode call) {
            if(call.owner.equals("com/iridium126/createmanaindustry/client/dimension/AllvrClientCubeCache")&&call.name.equals("setBlock"))writesCube=true;
            if(call.owner.equals("com/iridium126/createmanaindustry/client/particles/packages/PackageCollisionRuntime")&&call.name.equals("blockChanged"))invalidatesCube=true;
        }
        assertTrue(writesCube&&invalidatesCube,"successful Allay cube writes must revoke package collision coverage too");
    }
    static boolean annotationValueContains(AnnotationNode annotation,String key,String expected) {
        if(annotation.values==null)return false;
        for(int i=0;i<annotation.values.size();i+=2)if(annotation.values.get(i).equals(key)) {
            Object value=annotation.values.get(i+1);
            return value instanceof List<?> list?list.contains(expected)
                    :value instanceof String[] enumeration?enumeration.length==2&&expected.equals(enumeration[1]):expected.equals(value);
        }
        return false;
    }
    static boolean annotationAt(AnnotationNode inject,String expected) {
        if(inject.values==null)return false;
        for(int i=0;i<inject.values.size();i+=2)if(inject.values.get(i).equals("at")&&inject.values.get(i+1) instanceof List<?> entries)
            for(Object entry:entries)if(entry instanceof AnnotationNode at&&annotationValueContains(at,"value",expected))return true;
        return false;
    }
}
