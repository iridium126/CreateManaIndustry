package com.iridium126.createmanaindustry.content.fluids.fueltank;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Checks injection anchors against the actual resolved Create binary, without booting a world. */
class FuelTankCreateContractTest {
    @Test void captureAndPlacementHooksMatchTheResolvedCreateBinary() throws Exception {
        ClassNode node=new ClassNode();
        try(InputStream in=getClass().getResourceAsStream("/com/simibubi/create/content/contraptions/Contraption.class")) {
            assertNotNull(in);new ClassReader(in).accept(node,0);
        }
        assertTrue(node.methods.stream().anyMatch(m->m.name.equals("captureMultiblock") && m.desc.equals(
            "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;Lnet/minecraft/world/level/block/entity/BlockEntity;)V")));
        var placement=node.methods.stream().filter(m->m.name.equals("addBlocksToWorld") && m.desc.equals(
            "(Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/contraptions/StructureTransform;)V")).findFirst().orElseThrow();
        int calls=0;
        for(var insn:placement.instructions) if(insn instanceof MethodInsnNode call
            && call.owner.equals("com/simibubi/create/content/contraptions/StructureTransform")
            && call.name.equals("apply") && call.desc.equals("(Lnet/minecraft/world/level/block/entity/BlockEntity;)V")) calls++;
        assertEquals(1,calls);
        assertTrue(node.fields.stream().anyMatch(f->f.name.equals("updateTags") && f.desc.equals("Ljava/util/Map;")));
        try(InputStream in=getClass().getResourceAsStream("/createmanaindustry.mixins.json")) {
            assertNotNull(in);assertTrue(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("fluids.FuelTankContraptionMixin"));
        }
    }

    @Test void stableLazyTickDoesNotCallTheFullGroupLightingSweep() throws Exception {
        ClassNode node=new ClassNode();
        try(InputStream in=getClass().getResourceAsStream("/com/iridium126/createmanaindustry/content/fluids/fueltank/FuelTankBlockEntity.class")) {
            assertNotNull(in);new ClassReader(in).accept(node,0);
        }
        var lazy=node.methods.stream().filter(m->m.name.equals("lazyTick")).findFirst().orElseThrow();
        for(var insn:lazy.instructions) if(insn instanceof MethodInsnNode call)
            assertNotEquals("refreshLitStates",call.name);
    }
}
