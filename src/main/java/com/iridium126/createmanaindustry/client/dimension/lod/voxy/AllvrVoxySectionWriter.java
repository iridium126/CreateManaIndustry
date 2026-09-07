package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;

/**
 * The single-writer section queue (voxy integration plan §7.3/§7.4): every
 * voxy engine write — voxel injection, node forget, full rebase detach — is
 * serialized here and drained on the render thread under a per-tick budget
 * (plan §11.4: ≤2 ms p95, overflow defers, never blocks the frame).
 * <p>
 * {@code WorldSection._unsafeSetNonEmptyChildren} is reached only from this
 * drain, matching Voxy's own expectation that the mutation happens away
 * from arbitrary packet-handler threads.
 */
final class AllvrVoxySectionWriter {

    /** Injection budget per tick — sized so worst-case jobs stay under the
     *  2 ms frame budget (32K long fills dominate). */
    private static final int MAX_PER_TICK = 8;
    /** Backpressure cap — apply() rejects above this so requests re-issue. */
    private static final int QUEUE_CAP = 1024;

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    /** Control/mutation queue is independent of data backpressure: a forget
     * must not be dropped behind a full injection queue. */
    private final ArrayDeque<Job> controlQueue = new ArrayDeque<>();
    private final Set<ForgetKey> pendingForgets = new HashSet<>();
    private final ArrayDeque<Inject> failedInjects = new ArrayDeque<>();
    private AllvrVoxyYWindow window;
    private me.cortex.voxy.common.world.WorldEngine engine;
    private AllvrVoxyNodeRegistry registry;

    private record ForgetKey(int level, long cellLong) {}

    void bindWindow(AllvrVoxyYWindow window) {
        this.window = window;
    }

    void bindEngine(me.cortex.voxy.common.world.WorldEngine engine, AllvrVoxyNodeRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    /** True when the job was accepted (caller marks the node meshed). */
    boolean enqueue(Job job) {
        if (job instanceof Forget) {
            Forget forget = (Forget) job;
            if (this.pendingForgets.add(new ForgetKey(forget.level(), forget.cellLong()))) {
                this.controlQueue.add(job);
            }
            return true;
        }
        if (this.queue.size() >= QUEUE_CAP) {
            return false;
        }
        this.queue.add(job);
        return true;
    }

    /** Drains up to {@link #MAX_PER_TICK} jobs on the render thread. */
    void drain() {
        int budget = MAX_PER_TICK;
        while (budget-- > 0) {
            Job job = this.controlQueue.poll();
            if (job == null) {
                job = this.queue.poll();
            }
            if (job == null) {
                return;
            }
            if (job instanceof Forget forget) {
                this.pendingForgets.remove(new ForgetKey(forget.level(), forget.cellLong()));
            }
            try {
                job.run(this);
            } catch (Throwable t) {
                if (job instanceof Inject inject) {
                    this.failedInjects.add(inject);
                }
                com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error(
                    "[Allvr] voxy writer job failed", t);
            }
        }
    }

    void clear() {
        this.queue.clear();
        this.controlQueue.clear();
        this.pendingForgets.clear();
        this.failedInjects.clear();
    }

    Inject pollFailedInject() {
        return this.failedInjects.poll();
    }

    /** One queued voxy-engine write. */
    interface Job {
        void run(AllvrVoxySectionWriter writer);
    }

    /** Injects one absolute section payload into the engine (§7.3). */
    record Inject(AllvrLodSectionData data, Holder<Biome> biome) implements Job {
        @Override
        public void run(AllvrVoxySectionWriter writer) {
            AllvrVoxyEngineOps.writeSection(writer.engine, writer.window,
                writer.registry, this.data(), this.biome());
        }
    }

    /** Drops one owned node: air-fill, child teardown, parent bit revoke. */
    record Forget(int level, long cellLong) implements Job {
        @Override
        public void run(AllvrVoxySectionWriter writer) {
            AllvrVoxyEngineOps.forgetAbsolute(writer.engine, writer.window,
                writer.registry, this.level(), this.cellLong());
        }
    }
}
