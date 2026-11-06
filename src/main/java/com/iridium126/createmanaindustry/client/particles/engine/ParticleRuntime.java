package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime.GlWork;
import com.iridium126.createmanaindustry.client.particles.interaction.CombatSpecs;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu;
import com.iridium126.createmanaindustry.client.greattrick.partition.PartitionGpu;
import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import com.iridium126.createmanaindustry.client.packages.render.PackageRuntime;
import com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

public final class ParticleRuntime implements com.iridium126.createmanaindustry.client.allaystorm.StormParticlePort, com.iridium126.createmanaindustry.client.particles.interaction.ModelParticlePort, com.iridium126.createmanaindustry.client.packages.render.PackageParticlePort, ParticleAppendTarget {


    public static final ParticleRuntime INSTANCE = new ParticleRuntime();

    private static final int MAX_EMITTERS = 128;
    // Reserved headroom for future emitter admission limits.
    @SuppressWarnings("unused")
    private static final int SAFETY_MARGIN = 2048;
    /**
     * Width of the distance fade ramp past the configured fade distance
     * (blocks); must match the literal in additive.fsh / textured.fsh.
     */
    static final float FADE_RAMP_BLOCKS = 24.0f;
    /** Counting-sort passes over the 8-bit depth-band key (one, by design). */
    private static final int RADIX_SHIFTS[] = { 0 };
    /**
     * Draw elapsed-query ring depth. Compute uses independent timestamp pairs
     * so preparation can run inside an external elapsed query. Both poll
     * without waiting; their latest samples feed the throttle.
     */
    private static final int TIMER_RING = 4;

    /** One-shot burst request posted from a client thread. */
    private final List<Burst> frameBursts = new ArrayList<>();

    static final class Burst {
        final EmitterSpec spec;
        final Vec3 origin;
        final int count;

        Burst(EmitterSpec spec, Vec3 origin, int count) {
            this.spec = spec;
            this.origin = origin;
            this.count = count;
        }
    }

    record StreamReq(CMIParticleEngine.StreamHandle handle, EmitterSpec spec, Vec3 origin, double rate, double duration) {
    }

    record StreamCancel(CMIParticleEngine.StreamHandle handle) {
    }

    /** Live animation-switch request posted from a client thread. */
    record AnimReq(EmitterSpec spec, EmitterSpec.Animation animation) {
    }

    /** Active stream consumed each frame on the render thread. */
    private static final class Stream {
        final long id;
        final EmitterSpec spec;
        final Vec3 origin;
        final double rate;
        final double duration; // <= 0 = infinite
        double elapsed;
        double accumulator;

        Stream(long id, EmitterSpec spec, Vec3 origin, double rate, double duration) {
            this.id = id;
            this.spec = spec;
            this.origin = origin;
            this.rate = rate;
            this.duration = duration;
        }
    }

    final ParticleInputQueue pending = new ParticleInputQueue();
    final AtomicLong nextStreamId = new AtomicLong(1L);
    private final Map<EmitterSpec, Integer> emitterIds = new HashMap<>();
    private final BlockEmitterTable blockEmitters = BlockEmitterTable.INSTANCE;

    private final List<Stream> streams = new ArrayList<>();
    private final java.util.ArrayDeque<Object> retryInputs=new java.util.ArrayDeque<>();
    private final List<Object> drainedInputs=new ArrayList<>();
    private final List<Stream> checkpointStreams=new ArrayList<>();
    private final Set<Long> checkpointCancelled=new HashSet<>();
    private double[] checkpointClock=new double[32];
    private boolean inputCheckpoint;
    private void checkpointInputs() {
        if(inputCheckpoint)throw new IllegalStateException("Unfinished particle input candidate");
        checkpointStreams.clear();checkpointStreams.addAll(streams);checkpointCancelled.clear();checkpointCancelled.addAll(cancelledStreamIds);
        if(checkpointClock.length<streams.size()*2)checkpointClock=new double[streams.size()*4];
        for(int i=0;i<streams.size();i++){checkpointClock[2*i]=streams.get(i).elapsed;checkpointClock[2*i+1]=streams.get(i).accumulator;}
        drainedInputs.clear();inputCheckpoint=true;
        AllayStormRuntime.INSTANCE.checkpointSpawnInputs();
    }
    private Object nextFrameInput() {var item=retryInputs.pollFirst();return item!=null?item:pending.poll();}
    private void commitFrameInputs() {inputCheckpoint=false;drainedInputs.clear();checkpointStreams.clear();checkpointCancelled.clear();}
    private void restoreFrameInputs() {
        if(!inputCheckpoint)return;
        pending.deferred(drainedInputs.size());
        for(int i=drainedInputs.size()-1;i>=0;i--)retryInputs.addFirst(drainedInputs.get(i));
        streams.clear();streams.addAll(checkpointStreams);cancelledStreamIds.clear();cancelledStreamIds.addAll(checkpointCancelled);
        for(int i=0;i<streams.size();i++){streams.get(i).elapsed=checkpointClock[2*i];streams.get(i).accumulator=checkpointClock[2*i+1];}
        AllayStormRuntime.INSTANCE.restoreSpawnInputs();commitFrameInputs();
    }
    /** Render-thread ids cancelled before their request reaches the stream list. */
    private final Set<Long> cancelledStreamIds = new HashSet<>();
    final ParticleBuffers gpu = ParticleBuffers.INSTANCE;
    final ParticlePrograms programs = ParticlePrograms.INSTANCE;
    private final com.iridium126.createmanaindustry.client.gpu.frame.GpuWriteReservations writes=new com.iridium126.createmanaindustry.client.gpu.frame.GpuWriteReservations();
    private final CollisionBake collisionBake = new CollisionBake();
    private final ParticleAtlas spriteAtlas = ParticleAtlas.SPRITE;
    private final ParticleAtlas allayAtlas = ParticleAtlas.ALLAY;

    private final com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter emitCommands =
            new com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter();

    /** One-shot Hexcasting conjure spray (spawnStyle 4), drained next compute. */
    record HexSpray(float x, float y, float z, float vx, float vy, float vz,
            float fuzz, float spread, int count, com.iridium126.createmanaindustry.client.hex.particles.HexPigmentData pigment, Runnable recovery, int bornTick)
            implements com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter.Parameters {
        public void write(FloatBuffer target) {
            int start=target.position();
            pigment.write(target,fuzz,spread);
            target.put(start+43,Float.intBitsToFloat(bornTick));
        }
    }

    final List<HexSpray> hexSprays = new ArrayList<>();
    final com.iridium126.createmanaindustry.client.particles.emitter.DirectParticleBatch hexParticles =
            new com.iridium126.createmanaindustry.client.particles.emitter.DirectParticleBatch(true);
    private final com.iridium126.createmanaindustry.client.gpu.buffer.GpuGrowingBuffer hexDirectStorage =
            new com.iridium126.createmanaindustry.client.gpu.buffer.GpuGrowingBuffer();
    ClientLevel hexInputWorld;
    boolean replayingHex;
    int directHexEmitter=-1,sprayHexEmitter=-1,hexTick;

    private final SharedFateGpu sharedFate = SharedFateGpu.INSTANCE;
    private final PartitionGpu partition = PartitionGpu.INSTANCE;
    private final AllayFeatherGpu feathers=AllayFeatherGpu.INSTANCE;

    void replayHexParticle(ClientLevel world,double x,double y,double z,double vx,double vy,double vz,int color) {
        boolean previous=replayingHex;replayingHex=true;
        try{Minecraft.getInstance().particleEngine.createParticle(new at.petrak.hexcasting.common.particles.ConjureParticleOptions(color),x,y,z,vx,vy,vz);}
        finally{replayingHex=previous;}
    }

    private void replayHexInputs(){
        var world=hexInputWorld;
        boolean prior=replayingHex;replayingHex=true;
        try {
            if(world!=null&&world==Minecraft.getInstance().level) {
                hexParticles.replay((x,y,z,vx,vy,vz,color)->replayHexParticle(world,x,y,z,vx,vy,vz,color));
                for(var spray:hexSprays)if(spray.recovery()!=null)spray.recovery().run();
            } else hexParticles.clear();
        } finally {hexSprays.clear();hexInputWorld=null;replayingHex=prior;}
    }

    /** Packs RGB in the low 24 bits; the unused ARGB alpha bit carries gravity. */
    private static float packHexDirectColor(int color, boolean gravity) {
        int packed = (color & 0x00FF_FFFF) | (gravity ? 0x8000_0000 : 0);
        return Float.intBitsToFloat(packed);
    }

    /** fuzz (÷2) and spread (÷π), each 16-bit, packed into one float. */
    private static float packHexParams(double fuzz, double spread) {
        int f = Math.max(0, Math.min(0xFFFF, (int) Math.round(fuzz * 0.5 * 0xFFFF)));
        int s = Math.max(0, Math.min(0xFFFF, (int) Math.round(spread / Math.PI * 0xFFFF)));
        return Float.intBitsToFloat(f | (s << 16));
    }

    boolean initialized = false;
    boolean disabled = false;
    /**
     * Newest fence-signalled counter snapshot (may lag a few frames while the
     * GPU runs behind): {@code {exact live count, unculled translucent census}}
     * — the census counts ALPHA and MODEL particles (both feed the combined
     * translucent sort).
     */
    private final ParticleReadbacks readbacks = new ParticleReadbacks();
    private final StormBatchReadbacks stormBatches=new StormBatchReadbacks();
    private final StormProjectionGpu stormProjection=new StormProjectionGpu();
    private long committedGeneration;
    private ParticleHitRay submittedHitRay;
    private long lastCreationGeneration;
    private boolean poolMayBeAlive;
    private boolean frameIdle;

    /**
     * Counter ring slot of the last frame whose output pool was committed by a
     * swap — the ONLY slot update.comp may trust as the read buffer's exact
     * live count. Deriving that slot positionally ({@code current - 1}) breaks
     * when a frame aborts mid-flight: its reset/update passes have already
     * written a PARTIAL counter value into its own slot, and gating the next
     * frame's compaction on that garbage would let stale pool entries beyond
     * the true dense prefix resurrect as ghost particles. Pinning the gate to
     * the last GOOD slot keeps it matched to whatever pool the read side
     * actually holds. Every path that empties the pool ({@code init},
     * {@code clearParticles}) zeroes ALL counter slots, so a stale index left
     * behind by those paths safely reads a live count of 0.
     */
    private int lastGoodSlot = 0;
    /**
     * Sort buffer GL id holding the newest COMMITTED translucent permutation --
     * pointer-local {@code finalPerm} dies with runCompute, but the pack entity
     * merge hook runs inside renderLevel AFTER the AFTER_SKY compute commit and
     * BEFORE the AFTER_LEVEL draw phase, binding exactly the committed
     * permutation of this frame. -1 until the first sorted frame commits.
     */
    private int lastFinalPermId = -1;
    /** simFrame stamp of that newest committed permutation (consumption-age diagnostic). */
    private int lastFinalPermFrameSim = 0;
    volatile int liveDisplay = 0;
    volatile float scale = 1f;
    volatile int streamCount = 0;
    private int simFrame = 0;
    private int frameSeed = 0;
    /**
     * Per-frame handoff values computed by runCompute, consumed by runDraws —
     * the draws repeat the compute section's own guard so a frame where nothing
     * is alive still skips vertex work but keeps every buffer binding valid.
     */
    private int frameAliveEstimate = 0;
    private int frameEntryCount = 0;
    private boolean frameSorted = false;
    private int frameFinalPerm = -1;
    /** Scratch Proj*View and its 6 normalized frustum planes (keygen cull test). */
    private final Matrix4f projView = new Matrix4f();
    private final float[] frustumPlanes = new float[24];
    private final org.joml.Vector4f planeScratch = new org.joml.Vector4f();

    private ParticleRuntime() {
    }
    public void reloadPrograms() { ClientFrameCapture.INSTANCE.invalidate("particle program reload");com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime.INSTANCE.invalidate();this.programs.requestRebuild(); }

    /** Frees all GPU resources on client shutdown. Safe when never initialised. */
    public void closeResources() {
        var owner=com.iridium126.createmanaindustry.client.particles.engine.spatial.NativeStormSimulation.selected();
        if(owner!=null)owner.retire();
        stormProjection.close();
        com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime.INSTANCE.close();
        ClientFrameCapture.INSTANCE.invalidate("particle resources closed");
        replayHexInputs();
        try {
            this.blockEmitters.free();
            this.readbacks.close();
            this.stormBatches.close();
            this.gpu.free();
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] GPU free failed", e);
        }
        this.hexDirectStorage.close();
        this.directHexEmitter=this.sprayHexEmitter=-1;
        StormLodGpu.INSTANCE.close();feathers.close();
        this.programs.delete();
        ParticleDiagnostics.INSTANCE.close();
        try {
            this.spriteAtlas.free();
            this.allayAtlas.free();
            this.collisionBake.free();
            com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.reset();
        } catch (RuntimeException | LinkageError e) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] atlas/bake free failed", e);
        }
        this.pending.clear();retryInputs.clear();commitFrameInputs();
        this.streams.clear();
        this.cancelledStreamIds.clear();
        this.hexSprays.clear();
        this.hexParticles.clear();
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.invalidate();
        resetPoolState();
        GpuUniforms.clear();
        this.initialized = false;
        this.disabled = false;
    }
    public long currentGeneration(){return committedGeneration;}
    public void pollCommittedAttackBatches(){stormBatches.poll(gpu.identityEpoch());}

    /** Clears every particle, stream and queued request (render thread only). */
    private void dropAll() {
        replayHexInputs();
        this.pending.clear();retryInputs.clear();commitFrameInputs();
        this.streams.clear();
        this.cancelledStreamIds.clear();
        this.gpu.clearParticles();
        com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.invalidate();
        resetPoolState();
        AllayStormRuntime.INSTANCE.resetStormState();
        this.streamCount = 0;

    }


    /** Internal lifecycle boundary: discard snapshots only when identities actually change. */
    public void invalidateParticleIdentities() {
        this.readbacks.clear();
        invalidateStormTargetEdge();
        com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.dropPositionSnapshot();
        com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime.INSTANCE.dropHitSnapshots();
        this.gpu.invalidateIdentities();
    }

    private void resetPoolState() {
        feathers.reset();
        this.sharedFate.reset();
        this.partition.reset();
        PackageRuntime.INSTANCE.reset();
        this.liveDisplay = 0;
        com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime.INSTANCE.dropHitSnapshots();
        ModelInteractionRuntime.INSTANCE.reset();
        // member-keyed bursts must never resolve a stale identity -> slot
        // entry (close() frees the GPU first, hence the guard)
        if (this.initialized)
            invalidateParticleIdentities();
        this.readbacks.clear();
        this.lastFinalPermId = -1;
        this.lastFinalPermFrameSim = 0;
        this.committedGeneration = 0;
        this.lastCreationGeneration = 0;
        this.poolMayBeAlive = false;
    }
    private final FrameCandidate frameCandidate=new FrameCandidate();
    /** Reused transaction; only the scene composition root sequences its fixed phases. */
    public FrameCandidate prepareInput(Camera camera,Matrix4fc view,Matrix4fc projection,DeltaTracker delta,
            com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator coordinator,float scale,long simulationTick) {
        var frame=frameCandidate;
        frame.camera=camera;frame.view=view;frame.projectionMatrix=projection;frame.deltaTracker=delta;frame.frameCoordinator=coordinator;
        frame.snapshot=null;frame.stormBatch=null;frame.targetAim=null;frame.targetIdentity=null;
        frame.attackBatch=false;frame.spatial=null;frame.committed=false;frame.step=0;frame.simulationTick=simulationTick;
        frame.nativeStorm=com.iridium126.createmanaindustry.client.particles.engine.spatial.NativeStormSimulation.selected();
        this.scale=scale;
        ParticleChainCapture.INSTANCE.beginRequested(com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken(),committedGeneration+1,java.util.Map.of("input_generation",committedGeneration,"identity_epoch",gpu.identityEpoch(),"capacity",gpu.capacity()));
        try {frame.hasWork=frame.input();return frame;}
        catch(RuntimeException|LinkageError failure){frame.close();throw failure;}
    }
    public final class FrameCandidate implements AutoCloseable {
        private Camera camera;private Matrix4fc view,projectionMatrix;private DeltaTracker deltaTracker;
        private com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator frameCoordinator;
        private boolean hasWork,committed;private int step;private long simulationTick;
        public boolean committed(){return committed;}
        private com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialScene spatial;
        private int slot;
        private int cap;
        private float dt;
        private int directHexCount;
        private int consumedSprays;
        private int entryCount;
        private int totalSpawn;
        private int blockSpawnBound;
        private int ringId;
        private int damageCount;
        private boolean fateActive;
        private boolean partitionActive;
        private boolean createsParticles;
        private boolean sorted;
        private boolean packageStaged,modelMembersPrepared;
        private int finalPerm;
        private long nextGeneration;
        private long stormVersion;
        private StormShadowSnapshot shadowInput;
        private long packageVersion;
        private com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication candidate;
        private ParticleReadbacks.Slot snapshot;
        private StormBatchReadbacks.Batch stormBatch;
        private com.iridium126.createmanaindustry.client.particles.engine.spatial.NativeStormSimulation nativeStorm;
        private Vec3 targetAim;
        private LocalPlayer targetIdentity;
        private boolean attackBatch;
        private com.iridium126.createmanaindustry.client.render.scene.ParticleRenderFrame drawInput;
        public boolean hasWork(){return hasWork;}
        private void require(int expected) {
            var phase=expected==0?com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Phase.SIMULATION
                    :expected==1?com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Phase.GENERATION
                    :expected==5?com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Phase.PUBLISHED
                    :com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Phase.CULL_SORT;
            if(step!=expected||frameCoordinator.phase()!=phase)throw new IllegalStateException("Particle GPU pass order: "+step+" expected "+expected);
        }
        private boolean input() {

        checkpointInputs();
        writes.begin(committedGeneration+1,gpu.identityEpoch(),gpu.capacity());
        ParticleRuntime.this.frameSeed++;
        ParticleRuntime.this.frameIdle = false;

        // Draw-phase handoff resets: stale values from an aborted frame never
        // reach runDraws — the frameArmed gate is set only at this phase's
        // success tail, but keeping the handoff consistent anyway makes every
        // consumer read a value that belongs to THIS invocation.
        ParticleRuntime.this.frameEntryCount = 0;
        ParticleRuntime.this.frameAliveEstimate = 0;
        ParticleRuntime.this.frameSorted = false;
        ParticleRuntime.this.frameFinalPerm = -1;

        // Arbitration belongs to this frame even if the preceding late draw threw.
        com.iridium126.createmanaindustry.client.render.scene.ParticleRenderer.INSTANCE.beginFrame();


        slot= (ParticleRuntime.this.lastGoodSlot + 1) % ParticleBuffers.COUNTER_RING;
        ParticleRuntime.this.simFrame++;

        // 0. Refresh stale collision bakes + make CPU-side uploads visible.
        ParticleRuntime.this.collisionBake.tick();
        if (ParticleRuntime.this.collisionBake.metaDirty()) {
            ParticleRuntime.this.gpu.uploadBakeMeta(ParticleRuntime.this.collisionBake.meta());
            ParticleRuntime.this.collisionBake.markMetaClean();
        }
        if (ParticleRuntime.this.collisionBake.ready()) {
            // host texture writes must be visible to the compute pass
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }

        // 1. Drain requests from client thread.
        List<Burst> bursts = ParticleRuntime.this.frameBursts;
        bursts.clear();
        boolean doClear = false;

        Object item;
        while ((item = nextFrameInput()) != null) {
            drainedInputs.add(item);
            if (item instanceof Burst b) {
                bursts.add(b);
            } else if (item instanceof StreamReq sr) {
                if (!ParticleRuntime.this.cancelledStreamIds.remove(sr.handle().id())) {
                    ParticleRuntime.this.streams.add(new Stream(sr.handle().id(), sr.spec(), sr.origin(), sr.rate(), sr.duration()));
                }
            } else if (item instanceof StreamCancel sc) {
                long id = sc.handle().id();
                boolean removed = ParticleRuntime.this.streams.removeIf(stream -> stream.id == id);
                if (!removed)
                    ParticleRuntime.this.cancelledStreamIds.add(id);
            } else if (item instanceof AnimReq ar) {
                applyAnimation(ar.spec(), ar.animation());
            } else if (item instanceof Boolean) {
                doClear = true;

            }
        }
        pending.measure(retryInputs.size());
        hexParticles.measureQueue();
        if (doClear) {
            ParticleRuntime.this.streams.clear();
            ParticleRuntime.this.hexSprays.clear();
            ParticleRuntime.this.hexParticles.clear();
            ParticleRuntime.this.gpu.clearParticles();
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.invalidate();
            resetPoolState();
            writes.begin(committedGeneration+1,gpu.identityEpoch(),gpu.capacity());
            AllayStormRuntime.INSTANCE.resetStormState();
        }

        cap= ParticleRuntime.this.gpu.capacity();
        if (!doClear)pollCounterSnapshot(cap);
        // A storm kill queued THIS frame compacts the pool on the next update:
        // any hit snapshot (fresh or the one the poll just reloaded) would
        // mis-target a shifted particle, so drop it here. doClear already
        // cleared it via resetPoolState and skips the poll.
        if (AllayStormRuntime.INSTANCE.killPending())
            com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime.INSTANCE.dropHitKeys();

        dt=ParticleSimulationClock.STEP_SECONDS;
        if(PackageRuntime.INSTANCE.prepare(doClear))ParticleRuntime.this.gpu.beginBindings();
        // Shared clock: (gameTime mod 2^21)/20 — identical on every client (see
        // AllayStormRuntime's clock doc); drives the vortex phases and the
        // correction timestamps without any clock sync.
        Minecraft mcClock = Minecraft.getInstance();
        if (mcClock.level != null)
            AllayStormRuntime.INSTANCE.tickClock(simulationTick,0);

        // Producers share one bounded writer. No producer owns parallel command metadata.
        ParticleRuntime.this.emitCommands.clear();
        AllayStormRuntime.INSTANCE.scheduleSpawnRuns(ParticleRuntime.this.emitCommands);
        // continuous chased center: rewrite the storm header's anchor slot from
        // the interpolated center every frame (1 Hz snapshots, frame-rate motion)
        AllayStormRuntime.INSTANCE.refreshCenterHeader();
        // dive waves: expire dead slots, keep the target shafts baked (with
        // hysteresis), rebuild the wave uniform staging for both consumers
        AllayStormRuntime.INSTANCE.refreshWaves();
        com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient.INSTANCE.poll(ParticleRuntime.this.gpu.identityEpoch());
        if(nativeStorm==null)com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient.INSTANCE.prepare();

        for (Burst b : bursts) {
            int id = ensureEmitter(b.spec);
            if (id < 0)
                continue;
            ensureEmitterRuntime(id, b.spec, b.origin);
            int n = Math.max(1, Math.round(b.count * ParticleRuntime.this.scale));
            if (n <= 0 || ParticleRuntime.this.emitCommands.full())
                continue;
            ParticleRuntime.this.emitCommands.basic(id,n,b.origin,specSpawnLight(b.spec,b.origin));
        }

        // Vanilla combat particles: drain one-shot bursts (damage hearts) and
        // advance the tracking windows (crit / enchanted-hit stars). These
        // bypass the throttle scale — vanilla applies none and the counts are
        // tiny — and always target a pool particle (originRef) at the spawn
        // light sampled when the hit landed.
        ModelInteractionRuntime.INSTANCE.prepareVisuals(ParticleRuntime.this.emitCommands,dt);
        Iterator<Stream> it = ParticleRuntime.this.streams.iterator();
        while (it.hasNext()) {
            Stream s = it.next();
            s.elapsed += dt;
            if (s.duration > 0 && s.elapsed >= s.duration) {
                it.remove();
                continue;
            }
            double want = s.rate * ParticleRuntime.this.scale * dt + s.accumulator;
            int n = (int) want;
            s.accumulator = want - n;
            if (n <= 0)
                continue;
            int id = ensureEmitter(s.spec);
            if (id < 0 || ParticleRuntime.this.emitCommands.full())
                continue;
            ensureEmitterRuntime(id, s.spec, s.origin);
            ParticleRuntime.this.emitCommands.basic(id,n,s.origin,specSpawnLight(s.spec,s.origin));
        }
        // Direct inputs are a separate bounded batch, not one shared command per particle.
        if(hexInputWorld!=Minecraft.getInstance().level)hexParticles.clear();
        directHexCount=hexParticles.ready();
        // Sprays use one stable behavior header; per-batch pigment data follows the command table.
        consumedSprays=0;
        for (HexSpray hs : ParticleRuntime.this.hexSprays) {
            if(hexTick-hs.bornTick()<0)break;
            if (ParticleRuntime.this.emitCommands.full())
                break;
            EmitterSpec spec = HexSpecs.spraySpec();
            int id=sprayHexEmitter;
            if(id<0)break;
            if (!ParticleRuntime.this.emitCommands.parameterized(id,hs.count(),hs.x(),hs.y(),hs.z(),
                    hs.vx(),hs.vy(),hs.vz(),packHexParams(hs.fuzz(),hs.spread()),hs)) break;
            consumedSprays++;
        }

        ParticleRuntime.this.streamCount = ParticleRuntime.this.streams.size();


        ParticleRuntime.this.emitCommands.limitParticles(cap);
        entryCount=ParticleRuntime.this.emitCommands.size();totalSpawn=ParticleRuntime.this.emitCommands.particles();

        feathers.prepare(dt,ParticleRuntime.this.scale,ParticleRuntime.this,0);
        // CPU limits submission work only; GPU allocation and dispatch determine the exact live set.
        blockSpawnBound= ParticleRuntime.this.blockEmitters.spawnBound(dt, Math.max(0.0, Math.min(1.0, ParticleRuntime.this.scale)), cap);
        // Handoff for runDraws' empty-guard: final count after free-pool capping.
        ParticleRuntime.this.frameEntryCount = entryCount + (blockSpawnBound > 0 ? 1 : 0) + (directHexCount>0?1:0);

        // 4. Upload emit commands into the next ring slot + emitters.
        extractFrustum(projectionMatrix, view);
        ringId= ParticleRuntime.this.gpu.nextEmitBuffer();
        ParticleRuntime.this.gpu.uploadDirtyEmitters();
        if (entryCount > 0)
            ParticleRuntime.this.gpu.uploadEmits(ringId,ParticleRuntime.this.emitCommands.encode(ParticleRuntime.this.frameSeed));

        // 4b. Upload pending melee-damage entries (see enqueueDamage). Frames
        // without clicks skip the upload entirely; a frame with entries
        // uploads them and the NEXT frame uploads a zero count to clear.
        // Buffer discipline: absolute writes everywhere, then position=0 and
        // limit=used-bytes for the upload (LWJGL reads [position, limit)), and
        // the limit is restored to capacity afterwards -- absolute puts are
        // bounds-checked against LIMIT, so a lingering small limit would make
        // the next enqueueDamage throw IndexOutOfBoundsException (and an
        // un-restored position would skip the header in the upload).
        var damageInputs=ModelInteractionRuntime.INSTANCE.prepareDamage();
        damageCount=ModelInteractionRuntime.INSTANCE.pendingDamageCount();
        if(damageInputs!=null) {
            ParticleRuntime.this.gpu.damageApprovalCount(damageCount);
            ParticleRuntime.this.gpu.uploadDamageQueue(damageInputs);
        }

        // Every material submits indirect draws. Empty GPU counts perform no particle work.
        fateActive= ParticleRuntime.this.sharedFate.prepare(camera.getPosition(), 0);
        partitionActive= ParticleRuntime.this.partition.prepare(camera.getPosition());
        createsParticles= nativeStorm!=null&&AllayStormRuntime.INSTANCE.active()
                ||feathers.creates() || totalSpawn > 0 || directHexCount>0 || blockSpawnBound > 0 || fateActive || partitionActive
                || PackageRuntime.INSTANCE.hasInput();
        if (!ParticleRuntime.this.poolMayBeAlive && !createsParticles && !com.iridium126.createmanaindustry.client.allaystorm.lod.StormLodClient.active()) {
            ParticleRuntime.this.frameIdle = true;
                commitFrameInputs();feathers.idle();writes.retire();
            return false;
        }
        sorted= true;
        ParticleRuntime.this.frameSorted = true;
        packageStaged=false;modelMembersPrepared=false;

            return true;
        }
        public void simulate() {require(0);
            long updateFrame=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken(),updateGeneration=committedGeneration+1;
            ParticleChainCapture.INSTANCE.inputs(java.util.Map.of("dt",dt,"time",AllayStormRuntime.INSTANCE.timeSec(),"capacity",cap,"input_generation",committedGeneration,"identity_epoch",gpu.identityEpoch(),"previous_counter_slot",lastGoodSlot,"counter_slot",slot,"damage_count",damageCount,"frame_seed",frameSeed));
            spatial=null;
            shadowInput=null;
            if(ModelShadowGroups.requested()){
                var shadowStorm=AllayStormRuntime.INSTANCE;var shadowCenter=shadowStorm.center();
                shadowInput=StormShadowSnapshot.of(updateGeneration,true,shadowCenter.x,shadowCenter.y,shadowCenter.z,shadowStorm.radius());
            }
            ParticleDiagnostics.INSTANCE.beginUpdate(ParticleDiagnostics.UpdateStage.INPUT_PREPARE,updateFrame,updateGeneration);boolean inputPrepared=false;try{
            ParticleDiagnostics.INSTANCE.beginPreparation(ParticleDiagnostics.UpdateStage.INPUT_STATE,updateFrame,updateGeneration);
            if(AllayStormRuntime.INSTANCE.active())ParticleRuntime.this.gpu.ensureStormIdentities();
            ParticleRuntime.this.gpu.ensureCarrierCapacity(AllayStormRuntime.INSTANCE.carrierCount());
            if(AllayStormRuntime.INSTANCE.active()||StarfallGpuBuffers.INSTANCE.bytes()>0)
                StarfallGpuBuffers.INSTANCE.prepare(ParticleRuntime.this.committedGeneration+1,nativeStorm!=null);
            ParticleDiagnostics.INSTANCE.endPreparation(true);
            ParticleDiagnostics.INSTANCE.mark("reset_grid");
            ParticleDiagnostics.INSTANCE.beginPreparation(ParticleDiagnostics.UpdateStage.INPUT_ALLOCATOR,updateFrame,updateGeneration);
            ParticleRuntime.this.gpu.beginComputeFrame(dt);
            gpu.allayAnimation().prepareAllocator(programs.modelPrepare());
            ParticleDiagnostics.INSTANCE.endPreparation(true);
            ParticleDiagnostics.INSTANCE.beginPreparation(ParticleDiagnostics.UpdateStage.INPUT_RESET,updateFrame,updateGeneration);
            ParticleRuntime.this.gpu.bindDispatch();
            ParticleRuntime.this.gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING, ParticleRuntime.this.lastGoodSlot);
            ParticleRuntime.this.gpu.bindCounter(3, slot);
            ParticleRuntime.this.gpu.bindIndirect(2);
            ParticleRuntime.this.gpu.bindPopulationRead(ParticleRuntime.this.programs.prepareDispatch());
            gpu.bindOrdinaryRead(programs.prepareDispatch(),false,committedGeneration);
            ParticleRuntime.this.gpu.bindPopulationWrite(ParticleRuntime.this.programs.prepareDispatch(),committedGeneration+1);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(ParticleRuntime.this.programs.prepareDispatch(),loc(ParticleRuntime.this.programs.prepareDispatch(),"uCarrierLimit"),ParticleRuntime.this.gpu.carrierCapacity());
            ParticleDispatch.prepare(ParticleRuntime.this.programs, 0);
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(ParticleRuntime.this.programs.reset());
            ParticleRuntime.this.gpu.bindPopulationWrite(ParticleRuntime.this.programs.reset(),committedGeneration+1);
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bind(ParticleRuntime.this.programs.reset(),true);
            ParticleRuntime.this.gpu.bindIndirect(2);
            ParticleRuntime.this.gpu.bindCounter(3, slot);
            // reset.comp also re-arms the hit query to MISS -- EVERY binding a
            // pass declares must be bound at its dispatch (unbindShaders() has
            // cleared 0..17 since last frame; an unbound write is silently
            // dropped, which latched the hit key after its first real hit)
            ParticleRuntime.this.gpu.bindHit();
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1, 1, 1);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
            ParticleDiagnostics.INSTANCE.endPreparation(true);

            if(damageCount>0){
                ParticleDiagnostics.INSTANCE.beginPreparation(ParticleDiagnostics.UpdateStage.INPUT_DAMAGE,updateFrame,updateGeneration);
                int validation=programs.damagePrepare();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(validation);
                gpu.bindParticleRead(0);gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING,lastGoodSlot);
                gpu.bindEmitters(5);gpu.bindDamage();gpu.bindIdentities(validation);gpu.bindPopulationRead(validation);
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(damageCount,1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                ParticleDiagnostics.INSTANCE.endPreparation(true);
            }

            // Boids spatial hash (storm swarms): built from the SAME committed
            // read pool + prev-counter slot update.comp consumes below. Gated to
            // frames where storm steering can actually execute: an active storm,
            // or a kill request still standing (a stop whose frame aborted
            // before the swap leaves its targets alive in the READ pool — the
            // re-fired uKillEmit below expires them the moment a frame commits,
            // and any thread reaching the storm blocks then finds THIS frame's
            // freshly built grid). Non-storm emitters carry motion mode 0 in
            // header slot 18 and never touch the structure, so skipping both the
            // heads clear and the dispatch on stormless frames is safe.
            if (nativeStorm==null&&AllayStormRuntime.INSTANCE.needsGridPass()) {
                ParticleDiagnostics.INSTANCE.beginPreparation(ParticleDiagnostics.UpdateStage.INPUT_GRID,updateFrame,updateGeneration);
                ParticleRuntime.this.gpu.clearGridHeads();
                com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(ParticleRuntime.this.programs.grid());
                ParticleRuntime.this.gpu.bindParticleRead(0);
                ParticleRuntime.this.gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING, ParticleRuntime.this.lastGoodSlot);
                ParticleRuntime.this.gpu.bindEmitters(5);
                ParticleRuntime.this.gpu.bindGrid();
                ParticleRuntime.this.gpu.bindPopulationRead(ParticleRuntime.this.programs.grid());
                ParticleRuntime.this.gpu.dispatch(ParticleBuffers.STORM_DISPATCH);
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                ParticleDiagnostics.INSTANCE.endPreparation(true);
            }
            ParticleDiagnostics.INSTANCE.mark("update");
            inputPrepared=true;}finally{ParticleDiagnostics.INSTANCE.endUpdate(inputPrepared);}
            ParticleDiagnostics.INSTANCE.beginUpdate(ParticleDiagnostics.UpdateStage.PREPARE,updateFrame,updateGeneration);boolean prepareDone=false;int playerCount=0;
            try{
            playerCount=AllayStormRuntime.INSTANCE.needsGridPass()||gpu.allayAnimation().allocated()||feathers.needsPlayers()?AllayStormRuntime.INSTANCE.collectSyncedPlayers():0;
            if(AllayStormRuntime.INSTANCE.needsGridPass()||gpu.allayAnimation().allocated()||feathers.needsPlayers())gpu.uploadPlayerSnapshot(AllayStormRuntime.INSTANCE,playerCount);
            feathers.begin();feathers.players(playerCount);
            com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.prepare(ParticleRuntime.this.programs.update());
            if(nativeStorm==null&&AllayStormRuntime.INSTANCE.needsGridPass())spatial=com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime.INSTANCE.begin(simulationTick,AllayStormRuntime.INSTANCE.timeSec(),gpu.identityEpoch());
            gpu.beginBindings();
            prepareDone=true;}finally{ParticleDiagnostics.INSTANCE.endUpdate(prepareDone);}
            gpu.simulationParameters().upload(cap,dt,playerCount,collisionBake.ready());
            int first=nativeStorm==null&&com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient.INSTANCE.restoring()?0:1;
            for(int pass=first;pass<(nativeStorm==null?4:3);pass++){
                int updateProgram=switch(pass){case 0 -> programs.columnRecovery();case 1 -> programs.ordinaryUpdate();case 2 -> programs.modelUpdate();default -> programs.update();};
                ParticleDiagnostics.INSTANCE.beginUpdate(pass==0?ParticleDiagnostics.UpdateStage.RECOVERY:pass==1?ParticleDiagnostics.UpdateStage.GENERIC:ParticleDiagnostics.UpdateStage.MODEL,updateFrame,updateGeneration);boolean firstDone=false;
                try{
                com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(updateProgram);
                if(pass==0)ParticleRuntime.this.gpu.bindUpdateCapacity(updateProgram);
                if(pass==1)gpu.bindOrdinaryRead(updateProgram,false,committedGeneration);
                ParticleRuntime.this.gpu.bindIdentities(updateProgram);
                ParticleRuntime.this.gpu.bindParticleRead(0);
                ParticleRuntime.this.gpu.bindParticleWrite(1);
                if(pass==1||pass==3)ParticleRuntime.this.gpu.bindGrid();
                ParticleRuntime.this.gpu.bindCounter(3, slot);
                // NOT slot - 1: an aborted previous frame leaves its own counter
                // slot half-written; the read pool still belongs to the last
                // swapped (good) frame, so gate compaction on THAT frame's slot.
                ParticleRuntime.this.gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING, ParticleRuntime.this.lastGoodSlot);
                ParticleRuntime.this.gpu.bindEmitters(5);
                if(pass>=2)ParticleRuntime.this.gpu.bindDamage();
                ParticleRuntime.this.gpu.bindPlayers(); // all-player repulsion source
                if(pass==3){ParticleRuntime.this.gpu.bindCorrections();ParticleRuntime.this.gpu.bindMemberMap();}
                if (ParticleRuntime.this.collisionBake.ready()) {
                    ParticleRuntime.this.collisionBake.bind(0);
                    ParticleRuntime.this.gpu.bindBakeMeta();
                    com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.sampler(updateProgram,"uCollision",0);
                    if(pass==0)setIntUniform(updateProgram, "uCollisionOn", 1);
                } else {
                    if(pass==0)setIntUniform(updateProgram, "uCollisionOn", 0);
                }

                if(pass!=2)com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.bindCurrent(updateProgram);
                if(pass==1)feathers.bind(updateProgram);
                if(pass==0)setFloatUniform(updateProgram, "uDt", dt);
                if(pass==1){setUIntUniform(updateProgram,"uHexCurrentTick",hexTick);setIntUniform(updateProgram,"uHexTickReady",1);}
                setFloatUniform(updateProgram, "uTimeSec", AllayStormRuntime.INSTANCE.timeSec());
                // all synced players feed the repulsion (vanilla entity sync — no
                // extra packets); nearest 16 by distance to the storm anchor
                if(pass==0){setIntUniform(updateProgram, "uPlayerCount", playerCount);
                setUIntUniform(updateProgram, "uKillEmit", AllayStormRuntime.INSTANCE.killEmitId());
                // typhoon growth-law phases (see AllayStormRuntime's field doc):
                // the CPU integrates the R-dependent rates into two shared phase
                // scalars + their rates, so the shader never multiplies an
                // accumulated clock by a radius-dependent coefficient
                setFloatUniform(updateProgram, "uStormOmegaNow", AllayStormRuntime.INSTANCE.omegaNow());
                setFloatUniform(updateProgram, "uStormRotPhase", AllayStormRuntime.INSTANCE.rotPhase());
                setFloatUniform(updateProgram, "uStormConvPhase", AllayStormRuntime.INSTANCE.convPhase());
                setFloatUniform(updateProgram, "uStormConvRate", AllayStormRuntime.INSTANCE.convRate());}
                // dive-wave squads (staging rebuilt by refreshWaves above)
                if(pass==0||pass==3){
                    com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bind(updateProgram,true);
                    com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.uploadUniforms(updateProgram);
                    com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient.INSTANCE.bind(updateProgram);
                }
                if(pass==1)ClientFrameCapture.INSTANCE.simulationInput(updateFrame,AllayStormRuntime.INSTANCE.timeSec(),dt);
                if(pass==3&&spatial!=null)spatial.bindUpdate(updateProgram,simulationTick);else setIntUniform(updateProgram,"uSpatialEnabled",0);
                ParticleRuntime.this.gpu.dispatch(pass==0?0:pass==1?ParticleBuffers.ORDINARY_DISPATCH:pass==2?ParticleBuffers.MODEL_DISPATCH:ParticleBuffers.STORM_DISPATCH);
                if(pass==0)SpatialQueryRuntime.INSTANCE.submitted(GlWork.COLUMN_RECOVERY);
                if(pass==3&&AllayStormRuntime.INSTANCE.needsGridPass())SpatialQueryRuntime.INSTANCE.submitted(GlWork.STORM_STEP);
                AllayAnimationGpu.barrier();
                firstDone=true;}finally{ParticleDiagnostics.INSTANCE.endUpdate(firstDone);}
            }
            finishSimulation(playerCount);
        }
        private void finishSimulation(int playerCount){
            if(nativeStorm!=null){step=1;return;}
            long updateFrame=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken(),updateGeneration=committedGeneration+1;
            if(StarfallGpuBuffers.INSTANCE.hasDome()){
                ParticleDiagnostics.INSTANCE.beginUpdate(ParticleDiagnostics.UpdateStage.DOME_CONTACTS,updateFrame,updateGeneration);boolean domeDone=false;try{
                int domeProgram=ParticleRuntime.this.programs.domeContacts();
                for(int part:DomeBodyContacts.related(domeProgram))com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.bindCurrent(part);
                DomeBodyContacts.run(programs.grid(),domeProgram,gpu.particleReadBufferId(),gpu.counterBufferId(lastGoodSlot),
                        gpu.particleWriteBufferId(),gpu.counterBufferId(slot),gpu.gridBufferId(),AllayStormRuntime.INSTANCE.timeSec(),dt,
                        playerCount,StarfallGpuBuffers.INSTANCE,gpu.candidatePopulationBuffer(),gpu.dispatchBufferId());
                SpatialQueryRuntime.INSTANCE.submitted(GlWork.DOME_CONTACT);
                ParticleRuntime.this.gpu.bindParticleRead(0);ParticleRuntime.this.gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING,ParticleRuntime.this.lastGoodSlot);domeDone=true;}finally{ParticleDiagnostics.INSTANCE.endUpdate(domeDone);}
            }
            ParticleDiagnostics.INSTANCE.beginUpdate(ParticleDiagnostics.UpdateStage.DEPENETRATION,updateFrame,updateGeneration);boolean depenDone=false;try{
            if(StarfallGpuBuffers.INSTANCE.navigationReady()){
                StormDepenetration.run(programs.depenetrationPrepare(),programs.depenetrationSchedule(),programs.depenetration(),
                    AllayStormRuntime.INSTANCE.timeSec(),StarfallGpuBuffers.INSTANCE,gpu.dispatchBufferId(),gpu.dispatchBufferId());
                SpatialQueryRuntime.INSTANCE.submitted(GlWork.DEPENETRATION);
            }
            depenDone=true;}finally{ParticleDiagnostics.INSTANCE.endUpdate(depenDone);}
            if(StarfallGpuBuffers.INSTANCE.navigationReady()){
                ParticleDiagnostics.INSTANCE.beginUpdate(ParticleDiagnostics.UpdateStage.NAVIGATION,updateFrame,updateGeneration);boolean navDone=false;try{
                com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.bindCurrent(ParticleRuntime.this.programs.domePath());
                DomeGatherNavigation.run(ParticleRuntime.this.programs.domePath(),AllayStormRuntime.INSTANCE.timeSec(),StarfallGpuBuffers.INSTANCE,ParticleRuntime.this.gpu.dispatchBufferId());
                SpatialQueryRuntime.INSTANCE.submitted(GlWork.NAVIGATION);
                navDone=true;}finally{ParticleDiagnostics.INSTANCE.endUpdate(navDone);}
            }
            // uKillEmit is retired at the SUCCESS TAIL (after the pool swap), not
            // here: an aborted frame discards its output pool, so a stop whose
            // kill was consumed by this dispatch must refire next frame or the
            // untouched storm members would survive on a never-rebuilt grid.


            step=1;
        }
        public void generate() {require(1);
            if(nativeStorm!=null){
                try(var display=nativeStorm.display()){
                    stormProjection.stage(gpu,slot,lastGoodSlot,AllayStormRuntime.INSTANCE.nativeProjectionEmitter(),display);
                }
            }
            feathers.emit(slot,cap,ParticleRuntime.this.frameSeed);

            ParticleDiagnostics.INSTANCE.mark("emit");
            if (entryCount > 0) {

                com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(ParticleRuntime.this.programs.emit());feathers.bind(ParticleRuntime.this.programs.emit());
                com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bind(ParticleRuntime.this.programs.emit(),true);
                setFloatUniform(ParticleRuntime.this.programs.emit(),"uTimeSec",AllayStormRuntime.INSTANCE.timeSec());
            ParticleRuntime.this.gpu.bindIdentities(ParticleRuntime.this.programs.emit());
                ParticleRuntime.this.gpu.bindPopulationRead(ParticleRuntime.this.programs.emit());
                ParticleRuntime.this.gpu.bindParticleWrite(1);
                // combat spawn styles resolve their originRef against the
                // committed read pool (the target allay's live position)
                ParticleRuntime.this.gpu.bindParticleRead(0);
                ParticleRuntime.this.gpu.bindCounter(3, slot);
                ParticleRuntime.this.gpu.bindEmitBuffer(4, ringId);
                ParticleRuntime.this.gpu.bindEmitters(5);
                ParticleRuntime.this.gpu.bindMemberMap(); // combat styles resolve member-keyed origins through it
                setUIntUniform(ParticleRuntime.this.programs.emit(), "uDirectHexBatch", 0);
                setUIntUniform(ParticleRuntime.this.programs.emit(),"uHexCurrentTick",hexTick);
                setIntUniform(ParticleRuntime.this.programs.emit(),"uHexTickReady",1);
                setUIntUniform(ParticleRuntime.this.programs.emit(), "uTotalSpawn", totalSpawn);
                setUIntUniform(ParticleRuntime.this.programs.emit(), "uEmitCount", entryCount);
                setIntUniform(ParticleRuntime.this.programs.emit(),"uRecoverStorm",AllayStormRuntime.INSTANCE.glRecovery()?1:0);
                setUIntUniform(ParticleRuntime.this.programs.emit(),"uHexParameterBase",entryCount*ParticleBuffers.EMIT_ENTRY_FLOATS);

                // storm style 3 (analytic spawn): instance seed + the
                // growth-law phases (the spawn state IS the servo's
                // equilibrium at the current phase — see AllayStormRuntime)
                setFloatUniform(ParticleRuntime.this.programs.emit(), "uStormSeed", AllayStormRuntime.INSTANCE.seed());
                setFloatUniform(ParticleRuntime.this.programs.emit(), "uStormOmegaNow", AllayStormRuntime.INSTANCE.omegaNow());
                setFloatUniform(ParticleRuntime.this.programs.emit(), "uStormRotPhase", AllayStormRuntime.INSTANCE.rotPhase());
                setFloatUniform(ParticleRuntime.this.programs.emit(), "uStormConvPhase", AllayStormRuntime.INSTANCE.convPhase());
                setFloatUniform(ParticleRuntime.this.programs.emit(), "uStormConvRate", AllayStormRuntime.INSTANCE.convRate());
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(Math.max(1, (totalSpawn + 63) / 64), 1, 1);
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
            }

            if(directHexCount>0){
                int program=programs.emit();
                var records=hexParticles.encode(directHexEmitter,frameSeed);
                int buffer=hexDirectStorage.ensure((long)records.remaining()*4);
                GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,records);
                com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);
                gpu.bindParticleWrite(1);gpu.bindCounter(3,slot);gpu.bindEmitters(5);
                GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,4,buffer);
                gpu.beginBindings();
                setUIntUniform(program,"uTotalSpawn",directHexCount);
                setUIntUniform(program,"uEmitCount",directHexCount);
                setUIntUniform(program,"uDirectHexBatch",1);
                setUIntUniform(program,"uHexCurrentTick",hexTick);
                setIntUniform(program,"uHexTickReady",1);
                try{com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((directHexCount+63)/64,1,1);}
                finally{setUIntUniform(program,"uDirectHexBatch",0);}
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            }

            if (blockSpawnBound > 0) {
                int be = ParticleRuntime.this.programs.blockEmit();
                com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(be);
                ParticleRuntime.this.gpu.bindIdentities(be);
                ParticleRuntime.this.gpu.bindParticleWrite(1);
                ParticleRuntime.this.gpu.bindCounter(3, slot);
                ParticleRuntime.this.gpu.bindEmitters(5);
                ParticleRuntime.this.blockEmitters.uploadAndBind();
                setUIntUniform(be, "uEmitterCount", ParticleRuntime.this.blockEmitters.dispatchSize());

                setFloatUniform(be, "uDtScale", dt * (float) Math.max(0.0, Math.min(1.0, ParticleRuntime.this.scale)));
                setFloatUniform(be, "uSeed", (float) (ParticleRuntime.this.frameSeed & 0xffff));
                setUIntUniform(be, "uEmitterOffset", (int) (Integer.toUnsignedLong(ParticleRuntime.this.frameSeed * 16777619)
                        % ParticleRuntime.this.blockEmitters.dispatchSize()));
                Vec3 blockCamera = camera.getPosition();
                setFloatUniform(be, "uCamPos", (float) blockCamera.x, (float) blockCamera.y, (float) blockCamera.z);
                float range = (float) renderDistanceBlocks();
                setFloatUniform(be, "uRangeSquared", range * range);
                GL20.glUniform4fv(loc(be, "uFrustum"), ParticleRuntime.this.frustumPlanes);
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((ParticleRuntime.this.blockEmitters.dispatchSize() + 3) / 4, 1, 1);
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
            }

            if (fateActive) {
                ParticleRuntime.this.sharedFate.generate(ParticleRuntime.this, slot, view, projectionMatrix, ParticleRuntime.this.frustumPlanes, (float)ParticleRuntime.this.scale);
                ParticleRuntime.this.gpu.beginBindings();
                ParticleRuntime.this.gpu.bindDispatch();
            }
            if(partitionActive) {
                ParticleRuntime.this.partition.generate(ParticleRuntime.this,slot,view,projectionMatrix,ParticleRuntime.this.frustumPlanes,(float)ParticleRuntime.this.scale);
                ParticleRuntime.this.gpu.beginBindings();
            }
            if(PackageRuntime.INSTANCE.hasInput()){
                gpu.bindDispatchInputs(slot,lastGoodSlot);gpu.bindPopulationWrite(programs.prepareDispatch(),committedGeneration+1);gpu.bindModelMembers(programs.prepareDispatch());gpu.bindPopulationRead(programs.prepareDispatch(),true,committedGeneration+1);
                AllayAnimationGpu.barrier();ParticleDispatch.prepareModelPublication(programs,false,true);modelMembersPrepared=true;
            }
            packageVersion=PackageRuntime.INSTANCE.stage(ParticleRuntime.this.frustumPlanes,camera,(float)renderDistanceBlocks());
            packageStaged=packageVersion>=0;
            if(packageStaged) {
                // Package passes reuse bindings 0..13. Invalidate the owner's cached bindings.
                ParticleRuntime.this.gpu.beginBindings();
                ParticleRuntime.this.gpu.bindDispatch();ParticleRuntime.this.gpu.bindCounter(ParticleBuffers.COUNTER_BB,slot);
                ParticleRuntime.this.gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING,ParticleRuntime.this.lastGoodSlot);
            }

            // 6. keygen: GPU frustum cull + partition of both permutations. Runs in
            //    BOTH paths (it is the fast path's cull pass); it owns the draw
            //    counts, so update/emit never touch the indirect buffer.

            step=2;
        }
        private void filterPackageModels(){
            if(!packageStaged||!gpu.allayAnimation().allocated())return;
            for(int program:new int[]{programs.modelFilter(),programs.modelCompact()}){
                com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);gpu.bindModelMembers(program);gpu.bindPopulationRead(program,true,committedGeneration+1);gpu.bindParticleWrite(0);gpu.bindCounter(3,slot);gpu.bindEmitters(5);gpu.bindSort(7,gpu.sortBuffer(0));gpu.bindDispatch();gpu.dispatch(ParticleBuffers.MODEL_FILTER_DISPATCH);AllayAnimationGpu.barrier();
            }
            gpu.bindDispatchInputs(slot,lastGoodSlot);gpu.bindPopulationWrite(programs.prepareDispatch(),committedGeneration+1);gpu.bindPopulationRead(programs.prepareDispatch(),true,committedGeneration+1);
            ParticleDispatch.prepare(programs,4,true);
        }
        private void finishModelAnimation(){
            if(!gpu.allayAnimation().allocated())return;
            if(!gpu.allayAnimation().tickPending())return;
            int animate=programs.allayAnimate();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(animate);gpu.bindModelMembers(animate);
            gpu.bindPopulationRead(animate,true,committedGeneration+1);
            gpu.bindParticleWrite(0);gpu.bindCounter(3,slot);gpu.bindEmitters(5);gpu.bindPlayers();
            if(collisionBake.ready()){collisionBake.bind(0);gpu.bindBakeMeta();setIntUniform(animate,"uCollisionOn",1);}else setIntUniform(animate,"uCollisionOn",0);
            setIntUniform(animate,"uPlayerCount",AllayStormRuntime.INSTANCE.lastPlayerCount());
            setFloatUniform(animate,"uTimeSec",AllayStormRuntime.INSTANCE.timeSec());
            StarfallGpuBuffers.INSTANCE.bind(animate,true);StarfallGpuBuffers.INSTANCE.uploadUniforms(animate);
            com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.bindCurrent(animate);
            gpu.bindDispatch();gpu.dispatch(ParticleBuffers.CANDIDATE_MODEL_DISPATCH);AllayAnimationGpu.barrier();
        }

        public void cullSort() {require(2);

            finalPerm= -1;
            ParticleDiagnostics.INSTANCE.mark("keygen");
            // Shared Fate and package compute passes reuse the counter/indirect SSBO slots.
            // Publish from the actual particle counters, never their temporary state buffers.
            ParticleRuntime.this.gpu.bindDispatchInputs(slot, ParticleRuntime.this.lastGoodSlot);
            ParticleRuntime.this.gpu.bindPopulationWrite(programs.prepareDispatch(),committedGeneration+1);
            ParticleRuntime.this.gpu.bindModelMembers(programs.prepareDispatch());
            ParticleRuntime.this.gpu.bindPopulationRead(programs.prepareDispatch(),true,committedGeneration+1);
            AllayAnimationGpu.barrier();
            ParticleDispatch.prepareModelPublication(programs,packageStaged,!modelMembersPrepared);
            filterPackageModels();finishModelAnimation();
            int aliveEstimate = cap; // Draw counts and dispatch bounds live on the GPU.
            ParticleRuntime.this.frameAliveEstimate = aliveEstimate; // draw-phase handoff
            {
                // The ordinary census emits the compact list and feather grounding.
                // Camera coverage, carrier collection and sorting belong to display.
                int census=programs.ordinaryCensus();GpuBindings.program(census);
                feathers.bind(census);gpu.beginOrdinaryCensus(census,committedGeneration+1);
                gpu.bindDispatch();gpu.bindParticleWrite(0);gpu.bindCounter(3,slot);gpu.bindEmitters(5);
                setUIntUniform(census,"uUpper",cap);setUIntUniform(census,"uPackageStaged",packageStaged?1:0);
                gpu.dispatch(1);AllayAnimationGpu.barrier();
            }
            ParticleRuntime.this.frameFinalPerm = finalPerm; // draw-phase handoff (-1 = fast path)
            // 6b. Continuous crosshair hit query (player melee targeting): one
            // tiny dispatch over the freshly written pool; the 8-byte result
            // (nearest allay under the crosshair + its HP) rides this frame's
            // fence back to the CPU, so a click never stalls the pipeline.
            ParticleDiagnostics.INSTANCE.mark("queries");
            submittedHitRay=null;
            dispatchHitQuery(camera, slot, cap);

            // 6c. Authority-client readback: storm members within melee reach
            // of any synced player, for the position-correction stream. The
            // snapshot rides the same fence back and is sent at the next poll.
            // The dispatch bound comes from THIS engine's pool census — pool
            // bookkeeping stays on the skeleton side of the seam.
            int stormUpper = cap;
            if(nativeStorm==null)com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.dispatchStormPosReadback(slot, cap, stormUpper);
            feathers.ground();ParticleRuntime.this.gpu.beginBindings();
            // 6d. Wave-contact detection: only while a wave targets the LOCAL
            // player; the staging buffer rides the same frame fence back.
            targetIdentity=Minecraft.getInstance().player;
            if(nativeStorm==null&&targetIdentity!=null){
                targetAim=targetIdentity.position().add(0,.5,0);
                boolean eligible=targetIdentity.isAlive()&&!targetIdentity.isSpectator();
                Vec3 previous=eligible&&waveTargetIdentity==targetIdentity&&waveTargetTick==simulationTick-1&&waveTargetEpoch==gpu.identityEpoch()?waveTargetPrevious:null;
                attackBatch=com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.dispatchWaveContactReadback(slot,targetAim,previous,eligible?targetIdentity.getId():-1,dt);
                if(!eligible)targetAim=null;
            }

            // 7. Census capture + commit markers. NO DRAW SUBMISSION happens in
            //    this phase any more — the old "Draw" section moved to runDraws
            //    (AFTER_LEVEL), so on pack-active frames the merge hook fires
            //    BETWEEN this phase and those draws, mid-renderLevel, and binds
            //    THIS frame's camera-culled permutation (was: previous frame's,
            //    the one-frame entry-lag this split exists to remove). Draw
            //    ordering inside runDraws still puts every depth-writing pass
            //    first (OPAQUE cutout sprites, then ONE merged model multi-draw)
            //    so early-Z rejects occluded fragments of later blended passes;
            //    ALPHA sprites follow back-to-front through the combined sort;
            //    additive stays LAST — order-independent, and ghost depth
            //    occludes glow behind cloaks.
            //
            //    Capture this frame's UNculled translucent census
            //    (counter.translucentCensus, maintained by keygen before the
            //    frustum test) into the counter ring spare, then fence the
            //    phase: next frame POLLS the fence and only reads the counters
            //    once the GPU has finished writing them (a raw readback is a
            //    pipeline stall, however lagged the slot).
            ParticleDiagnostics.INSTANCE.mark("capture");
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(ParticleRuntime.this.programs.capture());
            ParticleRuntime.this.gpu.bindIdentities(ParticleRuntime.this.programs.capture());
            ParticleRuntime.this.gpu.bindCounter(3, slot);
            // capture also publishes the hit-query winner's HP beside its key,
            // so it reads the same fresh pool plus the hit result buffer
            ParticleRuntime.this.gpu.bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
            ParticleRuntime.this.gpu.bindHit();
            // capture resolves the winner's STORM identity through the emitter
            // header's storm slot (p0.w alone is ambiguous at member 0) — every
            // binding a pass declares must be bound at its dispatch
            ParticleRuntime.this.gpu.bindEmitters(5);
            // Publish N_model into the metadata tail slot of a sort buffer. When
            // this phase ran a sort, that is THE committed permutation buffer
            // (the same id lastFinalPermId promotes below), so count and items
            // stay structurally same-generation; on the fast path there are no
            // MODEL items at all and scratch buffer zero is a harmless dummy.
            GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,
                    ParticleBuffers.SORTWRITE_BINDING,
                    finalPerm >= 0 ? finalPerm : ParticleRuntime.this.gpu.sortBuffer(0));
            setIntUniform(ParticleRuntime.this.programs.capture(), "uMetaSlot", ParticleRuntime.this.gpu.metaSlotIndex());
            setIntUniform(ParticleRuntime.this.programs.capture(),"uHitResolved",1);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1, 1, 1);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT
                    | GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);


            // 8. Swap the ping-pong pool — success only: an aborted phase keeps
            // the last fully-written pool as the next read source, and
            // the finally below restores the post-phase GL state instead.

            step=3;
        }
        public com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication preparePublication() {require(3);
            ParticleDiagnostics.INSTANCE.beginUpdate(ParticleDiagnostics.UpdateStage.PUBLICATION,com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken(),committedGeneration+1);boolean published=false;try{
            writes.requireCommittable();
            nextGeneration=Math.incrementExact(ParticleRuntime.this.committedGeneration);
            stormVersion=com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.candidateGeneration();
            writes.validate(writes.appendDomain(),gpu.identityEpoch());
            candidate=frameCoordinator.candidate(nextGeneration,ParticleRuntime.this.gpu.particleWriteBufferId(),Math.max(0,finalPerm),
                    ParticleRuntime.this.gpu.indirectBufferId(),ParticleRuntime.this.gpu.counterBufferId(slot),ParticleRuntime.this.gpu.candidatePopulationBuffer(),nextGeneration,stormVersion,packageVersion);
            // All driver work and version checks precede owner promotion.
            if(attackBatch)stormBatch=stormBatches.stage(gpu.waveContactBufferId(),simulationTick,gpu.identityEpoch(),
                    AllayStormRuntime.INSTANCE.identity(),targetIdentity);
            snapshot= ParticleRuntime.this.readbacks.freeSlot();
            if(snapshot!=null) {
                snapshot.published=false;snapshot.generation=nextGeneration;snapshot.epoch=ParticleRuntime.this.gpu.identityEpoch();snapshot.hitRay=submittedHitRay;
                snapshot.positions=com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.positionSnapshotPending();
                snapshot.gameTime=com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.positionSnapshotTime();
                snapshot.positionIdentity=com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.positionSnapshotIdentity();
                try {
                    ParticleRuntime.this.gpu.copySnapshot(slot,com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken(),snapshot);feathers.copySnapshot(snapshot);
                    snapshot.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                    if(snapshot.fence==0)throw new IllegalStateException("Particle snapshot fence failed");
                } catch(RuntimeException|LinkageError failure) {if(snapshot.fence!=0)ParticleRuntime.this.readbacks.release(snapshot);throw failure;}
            }

            drawInput=new com.iridium126.createmanaindustry.client.render.scene.ParticleRenderFrame(
                    nextGeneration,frameAliveEstimate,frameEntryCount,frameSorted,frameFinalPerm);
            step=4;published=true;return candidate;
            }finally{ParticleDiagnostics.INSTANCE.endUpdate(published);}
        }
        public long packageVersion(){return packageVersion;}
        public com.iridium126.createmanaindustry.client.render.scene.ParticleRenderFrame commit() {require(4);
            com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.submitted();
            ParticleRuntime.this.gpu.swap();
            ParticleRuntime.this.gpu.commitCameraOrigin(camera.getPosition());
            ParticleRuntime.this.sharedFate.commit();
            ParticleRuntime.this.partition.commit();
            if(packageStaged) {
                PackageRuntime.INSTANCE.commit();
                ParticleRuntime.this.gpu.beginBindings();
            }
            // The swap committed this frame's output as the next read source —
            // only NOW does this frame's counter slot become the authoritative
            // live count for update.comp (see lastGoodSlot). Assigned after the
            // swap so an abort anywhere above can never promote a slot whose
            // pool was discarded.
            ParticleRuntime.this.lastGoodSlot = slot;
            // Same success-only discipline: the sort permutation id is promoted
            // only when the frame fully committed (finalPerm is -1 on the fast
            // path, leaving the previous generation's binding in place). The
            // simFrame stamp beside it feeds the consumption-age diagnostic:
            // hooks in this frame report age 0. MODEL shadows build a separate
            // list directly from this same committed pool and exact counter.
            if (finalPerm >= 0) {
                ParticleRuntime.this.lastFinalPermId = finalPerm;
                ParticleRuntime.this.lastFinalPermFrameSim = ParticleRuntime.this.simFrame;
            }
            // Same success-only discipline retires uKillEmit: the just-swapped
            // pool is now the authoritative read source, so every kill target
            // provably compacted away. Until then the pending id keeps the
            // grid pass armed and refires the kill (see the update dispatch).
            AllayStormRuntime.INSTANCE.retireKill();

            // Compute phase committed in full: pool swapped, permutation
            // promoted, kill retired. Only NOW may the draw phase submit — any
            // failure above leaves frameArmed clear so runDraws is skipped and
            // the last fully-committed generation persists one more frame.
            ParticleRuntime.this.committedGeneration++;
            ParticleRuntime.this.gpu.commitPopulation(ParticleRuntime.this.committedGeneration);
            AllayStormRuntime.INSTANCE.commitStarfall();
            if(nativeStorm!=null)stormProjection.commit(nativeStorm);
            if(spatial!=null){spatial.publish(gpu.particleReadBufferId(),gpu.counterBufferId(slot),gpu,committedGeneration);gpu.beginBindings();}
            if (createsParticles) {
                ParticleRuntime.this.lastCreationGeneration = ParticleRuntime.this.committedGeneration;
                ParticleRuntime.this.poolMayBeAlive = true;
            }

            feathers.commit(ParticleRuntime.this.committedGeneration);
            waveTargetPrevious=targetAim;waveTargetTick=simulationTick;waveTargetIdentity=targetIdentity;waveTargetEpoch=gpu.identityEpoch();
            stormBatches.commit(stormBatch);
            committed=true;step=5;
            return drawInput;
        }
        public void consumeInputs() {require(5);
            ModelInteractionRuntime.INSTANCE.commitInputs();
            ParticleRuntime.this.hexParticles.commit();
            if(consumedSprays>0)ParticleRuntime.this.hexSprays.subList(0,consumedSprays).clear();
            commitFrameInputs();
            if(snapshot!=null) {
                snapshot.published=true;
                if(snapshot.positions)com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.dropPositionSnapshot();
            }
            step=6;
            var columnLevel=Minecraft.getInstance().level;
            if(nativeStorm==null&&columnLevel!=null)com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient.INSTANCE.capture(ParticleRuntime.this.programs.columnSnapshot(),ParticleRuntime.this.gpu.particleReadBufferId(),ParticleRuntime.this.gpu.counterBufferId(slot),ParticleRuntime.this.gpu.emitterBufferId(),ParticleRuntime.this.gpu.identityEpoch(),columnLevel.getGameTime(),AllayStormRuntime.INSTANCE.timeSec(),frameAliveEstimate);
            ParticleRuntime.this.gpu.beginBindings();
            if(packageStaged)com.iridium126.createmanaindustry.client.packages.authority.PackageAuthorityClient.committed(Integer.toUnsignedLong(ParticleRuntime.this.simFrame));
        }
        @Override public void close() {
            ParticleChainCapture.INSTANCE.finish(committed);
            if(spatial!=null&&!committed)spatial.abort();
            if(!committed&&snapshot!=null&&snapshot.fence!=0)readbacks.release(snapshot);
            if(!committed) {
                com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.submitted();
                com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.dropPositionSnapshot();
            }

            writes.retire();
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.abort();
            ParticleRuntime.this.gpu.restoreCommittedFrame();
            PackageRuntime.INSTANCE.abort();
            // Restore the exact post-phase state the success path leaves behind.
            // Blend/depth state is deliberately NOT touched here — the compute
            // phase issues no draws and cannot modify it; runDraws owns those
            // restores. Each group stays isolated: a secondary failure must not
            // mask the original exception nor skip the remaining groups.
            try {
                com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(0);
                GL30.glBindVertexArray(0);
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
            try {
                // SSBO bases 0-15 (permutations, sort data, counters, model geo…)
                ParticleRuntime.this.gpu.unbindShaders();
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            } catch (RuntimeException | LinkageError ignoredCleanup) {
                // see above
            }
        
        }
    }


    /**
     * 6b. Continuous crosshair hit query: one tiny dispatch over the freshly
     * written pool (see hit.comp) whose 16-byte result (packed key + winner
     * HP + storm member identity) rides the frame fence back to the CPU.
     * Skipped entirely when no translucent particle may
     * exist -- every hittable allay is translucent, so an empty translucent
     * census means nothing to hit and the frame costs nothing.
     */
    private void dispatchHitQuery(Camera camera, int slot, int cap) {
        int upper = cap;
        if (upper <= 0)
            return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null)
            return;
        int hg = this.programs.hit();
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(hg);
        // the freshly written pool lives on the write side until the swap;
        // hit.comp declares it at PARTICLE_BB_WRITE
        this.gpu.bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
        this.gpu.bindCounter(3, slot);
        this.gpu.bindEmitters(5);
        this.gpu.bindHit();
        this.gpu.bindPopulationRead(hg,true,committedGeneration+1);
        Vec3 eye = player.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        submittedHitRay=new ParticleHitRay((float)eye.x,(float)eye.y,(float)eye.z,(float)look.x,(float)look.y,(float)look.z,(float)player.entityInteractionRange());
        setFloatUniform(hg, "uEye", (float) eye.x, (float) eye.y, (float) eye.z);
        setFloatUniform(hg, "uLook", (float) look.x, (float) look.y, (float) look.z);
        setFloatUniform(hg, "uReach", (float) player.entityInteractionRange());
        setIntUniform(hg,"uHitResolve",0);
        this.gpu.dispatch(ParticleBuffers.MODEL_DISPATCH);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        setIntUniform(hg,"uHitResolve",1);this.gpu.dispatch(ParticleBuffers.MODEL_DISPATCH);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    /**
     * Vanilla packed-light levels at {@code pos}, packed as blockLight +
     * 16·skyLight for the GPU lightmap sample (textured.vsh header lightMode).
     * Combat particles keep this spawn-time sample for their whole short life
     * — vanilla re-samples per tick, imperceptible over 4-20 ticks.
     */
    private static float lightSample(ClientLevel level, Vec3 pos) {
        int packed = LevelRenderer.getLightColor(level, BlockPos.containing(pos));
        return LightTexture.block(packed) + 16f * LightTexture.sky(packed);
    }

    /**
     * Spawn-time packed light for one classic (style-0) emit command: specs
     * carrying the lightmap flag (vanilla cherry leaves, same mechanism as the
     * combat particles) sample {@code LevelRenderer.getLightColor} at the
     * spawn origin; everything else keeps 0. Sampled HERE rather than at spawn
     * call time because emit commands are packed per frame on the render
     * thread, where the ClientLevel is guaranteed valid.
     */
    private float specSpawnLight(EmitterSpec spec, Vec3 origin) {
        if (!spec.lightmap || origin == null)
            return 0f;
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0f : lightSample(level, origin);
    }

    /** Consume completed staging slots in generation order; counts are diagnostic only. */
    private void pollCounterSnapshot(int cap) {
        ParticleReadbacks.Slot snapshot;
        while ((snapshot = this.readbacks.completed()) != null) {
            try {
                if (snapshot.epoch != this.gpu.identityEpoch()) continue;
                ParticleDiagnostics.INSTANCE.readback(this.committedGeneration - snapshot.generation);
                ByteBuffer data = snapshot.data;feathers.observe(snapshot);
                if(snapshot.profile)ParticleDiagnostics.INSTANCE.simulationCounts(snapshot.profileFrame,snapshot.generation,
                        data.getInt(ParticleReadbacks.PROFILE_SIM_OFFSET),data.getInt(ParticleReadbacks.PROFILE_SIM_OFFSET+28)==0
                        ||Integer.toUnsignedLong(data.getInt(ParticleReadbacks.PROFILE_SIM_OFFSET+32))+(Integer.toUnsignedLong(data.getInt(ParticleReadbacks.PROFILE_SIM_OFFSET+36))<<32)!=snapshot.generation?-1:data.getInt(ParticleReadbacks.PROFILE_SIM_OFFSET+16));
                this.liveDisplay = Math.max(0, Math.min(cap, data.getInt(0)));
                // Only an empty snapshot covering every subsequent creation proves idle.
                this.poolMayBeAlive = this.liveDisplay > 0 || snapshot.generation < this.lastCreationGeneration;
                int member = data.getInt(24);

                // Local MODEL damage uses the stable token; pool indices are never reused as identity.
                ModelInteractionRuntime.INSTANCE.onHitReadback(data.getInt(16), data.getInt(20), member, data.getInt(28),snapshot.hitRay,snapshot.generation);
                if (snapshot.positions) com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.acceptPositionSnapshot(snapshot.positionIdentity, snapshot.gameTime,
                        ParticleReadbacks.entries(data, ParticleReadbacks.STORM_OFFSET,
                                ParticleBuffers.STORMPOS_CAP, ParticleBuffers.STORMPOS_ENTRY_FLOATS));
            } finally {
                this.readbacks.release(snapshot);
            }
        }
    }

    /**
     * Power-of-two sort far bound covering the configured fade end (>= 64).
     * The 256 logarithmic depth bands then carry ~constant relative
     * resolution: 2^(log2(far)/256) per band, e.g. ~1.9% at 128 blocks.
     */
    private static float sortFarBlocks() {
        int far = (int) Math.ceil(ClientConfig.particleFadeDistance + FADE_RAMP_BLOCKS);
        return Math.max(64f, Integer.highestOneBit(Math.max(1, far - 1)) << 1);
    }

    /**
     * Extracts the six frustum planes of Proj*View into {@link #frustumPlanes}
     * for keygen's per-particle sphere test, using JOML's built-in
     * Gribb–Hartmann extraction (PLANE_NX..PLANE_PZ; inside points satisfy
     * dot(n,p)+d >= 0). Hand-rolling this with mAB() accessors is a convention
     * trap — JOML's mAB is column-A/row-B, and mixing up rows/columns yields
     * garbage planes that cull everything. The view matrix is the camera
     * rotation, so plane distances are measured against camera-relative
     * positions — matching the shader convention
     * {@code uView * vec4(worldPos - uCamPos, 1)}.
     */
    private void extractFrustum(Matrix4fc projectionMatrix, Matrix4fc view) {
        Matrix4f m = this.projView.set(projectionMatrix).mul(view);
        for (int i = 0; i < 6; i++) {
            m.frustumPlane(i, this.planeScratch);
            float a = this.planeScratch.x;
            float b = this.planeScratch.y;
            float c = this.planeScratch.z;
            float d = this.planeScratch.w;
            float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c);
            int o = i * 4;
            this.frustumPlanes[o] = a * inv;
            this.frustumPlanes[o + 1] = b * inv;
            this.frustumPlanes[o + 2] = c * inv;
            this.frustumPlanes[o + 3] = d * inv;
        }
    }

    /** Allocates (or returns) the GPU emitter header id for a spec, cached by equals. */
    public int ensureEmitter(EmitterSpec spec) {
        Integer existing = this.emitterIds.get(spec);
        if (existing != null)
            return existing;
        if (this.emitterIds.size() >= MAX_EMITTERS) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] emitter header limit ({}); request dropped", MAX_EMITTERS);
            return -1;
        }
        int id = this.emitterIds.size();
        this.emitterIds.put(spec, id);
        // MODEL particles die by HP: their header carries the poof emitter id
        // (deathEmitId, header 16.x) so update.comp can spawn the vanilla death
        // poof at the corpse's GPU-only expiry position. The poof spec itself is
        // OPAQUE, so this recursion terminates after one level.
        if (spec.type.material() == ParticleTypes.Material.MODEL) {
            float[] h = spec.packed().clone();
            h[CombatSpecs.HDR_DEATH_EMIT] = ensurePoofEmitter();
            this.gpu.setEmitterHeader(id, h);
        } else {
            this.gpu.setEmitterHeader(id, spec.packed());
        }
        return id;
    }

    /**
     * Engine-private combat spec ({@link CombatSpecs}): ensure + apply the
     * combat header fields (spawnStyle/colorMode/lightMode/frameBase/growIn)
     * once on creation — {@code EmitterSpec.packed()} knows nothing about them.
     * Also loads the sprite atlas HERE: combat particles are emitted through
     * the tracking/burst/death-chain paths, which bypass the burst/stream
     * emission that normally loads it — without this, the atlas texture never
     * comes into existence, bind(1) silently no-ops, and the quad samples the
     * stale unit-1 texture (renders as flat colour squares).
     */
    public int ensureCombatEmitter(EmitterSpec spec) {
        Integer existing = this.emitterIds.get(spec);
        if (existing != null)
            return existing;
        int id = ensureEmitter(spec);
        if (id >= 0) {
            this.spriteAtlas.ensureLoaded();
            this.gpu.setEmitterHeader(id, CombatSpecs.packedHeader(spec));
        }
        return id;
    }

    /** The death-poof emitter id (0 = none: emitter table full). */
    public float ensurePoofEmitter() {
        int id = ensureCombatEmitter(CombatSpecs.POOF);
        return id >= 0 ? (float) id : 0f;
    }
    public void resetWorld() {
        var owner=com.iridium126.createmanaindustry.client.particles.engine.spatial.NativeStormSimulation.selected();
        if(owner!=null)owner.retire();
        stormProjection.close();
        com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime.INSTANCE.invalidate();
        com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.reset();
        if(initialized){dropAll();collisionBake.reset();}
    }


    private void applyAnimation(EmitterSpec spec, EmitterSpec.Animation animation) {
        if (spec.type.material() != ParticleTypes.Material.MODEL)
            return;
        for (var e : this.emitterIds.entrySet()) {
            if (e.getKey().equals(spec)) {
                float[] h = spec.packedWithAnimation(animation.index());
                h[CombatSpecs.HDR_DEATH_EMIT] = ensurePoofEmitter(); // survive the rewrite
                this.gpu.setEmitterHeader(e.getValue(), h);
            }
        }
    }

    /**
     * Per-spawn runtime state for an emitter: lazy-loads the sprite atlas and
     * keeps the collision bake volume near the spawn site alive. The emitter
     * header itself stays position-free — update.comp picks the containing
     * bake slice per particle, so one spec can serve many spawn sites.
     */
    private void ensureEmitterRuntime(int id, EmitterSpec spec, Vec3 origin) {
        if(spec.type.id()==com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.TYPE||spec.type.id()==com.iridium126.createmanaindustry.client.particles.emitter.AllayGoldSpecs.TYPE)feathers.source(spec,origin);
        if (spec.type.features().contains(com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Feature.SPRITE_ATLAS)) {
            this.spriteAtlas.ensureLoaded();
        } else if (spec.type.features().contains(com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Feature.MODEL_ATLAS)) {
            this.allayAtlas.ensureLoaded();
        }
        if (spec.type.id()!=com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.TYPE && spec.collideMode != EmitterSpec.CollideMode.NONE && origin != null)
            this.collisionBake.ensure(origin);
    }

    /** Advances only the native Hex logical clock, including while no world frame is drawn. */
    public void nativeParticleTick() { hexTick++;hexParticles.tick(); }
    public record Statistics(int live,int capacity,int streams,float emissionScale) {}
    public Statistics statistics() { return new Statistics(liveDisplay,gpu.capacity(),streamCount,scale); }

    public boolean initialized() {
        return this.initialized;
    }

    public int capacity() {
        return this.gpu.capacity();
    }

    public boolean available() {
        return this.initialized && !this.disabled && this.programs.ready();
    }

    /** Distance where the particle shaders begin their configured fade. */
    public double fadeDistanceBlocks() {
        return ClientConfig.particleFadeDistance;
    }

    /**
     * Distance at which particle streams can be stopped: the configured fade
     * distance plus the same ramp used by the render/sort path.
     */
    public double renderDistanceBlocks() {
        return ClientConfig.particleFadeDistance + FADE_RAMP_BLOCKS;
    }

    /** Whether a spec's particles feed the combined translucent sort (ALPHA or MODEL). */
    public static boolean isTranslucent(EmitterSpec spec) {
        return spec.type.material() == ParticleTypes.Material.ALPHA || spec.type.material() == ParticleTypes.Material.MODEL;
    }    public void header(int emitter, float[] words) { gpu.setEmitterHeader(emitter, words); }
    public void correction(int member, float x, float y, float z, float arrival,
                           float vx, float vy, float vz, float strength) {
        if(AllayStormRuntime.INSTANCE.stageNativeCorrection(member,x,y,z,arrival,vx,vy,vz,strength))return;
        gpu.writeCorrection(member, x, y, z, arrival, vx, vy, vz, strength);
    }
    public void clearMembers() { gpu.clearMemberMap(); }
    public void prepareAppearance(Vec3 center) {
        allayAtlas.ensureLoaded();
        // Storm owns an independent complete collision atlas.
    }
    public void collisionColumn(double x, double y, double z) { collisionBake.ensureColumn(x, y, z); }
    public boolean collisionWindow(double x, double y, double z, boolean prefetch) {
        return collisionBake.helixWindow(x, y, z, prefetch);
    }

    public boolean capturePositions(int counterSlot, Vec3 anchor, int players) {
        int program = programs.stormPos();
        if (program == 0) return false;
        var pool = gpu;
        pool.clearStormPosCount();
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);
        pool.bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
        pool.bindParticleRead(ParticleBuffers.PARTICLE_BB_READ);
        pool.bindMemberMap();
        pool.bindCounter(3, counterSlot);
        pool.bindEmitters(5);
        pool.bindPlayers();
        pool.bindStormPos();
        pool.bindPopulationRead(program, true, currentGeneration() + 1);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setFloatUniform(program, "uAnchor", (float) anchor.x, (float) anchor.y, (float) anchor.z);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setIntUniform(program, "uPlayerCount", players);
        pool.dispatch(ParticleBuffers.STORM_DISPATCH);
        feedbackBarrier();
        return true;
    }

    private Vec3 waveTargetPrevious;
    private long waveTargetTick=Long.MIN_VALUE,waveTargetEpoch=Long.MIN_VALUE;
    private LocalPlayer waveTargetIdentity;
    public void invalidateStormTargetEdge(){
        waveTargetPrevious=null;waveTargetIdentity=null;
        AllayStormRuntime.INSTANCE.invalidateNativeTargetEdge();
    }
    public boolean captureWaveContacts(int counterSlot, int localMask, Vec3 aim,Vec3 previous,float time, float dt,
                                       StarfallGpuBuffers state) {
        int program = programs.waveContact();
        if (program == 0) return false;
        ParticleDiagnostics.INSTANCE.beginUpdate(ParticleDiagnostics.UpdateStage.FEEDBACK,com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken(),committedGeneration+1);boolean completed=false;try{
        var pool = gpu;
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);
        pool.bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
        pool.bindCounter(3, counterSlot);
        pool.bindEmitters(5);
        pool.clearWaveContactCount();
        pool.bindWaveContact();
        pool.bindParticleRead(ParticleBuffers.PARTICLE_BB_READ);
        pool.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING,lastGoodSlot);
        pool.bindMemberMap();
        state.bind(program,true);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setIntUniform(program,"uStormNavigationDiagnostics",1);
        pool.bindPopulationRead(program, true, currentGeneration() + 1);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setUIntUniform(program, "uLocalWaveMask", localMask);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setFloatUniform(program, "uLocalPlayerPos", (float) aim.x, (float) aim.y, (float) aim.z);
        boolean targetHistory=previous!=null;
        Vec3 previousAim=targetHistory?previous:aim;
        GpuUniforms.setFloatUniform(program,"uLocalPlayerPrevious",(float)previousAim.x,(float)previousAim.y,(float)previousAim.z);
        GpuUniforms.setIntUniform(program,"uLocalPlayerSweepEnabled",targetHistory?1:0);
        GpuUniforms.setIntUniform(program,"uContactHistoryEnabled",1);
        var local=Minecraft.getInstance().player;
        GpuUniforms.setIntUniform(program,"uContactTargetEnabled",local!=null&&local.isAlive()&&!local.isSpectator()?1:0);
        GpuUniforms.setUIntUniform(program,"uContactBatchStamp",simFrame==0?1:simFrame);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setFloatUniform(program, "uTimeSec", time);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setFloatUniform(program, "uDt", dt);
        state.uploadUniforms(program);
        if(localMask==0){ParticleDiagnostics.INSTANCE.call();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);}
        else {pool.bindDispatch();pool.dispatch(ParticleBuffers.STORM_DISPATCH);}
        SpatialQueryRuntime.INSTANCE.submitted(GlWork.ATTACK_BATCH);
        feedbackBarrier();
        completed=true;return true;
        }finally{ParticleDiagnostics.INSTANCE.endUpdate(completed);}
    }


    private static void feedbackBarrier(){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);}
    public boolean prepareResources() {
        if(disabled)return false;
        if (!this.initialized) {
            try {
            this.initialized = this.gpu.init(ClientConfig.particleMaxCount, MAX_EMITTERS);
            if (this.initialized)
                this.gpu.uploadModelGeometry(
                        AllayModelGeometry.VERTICES, AllayModelGeometry.INDICES,
                        AllayModelGeometry.BODY_OPAQUE_INDEX_COUNT,
                        AllayModelGeometry.OPAQUE_INDEX_COUNT);
            } catch (RuntimeException | LinkageError e) {
                this.initialized = false;
                CreateManaIndustry.LOGGER.error("[CMI particles] GPU init failed", e);
            }
            if (!this.initialized) {
                this.disabled = true;
                CreateManaIndustry.LOGGER.error(
                        "[CMI particles] GPU particle engine failed to initialise; disabled for this session.");
                return false;
            }
        }
        if (this.programs.needsRebuild()) {
            if (this.programs.rebuild()) {
                invalidateParticleIdentities();
                GpuUniforms.clear(); // every program id was recreated
                this.programs.configureStatic(this.gpu.capacity());
            }
        }
        if (!this.programs.ready())
            return false; // shaders not compiled yet (or compile failed) — retry on reload

        return true;
    }
    public boolean idle() { return frameIdle; }
    public void clearParticles() { if(initialized)dropAll(); }
    public void recoverFailedInputs() { writes.fail();restoreFrameInputs();replayHexInputs(); }
    public void flushHeaders() { gpu.uploadDirtyEmitters(); }
    public void bindHistory(int program){gpu.bindIdentities(program);}
    public long historyGeneration(){return committedGeneration+1;}
    public com.iridium126.createmanaindustry.client.gpu.frame.GpuWriteReservations.Range reserveOutput() { return writes.appendDomain(); }
    public void bind(com.iridium126.createmanaindustry.client.gpu.frame.GpuWriteReservations.Range reservation,int slot,int counterBinding,int particleBinding) {
        writes.validate(reservation,gpu.identityEpoch());gpu.bindCounter(counterBinding,slot);gpu.bindParticleWrite(particleBinding);
    }
    public long importPackages(com.iridium126.createmanaindustry.client.packages.render.PackagePoolGpu pool,int emitter,float[] frustum,Camera camera) {
        writes.validate(writes.appendDomain(),gpu.identityEpoch());
        var position=camera.getPosition();
        pool.stage(gpu.particleWriteBufferId(),gpu.counterBufferId(frameCandidate.slot),emitter,frustum,
                (float)position.x,(float)position.y,(float)position.z,
                !dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse());
        return pool.validateCandidate(gpu.particleWriteBufferId());
    }
}
