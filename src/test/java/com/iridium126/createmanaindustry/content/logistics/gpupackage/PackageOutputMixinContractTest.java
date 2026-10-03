package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.util.List;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** Check the exact resolved Create spawn sites; live Mixin application remains a game check. */
class PackageOutputMixinContractTest {
    private static ClassNode read(String name) throws Exception {
        try(var resource=PackageOutputMixinContractTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(resource,name);var node=new ClassNode();new ClassReader(resource).accept(node,0);return node;
        }
    }
    @Test void machineSpawnHooksMatchResolvedCreateMethodsAndFollowTheirImpulseSetters() throws Exception {
        String prefix="com/simibubi/create/content/";
        for(var target:List.of(
                new String[]{"logistics/chute/ChuteBlockEntity","handleDownwardOutput","(Z)Z"},
                new String[]{"logistics/chute/ChuteBlockEntity","handleUpwardOutput","(Z)Z"},
                new String[]{"kinetics/belt/transport/BeltInventory","eject","(L"+prefix+"kinetics/belt/transport/TransportedItemStack;)V"},
                new String[]{"logistics/funnel/FunnelBlockEntity","activateExtractor","()V"},
                new String[]{"fluids/drain/ItemDrainBlockEntity","tick","()V"},
                new String[]{"kinetics/saw/SawBlockEntity","tick","()V"},
                new String[]{"logistics/tunnel/BrassTunnelBlockEntity","insertIntoTunnel","(L"+prefix+"logistics/tunnel/BrassTunnelBlockEntity;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"})) {
            var node=read(prefix+target[0]);
            var method=node.methods.stream().filter(m->m.name.equals(target[1])&&m.desc.equals(target[2])).findFirst().orElseThrow();
            int motion=-1,spawn=-1,count=0,index=0;
            for(var instruction:method.instructions) {
                if(instruction instanceof MethodInsnNode call) {
                    if(call.name.equals("setDeltaMovement"))motion=index;
                    if(call.owner.equals("net/minecraft/world/level/Level")&&call.name.equals("addFreshEntity")&&call.desc.equals("(Lnet/minecraft/world/entity/Entity;)Z")){spawn=index;count++;}
                }index++;
            }
            assertEquals(1,count,target[0]+"#"+target[1]);assertTrue(motion>=0&&motion<spawn,"Output hook must capture the final native impulse");
        }
        var belt=read(prefix+"kinetics/belt/transport/BeltInventory");
        assertTrue(belt.fields.stream().anyMatch(f->f.name.equals("belt")&&f.desc.equals("L"+prefix+"kinetics/belt/BeltBlockEntity;")));
    }
    @Test void outputMixinsAreRegisteredOnTheCommonSideAndHaveNoClientOrPhysicsLinkage() throws Exception {
        try(var stream=getClass().getClassLoader().getResourceAsStream("createmanaindustry.mixins.json")) {
            assertNotNull(stream);var common=JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject().getAsJsonArray("mixins");
            for(String name:List.of("PackageChuteOutputMixin","PackageBeltOutputMixin","PackageFunnelLightQueryMixin","PackageDrainOutputMixin","PackageSawOutputMixin","PackageTunnelOutputMixin")) {
                assertTrue(java.util.stream.StreamSupport.stream(common.spliterator(),false).anyMatch(v->v.getAsString().equals("packages."+name)),name);
                var node=read("com/iridium126/createmanaindustry/mixin/packages/"+name);
                assertTrue(node.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                        .anyMatch(i->i instanceof MethodInsnNode c&&c.owner.endsWith("/PackageOutputHooks")&&c.name.equals("prepare")),name);
            }
        }
        for(String name:List.of("PackageOutputHooks","PackageOutputPose"))for(var method:read("com/iridium126/createmanaindustry/content/logistics/gpupackage/"+name).methods)
            for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode c) {
                assertFalse(c.owner.startsWith("net/minecraft/client/"));assertFalse(c.owner.contains("client/particles/"));
                assertFalse((c.owner.startsWith("net/minecraft/world/entity/")&&(c.name.equals("move")||c.name.equals("travel")))||c.name.equals("getBlockCollisions"));
            }
    }
}
