package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.hextrick.execution.BridgeExecutionContext;
import com.iridium126.createmanaindustry.compat.hextrick.execution.ExecutorBudgetAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellExecutor;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import java.util.Optional;
import java.util.function.BiFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = DefaultSpellExecutor.class, remap = false, priority = 1200)
public abstract class DefaultExecutorBudgetMixin implements ExecutorBudgetAccess {
    @Shadow private ExecutionState state;
    @Shadow private Optional<SpellExecutor> child;
    @Unique private BridgeExecutionContext cmi$context;
    @Unique private String cmi$savedBudget = "";
    @Override public String cmi$encodeBudget() { return cmi$context == null ? cmi$savedBudget : cmi$context.encodeBudget(); }
    @Override public void cmi$restoreBudget(String encoded) { cmi$savedBudget = encoded; cmi$context = null; }
    @Override public boolean cmi$hasMixedBudget() { return cmi$context != null && cmi$context.mixed() || !cmi$savedBudget.isEmpty(); }
    @WrapMethod(method = "run")
    private Optional<Fragment> cmi$budget(SpellSource source, TickData data, Operation<Optional<Fragment>> original) {
        var current = BridgeExecutionContext.current();
        boolean delayed = child.isEmpty() && state.isDelayed();
        if (delayed && current == null && cmi$context == null && cmi$savedBudget.isEmpty()) return original.call(source, data);
        var server = source.getWorld().getServer();
        if (current != null) cmi$context = current;
        else if (cmi$context == null) cmi$context = cmi$savedBudget.isEmpty() ? new BridgeExecutionContext(server, null)
                : BridgeExecutionContext.restore(server, null, cmi$savedBudget);
        var context = cmi$context;
        if (delayed) return original.call(source, data);
        try (var scope = context.enter(); var accounting = context.trickRun()) {
            TickData shared = context.tickData(data);
            try { return original.call(source, shared); }
            finally { context.recordTrick(shared); }
        }
    }

    @WrapOperation(method = "run", at = @At(
            value = "INVOKE", target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object cmi$beginInstruction(BiFunction<?, ?, ?> function, Object context, Object arguments,
            Operation<Object> original) {
        var bridge = BridgeExecutionContext.current();
        if (bridge != null) bridge.beginTrickInstruction();
        return original.call(function, context, arguments);
    }
}
