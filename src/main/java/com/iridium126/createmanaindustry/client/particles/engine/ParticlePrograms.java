package com.iridium126.createmanaindustry.client.particles.engine;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.ARBParallelShaderCompile;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL20;

/**
 * Self-hosted GLSL programs for the particle engine, compiled with raw LWJGL
 * from the mod's bundled sources ({@code assets/createmanaindustry/shaders/
 * particles/*}). This deliberately bypasses Veil's {@code ShaderManager}, whose
 * compute programs turned out inert in this environment (dispatch with no GL
 * error and no kernel execution). Because we compile the GLSL ourselves, the
 * {@code layout(binding=N)} qualifiers are honored directly by GL, and the
 * programs work with or without Veil loaded.
 * <p>
 * Pipeline: reset -> update -> emit (fast path) or
 * reset -> update -> emit -> keygen -> radix{hist,scan,scatter} x1 (sorted
 * path: single-pass counting sort over an inverted 8-bit depth band, i.e.
 * back-to-front), then the model / textured-sprite / additive render programs.
 * <p>
 * A common {@link #PRELUDE} of {@code #define} constants is injected ahead of
 * every shader source; it is GENERATED from the {@code ParticleBuffers}
 * constants so the Java side stays the single source of truth for bindings,
 * indirect-buffer layout indices and structural sizes.
 * <p>
 * A {@code #version} header is prepended (raw GL requires one; Veil used to
 * inject it). Programs are rebuilt by {@link #rebuild()} — called lazily on the
 * render thread whenever {@link #needsRebuild()} is true, which the resource
 * reload listener flips. Changed sources compile incrementally; unchanged
 * sources reuse the active set, including across world transitions.
 */
public final class ParticlePrograms {
    public static final ParticlePrograms INSTANCE=new ParticlePrograms();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ParticlePrograms.class);

    private static final String GLSL_DIR = "shaders/particles/";
    private static final String VERSION = "#version 450 core\n";
    private record Specification(String vertex,String fragment,ObjIntConsumer<ParticlePrograms> install) {
        String path(){return GLSL_DIR+vertex;}
        String key(){return fragment==null?path():path()+"|"+GLSL_DIR+fragment;}
    }
    private static final List<Specification> SPECIFICATIONS=List.of(
        new Specification("prepare_dispatch.comp",null,(owner,id)->owner.prepareDispatch=id),
        new Specification("reset.comp",null,(owner,id)->owner.reset=id),
        new Specification("update.comp",null,(owner,id)->owner.update=id),
        new Specification("emit.comp",null,(owner,id)->owner.emit=id),
        new Specification("block_emit.comp",null,(owner,id)->owner.blockEmit=id),
        new Specification("keygen.comp",null,(owner,id)->owner.keygen=id),
        new Specification("radix_hist.comp",null,(owner,id)->owner.radixHist=id),
        new Specification("radix_scan.comp",null,(owner,id)->owner.radixScan=id),
        new Specification("radix_scatter.comp",null,(owner,id)->owner.radixScatter=id),
        new Specification("capture.comp",null,(owner,id)->owner.capture=id),
        new Specification("gridbuild.comp",null,(owner,id)->owner.grid=id),
        new Specification("dome_contacts.comp",null,(owner,id)->owner.domeContacts=id),
        new Specification("dome_path.comp",null,(owner,id)->owner.domePath=id),
        new Specification("hit.comp",null,(owner,id)->owner.hit=id),
        new Specification("damage_prepare.comp",null,(owner,id)->owner.damagePrepare=id),
        new Specification("stormpos.comp",null,(owner,id)->owner.stormPos=id),
        new Specification("column_snapshot.comp",null,(owner,id)->owner.columnSnapshot=id),
        new Specification("column_restore.comp",null,(owner,id)->owner.columnRecovery=id),
        new Specification("starfall_ring_reserve.comp",null,(owner,id)->owner.ringReserve=id),
        new Specification("wavecontact.comp",null,(owner,id)->owner.waveContact=id),
        new Specification("additive.vsh","additive.fsh",(owner,id)->owner.render=id),
        new Specification("textured.vsh","textured.fsh",(owner,id)->owner.texturedRender=id),
        new Specification("model.vsh","model.fsh",(owner,id)->owner.modelRender=id));

    private static final class Reload implements AutoCloseable {
        final Map<String,String> sources;
        final Map<String,Integer> completed=new HashMap<>();
        final long started=System.nanoTime();
        final boolean parallel=GL.getCapabilities().GL_ARB_parallel_shader_compile;
        final int previousThreads=parallel?GL11.glGetInteger(ARBParallelShaderCompile.GL_MAX_SHADER_COMPILER_THREADS_ARB):0;
        GpuShaderCompiler.Compilation current;
        int index;
        long programStarted,progress;
        boolean closed;
        Reload(Map<String,String> sources){
            this.sources=sources;
            if(parallel)ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(2);
            else LOGGER.warn("[CMI particles] background shader compilation unavailable; compiling one stage per frame synchronously");
        }
        boolean advance(){
            var specification=SPECIFICATIONS.get(index);
            if(current==null){
                programStarted=progress=System.nanoTime();
                LOGGER.info("[CMI particles] shader compilation {}/{} started: {} ({})",index+1,SPECIFICATIONS.size(),specification.path(),parallel?"background":"synchronous fallback");
                String vertex=VERSION+PRELUDE+sources.get(specification.path());
                current=specification.fragment==null?GpuShaderCompiler.beginCompute(specification.path(),vertex):GpuShaderCompiler.beginGraphics(specification.path(),vertex,VERSION+PRELUDE+sources.get(GLSL_DIR+specification.fragment));
                return false;
            }
            if(!current.poll()){
                long now=System.nanoTime();if(now-progress>=5_000_000_000L){progress=now;LOGGER.info("[CMI particles] shader compilation still pending: {} ({}s); retaining active programs",specification.path(),(now-programStarted)/1_000_000_000L);}return false;
            }
            completed.put(specification.key(),current.take());current.close();current=null;index++;
            LOGGER.info("[CMI particles] shader compilation finished: {} ({}ms)",specification.path(),(System.nanoTime()-programStarted)/1_000_000L);
            return index==SPECIFICATIONS.size();
        }
        int take(String key){Integer id=completed.remove(key);return id==null?0:id;}
        @Override public void close(){
            if(closed)return;closed=true;
            if(current!=null){current.close();current=null;}
            for(int program:completed.values())com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(program);completed.clear();
            if(parallel&&GL11.glGetInteger(ARBParallelShaderCompile.GL_MAX_SHADER_COMPILER_THREADS_ARB)==2)ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(previousThreads);
        }
    }

    /**
     * Common GLSL header injected ahead of every shader source: engine-wide
     * constants generated from the {@code ParticleBuffers} constants so there
     * is exactly one place to change a binding or an indirect-layout index.
     * Plain {@code #define}s rather than {@code const}s because they must stay
     * legal inside {@code layout()} qualifiers on every driver (macro
     * substitution is textual and needs no constant-expression support).
     */
    private static final String PRELUDE = buildPrelude();

    public static String commonPrelude(){return PRELUDE;}

    private static String buildPrelude() {
        StringBuilder sb = new StringBuilder(768);
        sb.append("#define CMI_DOME_CONTACT_DELTA ").append(com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.DOME_CONTACT_DELTA).append("u\n");
        sb.append("#define CMI_DOME_CONTACT_QUEUE ").append(com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.DOME_CONTACT_QUEUE).append("u\n");
        sb.append("#define CMI_DOME_CONTACT_ARGS ").append(com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.DOME_CONTACT_ARGS).append("u\n");
        sb.append("#define CMI_DOME_HANDOFF_SECONDS ").append(com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.HANDOFF_SECONDS).append("\n");
        sb.append("#define CMI_DOME_POSE ").append(com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.DOME_POSE).append("u\n");
        sb.append("// ==== CMI particle engine common constants ====\n");
        sb.append("// ==== GENERATED from ParticleBuffers by ParticlePrograms -- edit THERE, not here ====\n");
        sb.append("#define BIND_IDENTITY ").append(ParticleBuffers.IDENTITY_BB).append('\n');
        sb.append("#define BIND_POPULATION_READ ").append(ParticleBuffers.POPULATION_READ_BB).append('\n');
        sb.append("#define BIND_POPULATION_WRITE ").append(ParticleBuffers.POPULATION_WRITE_BB).append('\n');
        sb.append("#define BIND_WAVE_STAMP ").append(ParticleBuffers.WAVE_STAMP_BB).append('\n');
        sb.append("#define BIND_DISPATCH ").append(ParticleBuffers.DISPATCH_BB).append('\n');
        sb.append("#define BIND_POOL_READ ").append(ParticleBuffers.PARTICLE_BB_READ).append('\n');
        sb.append("#define BIND_POOL_WRITE ").append(ParticleBuffers.PARTICLE_BB_WRITE).append('\n');
        sb.append("#define BIND_INDIRECT ").append(ParticleBuffers.INDIRECT_BB).append('\n');
        sb.append("#define BIND_COUNTER ").append(ParticleBuffers.COUNTER_BB).append('\n');
        sb.append("#define BIND_EMITCMD ").append(ParticleBuffers.EMIT_BB).append('\n');
        sb.append("#define BIND_EMITTER ").append(ParticleBuffers.EMITTER_BB).append('\n');
        sb.append("#define BIND_SORT_READ ").append(ParticleBuffers.SORTREAD_BINDING).append('\n');
        sb.append("#define BIND_SORT_WRITE ").append(ParticleBuffers.SORTWRITE_BINDING).append('\n');
        sb.append("#define BIND_ORDER_ADD ").append(ParticleBuffers.ORDERADD_BINDING).append('\n');
        sb.append("#define BIND_HIST ").append(ParticleBuffers.HIST_BINDING).append('\n');
        sb.append("#define BIND_OFFSETS ").append(ParticleBuffers.OFFSET_BINDING).append('\n');
        sb.append("#define BIND_BAKEMETA ").append(ParticleBuffers.BAKEMETA_BINDING).append('\n');
        sb.append("#define BIND_MODELGEO ").append(ParticleBuffers.MODELGEO_BINDING).append('\n');
        sb.append("#define BIND_PREV_COUNTER ").append(ParticleBuffers.PREVCOUNTER_BINDING).append('\n');
        sb.append("#define BIND_ORDER_OPAQUE ").append(ParticleBuffers.ORDEROPAQUE_BINDING).append('\n');
        sb.append("#define BIND_GRID ").append(ParticleBuffers.GRID_BB).append('\n');
        // NO 'u' suffix: used as a GLSL ARRAY DIMENSION, which wants a signed
        // integer constant; mixed int/uint arithmetic at the use sites promotes
        // correctly.
        sb.append("#define GRID_TABLE ").append(ParticleBuffers.GRID_TABLE).append('\n');
        sb.append("#define INDIRECT_COMMANDS ").append(ParticleBuffers.INDIRECT_COMMANDS).append('\n');
        sb.append("#define INDIRECT_STRIDE ").append(ParticleBuffers.INDIRECT_STRIDE).append('\n');
        sb.append("#define INDIRECT_UINTS ").append(ParticleBuffers.INDIRECT_UINTS).append('\n');
        sb.append("#define IDX_CNT_ADD ").append(ParticleBuffers.IDX_CNT_ADD).append('\n');
        sb.append("#define IDX_CNT_SPRITE ").append(ParticleBuffers.IDX_CNT_SPRITE).append('\n');
        sb.append("#define IDX_CNT_MODELOP ").append(ParticleBuffers.IDX_CNT_MODELOP).append('\n');
        sb.append("#define IDX_CNT_CARRIER ").append(ParticleBuffers.IDX_CNT_CARRIER).append('\n');
        sb.append("#define IDX_CNT_GHOST ").append(ParticleBuffers.IDX_CNT_GHOST).append('\n');
        sb.append("#define IDX_CNT_ALPHA ").append(ParticleBuffers.IDX_CNT_ALPHA).append('\n');
        sb.append("#define VEC4_PER_PARTICLE ").append(ParticleBuffers.VEC4_PER_PARTICLE).append("u\n");
        sb.append("#define VEC4_PER_EMITTER ").append(ParticleBuffers.VEC4_PER_EMITTER).append("u\n");
        sb.append("#define SORT_GROUP_THRESHOLD ").append(ParticleBuffers.SORT_GROUP_THRESHOLD).append("u\n");
        sb.append("#define RADIX_BINS ").append(ParticleBuffers.RADIX_BINS).append("u\n");
        sb.append("#define DEPTH_BANDS ").append(ParticleBuffers.DEPTH_BANDS).append("u\n");
        sb.append("#define BAND_NEAR ").append(ParticleBuffers.BAND_NEAR).append('\n');
        // bit position of the material bits inside the 10-bit sort key (8 = low byte is the depth band)
        sb.append("#define SORT_KEY_TYPE_SHIFT ").append(ParticleBuffers.SORT_TYPE_SHIFT).append("u\n");
        sb.append("#define MODEL_VERTEX_FLOATS ").append(AllayModelGeometry.VERTEX_FLOATS).append('\n');
        // HP / melee-hit system
        sb.append("#define BIND_DAMAGE ").append(ParticleBuffers.DAMAGE_BB).append('\n');
        sb.append("#define BIND_HIT ").append(ParticleBuffers.HIT_BB).append('\n');
        sb.append("#define DAMAGE_QUEUE_CAP ").append(ParticleBuffers.DAMAGE_QUEUE_CAP).append("u\n");
        // storm sync: all-player repulsion, correction slots, authority readback
        sb.append("#define BIND_PLAYERS ").append(ParticleBuffers.PLAYERS_BB).append('\n');
        sb.append("#define BIND_CORRECTION ").append(ParticleBuffers.CORRECTION_BB).append('\n');
        sb.append("#define BIND_STORMPOS ").append(ParticleBuffers.STORMPOS_BB).append('\n');
        // dive-wave contact self-report staging
        sb.append("#define BIND_WAVECONTACT ").append(ParticleBuffers.WAVECONTACT_BB).append('\n');
        sb.append("#define WAVECONTACT_CAP ").append(ParticleBuffers.WAVECONTACT_CAP).append("u\n");
        // storm identity packing: bits 0..17 memberIdx+1, bit 18 the wave latch
        sb.append("#define MEMBER_IDX_MASK ").append(ParticleBuffers.MEMBER_IDX_MASK).append("u\n");
        sb.append("#define MEMBER_LATCH_BIT ").append(ParticleBuffers.MEMBER_LATCH_BIT).append("u\n");
        // storm identity -> pool-slot map (combat origin resolution)
        sb.append("#define BIND_MEMBERMAP ").append(ParticleBuffers.MEMBERMAP_BB).append('\n');
        // keygen's carrier-sink dual-write target (second sort buffer)
        sb.append("#define BIND_CARRIERSINK ").append(ParticleBuffers.CARRIERSINK_BB).append('\n');
        sb.append("#define MAX_STORM_PLAYERS ").append(ParticleBuffers.MAX_STORM_PLAYERS).append("u\n");
        sb.append("#define STORMPOS_CAP ").append(ParticleBuffers.STORMPOS_CAP).append("u\n");
        // held-item carrier region capacity (keygen's overflow guard)
        sb.append("#define CARRIER_CAP ").append(ParticleBuffers.CARRIER_CAP).append("u\n");
        // vanilla pick forgiveness (unscaled AABB inflation, like GameRenderer)
        sb.append("#define HIT_INFLATE ").append(ParticleBuffers.HIT_INFLATE).append('\n');
        // rest-pose model above-feet height in blocks (vanilla size divisor)
        sb.append("#define MODEL_ABOVE_FEET ").append(AllayModelGeometry.MODEL_ABOVE_FEET).append('\n');
        // held item (MODEL partId 7): the vanilla handheld display transform as
        // ONE constant mat4 — JOML-computed from the frozen vanilla constants,
        // see HeldItemGeometry (the pack merged source declares it as a const
        // instead — #defines do not survive the AST transplant)
        sb.append("#define CMI_HELD_DISPLAY ").append(HeldItemGeometry.displayMatrixGLSL()).append('\n');
        sb.append("#define DAMAGE_ENTRY_WORDS ").append(ParticleBuffers.DAMAGE_ENTRY_BYTES / 4).append("u\n");
        for (var material : com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Material.values())
            sb.append("#define CMI_MATERIAL_").append(material.name()).append(' ').append(material.index()).append("u\n");
        return sb.toString();
    }

    private int prepareDispatch;
    public int prepareDispatch() { return prepareDispatch; }
    private int reset;
    private int update;
    private int emit;
    private int blockEmit;
    private int keygen;
    private int radixHist;
    private int radixScan;
    private int radixScatter;
    private int capture;
    private int grid;           // boids spatial-hash build (storm swarms)
    private int domeContacts;
    public int domeContacts(){return domeContacts;}
    private int domePath;
    public int domePath(){return domePath;}
    private int hit;            // per-frame crosshair hit query (melee targeting)
    private int damagePrepare;
    public int damagePrepare(){return damagePrepare;}
    private int columnRecovery;
    public int columnRecovery() { return columnRecovery; }
    private int columnSnapshot;
    public int columnSnapshot() { return columnSnapshot; }
    private int stormPos;       // authority readback: near-player members (storm sync)
    private int ringReserve;
    public int ringReserve(){return ringReserve;}
    private int waveContact;    // dive-wave contact self-report detection (storm waves)
    private int render;          // additive billboards (soft circle)
    private int texturedRender;  // textured sprite billboards: uMode 0 blended / 1 OPAQUE cutout
    private int modelRender;     // instanced allay models via one merged multi-draw

    private volatile boolean dirty = true;
    private Map<String,String> committedSources=Map.of();
    private Reload pendingReload;

    /** Marks the programs stale; {@link #rebuild()} is safe to call any time. */
    public void requestRebuild() {
        this.dirty = true;
    }

    public boolean needsRebuild() {
        return this.dirty||this.pendingReload!=null;
    }

    /** Advances the pending compilation; returns true only on atomic publication. Render-thread only. */
    public boolean rebuild() {
        return rebuildWithSources(ParticlePrograms::load);
    }

    boolean rebuildWithSources(Function<String,String> loader){
        try {
            if(dirty){
                dirty=false;var sources=new LinkedHashMap<String,String>();
                for(var specification:SPECIFICATIONS){sources.computeIfAbsent(specification.path(),loader);if(specification.fragment!=null)sources.computeIfAbsent(GLSL_DIR+specification.fragment,loader);}
                for(var specification:SPECIFICATIONS)if(!sources.containsKey(specification.path())||specification.fragment!=null&&!sources.containsKey(GLSL_DIR+specification.fragment))throw new IllegalStateException("Missing particle shader: "+specification.path());
                if(pendingReload==null||!pendingReload.sources.equals(sources)){
                    if(pendingReload!=null){pendingReload.close();pendingReload=null;}
                    if(ready()&&committedSources.equals(sources)){LOGGER.info("[CMI particles] shader sources unchanged; reusing active programs");return false;}
                    pendingReload=new Reload(Map.copyOf(sources));
                }
            }
            if(pendingReload==null||!pendingReload.advance())return false;
            Reload finished=pendingReload;pendingReload=null;
            try {
                boolean published=rebuildWithCompiler(finished::take,(vertex,fragment)->finished.take(vertex+"|"+fragment));
                if(published){committedSources=finished.sources;LOGGER.info("[CMI particles] shader set published atomically ({}ms)",(System.nanoTime()-finished.started)/1_000_000L);}
                return published;
            } finally {finished.close();}
        } catch(RuntimeException failure){
            if(pendingReload!=null){pendingReload.close();pendingReload=null;}
            LOGGER.error("[CMI particles] shader reload failed; retaining previous program set",failure);return false;
        }
    }

    /** Package-private injection seam for real-driver reload failure tests. */
    boolean rebuildWithCompiler(java.util.function.ToIntFunction<String> compute,
                             java.util.function.BiFunction<String, String, Integer> graphics) {
        this.dirty = false;
        ParticlePrograms candidate = new ParticlePrograms();
        try {
            for(var specification:SPECIFICATIONS)specification.install.accept(candidate,specification.fragment==null?compute.applyAsInt(specification.path()):graphics.apply(specification.path(),GLSL_DIR+specification.fragment));
            if (!candidate.ready()) {
                LOGGER.error("[CMI particles] shader reload failed; retaining previous program set");
                return false;
            }
            this.delete();
            this.prepareDispatch = candidate.prepareDispatch; candidate.prepareDispatch = 0;
            this.reset = candidate.reset; candidate.reset = 0;
            this.update = candidate.update; candidate.update = 0;
            this.emit = candidate.emit; candidate.emit = 0;
            this.blockEmit = candidate.blockEmit; candidate.blockEmit = 0;
            this.keygen = candidate.keygen; candidate.keygen = 0;
            this.radixHist = candidate.radixHist; candidate.radixHist = 0;
            this.radixScan = candidate.radixScan; candidate.radixScan = 0;
            this.radixScatter = candidate.radixScatter; candidate.radixScatter = 0;
            this.capture = candidate.capture; candidate.capture = 0;
            this.grid = candidate.grid; candidate.grid = 0;
            this.domeContacts = candidate.domeContacts; candidate.domeContacts = 0;
            this.domePath = candidate.domePath; candidate.domePath = 0;
            this.hit = candidate.hit; candidate.hit = 0;
            this.damagePrepare=candidate.damagePrepare;candidate.damagePrepare=0;
            this.stormPos = candidate.stormPos; candidate.stormPos = 0;
            this.columnSnapshot = candidate.columnSnapshot; candidate.columnSnapshot = 0;
            this.columnRecovery = candidate.columnRecovery; candidate.columnRecovery = 0;
            this.ringReserve=candidate.ringReserve;candidate.ringReserve=0;
            this.waveContact = candidate.waveContact; candidate.waveContact = 0;
            this.render = candidate.render; candidate.render = 0;
            this.texturedRender = candidate.texturedRender; candidate.texturedRender = 0;
            this.modelRender = candidate.modelRender; candidate.modelRender = 0;
            return true;
        } finally {
            candidate.delete();
        }
    }

    public void configureStatic(int capacity) {
        for (int p : new int[] {prepareDispatch, update, columnRecovery, emit, blockEmit}) {
            int location = GL20.glGetUniformLocation(p, "uCapacity");
            if (location >= 0) org.lwjgl.opengl.GL41.glProgramUniform1ui(p, location, capacity);
        }
    }

    public boolean ready() {
        return this.prepareDispatch != 0 && this.blockEmit != 0 && this.reset != 0 && this.update != 0 && this.emit != 0 && this.render != 0
                && this.texturedRender != 0 && this.modelRender != 0
                && this.keygen != 0 && this.radixHist != 0 && this.radixScan != 0
                && this.radixScatter != 0 && this.capture != 0 && this.grid != 0
                && this.hit != 0 && this.damagePrepare!=0 && this.stormPos != 0 && this.columnSnapshot != 0 && this.columnRecovery != 0 && this.waveContact != 0 && this.ringReserve!=0 && this.domeContacts!=0 && this.domePath!=0;
    }

    public int reset() {
        return this.reset;
    }

    public int update() {
        return this.update;
    }

    public int blockEmit() { return this.blockEmit; }

    public int emit() {
        return this.emit;
    }

    public int keygen() {
        return this.keygen;
    }

    public int radixHist() {
        return this.radixHist;
    }

    public int radixScan() {
        return this.radixScan;
    }

    public int radixScatter() {
        return this.radixScatter;
    }

    public int capture() {
        return this.capture;
    }

    public int grid() {
        return this.grid;
    }

    public int hit() {
        return this.hit;
    }

    public int stormPos() {
        return this.stormPos;
    }

    public int waveContact() {
        return this.waveContact;
    }

    public int render() {
        return this.render;
    }

    public int texturedRender() {
        return this.texturedRender;
    }

    public int modelRender() {
        return this.modelRender;
    }

    // ------------------------------------------------------------------
    // GL helpers
    // ------------------------------------------------------------------

    /**
     * Public plain-source loader (include-resolved, no PRELUDE/#version) for
     * consumers outside this package -- the shader-pack compiler assembles the
     * merged MODEL vertex source from the same chunk files this class compiles.
     */
    public static String loadPlain(String path) {
        String s = load(path);
        return s == null ? "" : s;
    }

    /** Include-resolved source named relative to shaders/particles/, without #version or PRELUDE. */
    public static String loadParticlePlain(String name) {
        String s = ParticleShaderSource.loadParticle(name, ParticlePrograms::readSource);
        return s == null ? "" : s;
    }

    /** Per-tier atlas UV rects ({@code uHeldItemUV}) for the MODEL render path. */

    /**
     * Loads a bundled shader file as text, or null on failure. Lines of the form
     * {@code #pragma cmi_include chunks/name.glsl} (path relative to
     * {@code shaders/particles/}) are replaced with the referenced file's content,
     * recursively up to a small depth bound. This is the shared-source mechanism
     * that keeps the pose math single-sourced between model.vsh and the
     * shader-pack merged programs (see ParticleVertexInjector).
     */
    private static String load(String path) {
        return ParticleShaderSource.loadResource(path, ParticlePrograms::readSource);
    }

    private static String readSource(String path) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        ResourceLocation id = CreateManaIndustry.modLoc(path);
        String raw;
        try (Reader r = rm.openAsReader(id)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1)
                sb.append(buf, 0, n);
            raw = sb.toString();
        } catch (IOException e) {
            LOGGER.error("[CMI particles] cannot read shader {}", id, e);
            return null;
        }
        for (String phase : new String[] {"spawn", "update"})
            if (raw.contains("#pragma cmi_types " + phase)) raw = raw.replace("#pragma cmi_types " + phase,
                    com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks(phase));
        return raw;
    }

    /** Deletes all program ids. Render-thread only. */
    public void delete() {
        if(pendingReload!=null){pendingReload.close();pendingReload=null;}
        committedSources=Map.of();
        for (int p : new int[] {
                this.prepareDispatch, this.reset, this.update, this.emit, this.blockEmit, this.keygen,
                this.radixHist, this.radixScan, this.radixScatter, this.capture,
                this.grid, this.hit, this.damagePrepare,this.stormPos,this.columnSnapshot,this.columnRecovery, this.waveContact, this.ringReserve,this.domeContacts,this.domePath,
                this.render, this.texturedRender, this.modelRender }) {
            if (p != 0){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(p);}
        }
        this.prepareDispatch = this.reset = this.update = this.emit = this.blockEmit = this.keygen = 0;
        this.radixHist = this.radixScan = this.radixScatter = this.capture = 0;
        this.grid = this.hit = this.damagePrepare = this.stormPos = this.waveContact = this.ringReserve = 0;
        this.domeContacts=0;this.domePath=0;this.columnSnapshot=0;this.columnRecovery=0;
        this.render = this.texturedRender = this.modelRender = 0;
    }
}
