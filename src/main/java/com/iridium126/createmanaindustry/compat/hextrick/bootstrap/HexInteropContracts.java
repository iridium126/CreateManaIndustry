package com.iridium126.createmanaindustry.compat.hextrick.bootstrap;

import java.util.List;
import java.util.Map;
import org.objectweb.asm.tree.ClassNode;

/** Bootstrap-safe ABI contracts: inspect bytecode without loading either optional mod. */
public final class HexInteropContracts {
    public static final String HEX_VERSION = "0.12.0-devel-pre-64";
    public static final String TRICKSTER_VERSION = "2.0.0-beta.56";
    public static final Map<String, List<String>> METHODS = Map.of(
            "dev.enjarai.trickster.spell.execution.TickData", List.of("incrementExecutions()V", "isExecutionLimitReached()Z"),
            "dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor", List.of(
                    "run(Ldev/enjarai/trickster/spell/execution/source/SpellSource;Ldev/enjarai/trickster/spell/execution/TickData;)Ljava/util/Optional;"),
            "dev.enjarai.trickster.spell.execution.PlayerSpellExecutionManager", List.of(
                    "queue(Ldev/enjarai/trickster/spell/SpellExecutor;)Ljava/util/Optional;", "kill(I)Z", "killAll()V"),
            "at.petrak.hexcasting.api.casting.eval.vm.CastingVM", List.of(
                    "queueExecuteAndWrapIotas(Ljava/util/List;Lnet/minecraft/server/level/ServerLevel;)Lat/petrak/hexcasting/api/casting/eval/ExecutionClientView;"),
            "at.petrak.hexcasting.common.blocks.circles.BlockEntitySlate", List.of(
                    "saveModData(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
                    "loadModData(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"),
            "at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus", List.of(
                    "saveModData(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
                    "loadModData(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"));

    private HexInteropContracts() {}

    public static void verify(String name, ClassNode target) {
        for (String signature : METHODS.getOrDefault(name, List.of())) {
            if (target.methods.stream().noneMatch(method -> (method.name + method.desc).equals(signature))) {
                throw new IllegalStateException("CMI Hex/Trickster ABI mismatch: " + name + "." + signature
                        + "; supported Hexcasting " + HEX_VERSION + ", Trickster " + TRICKSTER_VERSION);
            }
        }
        if (name.equals("dev.enjarai.trickster.spell.execution.TickData")
                && target.fields.stream().noneMatch(field -> field.name.equals("executionLimit") && field.desc.equals("I"))) {
            throw new IllegalStateException("CMI Hex/Trickster ABI mismatch: TickData.executionLimit:I");
        }
    }
}
