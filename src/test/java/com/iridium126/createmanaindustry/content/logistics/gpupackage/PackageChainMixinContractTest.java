package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Check actual resolved Create bytecode, never the .refs version, without initializing mod classes.
 * This verifies injection targets/field owners. It does not replace a game startup/injection test. */
class PackageChainMixinContractTest {
    @Test void commonPickupPayloadsDoNotLinkClientImplementations() throws IOException {
        for(String name:List.of("ClientboundChainInteractionPacket","ServerboundChainInteractionPacket")) {
            var node=read("com/iridium126/createmanaindustry/content/logistics/gpupackage/network/"+name);
            for(var m:node.methods) {
                assertFalse(m.desc.contains("net/minecraft/client/"));
                for(var i:m.instructions)if(i instanceof MethodInsnNode call) {
                    assertFalse(call.owner.startsWith("net/minecraft/client/"));assertFalse(call.owner.contains("client/particles/"));
                }
            }
        }
    }
    @Test void commonLifecycleHooksHaveNoClientOrReflectiveLinkage() throws IOException {
        for(String name:List.of("PackageChainClientHooks","PackageChainClientHooks$Listener","PackageChainRenderAccess")) {
            var node=read("com/iridium126/createmanaindustry/content/logistics/gpupackage/"+name);
            for(var m:node.methods) {
                assertFalse(m.desc.contains("net/minecraft/client/"));assertFalse(m.desc.contains("client/particles/"));
                for(var i:m.instructions)if(i instanceof MethodInsnNode call) {
                    assertFalse(call.owner.startsWith("net/minecraft/client/"));assertFalse(call.owner.contains("client/particles/"));
                    assertFalse(call.owner.startsWith("java/lang/reflect/"));assertFalse(call.name.equals("forName"));
                }
            }
        }
    }
    @Test void rendererAndFlywheelRedirectsMatchResolvedFieldsAndRetainRecyclerCleanup() throws IOException {
        var renderer=read("com/simibubi/create/content/kinetics/chainConveyor/ChainConveyorRenderer");
        var safe=method(renderer,"renderSafe","(L"+CONVEYOR+";FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V");
        var visual=read("com/simibubi/create/content/kinetics/chainConveyor/ChainConveyorVisual");
        var begin=method(visual,"beginFrame","(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V");
        for(var m:List.of(safe,begin))for(String name:List.of("loopingPackages","travellingPackages")) {
            boolean found=false;for(var i:m.instructions)if(i instanceof FieldInsnNode f && f.getOpcode()==Opcodes.GETFIELD && f.owner.equals(CONVEYOR) && f.name.equals(name))found=true;
            assertTrue(found,m.name+" missing "+name);
        }
        int resets=0,discards=0;
        for(var i:begin.instructions)if(i instanceof MethodInsnNode m && m.owner.equals("dev/engine_room/flywheel/lib/visual/util/SmartRecycler")) {
            if(m.name.equals("resetCount"))resets++;if(m.name.equals("discardExtra"))discards++;
        }
        assertEquals(2,resets);assertEquals(2,discards);
    }
    private static final String CONVEYOR="com/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity";
    private static ClassNode read(String name) throws IOException {
        try(var resource=PackageChainMixinContractTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(resource,"Missing resolved Create class: "+name);var node=new ClassNode();new ClassReader(resource).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode node,String name,String descriptor) {
        return node.methods.stream().filter(m->m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElseThrow(()->new AssertionError("Missing Create method: "+name+descriptor));
    }
    @Test void simulationRedirectsExistInAllThreeExactCreateMethods() throws IOException {
        var conveyor=read(CONVEYOR);
        for(String name:List.of("tick","updateBoxWorldPositions","tickBoxVisuals")) {
            var method=method(conveyor,name,"()V");
            for(String field:List.of("loopingPackages","travellingPackages")) {
                boolean found=false;
                for(var instruction:method.instructions)if(instruction instanceof FieldInsnNode f && f.getOpcode()==Opcodes.GETFIELD
                        && f.owner.equals(CONVEYOR) && f.name.equals(field) && f.desc.equals(field.equals("loopingPackages")?"Ljava/util/List;":"Ljava/util/Map;"))found=true;
                assertTrue(found,"Missing Create field access: "+name+" / "+field);
            }
        }
    }
    @Test void persistenceBreakAndTransferTargetsMatchTheResolvedAbi() throws IOException {
        var conveyor=read(CONVEYOR);String box="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorPackage;";
        method(conveyor,"addTravellingPackage","("+box+"Lnet/minecraft/core/BlockPos;)Z");
        method(conveyor,"addLoopingPackage","("+box+")Z");method(conveyor,"notifyUpdate","()V");
        method(conveyor,"exportToPort","("+box+"Lnet/minecraft/core/BlockPos;)Z");
        method(conveyor,"notifyPortToAnticipate","(Lnet/minecraft/core/BlockPos;)V");
        method(conveyor,"removeConnectionTo","(Lnet/minecraft/core/BlockPos;)Z");method(conveyor,"drop","("+box+")V");
        for(String name:List.of("write","read"))method(conveyor,name,"(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V");
        for(String name:List.of("destroy","clearContent","remove"))method(conveyor,name,"()V");
        method(conveyor,"transform","(Lnet/minecraft/world/level/block/entity/BlockEntity;Lcom/simibubi/create/content/contraptions/StructureTransform;)V");
        assertTrue(conveyor.fields.stream().anyMatch(f->f.name.equals("loopingPackages") && f.desc.equals("Ljava/util/List;") && (f.access&Opcodes.ACC_FINAL)==0));
        assertTrue(conveyor.fields.stream().anyMatch(f->f.name.equals("travellingPackages") && f.desc.equals("Ljava/util/Map;") && (f.access&Opcodes.ACC_FINAL)==0));
    }
    @Test void interactionHookMatchesSpecializedMethodAndRemovalFlag() throws IOException {
        var packet=read("com/simibubi/create/content/kinetics/chainConveyor/ChainPackageInteractionPacket");
        method(packet,"applySettings","(Lnet/minecraft/server/level/ServerPlayer;L"+CONVEYOR+";)V");
        assertTrue(packet.fields.stream().anyMatch(f->f.name.equals("removingPackage") && f.desc.equals("Z")));
        var handler=read("com/simibubi/create/content/kinetics/chainConveyor/ChainPackageInteractionHandler");
        assertTrue((method(handler,"onUse","()Z").access&Opcodes.ACC_STATIC)!=0);
        var base=read("com/simibubi/create/foundation/networking/BlockEntityConfigurationPacket");
        method(base,"handle","(Lnet/minecraft/server/level/ServerPlayer;)V");
        var ray=read("com/simibubi/create/foundation/utility/RaycastHelper");
        method(ray,"getTraceTarget","(Lnet/minecraft/world/entity/player/Player;DLnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;");
    }
}
