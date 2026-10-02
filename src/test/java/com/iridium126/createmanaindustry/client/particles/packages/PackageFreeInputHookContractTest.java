package com.iridium126.createmanaindustry.client.particles.packages;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Verify resolved bytecode targets without a live client. Actual Mixin/input testing remains
 * a game check; this test prevents target/version drift from silently breaking the bridge. */
class PackageFreeInputHookContractTest {
    @Test void inputInvokersAndNativePickingPredicateTargetMatchResolvedMinecraft() throws Exception {
        var mc=PackageCollisionHookContractTest.type("net/minecraft/client/Minecraft");
        assertTrue(mc.fields.stream().anyMatch(f->f.name.equals("missTime")&&f.desc.equals("I")));
        for(String method:List.of("startUseItem()V","startAttack()Z","continueAttack(Z)V"))
            assertTrue(mc.methods.stream().anyMatch(m->(m.name+m.desc).equals(method)),method);
        var renderer=PackageCollisionHookContractTest.type("net/minecraft/client/renderer/GameRenderer");
        var pick=renderer.methods.stream().filter(m->m.name.equals("pick")&&m.desc.equals("(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;")).findFirst().orElseThrow();
        int calls=0;for(var insn:pick.instructions)if(insn instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/entity/projectile/ProjectileUtil")
                &&call.name.equals("getEntityHitResult")&&call.desc.equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"))calls++;
        assertEquals(1,calls);
    }
    @Test void newHooksAreClientOnlyAndNativeGameModeStillUsesServerEntityPackets() throws Exception {
        try(var resource=getClass().getClassLoader().getResourceAsStream("createmanaindustry.mixins.json")) {
            assertNotNull(resource);var json=JsonParser.parseReader(new InputStreamReader(resource)).getAsJsonObject();
            var clients=new HashSet<String>();var shared=new HashSet<String>();
            json.getAsJsonArray("client").forEach(v->clients.add(v.getAsString()));json.getAsJsonArray("mixins").forEach(v->shared.add(v.getAsString()));
            for(String hook:List.of("packages.PackageFreeGpuInputMixin")){assertTrue(clients.contains(hook));assertFalse(shared.contains(hook));}
            assertTrue(clients.contains("vanilla.GameRendererPickMixin"));assertFalse(shared.contains("vanilla.GameRendererPickMixin"));
        }
        var interaction=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/client/particles/packages/PackageFreeInteractionClient");
        assertTrue(interaction.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .anyMatch(call->call.owner.endsWith("/ServerboundLightPackageInteraction")&&call.name.equals("<init>")));
        for(var m:interaction.methods)for(var instruction:m.instructions)if(instruction instanceof MethodInsnNode call)
            assertFalse(call.owner.equals("com/simibubi/create/content/logistics/box/PackageEntity"),"Record interactions must not replay on a native package entity");
        var mode=PackageCollisionHookContractTest.type("net/minecraft/client/multiplayer/MultiPlayerGameMode");
        for(String name:List.of("interact","interactAt","attack")) {
            var method=mode.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();boolean sends=false;
            for(var insn:method.instructions)if(insn instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/network/protocol/game/ServerboundInteractPacket")&&call.name.startsWith("create"))sends=true;
            assertTrue(sends,name);
        }
    }
}
