package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.lod.AllvrLodBackend;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodPos;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;

/**
 * The voxy LOD backend (voxy integration plan §7.1/§5.3): binds the client
 * level to the live voxy engine, publishes decoded section payloads through
 * the single-writer queue, and drives the rebase state machine (STEADY →
 * DETACH batching → MOVE → REFILL → STEADY). Everything here runs on the
 * render thread; the voxy types stay confined to this package.
 */
final class VoxyLodBackend implements AllvrLodBackend {

    private static final int STATE_STEADY = 0;
    private static final int STATE_REFILL = 1;
    private static final int STATE_DETACH = 2;

    /** Detach entries processed per tick while a rebase is batching (§11.4:
     *  no single frame may stall; the request walk is frozen meanwhile). */
    private static final int DETACH_PER_TICK = 2048;
    /** REFILL ticks before the walk returns to normal ordering (§5.3 step 5;
     *  the allay fog covers the window move, so a bounded refill is safe). */
    private static final int REFILL_TICKS = 60;

    private ClientLevel level;
    private AllvrVoxyYWindow window;
    private AllvrVoxyNodeRegistry registry;
    private AllvrVoxySectionWriter writer;
    private me.cortex.voxy.common.world.WorldEngine engine;
    private int state = STATE_STEADY;
    private List<AllvrVoxyNodeRegistry.Entry> detachQueue;
    private long rebaseCount;
    private double lastCameraY;
    private int refillTicksLeft;

    /** True while the backend is in the rebase REFILL phase (§5.3 step 5). */
    boolean isRefilling() {
        return this.state == STATE_REFILL;
    }

    @Override
    public boolean requestsOpen() {
        return this.engine != null && this.state == STATE_STEADY;
    }

    @Override
    public java.util.List<AllvrLodBackend.Failure> drainFailures() {
        if (this.writer == null) {
            return java.util.List.of();
        }
        java.util.ArrayList<AllvrLodBackend.Failure> failures = new java.util.ArrayList<>();
        AllvrVoxySectionWriter.Inject failed;
        while ((failed = this.writer.pollFailedInject()) != null) {
            failures.add(new AllvrLodBackend.Failure(failed.data().level(), failed.data().cellLong()));
        }
        return failures;
    }

    /** The virtual Y window this backend tracks (the viewport camera patch). */
    AllvrVoxyYWindow window() {
        return this.window;
    }

    @Override
    public Availability probe() {
        return VoxyCompatibilityProbe.probe();
    }

    @Override
    public void enter(ClientLevel level) {
        this.level = level;
        this.window = new AllvrVoxyYWindow();
        this.registry = new AllvrVoxyNodeRegistry();
        this.writer = new AllvrVoxySectionWriter();
        this.writer.bindWindow(this.window);
        this.engine = null;
        this.state = STATE_STEADY;
        this.detachQueue = null;
    }

    @Override
    public boolean apply(AllvrLodSectionData data, Holder<Biome> biome) {
        // The section codec uses null for its valid all-air marker. That
        // marker is consumed by AllvrLodClientState as a forget and must
        // never become an asynchronous writer job.
        if (data == null) {
            return false;
        }
        // Do this check before enqueueing: an absolute section outside the
        // current virtual window cannot be made resident by merely accepting
        // a queue entry.  The walk will retry it after the next rebase.
        AllvrLodPos pos = AllvrLodPos.fromCellLong(data.level(), data.cellLong());
        int virtualY = this.window.virtualCellY(data.level(), pos.cellY());
        if (virtualY < AllvrVoxyYWindow.VOXY_MIN_CELL_Y
            || virtualY > AllvrVoxyYWindow.VOXY_MAX_CELL_Y) {
            return false;
        }
        if (this.state == STATE_DETACH || this.engine == null || this.writer == null) {
            return false; // frozen (rebase detach) or not yet bound — re-request
        }
        return this.writer.enqueue(new AllvrVoxySectionWriter.Inject(data, biome));
    }

    @Override
    public void forget(int level, long cellLong) {
        if (this.engine == null || this.writer == null) {
            return;
        }
        this.writer.enqueue(new AllvrVoxySectionWriter.Forget(level, cellLong));
    }

    @Override
    public void tick(double cameraX, double cameraY, double cameraZ) {
        if (this.window == null || this.level == null) {
            return;
        }
        this.lastCameraY = cameraY;
        if (this.engine != null && !this.engine.isLive()) {
            // voxy tore its renderer down underneath us — drop ownership and
            // re-probe on a later tick (plan §13: never write to a dead engine)
            releaseAll();
            this.engine = null;
        }
        if (this.engine == null) {
            this.engine = VoxyApi_0215_1211.findEngine(this.level);
            if (this.engine == null) {
                return; // not ready yet — apply() already refuses
            }
            this.writer.bindEngine(this.engine, this.registry);
        }
        if (this.state == STATE_DETACH) {
            runDetachBatch();
            return;
        }
        if (this.state == STATE_STEADY && this.window.needsRebase(cameraY)) {
            beginRebase();
            runDetachBatch();
            return;
        }
        if (this.state == STATE_REFILL) {
            if (this.window.needsRebase(cameraY)) {
                beginRebase();
                runDetachBatch();
                return;
            }
            this.writer.drain();
            if (--this.refillTicksLeft <= 0) {
                this.state = STATE_STEADY;
            }
            return;
        }
        this.writer.drain();
    }

    /** Rebase step 3 (§5.3): snapshot the ledger, freeze the request walk. */
    private void beginRebase() {
        this.window.invalidateQueuedWork();
        this.writer.clear();
        this.detachQueue = new ArrayList<>();
        this.registry.forEachOwned(entry -> this.detachQueue.add(entry));
        this.state = STATE_DETACH;
        this.rebaseCount++;
        CreateManaIndustry.LOGGER.info("[Allvr] voxy rebase #{}: detaching {} owned nodes",
            this.rebaseCount, this.detachQueue.size());
    }

    /**
     * Detach batching (§5.3 step 3): fine-to-coarse forgets, one bounded
     * batch per tick; when the snapshot drains, MOVE to the new origin and
     * open the REFILL phase (§5.3 steps 4-5).
     */
    private void runDetachBatch() {
        if (this.detachQueue.isEmpty()) {
            int nextOrigin = this.window.nextOrigin(this.lastCameraY);
            this.window.moveOrigin(nextOrigin);
            this.detachQueue = null;
            this.state = STATE_REFILL;
            this.refillTicksLeft = REFILL_TICKS;
            CreateManaIndustry.LOGGER.info(
                "[Allvr] voxy rebase #{} moved origin to {}", this.rebaseCount, nextOrigin);
            return;
        }
        int budget = DETACH_PER_TICK;
        while (budget-- > 0 && !this.detachQueue.isEmpty()) {
            AllvrVoxyNodeRegistry.Entry entry =
                this.detachQueue.remove(this.detachQueue.size() - 1);
            // the forget protocol decodes the VIRTUAL key straight out of the
            // voxy section key; the ledger entry went with the snapshot
            AllvrVoxyEngineOps.forgetVirtual(this.engine, this.window,
                this.registry, entry.level, entry.key);
        }
    }

    @Override
    public void leave() {
        releaseAll();
        this.level = null;
        this.engine = null;
        this.window = null;
        this.registry = null;
        this.writer = null;
        this.detachQueue = null;
        this.state = STATE_STEADY;
    }

    /** Releases every held reference and drops the writer queue. */
    private void releaseAll() {
        if (this.writer != null) {
            this.writer.clear();
        }
        if (this.registry != null) {
            List<AllvrVoxyNodeRegistry.Entry> owned = new ArrayList<>();
            this.registry.forEachOwned(owned::add);
            for (AllvrVoxyNodeRegistry.Entry entry : owned) {
                try {
                    entry.section.release(me.cortex.voxy.common.world.WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
                } catch (Throwable ignored) {
                    // The engine may already be dead; never retain stale keys.
                }
            }
            this.registry.clear();
        }
        this.detachQueue = null;
        this.state = STATE_STEADY;
    }

    @Override
    public String debugState() {
        String state = switch (this.state) {
            case STATE_DETACH -> "/detach(" + (this.detachQueue == null ? 0 : this.detachQueue.size()) + ")";
            case STATE_REFILL -> "/refill";
            default -> "";
        };
        return "voxy" + state + " origin="
            + (this.window == null ? "?" : String.valueOf(this.window.originBlockY()))
            + " owned=" + (this.registry == null ? 0 : this.registry.ownedCount());
    }
}
