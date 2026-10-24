package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class CastChainBoundaryContractTest {
    @Test void pre64PostCastBoundaryExposesTheFinalResolutionAndClearFlag() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("at/petrak/hexcasting/api/casting/eval/vm/CastingVM.class")) {
            assertNotNull(input);
            var node = new ClassNode(); new ClassReader(input).accept(node, 0);
            var method = node.methods.stream().filter(m -> m.name.equals("queueExecuteAndWrapIotas")).findFirst().orElseThrow();
            assertTrue(method.localVariables.stream().anyMatch(v -> v.name.equals("isStackClear") && v.desc.equals("Z")));
            assertEquals(1, method.localVariables.stream().filter(v -> v.desc.equals("Lat/petrak/hexcasting/api/casting/eval/ResolvedPatternType;")).count());
            int calls = 0; boolean transientRemoved = false;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("removeTransientComponents")) transientRemoved = true;
                if (call.owner.equals("at/petrak/hexcasting/api/casting/eval/CastingEnvironment") && call.name.equals("postCast")) {
                    assertTrue(transientRemoved, "Snapshots must be published after upstream transient cleanup");
                    assertEquals("(Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;)V", call.desc); calls++;
                }
            }
            assertEquals(1, calls);
        }
    }
    @Test void unknownForeignExecutionMixinsRemainRejected() throws Exception {
        var type = Class.forName("com.iridium126.createmanaindustry.mixin.hexjit.HexJitMixinPlugin$FinalVerifier");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        var verifier = constructor.newInstance();
        var foreign = type.getDeclaredMethod("foreign", List.class); foreign.setAccessible(true);
        var annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        annotation.values = List.of("mixin", "third.party.UnknownExecutionMixin");
        assertEquals(true, foreign.invoke(verifier, List.of(annotation)));
        annotation.values = List.of("mixin", "com.iridium126.createmanaindustry.mixin.hexjit.CastingVMMixin");
        assertEquals(false, foreign.invoke(verifier, List.of(annotation)));
    }
}
