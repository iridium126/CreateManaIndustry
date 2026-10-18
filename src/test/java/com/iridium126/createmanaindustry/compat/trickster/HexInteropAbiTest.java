package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.compat.hextrick.bootstrap.HexInteropContracts;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class HexInteropAbiTest {
    private static ClassNode read(String name) throws Exception {
        try (InputStream input = HexInteropAbiTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
    @Test void persistenceAndInstructionHooksMatchResolvedDependencies() throws Exception {
        for (String name : HexInteropContracts.METHODS.keySet()) HexInteropContracts.verify(name, read(name));
        var executor = read("dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor");
        var run = executor.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
        long counts = java.util.Arrays.stream(run.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals("dev/enjarai/trickster/spell/execution/TickData") && call.name.equals("incrementExecutions")).count();
        assertEquals(1, counts, "Native instruction increment sites changed; audit shared accounting before upgrading");
        assertEquals(1, java.util.Arrays.stream(run.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals("java/util/function/BiFunction") && call.name.equals("apply")).count(),
                "Native pre-instruction reservation site changed");
        var store = read("dev.enjarai.trickster.spell.trick.entity.StoreEntityTrick");
        assertEquals(1, store.methods.stream().flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && (call.owner.equals("net/minecraft/server/level/ServerPlayer") && call.name.equals("position")
                        || call.owner.equals("net/minecraft/class_3222") && call.name.equals("method_19538"))).count(),
                "Native store-entity anchor site changed (Mojmap runtime / intermediary JVM dependency)");
        var vm = read("at.petrak.hexcasting.api.casting.eval.vm.CastingVM");
        var queue = vm.methods.stream().filter(method -> method.name.equals("queueExecuteAndWrapIotas")).findFirst().orElseThrow();
        assertEquals(1, java.util.Arrays.stream(queue.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals("at/petrak/hexcasting/api/casting/eval/vm/ContinuationFrame") && call.name.equals("evaluate")).count());
    }
    @Test void changedAbiFailsWithAnExplicitDiagnostic() {
        var missing = new ClassNode();
        var failure = assertThrows(IllegalStateException.class,
                () -> HexInteropContracts.verify("dev.enjarai.trickster.spell.execution.TickData", missing));
        assertTrue(failure.getMessage().contains("ABI mismatch"));
        assertTrue(failure.getMessage().contains(HexInteropContracts.TRICKSTER_VERSION));
    }
}
