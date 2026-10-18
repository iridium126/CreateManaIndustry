package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.google.gson.Gson;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import dev.enjarai.trickster.Trickster;
import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import dev.enjarai.trickster.spell.execution.TickData;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Root instruction accounting is deliberately independent of effect ownership. */
public final class BridgeExecutionContext {
    private static final ThreadLocal<BridgeExecutionContext> CURRENT = new ThreadLocal<>();
    private static final Map<MinecraftServer, Budgets> BUDGETS = new IdentityHashMap<>();
    private static final class BudgetReference extends WeakReference<Budget> {
        final UUID id;
        BudgetReference(Budget budget, ReferenceQueue<Budget> queue) { super(budget, queue); id = budget.id; }
    }
    private static final class Budgets {
        final Map<UUID, BudgetReference> values = new HashMap<>();
        final ReferenceQueue<Budget> queue = new ReferenceQueue<>();
        void clean() {
            BudgetReference reference;
            while ((reference = (BudgetReference) queue.poll()) != null) values.remove(reference.id, reference);
        }
    }
    private static final Gson JSON = new Gson();
    private final MinecraftServer server;
    private final Budget budget;
    private final CastingOrigin origin;

    public static final class Budget {
        private UUID id = UUID.randomUUID();
        private long hexUsed;
        private long hexLimit = Long.MAX_VALUE;
        private long trickTick = Long.MIN_VALUE;
        private int trickUsed;
        private transient int depth;
        private boolean mixed;
        private transient final ArrayDeque<InstructionFrame> instructions = new ArrayDeque<>();
        private transient final ArrayDeque<HexFrame> hexFrames = new ArrayDeque<>();
    }

    private static final class InstructionFrame { int prepaid; }

    public interface Scope extends AutoCloseable { @Override void close(); }

    public BridgeExecutionContext(MinecraftServer server, CastingOrigin origin) {
        this(server, origin, new Budget());
    }

    private BridgeExecutionContext(MinecraftServer server, CastingOrigin origin, Budget budget) {
        this.server = server;
        this.origin = origin;
        this.budget = canonical(server, budget);
    }

    private static Budget canonical(MinecraftServer server, Budget candidate) {
        var budgets = BUDGETS.computeIfAbsent(server, key -> new Budgets());
        budgets.clean();
        BudgetReference reference = budgets.values.get(candidate.id);
        Budget existing = reference == null ? null : reference.get();
        if (existing == null) {
            budgets.values.put(candidate.id, new BudgetReference(candidate, budgets.queue));
            return candidate;
        }
        existing.hexUsed = Math.max(existing.hexUsed, candidate.hexUsed);
        existing.hexLimit = Math.min(existing.hexLimit, candidate.hexLimit);
        existing.mixed |= candidate.mixed;
        if (existing.trickTick == candidate.trickTick) existing.trickUsed = Math.max(existing.trickUsed, candidate.trickUsed);
        return existing;
    }

    public static BridgeExecutionContext current() { return CURRENT.get(); }
    public CastingOrigin origin() { return origin; }
    public MinecraftServer server() { return server; }
    public boolean mixed() { return budget.mixed; }
    public long hexUsed() { return budget.hexUsed; }

    public BridgeExecutionContext withOrigin(CastingOrigin value) {
        return new BridgeExecutionContext(server, value, budget);
    }

    public Scope enter() {
        BridgeExecutionContext previous = CURRENT.get();
        CURRENT.set(this);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    public Scope crossing() {
        budget.mixed = true;
        if (budget.hexUsed > budget.hexLimit || budget.hexUsed == budget.hexLimit
                && (budget.hexFrames.isEmpty() || budget.hexFrames.peek().prepaid == 0)) throw new BridgeFailure("hex_budget");
        if (budget.depth >= ServerConfig.hexTrickMaxDepth) throw new BridgeFailure("cross_language_depth");
        budget.depth++;
        return () -> budget.depth--;
    }

    public void hexLimit(long limit, long initial) {
        budget.hexLimit = Math.min(budget.hexLimit, Math.max(0, limit));
        budget.hexUsed = Math.max(budget.hexUsed, initial);
    }

    public boolean consumeHex(long operations) {
        if (operations > 0) budget.hexUsed = operations > Long.MAX_VALUE - budget.hexUsed
                ? Long.MAX_VALUE : budget.hexUsed + operations;
        return !budget.mixed || budget.hexUsed <= budget.hexLimit;
    }

    public final class HexFrame implements Scope {
        private long prepaid;
        public boolean account(long operations) { return consumeHex(Math.max(0, operations - prepaid)); }
        @Override public void close() { budget.hexFrames.pop(); }
    }

    public HexFrame hexFrame() {
        var frame = new HexFrame();
        budget.hexFrames.push(frame);
        return frame;
    }

    public boolean beginHexBridgeInstruction() {
        HexFrame frame = budget.hexFrames.peek();
        if (frame != null) frame.prepaid++;
        return consumeHex(1);
    }

    public TickData tickData(TickData supplied) {
        long now = server.overworld().getGameTime();
        if (budget.trickTick != now) {
            budget.trickTick = now;
            budget.trickUsed = 0;
        }
        budget.trickUsed = Math.max(budget.trickUsed, supplied.getExecutions());
        return supplied;
    }

    public void trickInstruction() {
        var frame = budget.instructions.peek();
        if (frame != null && frame.prepaid > 0) { frame.prepaid--; return; }
        if (budget.trickUsed < Integer.MAX_VALUE) budget.trickUsed++;
    }

    public Scope trickRun() {
        var frame = new InstructionFrame();
        budget.instructions.push(frame);
        return () -> budget.instructions.pop();
    }

    /** Count entry before a glyph can recursively enter another VM. The native
     * increment remains observable and consumes this reservation on return. */
    public void beginTrickInstruction() {
        var frame = budget.instructions.peek();
        if (frame == null) return;
        if (budget.trickUsed < Integer.MAX_VALUE) budget.trickUsed++;
        frame.prepaid++;
    }

    public boolean trickReached(int localLimit) {
        return budget.mixed && budget.trickUsed >= Math.min(localLimit,
                Trickster.CONFIG.maxExecutionsPerSpellPerTick());
    }

    public void recordTrick(TickData data) {
        budget.trickTick = server.overworld().getGameTime();
        budget.trickUsed = Math.max(budget.trickUsed, data.getExecutions());
    }

    public String encodeBudget() { return JSON.toJson(budget); }

    public static BridgeExecutionContext restore(MinecraftServer server, CastingOrigin origin, String encoded) {
        if (encoded.length() > 4096) throw new IllegalArgumentException("Budget too large");
        Budget budget = JSON.fromJson(encoded, Budget.class);
        if (budget == null || budget.id == null || budget.hexUsed < 0 || budget.hexLimit < 0
                || budget.trickUsed < 0) throw new IllegalArgumentException("Invalid bridge budget");
        return new BridgeExecutionContext(server, origin, budget);
    }

    public static ServerLevel world(ServerLevel fallback) {
        var context = current();
        if (context == null || context.origin == null) return fallback;
        var resolution = context.origin.source().resolve(context.server);
        return resolution.level() == null ? fallback : resolution.level();
    }

    public static void stopped(MinecraftServer server) { BUDGETS.remove(server); CURRENT.remove(); }

    public static final class BridgeFailure extends WrappableBlunder {
        private static final long serialVersionUID = 1L;
        private final String reason;
        public BridgeFailure(String reason) { this.reason = reason; }
        public String reason() { return reason; }
        @Override public MutableComponent createMessage(TrickContext context) {
            return Component.translatable("createmanaindustry.bridge.failed", reason);
        }
    }
}
