package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import org.lwjgl.opengl.GL45;

import com.iridium126.createmanaindustry.client.gpu.GpuCapabilities;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

/**
 * Owns all GPU resources of the particle engine: the double-buffered particle
 * SSBOs (64 B/particle = 4 vec4), the emit-command ring, the emitter header
 * SSBO, the indirect draw command buffer (five commands at a uniform 20-byte
 * stride: additive billboards, OPAQUE cutout sprites, the two model segments
 * sharing one multi-draw, ALPHA blended billboards), the counter ring, the
 * counting-sort data/histogram/offset buffers, and the collision bake-meta
 * SSBO — plus the packless vertex array used for the instanced draws.
 * <p>
 * All callers must be on the render thread with a current GL context. Lazily
 * created by {@code ParticleRuntime} on the first frame; effective capacity
 * is capped by {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE}.
 */
public final class ParticleBuffers implements com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement.Snapshot {
    private final SimulationParameters simulationParameters=new SimulationParameters();
    public SimulationParameters simulationParameters(){return simulationParameters;}
    private final int[] ordinaryRosters=new int[2];
    private final ByteBuffer ordinaryHeader=BufferUtils.createByteBuffer(16);
    private final long[] ordinaryGenerations={-1,-1},ordinaryEpochs={-1,-1};
    public void bindOrdinaryRead(int program,boolean candidate,long generation){
        int bank=candidate?1-readIndex:readIndex;
        boolean valid=ordinaryRosters[bank]!=0&&ordinaryGenerations[bank]==generation&&ordinaryEpochs[bank]==identityEpoch();
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uOrdinaryRosterEnabled"),valid?1:0);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uOrdinaryWriteEnabled"),0);
        if(valid)GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.ORDINARY_ROSTER_BINDING,ordinaryRosters[bank]);
    }
    public void beginOrdinaryCensus(int program,long generation){
        int bank=1-readIndex;
        ordinaryHeader.clear();ordinaryHeader.putInt(0).putInt(capacity).putInt((int)generation).putInt((int)(generation>>>32)).flip();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.namedBufferSubData(ordinaryRosters[bank],0,ordinaryHeader);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.ORDINARY_ROSTER_BINDING,ordinaryRosters[bank]);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uOrdinaryRosterEnabled"),0);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uOrdinaryWriteEnabled"),1);
        ordinaryGenerations[bank]=generation;ordinaryEpochs[bank]=identityEpoch();
    }
    private final AllayAnimationGpu allayAnimation=new AllayAnimationGpu();
    private boolean allayAnimationRequested;
    public AllayAnimationGpu allayAnimation(){return allayAnimation;}
    private int displayEmitter,displayAnimation,displayPoseStamp;
    private int displayVisibility;
    public void displayVisibility(int buffer){displayVisibility=buffer;}
    public int displayVisibility(){return displayVisibility;}
    public void bindVisibility(int program){
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uRtVisibleGroups"),displayVisibility!=0?1:0);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setIntUniform(program,"uRtVisibilityMeasure",
                displayVisibility!=0&&ClientFrameCapture.INSTANCE.collecting()?1:0);
        if(displayVisibility!=0)GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,29,displayVisibility);
    }
    private int displayPool,displayCommands,displayAdd,displayOpaque,displayPermutation,displayGroups;
    private float displayAlpha=-1;
    public void bindAllayAnimation(int program){
        var uniforms=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.currentCache();
        if(displayAnimation!=0){
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,uniforms.location(program,"uAllayAnimationEnabled"),1);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.sampler(program,"cmi_AllayAnimation",
                    com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.SAMPLER_ANIMATION_DRAW);
            GpuBindings.texture(com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.SAMPLER_ANIMATION_DRAW,GL31.GL_TEXTURE_BUFFER,displayAnimation);
        }else allayAnimation.bindDraw(program);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,uniforms.location(program,"uAllayPoseEnabled"),displayAnimation!=0&&displayPoseStamp!=0?1:0);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,uniforms.location(program,"uAllayPoseStamp"),displayPoseStamp);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,uniforms.location(program,"uAllayPoseBase"),com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.POSE_ANIMATION_ROWS);
        if(displayAlpha>=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,uniforms.location(program,"uAllayAnimationAlpha"),displayAlpha);
    }
    public void displayFrozenInputs(int emitters,int animation,int poseStamp){displayEmitter=emitters;displayAnimation=animation;displayPoseStamp=poseStamp;beginBindings();}
    public int particleDrawEmitterBufferId(){return displayEmitter!=0?displayEmitter:emitterSSBO;}
    public void clearDisplayResources(){AllayFeatherGpu.INSTANCE.clearDisplay();com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu.INSTANCE.clearDisplay();displayEmitter=displayAnimation=displayPoseStamp=displayVisibility=0;displayPool=displayCommands=displayAdd=displayOpaque=displayPermutation=displayGroups=0;displayAlpha=-1;beginBindings();}
    public void setDisplayResources(int pool,int commands,int add,int opaque,int permutation,int groups,float alpha){displayPool=pool;displayCommands=commands;displayAdd=add;displayOpaque=opaque;displayPermutation=permutation;displayGroups=groups;displayAlpha=alpha;beginBindings();}
    public int particleDrawBufferId(){return displayPool!=0?displayPool:particleReadBufferId();}
    public int displayPermutation(){return displayPermutation;}
    public int previousParticleBufferId(){return particleSSBOs[1-readIndex];}
    private net.minecraft.world.phys.Vec3 currentCameraOrigin=net.minecraft.world.phys.Vec3.ZERO,previousCameraOrigin=net.minecraft.world.phys.Vec3.ZERO;
    public void commitCameraOrigin(net.minecraft.world.phys.Vec3 origin){previousCameraOrigin=currentCameraOrigin;currentCameraOrigin=origin;}
    public net.minecraft.world.phys.Vec3 currentCameraOrigin(){return currentCameraOrigin;}
    public net.minecraft.world.phys.Vec3 previousCameraOrigin(){return previousCameraOrigin;}
    public int modelIndexCapacity(){return modelIndexCapacity;}
    public static final ParticleBuffers INSTANCE=new ParticleBuffers();

    public static final int VEC4_PER_PARTICLE = com.iridium126.createmanaindustry.client.particles.emitter.ParticleLayout.PARTICLE_VEC4;  // 4 vec4 = 16 floats
    /** Bytes per particle in the double-buffered particle SSBOs (4 vec4 × 4 floats × 4 B). */
    public static final int BYTES_PER_PARTICLE = com.iridium126.createmanaindustry.client.particles.emitter.ParticleLayout.PARTICLE_BYTES; // 64 B
    public static final int MAX_EMIT_COMMANDS = com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter.LIMIT;
    /**
     * Floats per emit command: a(origin.xyz, count) + b(emitterId, seed,
     * prefixOffset, originRef) + c(lightPacked, 0, 0, 0). {@code originRef} is
     * 0 for an absolute {@code origin.xyz}, else (particle index + 1) whose
     * pool position emit.comp resolves as the spawn origin (tracking bursts).
     * {@code lightPacked} = blockLight + 16·skyLight sampled at spawn; combat
     * particles carry it in p2.w for the lightmap sample in textured.vsh.
     */
    public static final int EMIT_ENTRY_FLOATS = com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter.WORDS;
    public static final int EMIT_PARAMETER_FLOATS = com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter.PARAMETER_WORDS;
    public static final int EMIT_STORAGE_FLOATS = MAX_EMIT_COMMANDS * (EMIT_ENTRY_FLOATS + EMIT_PARAMETER_FLOATS);
    /**
     * Emit-command ring depth. Deliberately aligned with {@link #COUNTER_RING}
     * (4): unlike the counter ring this ring uses ordered glBufferSubData writes when reusing
     * its oldest slot, so its margin against a CPU run-ahead overwriting a slot
     * the GPU is still reading must not be smaller than the counter ring's.
     */
    public static final int EMIT_RING_SIZE = 4;
    /** Emitter header in vec4 — mirrors EmitterSpec.VEC4_PER_EMITTER. */
    public static final int VEC4_PER_EMITTER = EmitterSpec.VEC4_PER_EMITTER;
    /**
     * Counter-buffer ring. Each frame's counters live in one slot; the CPU reads
     * the slot written by the previous frame (1 frame old, producing no
     * read-after-write hazard against this frame's reset). 4 slots give the
     * previous frame's value plenty of margin before it is reused.
     */
    public static final int COUNTER_RING = 4;
    /**
     * Number of draw commands in the indirect buffer: additive, OPAQUE
     * cutout billboards, model body-opaque segment, model CARRIER segment
     * (held item, instances from the carrier region), model translucent
     * segment, ALPHA blended billboards.
     */
    public static final int INDIRECT_COMMANDS = 6;
    /**
     * Uniform byte stride of every indirect command. ELEMENT commands
     * ({@code glDrawElementsIndirect}, commands 2/3) read the full
     * DrawElementsIndirectCommand — 5 uints: indexCount, instanceCount,
     * firstIndex, baseVertex, baseInstance. Arrays commands (0/1) read only
     * the first 16 bytes, so their 5th uint is padding. A uniform stride
     * keeps both the draw offsets and the compute shaders' flat-uint SSBO
     * view (field j of command i = index {@code 5*i + j}) trivially aligned.
     */
    public static final int INDIRECT_STRIDE = 20;
    /** Counting-sort passes over the 9-bit key (one, by design). */
    public static final int RADIX_PASSES = 1;
    public static final int SORT_GROUP_THRESHOLD = 65536;
    /**
     * Radix bin count. The sort key is 9 bits: bit {@link #SORT_TYPE_SHIFT}
     * selects the translucent item type (0 = MODEL, 1 = ALPHA sprite) and the
     * low byte carries the inverted log depth band. Binning over all 9 bits
     * makes the scatter place each type in its own CONTIGUOUS partition --
     * MODEL items at [0, N_model), ALPHA items at [N_model, N_total) -- so
     * every translucent draw command gets an exact instanceCount and no
     * vertex invocations are wasted filtering foreign-type items.
     */
    public static final int RADIX_BINS = 512;

    // The binding constants below are the SINGLE SOURCE OF TRUTH for the
    // GLSL side too: ParticlePrograms#commonPrelude generates #define lines
    // from them and injects the prelude into every shader source.
    /**
     * Read pool of the double-buffered particle SSBOs — update/gridbuild/keygen
     * (keygen via a WRITE-slot binding while pre-swap) source, and, after each
     * AFTER_SKY commit, ALSO every render pass's data source.
     */
    public static final int PARTICLE_BB_READ=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_PARTICLE_BB_READ;
    /**
     * Write pool of the double-buffered particle SSBOs — compute-phase output
     * target only (update/emit write it pre-swap). Since the frame split,
     * render passes must NEVER bind this side: by draw time the freshly written
     * generation already moved to the read side via {@link #swap()}.
     */
    public static final int PARTICLE_BB_WRITE=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_PARTICLE_BB_WRITE;
    public static final int INDIRECT_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_INDIRECT_BB;
    public static final int COUNTER_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_COUNTER_BB;
    public static final int EMIT_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_EMIT_BB;
    public static final int EMITTER_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_EMITTER_BB;
    // 6/7 = LSD radix sort data (read/write), rebound per pass
    // 8 = additive-only permutation (orderAdd) for the additive draw
    // 9 = radix histogram, 10 = radix offsets, 11 = collision bake meta
    public static final int SORTREAD_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_SORTREAD_BINDING;
    public static final int SORTWRITE_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_SORTWRITE_BINDING;
    public static final int ORDERADD_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_ORDERADD_BINDING;
    public static final int HIST_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_HIST_BINDING;
    public static final int OFFSET_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_OFFSET_BINDING;
    public static final int BAKEMETA_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_BAKEMETA_BINDING;
    /** Static baked model geometry (flat float array, MODEL particles). */
    public static final int MODELGEO_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_MODELGEO_BINDING;
    // 13 was RETIRED (former dense MODEL permutation; both model segments now
    // walk the combined sort array) and has been RE-PURPOSED for the boids
    // spatial-hash grid rather than renumbering anything else.
    /** Previous frame's counter slot, read by update.comp for the GPU-exact live count. */
    public static final int PREVCOUNTER_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_PREVCOUNTER_BINDING;
    /** Dense permutation of visible OPAQUE cutout billboards (keygen's fourth bucket). */
    public static final int ORDEROPAQUE_BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_ORDEROPAQUE_BINDING;
    /** Boids spatial-hash grid: heads table + next chains in ONE buffer. */
    public static final int GRID_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_GRID_BB;
    /** Spatial-hash table size in cells (power of two; masked hashing). */
    public static final int GRID_TABLE = 16384;
    /** CPU melee-damage queue (see ModelInteractionRuntime.enqueueDamage). */
    public static final int DAMAGE_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_DAMAGE_BB;
    /** Per-frame crosshair hit-query result (see hit.comp / capture.comp). */
    public static final int HIT_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_HIT_BB;
    /**
     * Storm player-repulsion source: up to {@link #MAX_STORM_PLAYERS} vec4
     * player positions (xyz), uploaded every frame the storm can steer. All
     * SYNCED players repel on every client (positions come from vanilla
     * entity sync — no extra packets), so the force field is near-identical
     * across clients; only each client's own local player is exact-to-itself.
     */
    public static final int PLAYERS_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_PLAYERS_BB;
    /**
     * Storm correction slots, indexed by member identity: two vec4 per member
     * ({target.xyz, arrivalTime}, {targetVel.xyz, strengthScale}; 0 = invalid).
     * Written sparsely by network snapshots / hit corrections, read O(1) by
     * update.comp; expiry is by timestamp, so slots never need clearing.
     */
    public static final int CORRECTION_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_CORRECTION_BB;
    /** Authority readback staging (see stormpos.comp): uint count + entry floats. */
    public static final int STORMPOS_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_STORMPOS_BB;
    /**
     * Storm member identity -> pool-slot map, rebuilt by update.comp every
     * frame (slot+1 per member present in the input pool, 0 = absent; sized
     * cap * 4 B like the correction slots). Combat spawn commands carry
     * memberIdx+1 in command word c.z and resolve their origin source through
     * this map (emit.comp styles 1/2), so origin tracking survives pool
     * recompaction across frames — the last pool-index consumer that outlives
     * a frame goes member-addressed, matching the damage queue / corrections /
     * hit reporting. Cleared on every generation-killing storm transition and
     * on pool resets; identity &gt;= capacity can never hold a slot (same
     * guard as the correction slots).
     */
    public static final int MEMBERMAP_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_MEMBERMAP_BB;
    /**
     * Wave-contact self-report staging (see wavecontact.comp): uint count +
     * entries of 5 floats {memberIdx, waveId, pos.xyz}. The result rides the
     * frame fence back; the runtime dedupes per member per wave and ships
     * ServerboundStormWaveContactPacket reports.
     */
    public static final int WAVECONTACT_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_WAVECONTACT_BB;
    /**
     * Second sort buffer, bound ONLY during the keygen dispatch as the
     * held-item carrier sink: keygen dual-writes every carrier instance into
     * sortBuffer(0) (BIND_SORT_WRITE) and sortBuffer(1) (this binding),
     * because which buffer ends up committed differs by path — the radix
     * scatter moves the ITEMS buf0 -> buf1 but never touches the carrier
     * region, so the sorted path's final permutation (buf1) needs the
     * keygen-written carrier entries there, while the fast path's draw binds
     * buf0. Dual-writing keeps both buffers structurally same-generation.
     */
    public static final int CARRIERSINK_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_CARRIERSINK_BB;
    public static final int POPULATION_READ_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_POPULATION_READ_BB,POPULATION_WRITE_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_POPULATION_WRITE_BB;
    public static final int POPULATION_HEADER_BYTES=32,MODEL_INDEX_LIMIT=262144,STORM_INDEX_LIMIT=131072;
    public static final int MODEL_DISPATCH=3,STORM_DISPATCH=4,CANDIDATE_MODEL_DISPATCH=5,MODEL_FILTER_DISPATCH=6,ORDINARY_DISPATCH=7,DISPATCH_COMMANDS=8;
    /** Entries per wave-contact readback (the touches in ONE frame; tiny). */
    public static final int WAVECONTACT_CAP = 4 * 4096;
    /** Floats per wave-contact entry: memberIdx, waveId, pos.xyz. */
    public static final int WAVECONTACT_ENTRY_FLOATS = 6;
    /**
     * Storm-member identity packing in p0.w: bits 0..17 hold memberIdx+1
     * (max 131072 = 2^17, so bit 17 IS used), and the next free bit —
     * {@link #MEMBER_LATCH_BIT}, bit 18 — is the dive-wave pass-completed
     * latch (set once, on the first contact frame). The packed value stays
     * float-exact (max 393216 &lt;&lt; 2^24). EVERY identity extraction must
     * mask with {@link #MEMBER_IDX_MASK}; the size-multiplier consumers are
     * exempt (they take the constant 1.0 on the storm branch).
     */
    public static final int MEMBER_IDX_BITS = 18;
    public static final int MEMBER_IDX_MASK = (1 << MEMBER_IDX_BITS) - 1;
    public static final int MEMBER_LATCH_BIT = 1 << MEMBER_IDX_BITS;
    /** Initial player staging capacity; grows to include every eligible synced player. */
    public static final int MAX_STORM_PLAYERS = 16;
    /** Base ordinary-MODEL carrier allowance. Active starfall rosters grow the tail on demand. */
    public static final int CARRIER_CAP = 4 * 4096;
    private int carrierCapacity=CARRIER_CAP;
    public int carrierCapacity(){return carrierCapacity;}
    /** Entries per authority readback snapshot (nearest-to-players cap). */
    public static final int STORMPOS_CAP = 256;
    /** Floats per readback entry: memberIdx, pad, pos.xyz, vel.xyz. */
    public static final int STORMPOS_ENTRY_FLOATS = 8;
    /** Damage-queue capacity in entries (player melee clicks, a few per frame). */
    public static final int DAMAGE_QUEUE_CAP = 64;
    /**
     * CPU-side layout of the damage queue upload: an std430 uvec4 header
     * (x = entry count) followed by 64-byte entries of 16 words each:
     * key/damage/knockback/light/flags/token/validation, then click eye/reach/look/ray-present.
     * {@code key} is the
     * diagnostic POOL INDEX for local damage (matched only by stable token and click ray,
     * client-authoritative) or the MEMBER INDEX for storm damage; flags bit 0
     * selects the interpretation and bit 1 carries the server-decided death
     * bit (storm path only — a client must never decide a storm member's
     * death from its local HP mirror). The light is the vanilla packed-light
     * levels sampled at the hit position; update.comp stashes it into the
     * target's p1.w so the GPU death chain can spawn poof particles with the
     * same lighting.
     */
    public static final int DAMAGE_HEADER_BYTES = 16;
    public static final int DAMAGE_ENTRY_BYTES = 64;
    /** Damage-entry flags: key is a storm MEMBER index (vs pool index). */
    public static final int DAMAGE_FLAG_MEMBER = 1;
    /** Damage-entry flags: server-authoritative death — force the corpse. */
    public static final int DAMAGE_FLAG_DIED = 2;
    /**
     * Vanilla pick forgiveness: GameRenderer inflates a picked entity's AABB
     * by this much (Entity.getPickRadius default). Unscaled, like vanilla.
     */
    public static final float HIT_INFLATE = 0.1f;
    /**
     * Hit-query result words: no hit. (Also the memberIdx sentinel in word 2
     * for non-storm MODEL particles.)
     */
    public static final int HIT_MISS = 0xFFFFFFFF;
    /** Hit-result key packing: 10 quantized distance bits above 22 index bits. */
    public static final int HIT_KEY_INDEX_BITS = 22;

    /**
     * Flat-uint index of field {@code f} of indirect command {@code c}: with
     * the uniform {@link #INDIRECT_STRIDE}, command i occupies bytes
     * {@code [i*STRIDE, (i+1)*STRIDE)} and field j sits at flat SSBO index
     * {@code 5*i + j}. All instanceCount indices below are DERIVED from this,
     * so a future stride/layout change propagates instead of desyncing.
     */
    public static int cmdField(int cmd, int field) {
        return cmd * (INDIRECT_STRIDE / 4) + field;
    }

    /** Flat-uint view of the indirect buffer: total uints ({@code 5 cmds x 5}). */
    public static final int INDIRECT_UINTS = INDIRECT_COMMANDS * (INDIRECT_STRIDE / 4);
    /** instanceCount of cmd0 — additive billboards. */
    public static final int IDX_CNT_ADD = cmdField(0, 1);
    /** instanceCount of cmd1 — OPAQUE cutout billboards. */
    public static final int IDX_CNT_SPRITE = cmdField(1, 1);
    /** instanceCount of cmd2 — model body-opaque segment. EXACT count of MODEL
     * items: the sorted array is type-partitioned (MODEL first), so this
     * equals the model partition length. Its value also serves as the ALPHA
     * partition's start offset (read by textured.vsh from the indirect SSBO).
     */
    public static final int IDX_CNT_MODELOP = cmdField(2, 1);
    /**
     * instanceCount of cmd3 — model CARRIER segment (held item). EXACT count
     * of carrier instances (keygen's atomic cursor over the wave-claim /
     * header-item sweep); each carrier instance is ALSO a MODEL item (its
     * body draws from cmd2/cmd4), so the instance is dual-listed.
     */
    public static final int IDX_CNT_CARRIER = cmdField(3, 1);
    /**
     * instanceCount of cmd4 — model translucent segment. Same exact MODEL
     * item total as {@link #IDX_CNT_MODELOP} (both body segments cover the
     * same partition, only their element ranges differ); keygen increments
     * both. (Was cmd3 before the carrier segment split the two apart.)
     */
    public static final int IDX_CNT_GHOST = cmdField(4, 1);
    /** instanceCount of cmd5 — ALPHA blended billboards. EXACT sprite-item count (the upper partition). */
    public static final int IDX_CNT_ALPHA = cmdField(5, 1);

    /**
     * Depth-band count of the sort key's LOW BYTE (one counting-sort pass).
     * Deliberately DECOUPLED from {@link #RADIX_BINS}: bins = translucent
     * types × bands, because the type bit rides above the low byte (see
     * {@link #SORT_TYPE_SHIFT}).
     */
    public static final int DEPTH_BANDS = 256;
    /**
     * Bit offset of the type bit inside the sort key: the low byte holds one
     * of {@link #DEPTH_BANDS} depth bands, so the type occupies the next bit.
     * Emitted into GLSL as SORT_KEY_TYPE_SHIFT.
     */
    public static final int SORT_TYPE_SHIFT = Integer.numberOfTrailingZeros(DEPTH_BANDS);
    /** Logarithmic quantization lower bound in blocks. */
    public static final float BAND_NEAR = 1.0f;

    private final int[] particleSSBOs = new int[2];
    private final int[] populationSSBOs=new int[2],populationTextures=new int[2];
    private final long[] populationGenerations={-1,-1};
    private int modelIndexCapacity,stormIndexCapacity;
    private final int[] emitSSBOs = new int[EMIT_RING_SIZE];
    private final int[] counterSSBOs = new int[COUNTER_RING];
    private final int[] sortSSBOs = new int[4];
    private final int[] indirectFrames = new int[2], additiveFrames = new int[2], opaqueFrames = new int[2];
    private int renderIndex;
    private boolean bindingScope;
    private com.iridium126.createmanaindustry.client.gpu.shader.GpuUniformCache uniforms;
    private final int[] bound = new int[32];
    private void bindBase(int target, int binding, int buffer) {
        ParticleDiagnostics.INSTANCE.call();
        GpuBindings.bindBase(target, binding, buffer);
    }
    public void beginBindings() {
        uniforms=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.currentCache();
        if(!bindingScope){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement.capture(this);GpuBindings.enter();GpuBindings.invalidate();}
        bindingScope = true;
    }
    @Override public void forgetBuffer(int buffer) {
        for(int binding=0;binding<bound.length;binding++)if(bound[binding]==buffer)bound[binding]=-1;
    }
    private void endBindings() {
        if(bindingScope){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement.restore(this);GpuBindings.exit();GpuBindings.invalidate();}
        bindingScope=false;
    }
    public static final int IDENTITY_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_IDENTITY_BB;
    private int identitySSBO = -1;
    private int damageApprovalCount;
    public void damageApprovalCount(int count){damageApprovalCount=Math.max(0,Math.min(DAMAGE_QUEUE_CAP,count));}
    public static final int DISPATCH_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_DISPATCH_BB;
    public static final int WAVE_STAMP_BB=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.BINDING_WAVE_STAMP_BB;
    private int waveStampSSBO = -1;
    private long identityEpoch;
    public long identityEpoch() { return identityEpoch; }
    public static final int DISPATCH_BYTES = 12;
    private int dispatchSSBO = -1;
    private int emitterSSBO = -1;
    private int orderAddSSBO = -1;
    private int indirectSSBO = -1;
    private int histSSBO = -1;
    private int offsetSSBO = -1;
    private int bakeMetaSSBO = -1;
    private int modelGeoSSBO = -1;
    private int orderOpaqueSSBO = -1;
    private int gridSSBO = -1;
    private int gridExtraBase=-1,gridExtraWords,gridExtraAlignment;
    private int damageSSBO = -1;
    private int hitSSBO = -1;
    private int playersSSBO = -1;
    private final com.iridium126.createmanaindustry.client.allaystorm.StormIdentityBuffers stormIdentities = new com.iridium126.createmanaindustry.client.allaystorm.StormIdentityBuffers();
    private int stormPosSSBO = -1;

    private int waveContactSSBO = -1;
    /** Static element indices for the MODEL sub-draws (bound into the VAO). */
    private int modelIndexBuffer = -1;
    private int vao = -1;

    private int readIndex = 0;
    private int ringIndex = 0;
    private int capacity = 0;
    private int maxEmitters = 0;
    private boolean initialized = false;

    private ParticleEmitterUploads emitterUploads;

    // Reusable scratch for tiny uploads. The largest writer is the initial
    // indirect payload (INDIRECT_COMMANDS x INDIRECT_STRIDE) — pitfall #22
    // discipline: keep headroom above that, not just equality.
    private final ByteBuffer tmp4 = BufferUtils.createByteBuffer(160);
    /** Zeroes the whole radix histogram (RADIX_BINS x uint = 2 KiB). */
    private final ByteBuffer zero2048 = BufferUtils.createByteBuffer(RADIX_BINS * 4);
    /** Matches the "major.minor" prefix of a GL_VERSION string. */

    /** Staging for per-frame player positions (MAX_STORM_PLAYERS vec4). */
    private FloatBuffer playersTmp = BufferUtils.createFloatBuffer(MAX_STORM_PLAYERS * 4);
    /** Staging for one correction-slot write (2 vec4). */
    private final FloatBuffer correctionTmp = BufferUtils.createFloatBuffer(8);

    /**
     * Allocates all buffers. Fails (logs + returns false) if the GPU cannot
     * provide a usable SSBO capacity or the max-width it supports is tiny.
     */
    public boolean init(int maxParticles, int maxEmitters) {
        var limits=GpuCapabilities.current();
        if(!limits.supportsCompute(IDENTITY_BB,1,64)) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] Device does not support the particle compute pipeline; engine disabled");
            return false;
        }
        int cap=limits.elementCapacity(maxParticles,BYTES_PER_PARTICLE,64);
        if(cap<1000) {
            CreateManaIndustry.LOGGER.warn("[CMI particles] Device capacity too small for a usable particle pool; engine disabled");
            return false;
        }
        this.capacity = cap;
        this.identitySSBO = createBuffer(4L + cap * 12L+8L*DAMAGE_QUEUE_CAP, null);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (java.nio.IntBuffer) null);
        this.waveStampSSBO = -1; // stable Storm identities, allocated only on first contact query
        this.maxEmitters = maxEmitters;
        this.emitterUploads = new ParticleEmitterUploads(maxEmitters);
        this.dispatchSSBO = createBuffer((long)DISPATCH_COMMANDS * DISPATCH_BYTES, null);
        for(int i=0;i<2;i++){ordinaryRosters[i]=createBuffer(16L+cap*4L,null);ordinaryGenerations[i]=ordinaryEpochs[i]=-1;}
        modelIndexCapacity=Math.min(cap,MODEL_INDEX_LIMIT);stormIndexCapacity=Math.min(cap,STORM_INDEX_LIMIT);
        for(int i=0;i<2;i++){
            populationSSBOs[i]=createBuffer(POPULATION_HEADER_BYTES+8L*(modelIndexCapacity+stormIndexCapacity),null);
            populationTextures[i]=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,populationTextures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,populationSSBOs[i]);
        }
        clearModelPopulation();

        this.vao = GL30.glGenVertexArrays();
        for (int i = 0; i < 2; i++) {
            this.particleSSBOs[i] = createBuffer(cap * (long) BYTES_PER_PARTICLE, null);
        }
        for (int i = 0; i < EMIT_RING_SIZE; i++) {
            this.emitSSBOs[i] = createBuffer((long) EMIT_STORAGE_FLOATS * 4, null);
        }
        this.emitterSSBO = createBuffer((long) maxEmitters * VEC4_PER_EMITTER * 4 * 4, null);
        for (int i = 0; i < 2; i++) {
            this.indirectFrames[i] = createBuffer((long) INDIRECT_COMMANDS * INDIRECT_STRIDE, null);
            this.additiveFrames[i] = createBuffer(cap * 4L, null);
            this.opaqueFrames[i] = createBuffer(cap * 4L, null);
        }
        selectRenderFrame(0);
        for (int i = 0; i < COUNTER_RING; i++) {
            this.counterSSBOs[i] = createBuffer(16, null);
        }
        // Radix sort data: (key, payload) per translucent item, double-buffered.
        // BEHIND the [0, cap) radix output sit two reserved regions: the CARRIER
        // region [cap, cap + CARRIER_CAP) — keygen appends held-item instances
        // there (never radix-touched: the radix passes only ever address
        // [0, translucentUpper) with translucentUpper <= cap) — and ONE METADATA
        // tail slot (index cap + CARRIER_CAP): capture.comp stores this
        // generation's N_MODEL there, next to the permutation it describes. The
        // shader-pack merge reads N from the same buffer it reads items from, so
        // N and items are structurally always the same generation -- an aborted
        // frame leaves them stale TOGETHER instead of pairing a fresh count with
        // a stale permutation. Safety: radix scatter writes [0, N_total), forward
        // reads stay < N_total, the reversed cutout read stays < N_model <=
        // N_total -- neither the carrier region nor the metadata slot is touched
        // by any of them. L0 shaders fetch the carrier region only through the
        // cmd3 carrier segment (whose instanceCount keygen clamps to CARRIER_CAP).
        for (int i = 0; i < 4; i++) {
            this.sortSSBOs[i] = createBuffer((cap + (long) CARRIER_CAP + 1L) * 8L, null);
        }
        // Additive permutation (dense, uint per additive particle).

        // OPAQUE cutout-billboard permutation (dense, uint per particle).

        this.histSSBO = createBuffer((long) RADIX_BINS * 4, null);
        this.offsetSSBO = createBuffer((long) (RADIX_BINS + 1) * 4, null);
        this.bakeMetaSSBO = createBuffer(CollisionBake.MAX_SLICES * 16L, null);
        // Boids spatial hash: heads table followed by the per-live-index next
        // chain array, sized by POOL CAPACITY so every storm member (up to the
        // 131072 stress cap) keeps neighbour coupling regardless of how sprite
        // indices interleave in the dense prefix. heads is cleared every time
        // gridbuild.comp runs (the pass itself is storm-gated in the engine);
        // next needs no clearing (written before read via atomicExchange).
        this.gridSSBO = createBuffer((GRID_TABLE + (long) cap) * 4L, null);
        // CPU melee-damage queue: uvec4 count header + vec4 entries. NOT ringed:
        // the GL command stream orders this frame's update before the next
        // frame's CPU header rewrite, and the engine uploads the header every
        // frame it has entries plus one zero-count upload the frame after.
        this.damageSSBO = createBuffer(
                DAMAGE_HEADER_BYTES + (long) DAMAGE_QUEUE_CAP * DAMAGE_ENTRY_BYTES, null);
        // Crosshair hit-query result uvec4: {packed key, winner HP bits,
        // memberIdx (HIT_MISS for non-storm MODEL), unused}.
        this.hitSSBO = createBuffer(16, null);
        this.tmp4.clear();
        this.tmp4.putInt(HIT_MISS).putInt(0).putInt(HIT_MISS).putInt(0);
        this.tmp4.flip();
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.hitSSBO);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        // Storm player-repulsion source (vec4 per player, up to 16).
        this.playersSSBO = createBuffer(playersTmp.capacity() * 4L, null);
        // Minimal legal bindings until Storm identities are actually used.
        this.stormIdentities.initialize();
        // Authority readback staging: uint count + capped entry floats.
        this.stormPosSSBO = createBuffer(
                4L + (long) STORMPOS_CAP * STORMPOS_ENTRY_FLOATS * 4L, null);
        // Storm member identity -> pool-slot map (see MEMBERMAP_BB).

        // Wave-contact self-report staging: uint count + capped entry floats.
        this.waveContactSSBO = createBuffer(
                4L + (long) WAVECONTACT_CAP * WAVECONTACT_ENTRY_FLOATS * 4L, null);

        // initial indirect payload: one command per slot (6 verts / 0 inst
        // each; the 5th uint pads arrays commands to the uniform 20 B stride)
        this.tmp4.clear();
        for (int i = 0; i < INDIRECT_COMMANDS; i++) {
            this.tmp4.putInt(6).putInt(0).putInt(0).putInt(0).putInt(0);
        }
        this.tmp4.flip();
        for (int id : this.indirectFrames) {
            GpuBindings.bind(GL40.GL_DRAW_INDIRECT_BUFFER, id);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL40.GL_DRAW_INDIRECT_BUFFER, 0, this.tmp4);
        }

        // zero all counter slots
        this.tmp4.clear();
        this.tmp4.putInt(0).putInt(0);
        this.tmp4.flip();
        for (int id : this.counterSSBOs) {
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, id);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        }

        invalidateIdentities();
        this.initialized = true;
        return true;
    }

    private static int createBuffer(long sizeBytes, FloatBuffer data) {
        int id = GL15.glGenBuffers();
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, id);
        if (data == null) {
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER, sizeBytes, GL15.GL_DYNAMIC_DRAW);
        } else {
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        }
        return id;
    }

    public boolean initialized() {
        return this.initialized;
    }

    /**
     * Binds the NEWEST fully-written generation's backing buffer — the write
     * side while called inside the compute phase (pre-swap), which becomes the
     * read side for every consumer after the AFTER_SKY commit. The {@code
     * binding} ARGUMENT is a shader-declared binding POINT, not a side
     * selector: pass the constant the consuming GLSL block actually declares
     * (the L0 render vertex shaders use PARTICLE_BB_WRITE even though they
     * mean "fresh data"). Callers choose generation, this class owns buffers.
     */
    public void bindNewestPool(int binding) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, particleDrawBufferId());
    }

    public int capacity() {
        return this.capacity;
    }

    /**
     * Fixed sort-buffer base of the held-item CARRIER region (keygen appends
     * carrier instances here; model.vsh / the merged source index the region
     * through {@code uCarrierBase} / {@code cmi_CarrierBase} set from this).
     * Derived from the live capacity so the uniform can never drift from the
     * allocation.
     */
    public void ensureCarrierCapacity(int participants){
        int required=CARRIER_CAP+Math.min(131072,Math.max(0,participants));if(required<=carrierCapacity)return;
        int grown=Math.min(CARRIER_CAP+131072,Math.max(required,carrierCapacity+carrierCapacity/2));
        long oldBytes=(capacity+(long)carrierCapacity+1)*8,newBytes=(capacity+(long)grown+1)*8;
        int scratch=GL15.glGenBuffers();GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER,scratch);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL31.GL_COPY_WRITE_BUFFER,oldBytes,GL15.GL_STREAM_COPY);
        try{for(int buffer:sortSSBOs){GpuBindings.bind(GL31.GL_COPY_READ_BUFFER,buffer);GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER,scratch);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,oldBytes);
            GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER,buffer);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL31.GL_COPY_WRITE_BUFFER,newBytes,GL15.GL_DYNAMIC_DRAW);GpuBindings.bind(GL31.GL_COPY_READ_BUFFER,scratch);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,oldBytes-8);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,oldBytes-8,newBytes-8,8);
        }}finally{com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(scratch);}carrierCapacity=grown;
    }
    public int carrierBase() {
        return this.capacity;
    }

    /**
     * Sort-buffer index of the reserved METADATA tail slot (capture.comp's
     * {@code uMetaSlot}, the shader-pack merge's {@code cmi_MetaSlot}): it
     * sits BEHIND the carrier region.
     */
    public int metaSlotIndex() {
        return this.capacity + carrierCapacity;
    }

    public int maxEmitters() {
        return this.maxEmitters;
    }

    public int readIndex() {
        return this.readIndex;
    }

    public int writeIndex() {
        return 1 - this.readIndex;
    }

    /** Call after a full frame: the freshly written buffer becomes the next read source. */
    public void beginComputeFrame(){beginComputeFrame(0);}
    public int historyBufferId(){return identitySSBO;}
    public int historyWordBase(){return 1+2*capacity+2*DAMAGE_QUEUE_CAP;}
    public void beginComputeFrame(float dt) {
        // Birth is the default for every producer. Compaction alone writes an edge.
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,identitySSBO);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,historyWordBase()*4L,capacity*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);
        beginBindings();if(allayAnimationRequested)allayAnimation.ensure(AllayAnimationGpu.CAPACITY);allayAnimation.beginFrame(dt);selectRenderFrame(1-this.readIndex);populationGenerations[1-readIndex]=-1; }
    public void bindModelMembers(int program){
        allayAnimation.bindCompute(program);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uPopulationStormPairs"),1);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uPopulationModelCapacity"),modelIndexCapacity);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uPopulationStormCapacity"),stormIndexCapacity);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uModelMembersEnabled"),1);
        GpuBindings.image(2,populationTextures[1-readIndex],0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);
        bindPopulationRead(program);
    }
    private void clearModelPopulation(){
        var header=BufferUtils.createIntBuffer(8);header.put(new int[]{0,0,0,3,0,0,modelIndexCapacity,stormIndexCapacity}).flip();
        for(int buffer:populationSSBOs){GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffer);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,header);}
        java.util.Arrays.fill(populationGenerations,0);
    }
    int candidatePopulationBuffer(){return populationSSBOs[1-readIndex];}
    public final ModelShadowGroups shadowGroups=new ModelShadowGroups();
    public void commitPopulation(long generation){populationGenerations[readIndex]=generation;shadowGroups.commit(readIndex,generation);}
    public int shadowGroupsBuffer(long generation){return displayPool!=0?displayGroups:shadowGroups.buffer(readIndex,generation);}
    public void beginShadowGroups(int program,long generation,StormShadowSnapshot snapshot,float[] planes,net.minecraft.world.phys.Vec3 camera){shadowGroups.begin(program,1-readIndex,modelIndexCapacity,generation,snapshot,planes,camera);bound[ModelShadowGroups.BINDING]=-1;}
    public int populationBufferId(long generation){return populationGenerations[readIndex]==generation?populationSSBOs[readIndex]:0;}
    public long populationStorageBytes(){return populationSSBOs[0]>0?2L*(POPULATION_HEADER_BYTES+8L*(modelIndexCapacity+stormIndexCapacity)):0;}
    public void bindPopulationRead(int program){bindPopulationRead(program,false,Math.max(0,populationGenerations[readIndex]));}
    public void bindPopulationRead(int program,boolean current,long generation){
        if(uniforms==null)uniforms=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.currentCache();
        int index=current?1-readIndex:readIndex;bindBase(GL43.GL_SHADER_STORAGE_BUFFER,POPULATION_READ_BB,populationSSBOs[index]);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,uniforms.location(program,"uPopulationReadEnabled"),current||populationGenerations[index]>=0?1:0);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform2ui(program,uniforms.location(program,"uPopulationGeneration"),(int)generation,(int)(generation>>>32));
    }
    public void bindPopulationWrite(int program,long generation){
        if(uniforms==null)uniforms=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.currentCache();
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER,POPULATION_WRITE_BB,populationSSBOs[1-readIndex]);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,uniforms.location(program,"uPopulationWriteEnabled"),1);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,uniforms.location(program,"uPopulationStormPairs"),1);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform2ui(program,uniforms.location(program,"uPopulationWriteGeneration"),(int)generation,(int)(generation>>>32));
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,uniforms.location(program,"uPopulationModelCapacity"),modelIndexCapacity);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,uniforms.location(program,"uPopulationStormCapacity"),stormIndexCapacity);
    }
    public void restoreCommittedFrame() { allayAnimation.abort();endBindings();selectRenderFrame(this.readIndex); }
    private void selectRenderFrame(int index) {
        this.renderIndex = index;
        this.indirectSSBO = this.indirectFrames[index];
        this.orderAddSSBO = this.additiveFrames[index];
        this.orderOpaqueSSBO = this.opaqueFrames[index];
    }

    public void swap() {
        this.readIndex = 1 - this.readIndex;allayAnimation.commit();
    }

    public void bindParticleRead(int binding) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.particleSSBOs[this.readIndex]);
    }

    public void bindParticleWrite(int binding) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.particleSSBOs[1 - this.readIndex]);
    }

    public void bindIndirect(int binding) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.indirectSSBO);
    }

    public void bindCounter(int binding, int slot) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.counterSSBOs[slot % COUNTER_RING]);
    }

    /**
     * Binds the given counter slot read-only as update.comp's exact live-count
     * source. The CALLER owns choosing which slot is authoritative — the engine
     * passes {@code lastGoodSlot}, the slot of the last frame whose output pool
     * was actually committed by a swap — so an aborted frame can never expose
     * its own partially-written counters as the read pool's live count.
     */
    public void bindPrevCounter(int binding, int slot) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding,
                this.counterSSBOs[slot % COUNTER_RING]);
    }

    public void bindEmitters(int binding) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, displayEmitter!=0?displayEmitter:this.emitterSSBO);
    }

    /** Advances the emit-command ring and returns the next buffer id. */
    public int nextEmitBuffer() {
        this.ringIndex = (this.ringIndex + 1) % EMIT_RING_SIZE;
        return this.emitSSBOs[this.ringIndex];
    }

    public void bindEmitBuffer(int binding, int bufferId) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, bufferId);
    }

    public void bindSort(int binding, int sortBufferId) {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, sortBufferId);
    }

    /** Physical GL buffer id of one of the two radix sort data buffers. */
    public int sortBuffer(int i) {
        return this.sortSSBOs[this.renderIndex * 2 + i];
    }

    /** Read-side pool backing buffer id (for the merged program's TBO view). */
    public int particleReadBufferId() {
        return this.particleSSBOs[this.readIndex];
    }

    /** Internal package pass uses the current submission's resources, before swap. */
    int particleWriteBufferId() { return this.particleSSBOs[1-this.readIndex]; }
    public int emitterBufferId(){return this.emitterSSBO;}
    int indirectBufferId(){return this.indirectSSBO;}
    int counterBufferId(int slot) { return this.counterSSBOs[slot % COUNTER_RING]; }

    // ---- merged-program TBO views (shader-pack path) --------------------
    // The pack-merged programs declare plain samplerBuffer uniforms instead of
    // SSBO interface blocks: ordinary global declarations survive every stage
    // of the in-game transform pipeline, interface blocks demonstrably do not.
    // Each view is {textureId, lastAttachedBufferId} and re-attaches lazily
    // when the backing buffer changes (pool flip / permutation rotation).

    private final int[] mergedTbos=new int[4];
    private final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap[] mergedViews={
        new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(),new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(),
        new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(),new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap()};
    private long mergedBufferEpoch=-1;
    private void clearMergedViews(){
        for(var views:mergedViews){for(int texture:views.values())com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(texture);views.clear();}
        java.util.Arrays.fill(mergedTbos,0);
    }
    private void ensureMergedTbo(int slot,int internalFormat,int bufferId){
        if(bufferId<=0)return;
        long epoch=com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.epoch();
        if(epoch!=mergedBufferEpoch){clearMergedViews();mergedBufferEpoch=epoch;}
        int texture=mergedViews[slot].get(bufferId);
        if(texture==0){texture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(texture,internalFormat,bufferId);mergedViews[slot].put(bufferId,texture);}
        mergedTbos[slot]=texture;
    }

    /** Binds the four TBO views to texture units baseUnit..baseUnit+3. */
    public void bindMergedTbos(int baseUnit, int poolReadBufferId, int permBufferId) {
        if (this.modelGeoSSBO <= 0 || this.emitterSSBO <= 0)
            return;
        ensureMergedTbo(0, GL30.GL_R32F, this.modelGeoSSBO);
        ensureMergedTbo(1, GL30.GL_RGBA32F, poolReadBufferId);
        ensureMergedTbo(2, GL30.GL_RGBA32F, particleDrawEmitterBufferId());
        ensureMergedTbo(3, GL32.GL_RG32UI, permBufferId);
        for (int i = 0; i < 4; i++) {
            GpuBindings.texture(baseUnit+i,GL31.GL_TEXTURE_BUFFER,this.mergedTbos[i]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Detaches the merged TBO views from their texture units. */
    public void unbindMergedTbos(int baseUnit) {
        for (int i = 0; i < 4; i++) {
            GpuBindings.texture(baseUnit+i,GL31.GL_TEXTURE_BUFFER,0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Binds the additive-permutation buffer at its fixed binding for keygen/draw. */
    public void bindOrderAdd() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, ORDERADD_BINDING, displayAdd!=0?displayAdd:this.orderAddSSBO);
    }

    /** Binds the OPAQUE cutout-billboard permutation at its fixed binding. */
    public void bindOrderOpaque() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, ORDEROPAQUE_BINDING, displayOpaque!=0?displayOpaque:this.orderOpaqueSSBO);
    }

    /** Binds the static model geometry for the model draw pass (must be re-bound every frame — see {@link #unbindShaders()}). */
    public void bindModelGeo() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, MODELGEO_BINDING, this.modelGeoSSBO);
    }

    /**
     * Uploads the static indexed model geometry (see {@link AllayModelGeometry}:
     * {@code VERTEX_FLOATS} stride) once after init: vertices go to the geo
     * SSBO, indices to an element buffer bound into the engine's VAO (harmless
     * for the unindexed particle draws), and draw commands 2/3/4 are rewritten
     * as element commands — cmd2 = body opaque cutout segment from index 0,
     * cmd3 = the held-item CARRIER segment (its own index range), cmd4 =
     * translucent blended segment starting at {@code opaqueIndexCount}. Body
     * commands' instanceCounts stay GPU-written each frame (keygen sets both
     * to the exact MODEL partition length N_model), cmd3's to the exact
     * carrier count; the disjoint element ranges make the segment id derivable
     * from partId alone.
     *
     * @param bodyOpaqueIndexCount end of the body-opaque index range (where
     *     the held-item indices begin)
     * @param opaqueIndexCount end of the held-item indices (start of the
     *     translucent segment)
     */
    public void uploadModelGeometry(float[] vertices, int[] indices,
            int bodyOpaqueIndexCount, int opaqueIndexCount) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(vertices.length);
            buf.put(vertices).flip();
            this.modelGeoSSBO = createBuffer(4L * vertices.length, buf);
        }
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, MODELGEO_BINDING, this.modelGeoSSBO);

        this.modelIndexBuffer = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GpuBindings.bind(GL15.GL_ELEMENT_ARRAY_BUFFER, this.modelIndexBuffer);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer ib = stack.mallocInt(indices.length);
            ib.put(indices).flip();
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
        }
        GL30.glBindVertexArray(0);

        // three full 20-byte element commands (indexCount, instanceCount=0,
        // firstIndex, baseVertex=0, baseInstance=0): cmd2 = body opaque
        // cutout from index 0, cmd3 = held-item carrier segment, cmd4 =
        // translucent blended starting at opaqueIndexCount
        this.tmp4.clear();
        this.tmp4.putInt(bodyOpaqueIndexCount).putInt(0).putInt(0).putInt(0).putInt(0);
        this.tmp4.putInt(opaqueIndexCount - bodyOpaqueIndexCount).putInt(0)
                .putInt(bodyOpaqueIndexCount).putInt(0).putInt(0);
        this.tmp4.putInt(indices.length - opaqueIndexCount).putInt(0)
                .putInt(opaqueIndexCount).putInt(0).putInt(0);
        this.tmp4.flip();
        for (int id : this.indirectFrames) {
            GpuBindings.bind(GL40.GL_DRAW_INDIRECT_BUFFER, id);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL40.GL_DRAW_INDIRECT_BUFFER, 2L * INDIRECT_STRIDE, this.tmp4);
        }
    }

    public void bindHist() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, HIST_BINDING, this.histSSBO);
    }

    public void bindOffsets() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, OFFSET_BINDING, this.offsetSSBO);
    }

    public void bindBakeMeta() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, BAKEMETA_BINDING, this.bakeMetaSSBO);
    }

    /** Uploads the collision bake-meta array to its SSBO. */
    public void uploadBakeMeta(float[] meta) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(meta.length);
            buf.put(meta).flip();
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.bakeMetaSSBO);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        }
    }

    /** Zeroes the radix histogram (2 KiB) before each pass. */
    public void clearHist() {
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.histSSBO);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.zero2048);
    }

    /** Aligns a range inside the existing grid buffer; queried only on first use. */
    public int alignGridWords(int words) {
        if(gridExtraAlignment==0)gridExtraAlignment=Math.max(64,GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT)/4);
        return (words+gridExtraAlignment-1)/gridExtraAlignment*gridExtraAlignment;
    }
    public int ensureGridExtra(int words) {
        if(gridExtraWords>=words)return gridExtraBase;
        int prefix=GRID_TABLE+capacity,base=alignGridWords(prefix);
        int next=createBuffer((long)(base+words)*4,null);
        GpuBindings.bind(GL31.GL_COPY_READ_BUFFER,gridSSBO);GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER,next);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,(long)prefix*4);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(gridSSBO);
        gridSSBO=next;gridExtraBase=base;gridExtraWords=words;beginBindings();return base;
    }
    /** Binds the boids spatial-hash buffer at its fixed binding. */
    public void bindGrid() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, GRID_BB, this.gridSSBO);
    }

    /** Binds the carrier-sink sort buffer (keygen's dual-write target) at its fixed binding. */
    public void bindCarrierSink() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, CARRIERSINK_BB, sortBuffer(1));
    }

    /** Binds the CPU melee-damage queue at its fixed binding. */
    public void bindDamage() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, DAMAGE_BB, this.damageSSBO);
    }

    /** Binds the crosshair hit-query result at its fixed binding. */
    public void bindHit() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, HIT_BB, this.hitSSBO);
    }

    /** Binds the storm player-position array at its fixed binding. */
    public void bindPlayers() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, PLAYERS_BB, this.playersSSBO);
    }

    /**
     * Uploads all player positions and a reusable 6m cell index; no count truncation. Packs positions (xyz packed
     * into vec4s, stride 4 floats). {@code count} entries are read from the
     * head of the flat array.
     */
    private final com.iridium126.createmanaindustry.client.allaystorm.StormPlayerGrid playerGrid=new com.iridium126.createmanaindustry.client.allaystorm.StormPlayerGrid();
    public void uploadPlayers(float[] xyz, int count) {
        playerGrid.build(xyz,count);uploadPlayerGrid();
    }
    public void uploadPlayerSnapshot(com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime runtime,int count){
        playerGrid.build(runtime.playerScratch(),runtime.playerEyes(),runtime.playerFeet(),runtime.playerIds(),count,runtime.attackEyes(),AllayFeatherGpu.INSTANCE.needsPlayers()?runtime.featherMovements():null);uploadPlayerGrid();
    }
    public int featherMovementBase(){return playerGrid.movementBase();}
    private void uploadPlayerGrid(){int length=playerGrid.words();float[] packed=playerGrid.data();
        if(playersTmp.capacity()<length){playersTmp=BufferUtils.createFloatBuffer(Integer.highestOneBit(length-1)<<1);GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,playersSSBO);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,playersTmp.capacity()*4L,GL15.GL_DYNAMIC_DRAW);}
        playersTmp.clear();playersTmp.put(packed,0,length).flip();GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,playersSSBO);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,playersTmp);
    }

    /** Binds the storm correction-slot buffer at its fixed binding. */
    public void bindCorrections() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, CORRECTION_BB, this.stormIdentities.corrections());
    }

    /**
     * Writes one member's correction slot (32 bytes at
     * {@code memberIdx * 32}): {target.xyz, arrivalTime} and
     * {targetVel.xyz, strengthScale} (scale &gt; 2 marks a hit correction with
     * its fixed short window; 0 disables).
     */
    public void writeCorrection(int memberIdx, float tx, float ty, float tz, float arrival,
            float vx, float vy, float vz, float scale) {
        this.correctionTmp.clear();
        this.correctionTmp.put(tx).put(ty).put(tz).put(arrival);
        this.correctionTmp.put(vx).put(vy).put(vz).put(scale);
        this.correctionTmp.flip();
        this.stormIdentities.write(memberIdx, this.correctionTmp);
    }

    /** Binds the authority readback staging buffer at its fixed binding. */
    public void bindStormPos() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, STORMPOS_BB, this.stormPosSSBO);
    }

    /** Zeroes the readback entry count ahead of a dispatch (4-byte write). */
    public void clearStormPosCount() {
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        this.tmp4.clear();
        this.tmp4.putInt(0);
        this.tmp4.flip();
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.stormPosSSBO);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
    }

    /** Binds the storm member identity map at its fixed binding. */
    public void bindMemberMap() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, MEMBERMAP_BB, this.stormIdentities.members());
    }

    /**
     * Zeroes the whole member identity map. Every generation-killing storm
     * transition (ACTIVATE restart / DEACTIVATE / STOP) and every pool reset
     * must call this: identities reshuffle or vanish, and a stale entry would
     * resolve a member-keyed combat origin onto whatever now occupies the old
     * slot.
     */
    public void invalidateIdentities() {
        this.identityEpoch++;
        if (this.waveStampSSBO > 0) {
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.waveStampSSBO);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                    GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (java.nio.IntBuffer) null);
        }
        if(waveContactSSBO>0)clearWaveContactCount();
        clearMemberMap();
    }

    /** Per-frame map maintenance must not invalidate asynchronous snapshots or event stamps. */
    public void clearMemberMap() { this.stormIdentities.clearMembers(); }

    public void ensureStormIdentities() {
        this.stormIdentities.ensureMembers();
        beginBindings();
    }

    /** Binds the wave-contact staging buffer at its fixed binding. */
    public void bindWaveContact() {
        if(waveStampSSBO<0){waveStampSSBO=createBuffer(68L*4,null);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);}
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, WAVE_STAMP_BB, this.waveStampSSBO);
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, WAVECONTACT_BB, this.waveContactSSBO);
    }

    public int waveContactBufferId(){return waveContactSSBO;}
    /** Starts a separate batch on the GL command stream. */
    public void clearWaveContactCount() {
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        this.tmp4.clear();
        this.tmp4.putInt(0);
        this.tmp4.flip();
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.waveContactSSBO);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
    }


    /**
     * Zeroes the heads table ({@link #GRID_TABLE} ints) ahead of each
     * gridbuild dispatch; the {@code next} chain half needs no clearing because
     * every slot is written before it can be traversed.
     */
    public void clearGridHeads() {
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer zero = stack.mallocInt(1);
            zero.put(0).flip();
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.gridSSBO);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                    0, (long) GRID_TABLE * 4, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, zero);
        }
    }

    /** Physical GL buffer id of the boids grid (heads+next). */
    public int gridBufferId() {
        return this.gridSSBO;
    }


    /**
     * Uploads the damage-queue header (+ entries when {@code buf} carries any)
     * to the GPU. Must be enqueued BEFORE this frame's update dispatch; the
     * buffer layout matches the shader's std430 block (uvec4 count header at
     * offset 0, vec4 entries from offset 16).
     */
    public void uploadDamageQueue(ByteBuffer buf) {
        ParticleDiagnostics.INSTANCE.upload(buf.remaining());
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.damageSSBO);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
    }

    /** Replaces the given emit-command ring slot contents (data already flipped). */
    public void uploadEmits(int bufferId, FloatBuffer data) {
        ParticleDiagnostics.INSTANCE.upload(data.remaining() * 4L);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, bufferId);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, data);
    }

    /** Records one emitter header; uploaded lazily on the next frame. */
    public void setEmitterHeader(int id, float[] block) { this.emitterUploads.set(id, block);if(block[28]>=1.5f&&block[28]<2.5f)allayAnimationRequested=true; }

    public void updateEmitterHeader(int id, float[] block) { setEmitterHeader(id, block); }

    /** Upload only changed, adjacent header ranges. */
    public void uploadDirtyEmitters() { this.emitterUploads.upload(this.emitterSSBO); }
    long emitterRevision(){return emitterUploads.revision();}
    long uploadedEmitterBytes(){return emitterUploads.uploadedBytes();}

    /** Instantly drops every particle (all counter slots + instance counts zeroed). */
    public void clearParticles() {
        allayAnimation.clear();clearModelPopulation();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        this.tmp4.clear();
        this.tmp4.putInt(0).putInt(0);
        this.tmp4.flip();
        for (int id : this.counterSSBOs) {
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, id);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        }

        this.tmp4.clear();
        this.tmp4.putInt(0);
        this.tmp4.flip();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        for (int id : this.indirectFrames) {
            GpuBindings.bind(GL40.GL_DRAW_INDIRECT_BUFFER, id);
            for (int i = 0; i < INDIRECT_COMMANDS; i++)
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL40.GL_DRAW_INDIRECT_BUFFER, i * INDIRECT_STRIDE + 4, this.tmp4);
        }

        // clear the damage queue and mark the hit query as a miss
        this.tmp4.clear();
        for (int i = 0; i < 4; i++)
            this.tmp4.putInt(0);
        this.tmp4.flip();
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.damageSSBO);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        this.tmp4.clear();
        this.tmp4.putInt(HIT_MISS).putInt(0).putInt(HIT_MISS).putInt(0);
        this.tmp4.flip();
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER, this.hitSSBO);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
    }

    /**
     * Unbinds the engine's SSBO bases so they don't linger bound into the rest
     * of the world's rendering after our frame.
     */
    public void unbindShaders() {
        endBindings();
        ParticleDiagnostics.INSTANCE.call();
        org.lwjgl.opengl.GL44.nglBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER, 0,
                IDENTITY_BB + 1, 0L);
        GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER, 0);
    }

    void copySnapshot(int counterSlot,long frameToken,ParticleReadbacks.Slot slot) {
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER, slot.buffer);
        copySnapshotPart(this.counterSSBOs[counterSlot], 0, 16);
        copySnapshotPart(this.hitSSBO, 16, 16);
        slot.profile=ParticleDiagnostics.INSTANCE.enabled()||com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.collecting();
        slot.profileFrame=frameToken;
        if(slot.profile){
            copySnapshotPart(this.counterSSBOs[counterSlot],ParticleReadbacks.PROFILE_SIM_OFFSET,4);
            copySnapshotPart(this.populationSSBOs[1-readIndex],ParticleReadbacks.PROFILE_SIM_OFFSET+16,POPULATION_HEADER_BYTES);
        }
        if (slot.positions) copySnapshotPart(this.stormPosSSBO, ParticleReadbacks.STORM_OFFSET,
                4L + STORMPOS_CAP * STORMPOS_ENTRY_FLOATS * 4L);
        GpuBindings.bind(GL31.GL_COPY_READ_BUFFER, 0);
        GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    private static void copySnapshotPart(int source, long offset, long bytes) {
        GpuBindings.bind(GL31.GL_COPY_READ_BUFFER, source);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, offset, bytes);
    }

    /** Every selected update program needs this frame's actual pool limit.
     * Late-compiled diagnostic programs do not receive configureStatic(). */
    public void bindUpdateCapacity(int program) {
        if (capacity <= 0) throw new IllegalStateException("Particle update has no allocated pool");
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uCapacity"),capacity);
    }

    public void bindIdentities(int program) {
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uHistoryEnabled"),1);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uHistoryBase"),historyWordBase());
        if(com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uModelMembersEnabled")>=0)bindModelMembers(program);
        else if(com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uAllayAnimationWriteEnabled")>=0)allayAnimation.bindCompute(program);
        if(uniforms==null)uniforms=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.currentCache();
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, IDENTITY_BB, this.identitySSBO);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,uniforms.location(program,"uIdentityReadBase"),readIndex*capacity);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,uniforms.location(program,"uIdentityWriteBase"),(1-readIndex)*capacity);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,uniforms.location(program,"uIdentityCapacity"),capacity);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,uniforms.location(program,"uDamageApprovalCount"),damageApprovalCount);
    }

    public void bindDispatch() {
        bindBase(GL43.GL_SHADER_STORAGE_BUFFER, DISPATCH_BB, this.dispatchSSBO);
        GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER, this.dispatchSSBO);
    }
    public int dispatchBufferId(){return this.dispatchSSBO;}

    /** Phase publication owns these inputs, even after external compute passes reuse their bindings. */
    public void bindDispatchInputs(int currentSlot, int previousSlot) {
        bindDispatch();
        bindIndirect(INDIRECT_BB);
        bindCounter(COUNTER_BB, currentSlot);
        bindPrevCounter(PREVCOUNTER_BINDING, previousSlot);
    }

    /** 0: committed pool, 1: current pool, 2: visible translucent items. */
    public void dispatch(int command) {
        ParticleDiagnostics.INSTANCE.call();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect((long) command * DISPATCH_BYTES);
    }

    /** Complete inputs for a billboard pass; do not depend on another material's bindings. */
    public void bindBillboardDrawInputs(int mode, int permutation) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Billboard mode");
        bindNewestPool(PARTICLE_BB_WRITE);
        bindEmitters(EMITTER_BB);
        if (mode == 0) bindOrderAdd();
        else {
            bindSort(SORTWRITE_BINDING, permutation);
            bindOrderOpaque(); // Keep both statically declared textured inputs valid.
        }
        // ALPHA reads its partition start from this same generation's commands.
        bindIndirect(INDIRECT_BB);
        bindVao();
        bindDrawIndirect();
    }

    /** MODEL body, ghost and carrier share the main permutation, never the shadow list. */
    public void bindModelDrawInputs(int permutation) {
        bindNewestPool(PARTICLE_BB_WRITE);
        bindEmitters(EMITTER_BB);
        bindSort(SORTWRITE_BINDING, permutation);
        bindModelGeo();
        bindVao();
        bindDrawIndirect();
    }

    public void bindDrawIndirect() {
        GpuBindings.bind(GL40.GL_DRAW_INDIRECT_BUFFER, displayCommands!=0?displayCommands:this.indirectSSBO);
    }

    /** Draws draw-command {@code cmd} (offset in the indirect buffer). */
    public void drawIndirect(int cmd) {
        ParticleDiagnostics.INSTANCE.drawCall();
        GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES, cmd * (long) INDIRECT_STRIDE);
    }

    /**
     * Draws ALL THREE MODEL sub-draws with one multi-draw: commands 2/3/4 are
     * contiguous element commands sharing program, VAO and state (body
     * cutout, held-item carrier, translucent ghost); their per-command
     * segment selection arrives through the vertex's own partId (the index
     * ranges are disjoint). Zero-instance commands are skipped by the GPU,
     * so the same call also serves the fast path (no carriers, empty ghost).
     */
    public void drawModelSegments() {
        ParticleDiagnostics.INSTANCE.drawCall();
        if(com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver.active()){
            ParticleDiagnostics.INSTANCE.drawCall();ParticleDiagnostics.INSTANCE.drawCall();
            ParticleFrameStages.draw("native.model.body",()->GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES,GL11.GL_UNSIGNED_INT,2L*INDIRECT_STRIDE,1,INDIRECT_STRIDE));
            ParticleFrameStages.draw("native.model.carrier",()->GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES,GL11.GL_UNSIGNED_INT,3L*INDIRECT_STRIDE,1,INDIRECT_STRIDE));
            ParticleFrameStages.draw("native.model.ghost",()->GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES,GL11.GL_UNSIGNED_INT,4L*INDIRECT_STRIDE,1,INDIRECT_STRIDE));return;
        }
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                2L * INDIRECT_STRIDE, 3, INDIRECT_STRIDE);
    }

    /** Draws ONLY the CUTOUT MODEL body segment (command 2) -- shader-pack early path. */
    public void drawModelCutout() {
        ParticleDiagnostics.INSTANCE.drawCall();
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                2L * INDIRECT_STRIDE, 1, INDIRECT_STRIDE);
    }

    /** Draws ONLY the held-item CARRIER segment (command 3) -- shader-pack early path. */
    public void drawModelCarrier() {
        ParticleDiagnostics.INSTANCE.drawCall();
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                3L * INDIRECT_STRIDE, 1, INDIRECT_STRIDE);
    }

    /** Draws ONLY the GHOST MODEL segment (command 4) -- shader-pack early path. */
    public void drawModelGhost() {
        ParticleDiagnostics.INSTANCE.drawCall();
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                4L * INDIRECT_STRIDE, 1, INDIRECT_STRIDE);
    }

    public void bindVao() {
        GL30.glBindVertexArray(this.vao);
    }

    public void free() {
        simulationParameters.close();
        for(int i=0;i<2;i++){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(ordinaryRosters[i]);ordinaryRosters[i]=0;ordinaryGenerations[i]=ordinaryEpochs[i]=-1;}
        clearDisplayResources();
        if (this.modelIndexBuffer > 0) {
            // detach the element binding from the VAO before deleting (the
            // ELEMENT_ARRAY binding is VAO state)
            if (this.vao >= 0)
                GL30.glBindVertexArray(this.vao);
            GpuBindings.bind(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.modelIndexBuffer);
        }
        if (this.vao >= 0)
            com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteVertexArray(this.vao);
        for (int id : this.particleSSBOs)
            if (id > 0)
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);
        shadowGroups.close();
        for(int texture:populationTextures)if(texture>0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(texture);
        java.util.Arrays.fill(populationTextures,0);
        for(int id:populationSSBOs)if(id>0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);
        java.util.Arrays.fill(populationSSBOs,0);java.util.Arrays.fill(populationGenerations,-1);
        for (int id : this.emitSSBOs)
            if (id > 0)
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);
        if (this.identitySSBO > 0) { com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.identitySSBO); this.identitySSBO = -1; }
        if (this.waveStampSSBO > 0) { com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.waveStampSSBO); this.waveStampSSBO = -1; }
        if (this.dispatchSSBO > 0) { com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.dispatchSSBO); this.dispatchSSBO = -1; }
        if (this.emitterSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.emitterSSBO);
        for (int[] frames : new int[][] {this.indirectFrames, this.additiveFrames, this.opaqueFrames})
            for (int id : frames) if (id > 0) com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);
        for (int id : this.counterSSBOs)
            if (id > 0)
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);
        for (int id : this.sortSSBOs)
            if (id > 0)
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);
        if (this.modelGeoSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.modelGeoSSBO);
        if (this.histSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.histSSBO);
        if (this.offsetSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.offsetSSBO);
        if (this.bakeMetaSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.bakeMetaSSBO);
        if (this.gridSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.gridSSBO);
        if (this.damageSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.damageSSBO);
        if (this.hitSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.hitSSBO);
        if (this.playersSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.playersSSBO);
        this.stormIdentities.close();
        if (this.stormPosSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.stormPosSSBO);

        if (this.waveContactSSBO > 0)
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(this.waveContactSSBO);
        clearMergedViews();
        java.util.Arrays.fill(this.particleSSBOs, 0);
        java.util.Arrays.fill(this.emitSSBOs, 0);
        java.util.Arrays.fill(this.counterSSBOs, 0);
        java.util.Arrays.fill(this.sortSSBOs, 0);
        java.util.Arrays.fill(this.indirectFrames, 0);
        java.util.Arrays.fill(this.additiveFrames, 0);
        java.util.Arrays.fill(this.opaqueFrames, 0);
        this.emitterSSBO = this.indirectSSBO = this.orderAddSSBO = this.orderOpaqueSSBO = -1;
        this.modelGeoSSBO = this.histSSBO = this.offsetSSBO = this.bakeMetaSSBO = -1;
        this.gridExtraBase=-1;this.gridExtraWords=0;
        this.gridSSBO = this.damageSSBO = this.hitSSBO = this.playersSSBO = -1;
        this.stormPosSSBO = this.waveContactSSBO = this.modelIndexBuffer = this.vao = -1;
        this.capacity = this.maxEmitters = this.readIndex = this.ringIndex = this.renderIndex = 0;
        allayAnimation.close();
        allayAnimationRequested=false;
        this.emitterUploads = null;
        endBindings();
        this.initialized = false;
    }
}
