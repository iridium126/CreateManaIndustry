package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

class PackageLightMigrationContractTest {
    @Test void transferredDataAndGameplayNeverRecreatePackageEntities() throws Exception {
        for(var name:List.of("PackageLightStore","PackageLightGameplay","PackageAuthorityManager"))for(var method:PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/content/logistics/gpupackage/"+name).methods)for(var instruction:method.instructions){
            if(instruction instanceof TypeInsnNode type)assertFalse(type.getOpcode()==Opcodes.NEW&&type.desc.equals("com/simibubi/create/content/logistics/box/PackageEntity"),name+"."+method.name);
            if(instruction instanceof MethodInsnNode call)assertFalse(call.name.equals("addFreshEntity")&&!name.equals("PackageLightGameplay"),"authority/storage must never reinsert native entities");
        }
        assertFalse(PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityManager").methods.stream().anyMatch(m->m.name.equals("restoreLight")||m.name.equals("materialize")));
    }
    @Test void machineQueriesMatchResolvedCreateMethods() throws Exception {
        var funnel=PackageCollisionHookContractTest.type("com/simibubi/create/content/logistics/funnel/FunnelBlockEntity");assertTrue(funnel.methods.stream().anyMatch(m->m.name.equals("activateExtractor")&&m.desc.equals("()V")));assertTrue(funnel.methods.stream().anyMatch(m->m.name.equals("getEntityOverflowScanningArea")&&m.desc.equals("()Lnet/minecraft/world/phys/AABB;")));
        var moving=PackageCollisionHookContractTest.type("com/simibubi/create/content/logistics/funnel/FunnelMovementBehaviour");assertTrue(moving.methods.stream().anyMatch(m->m.name.equals("succ")&&m.desc.equals("(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/core/BlockPos;)V")));
    }
    @Test void nativeCollisionBridgeMatchesTheResolvedCall() throws Exception {
        var entity=PackageCollisionHookContractTest.type("net/minecraft/world/entity/Entity");boolean found=false;for(var method:entity.methods)if(method.name.equals("collide"))for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/Level")&&call.name.equals("getEntityCollisions")&&call.desc.equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))found=true;assertTrue(found);
    }
    @Test void gatewayDestinationAndPortalShapeHelpersNeedNoPackageEntity() throws Exception {
        var gateway=PackageCollisionHookContractTest.type("net/minecraft/world/level/block/entity/TheEndGatewayBlockEntity");assertTrue(gateway.methods.stream().anyMatch(m->m.name.equals("getPortalPosition")&&m.desc.equals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;")));
        var shape=PackageCollisionHookContractTest.type("net/minecraft/world/level/portal/PortalShape");assertTrue(shape.methods.stream().anyMatch(m->m.name.equals("getRelativePosition")&&m.desc.equals("(Lnet/minecraft/BlockUtil$FoundRectangle;Lnet/minecraft/core/Direction$Axis;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/EntityDimensions;)Lnet/minecraft/world/phys/Vec3;")));
    }
}
