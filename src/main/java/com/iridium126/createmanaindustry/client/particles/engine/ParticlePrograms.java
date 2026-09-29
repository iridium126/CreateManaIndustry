package com.iridium126.createmanaindustry.client.particles.engine;

import java.io.IOException;
import java.io.Reader;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

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
 * reload listener flips so F3+T recompiles shaders.
 */
public final class ParticlePrograms {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ParticlePrograms.class);

    private static final String GLSL_DIR = "shaders/particles/";
    private static final String VERSION = "#version 450 core\n";

    /**
     * Common GLSL header injected ahead of every shader source: engine-wide
     * constants generated from the {@code ParticleBuffers} constants so there
     * is exactly one place to change a binding or an indirect-layout index.
     * Plain {@code #define}s rather than {@code const}s because they must stay
     * legal inside {@code layout()} qualifiers on every driver (macro
     * substitution is textual and needs no constant-expression support).
     */
    private static final String PRELUDE = buildPrelude();

    private static String buildPrelude() {
        StringBuilder sb = new StringBuilder(768);
        sb.append("// ==== CMI particle engine common constants ====\n");
        sb.append("// ==== GENERATED from ParticleBuffers by ParticlePrograms -- edit THERE, not here ====\n");
        sb.append("#define BIND_IDENTITY ").append(ParticleBuffers.IDENTITY_BB).append('\n');
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
        sb.append("#define IDX_CNT_PATTERN ").append(ParticleBuffers.IDX_CNT_PATTERN).append('\n');
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
        sb.append("#define BIND_HEX_INPUT ").append(HexPatternBuffers.INPUT_BIND).append('\n');
        sb.append("#define BIND_HEX_LIVE ").append(HexPatternBuffers.LIVE_BIND).append('\n');
        sb.append("#define BIND_HEX_RESOURCE ").append(HexPatternBuffers.RESOURCE_BIND).append('\n');
        sb.append("#define BIND_HEX_COMMAND ").append(HexPatternBuffers.COMMAND_BIND).append('\n');
        sb.append("#define BIND_HEX_POINT ").append(HexPatternBuffers.POINT_BIND).append('\n');
        sb.append("#define CMI_HEX_META_ROWS ").append(HexPatternBuffers.META_ROWS).append("u\n");
        sb.append("#define CMI_HEX_ANCHORS ").append(HexPatternBuffers.ANCHOR_BASE).append("u\n");
        sb.append("#define CMI_HEX_VERTEX_STRIDE ").append(HexPatternBuffers.VERTEX_STRIDE).append("u\n");
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
    private int hit;            // per-frame crosshair hit query (melee targeting)
    private int stormPos;       // authority readback: near-player members (storm sync)
    private int waveContact;    // dive-wave contact self-report detection (storm waves)
    private int render;          // additive billboards (soft circle)
    private int texturedRender;  // textured sprite billboards: uMode 0 blended / 1 OPAQUE cutout
    private int hexReconcile, hexPrepare, hexRender;
    private int modelRender;     // instanced allay models via one merged multi-draw

    private volatile boolean dirty = true;

    /** Marks the programs stale; {@link #rebuild()} is safe to call any time. */
    public void requestRebuild() {
        this.dirty = true;
    }

    public boolean needsRebuild() {
        return this.dirty;
    }

    /** Compiles/links all programs from the mod's bundled GLSL. Render-thread only. */
    public boolean rebuild() {
        return rebuildWithCompiler(ParticlePrograms::compileCompute, ParticlePrograms::link);
    }

    /** Package-private injection seam for real-driver reload failure tests. */
    boolean rebuildWithCompiler(java.util.function.ToIntFunction<String> compute,
                             java.util.function.BiFunction<String, String, Integer> graphics) {
        this.dirty = false;
        ParticlePrograms candidate = new ParticlePrograms();
        try {
            candidate.prepareDispatch = compute.applyAsInt(GLSL_DIR + "prepare_dispatch.comp");
            candidate.reset = compute.applyAsInt(GLSL_DIR + "reset.comp");
            candidate.update = compute.applyAsInt(GLSL_DIR + "update.comp");
            candidate.emit = compute.applyAsInt(GLSL_DIR + "emit.comp");
            candidate.blockEmit = compute.applyAsInt(GLSL_DIR + "block_emit.comp");
            candidate.keygen = compute.applyAsInt(GLSL_DIR + "keygen.comp");
            candidate.radixHist = compute.applyAsInt(GLSL_DIR + "radix_hist.comp");
            candidate.radixScan = compute.applyAsInt(GLSL_DIR + "radix_scan.comp");
            candidate.radixScatter = compute.applyAsInt(GLSL_DIR + "radix_scatter.comp");
            candidate.capture = compute.applyAsInt(GLSL_DIR + "capture.comp");
            candidate.grid = compute.applyAsInt(GLSL_DIR + "gridbuild.comp");
            candidate.hit = compute.applyAsInt(GLSL_DIR + "hit.comp");
            candidate.stormPos = compute.applyAsInt(GLSL_DIR + "stormpos.comp");
            candidate.waveContact = compute.applyAsInt(GLSL_DIR + "wavecontact.comp");
            candidate.render = graphics.apply(GLSL_DIR + "additive.vsh", GLSL_DIR + "additive.fsh");
            candidate.texturedRender = graphics.apply(GLSL_DIR + "textured.vsh", GLSL_DIR + "textured.fsh");
            candidate.modelRender = graphics.apply(GLSL_DIR + "model.vsh", GLSL_DIR + "model.fsh");
            candidate.hexReconcile = compute.applyAsInt(GLSL_DIR + "hex_reconcile.comp");
            candidate.hexPrepare = compute.applyAsInt(GLSL_DIR + "hex_prepare.comp");
            candidate.hexRender = graphics.apply(GLSL_DIR + "hex_pattern.vsh", GLSL_DIR + "hex_pattern.fsh");
            if (!candidate.ready() || !candidate.hexReady()) {
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
            this.hit = candidate.hit; candidate.hit = 0;
            this.stormPos = candidate.stormPos; candidate.stormPos = 0;
            this.waveContact = candidate.waveContact; candidate.waveContact = 0;
            this.render = candidate.render; candidate.render = 0;
            this.texturedRender = candidate.texturedRender; candidate.texturedRender = 0;
            this.modelRender = candidate.modelRender; candidate.modelRender = 0;
            this.hexReconcile = candidate.hexReconcile; candidate.hexReconcile = 0;
            this.hexPrepare = candidate.hexPrepare; candidate.hexPrepare = 0;
            this.hexRender = candidate.hexRender; candidate.hexRender = 0;
            return true;
        } finally {
            candidate.delete();
        }
    }

    public void configureStatic(int capacity) {
        for (int p : new int[] {prepareDispatch, update, emit, blockEmit, hexReconcile}) {
            int location = GL20.glGetUniformLocation(p, "uCapacity");
            if (location >= 0) org.lwjgl.opengl.GL41.glProgramUniform1ui(p, location, capacity);
        }
    }

    public boolean ready() {
        return this.prepareDispatch != 0 && this.blockEmit != 0 && this.reset != 0 && this.update != 0 && this.emit != 0 && this.render != 0
                && this.texturedRender != 0 && this.modelRender != 0
                && this.keygen != 0 && this.radixHist != 0 && this.radixScan != 0
                && this.radixScatter != 0 && this.capture != 0 && this.grid != 0
                && this.hit != 0 && this.stormPos != 0 && this.waveContact != 0;
    }

    public boolean hexReady() { return hexReconcile != 0 && hexPrepare != 0 && hexRender != 0; }
    public int hexReconcile() { return hexReconcile; }
    public int hexPrepare() { return hexPrepare; }
    public int hexRender() { return hexRender; }

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

    /** Compiles a single compute shader into a standalone compute program. */
    private static int compileCompute(String path) {
        String src = load(path);
        if (src == null)
            return 0;
        int shader = compileStage(VERSION + PRELUDE + src, GL43.GL_COMPUTE_SHADER);
        if (shader == 0)
            return 0;
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("[CMI particles] compute link failed ({}): {}", path,
                    GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int link(String vshPath, String fshPath) {
        String vs = load(vshPath);
        String fs = load(fshPath);
        if (vs == null || fs == null)
            return 0;
        int vsh = compileStage(VERSION + PRELUDE + vs, GL20.GL_VERTEX_SHADER);
        int fsh = compileStage(VERSION + PRELUDE + fs, GL20.GL_FRAGMENT_SHADER);
        if (vsh == 0 || fsh == 0) {
            if (vsh != 0)
                GL20.glDeleteShader(vsh);
            if (fsh != 0)
                GL20.glDeleteShader(fsh);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vsh);
        GL20.glAttachShader(prog, fsh);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vsh);
        GL20.glDeleteShader(fsh);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("[CMI particles] render link failed ({}): {}", vshPath,
                    GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int compileStage(String source, int type) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) {
            LOGGER.error("[CMI particles] glCreateShader({}) returned 0", type);
            return 0;
        }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("[CMI particles] shader compile failed (type {}): {}", type,
                    GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

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

    /** Vertex stride of the baked MODEL geometry (public for the shader-pack compiler). */
    public static int modelVertexFloats() {
        return AllayModelGeometry.VERTEX_FLOATS;
    }

    /**
     * Above-feet height in blocks of the rest-pose model — the vanilla-size
     * scale divisor (public for the shader-pack merged vertex source).
     */
    public static float modelAboveFeet() {
        return AllayModelGeometry.MODEL_ABOVE_FEET;
    }

    /**
     * The vanilla handheld display transform as a GLSL {@code mat4(...)}
     * constructor string — injected as {@code CMI_HELD_DISPLAY} into the
     * self-drawn prelude and the shader-pack merged vertex source.
     */
    public static String heldItemDisplayMatrix() {
        return HeldItemGeometry.displayMatrixGLSL();
    }

    /** Per-tier atlas UV rects ({@code uHeldItemUV}) for the MODEL render path. */
    public static float[] heldItemUVTable() {
        return HeldItemGeometry.uvTable();
    }

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
        for (int p : new int[] {
                this.prepareDispatch, this.reset, this.update, this.emit, this.blockEmit, this.keygen,
                this.radixHist, this.radixScan, this.radixScatter, this.capture,
                this.grid, this.hit, this.stormPos, this.waveContact,
                this.render, this.texturedRender, this.modelRender, this.hexReconcile, this.hexPrepare, this.hexRender }) {
            if (p != 0)
                GL20.glDeleteProgram(p);
        }
        this.prepareDispatch = this.reset = this.update = this.emit = this.blockEmit = this.keygen = 0;
        this.radixHist = this.radixScan = this.radixScatter = this.capture = 0;
        this.grid = this.hit = this.stormPos = this.waveContact = 0;
        this.render = this.texturedRender = this.modelRender = 0;
        this.hexReconcile = this.hexPrepare = this.hexRender = 0;
    }
}
