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
            "handleRemoveEntities","ClientboundRemoveEntitiesPacket","handleSetEntityPassengersPacket","ClientboundSetPassengersPacket");
        for(var entry:handlers.entrySet()) {
            var m=listener.methods.stream().filter(method->method.name.equals(entry.getKey())&&method.desc.equals("(Lnet/minecraft/network/protocol/game/"+entry.getValue()+";)V")).findFirst().orElseThrow();
            boolean guarded=false;int returns=0;
            for(var instruction:m.instructions){if(instruction instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/network/protocol/PacketUtils")&&call.name.equals("ensureRunningOnSameThread"))guarded=true;
                if(instruction.getOpcode()==Opcodes.RETURN){assertTrue(guarded,entry.getKey()+" lacks thread guard before RETURN");returns++;}}
            assertTrue(returns>0,entry.getKey());
        }
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
            for(String name:List.of("packages.NativePackagePacketMixin","packages.NativeMotionPacketAccessor","packages.NativePackageLifecycleMixin")){assertTrue(clients.contains(name));assertFalse(shared.contains(name));}
        }
    }
}
