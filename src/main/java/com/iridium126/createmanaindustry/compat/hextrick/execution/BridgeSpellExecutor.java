package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellExecutor;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.executor.SpellExecutorType;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import java.util.Optional;
import net.minecraft.core.Registry;

/** Restores the call-site source before the native manager can substitute a player source. */
public final class BridgeSpellExecutor implements SpellExecutor, ExecutorChainAccess {
    public static final StructEndec<BridgeSpellExecutor> ENDEC = StructEndecBuilder.of(
            SpellExecutor.INTERNAL_ENDEC.fieldOf("delegate", executor -> executor.delegate),
            Endec.BOOLEAN.fieldOf("circle", executor -> executor.origin != null),
            Endec.STRING.fieldOf("origin", executor -> executor.origin == null ? "" : executor.origin.encode()),
            Endec.STRING.fieldOf("budget", BridgeSpellExecutor::budget), BridgeSpellExecutor::new);
    public static final StructEndec<BridgeSpellExecutor> NET_ENDEC = StructEndecBuilder.of(
            SpellExecutor.NET_ENDEC.fieldOf("delegate", executor -> executor.delegate),
            delegate -> new BridgeSpellExecutor(delegate, false, "", ""));
    public static final SpellExecutorType<BridgeSpellExecutor> TYPE = Registry.register(SpellExecutorType.REGISTRY,
            CreateManaIndustry.modLoc("bridge"), new SpellExecutorType<>(ENDEC, NET_ENDEC));

    private final SpellExecutor delegate;
    private final CastingOrigin origin;
    private String savedBudget;
    private BridgeExecutionContext context;
    private int lastRun;

    private BridgeSpellExecutor(SpellExecutor delegate, boolean circle, String origin, String budget) {
        this.delegate = checkedDelegate(delegate);
        if (circle == origin.isEmpty()) throw new IllegalArgumentException("Missing or inconsistent bridge source");
        this.origin = origin.isEmpty() ? null : CastingOrigin.decode(origin);
        this.savedBudget = budget;
    }

    public BridgeSpellExecutor(SpellExecutor delegate, BridgeExecutionContext context) {
        this.delegate = checkedDelegate(delegate);
        this.origin = context.origin();
        this.context = context;
    }

    public static void register() {}
    private static SpellExecutor checkedDelegate(SpellExecutor delegate) {
        if (!(delegate instanceof DefaultSpellExecutor) || !(delegate instanceof ExecutorChainAccess))
            throw new IllegalArgumentException("Unsupported bridge executor delegate");
        return delegate;
    }
    private String budget() { return context == null ? savedBudget : context.encodeBudget(); }
    @Override public SpellExecutorType<?> type() { return TYPE; }
    @Override public SpellPart spell() { return delegate.spell(); }
    @Override public int getLastRunExecutions() { return lastRun; }
    @Override public ExecutionState getDeepestState() { return delegate.getDeepestState(); }
    @Override public CastChain cmi$executorChain() { return ((ExecutorChainAccess) delegate).cmi$executorChain(); }
    @Override public void cmi$executorChain(CastChain chain) { ((ExecutorChainAccess) delegate).cmi$executorChain(chain); }
    @Override public void cmi$releaseExecution() {
        ((ExecutorChainAccess) delegate).cmi$releaseExecution();
        CastChains.release(cmi$executorChain(), this);
    }

    @Override
    public Optional<Fragment> run(SpellSource source, TickData data) {
        lastRun = 0;
        var server = source.getWorld().getServer();
        boolean finished = true;
        try {
            if (context == null) {
                try { context = BridgeExecutionContext.restore(server, origin, savedBudget); }
                catch (RuntimeException invalid) { throw new BridgeExecutionContext.BridgeFailure("invalid_saved_execution"); }
            }
            if (origin != null) {
                var resolution = origin.source().resolve(server);
                if (resolution.status() == CircleSourceDescriptor.Status.INVALID)
                    throw new BridgeExecutionContext.BridgeFailure(resolution.reason());
                if (resolution.status() == CircleSourceDescriptor.Status.WAITING
                        || origin.player() != null && origin.owner(server) == null) {
                    finished = false;
                    return Optional.empty();
                }
                source = new CircleSpellSource(new AnchoredCircleCastEnv(resolution.level(), origin), origin);
            }
            try (var scope = context.enter()) {
                TickData shared = context.tickData(data);
                int before = shared.getExecutions();
                Optional<Fragment> result;
                try { result = delegate.run(source, shared); }
                finally { context.recordTrick(shared); lastRun = shared.getExecutions() - before; }
                finished = result.isPresent();
                return result;
            }
        } finally {
            if (finished) cmi$releaseExecution();
        }
    }
}
