package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;

import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

/**
 * BlockState → 16-bit render-state id table (doc §7.1 {@code AllvrRenderStateMap}).
 * <p>
 * V0 simplification: ids are assigned lazily at mesh time (not at resource
 * load) and carry what the V0 forward pass consumes — the atlas sprite rect of
 * EACH FACE of the state's block model (assumed a full cube: at least one
 * culled quad per direction, the island generator only produces
 * stone/dirt/grass), the resolved per-face biome tint, and a {@code renderable}
 * flag. States with multipart/overlay quads, partial geometry (stairs,
 * torches, …), or non-solid render types resolve to a non-renderable entry
 * and are handed to the generic dispatcher stream; descriptor certification
 * never silently drops model geometry.
 * <p>
 * Face order is {@link AllvrMesher#FACES} ({@code axis*2 + dir}, dir 0 =
 * positive axis) — the same index the vertex shader derives and uses to fetch
 * this table, so a multi-texture block (grass: dirt bottom / grass top /
 * grass side) textures every face with its own sprite.
 * <p>
 * Accessed from mesher worker threads; id assignment and table growth are
 * synchronized (lookups of existing states are unsynchronized map reads).
 * The float table is uploaded to the {@code AllvrBuffers} state TBO by the
 * render thread: {@link #TEXELS_PER_ENTRY} texels per id
 * (6 faces × uvRect / tint+flag / half-texel inset).
 */
public final class AllvrRenderStateMap {

    public static final short ID_AIR = 0;

    /**
     * Client-side mesh codec: the 16-bit render id, gated on the per-state
     * {@code renderable} flag (full-cube model assumption). Consumed by the
     * common mesher ({@code dimension.mesh.AllvrMesher}).
     */
    public static final com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshCodec CLIENT_CODEC =
        state -> {
            short id = idOf(state);
            return entryOf(id).renderable ? id : 0;
        };

    /** Texture-buffer layout: per face (uvRect, tint rgb + renderable flag, inset). */
    public static final int FACES = 6;
    public static final int TEXELS_PER_FACE = 3;
    public static final int TEXELS_PER_ENTRY = FACES * TEXELS_PER_FACE;

    /** One face's material: sprite rect (atlas space), tint rgb, half-texel inset (tile space). */
    private record FaceMaterial(float u0, float v0, float du, float dv,
            float tintR, float tintG, float tintB, float insetU, float insetV) {}

    /** Per-id material: per-face {@link FaceMaterial} (FACES order) + renderable flag. */
    public static final class Entry {
        public final FaceMaterial[] faces;
        public final boolean renderable;

        Entry(FaceMaterial[] faces, boolean renderable) {
            this.faces = faces;
            this.renderable = renderable;
        }
    }

    /** Lock-free reads from mesher workers; id assignment is atomic per state. */
    private static final Map<BlockState, Short> IDS = new ConcurrentHashMap<>();
    /** CopyOnWrite so worker {@link #entryOf} reads never race a resize. */
    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    /** Parallel to {@link #ENTRIES}: the state behind each id (null for air),
     *  feeding the iris customId resolution (grilling decision ⑦). */
    private static final List<BlockState> STATES = new CopyOnWriteArrayList<>();

    /**
     * Per-id iris block-material ids (the pack patch's {@code customId} —
     * Photon derives its material mask from {@code customId − 10000}). Written
     * render-thread only by {@link #setCustomIds}; read by
     * {@link #packedTable} for the spare texel of each face's inset entry.
     * {@link #customIdRevision} bumps on every change so the renderer can
     * re-upload the TBO lazily.
     */
    private static volatile int[] customIds = new int[] {0};
    private static volatile int customIdRevision = 0;

    private static final Entry NON_RENDERABLE = new Entry(zeroFaces(), false);

    private static FaceMaterial[] zeroFaces() {
        FaceMaterial[] faces = new FaceMaterial[FACES];
        for (int i = 0; i < FACES; i++) {
            faces[i] = new FaceMaterial(0, 0, 0, 0, 1, 1, 1, 0, 0);
        }
        return faces;
    }

    static {
        ENTRIES.add(NON_RENDERABLE); // id 0 = air
        STATES.add(null);
    }

    /** Id for a state, assigning one lazily. Thread-safe. */
    public static short idOf(BlockState state) {
        if (state.isAir()) {
            return ID_AIR;
        }
        Short boxed = IDS.get(state);
        if (boxed != null) {
            return boxed;
        }
        synchronized (AllvrRenderStateMap.class) {
            boxed = IDS.get(state);
            if (boxed != null) {
                return boxed;
            }
            // Model managers and color providers are mutable client resources.
            // A build worker may only consume the immutable table populated on
            // the render thread; it must never resolve a new model in the
            // background.  The owning cube apply path calls prepareCube first.
            if (!Minecraft.getInstance().isSameThread()) {
                return ID_AIR;
            }
            short id = (short) ENTRIES.size();
            if (id >= Short.MAX_VALUE) {
                com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER
                    .error("[Allvr] render state id space exhausted at {}", state);
                return ID_AIR;
            }
            IDS.put(state, id);
            ENTRIES.add(resolveEntry(state));
            STATES.add(state);
            // the fresh entry has no resolved customId yet — the renderer's
            // revision check re-runs setCustomIds and re-uploads the TBO
            customIdRevision++;
            return id;
        }
    }

    /** Resolves all states present in one decoded cube on the client thread. */
    public static void prepareCube(AllvrCube cube) {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("ALLVR model preparation must run on the client thread");
        }
        for (var section : cube.getSections()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        idOf(section.getBlockState(x, y, z));
                    }
                }
            }
        }
    }

    /**
     * Re-resolves the customId column against {@code ids} (iris's
     * {@code WorldRenderingSettings.getBlockStateIds()}, live read by the
     * pipeline data). Returns the new revision. Full re-resolve on every call
     * — pack switches re-run it wholesale, zero re-mesh (grilling decision ⑦).
     */
    public static int setCustomIds(it.unimi.dsi.fastutil.objects.Object2IntMap<BlockState> ids) {
        int n = ENTRIES.size();
        int[] out = new int[n];
        if (ids != null) {
            for (int i = 0; i < n && i < STATES.size(); i++) {
                BlockState state = STATES.get(i);
                out[i] = state == null ? 0 : ids.getOrDefault(state, 0);
            }
        }
        customIds = out;
        return ++customIdRevision;
    }

    /** Revision of the customId column — the renderer re-uploads when it moves. */
    public static int customIdRevision() {
        return customIdRevision;
    }

    public static Entry entryOf(int id) {
        return ENTRIES.get(id);
    }

    public static int entryCount() {
        return ENTRIES.size();
    }

    /**
     * Drops all resource-derived model/material entries.  The next client
     * thread preparation pass repopulates the table from the new model
     * manager; in-flight workers can only observe the old epoch/revision and
     * are rejected by the renderer.
     */
    public static synchronized void invalidateResources() {
        IDS.clear();
        ENTRIES.clear();
        STATES.clear();
        ENTRIES.add(NON_RENDERABLE);
        STATES.add(null);
        customIds = new int[] {0};
        customIdRevision++;
    }

    /** Packed float table for the state TBO: TEXELS_PER_FACE vec4 per face × 6 faces per id.
     *  The inset texel's z component carries the iris customId (spare texel, grilling
     *  decision ⑦); w stays spare. */
    public static float[] packedTable() {
        float[] out = new float[ENTRIES.size() * TEXELS_PER_ENTRY * 4];
        int[] ids = customIds;
        int i = 0;
        for (int e = 0; e < ENTRIES.size(); e++) {
            Entry entry = ENTRIES.get(e);
            int customId = e < ids.length ? ids[e] : 0;
            for (FaceMaterial f : entry.faces) {
                out[i++] = f.u0();
                out[i++] = f.v0();
                out[i++] = f.du();
                out[i++] = f.dv();
                out[i++] = f.tintR();
                out[i++] = f.tintG();
                out[i++] = f.tintB();
                out[i++] = entry.renderable ? 1.0f : 0.0f;
                out[i++] = f.insetU();
                out[i++] = f.insetV();
                out[i++] = (float) customId;
                out[i++] = 0.0f;
            }
        }
        return out;
    }

    /**
     * Descriptor certification: exactly one culled quad per direction and no
     * unculled overlay quad. Anything else (missing face, multipart/overlay,
     * partial model, null) resolves to the generic dispatcher stream. This is
     * intentionally conservative: a state is fast-pathed only when the
     * descriptor is provably equivalent to the baked model.
     */
    private static Entry resolveEntry(BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        // This is the shared certification consumed by both the descriptor
        // codec and the fallback collector. A six-quad model alone is not
        // proof of full-cube solid semantics (glass, offset and multipart
        // models commonly satisfy weaker tests).
        if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
            || ItemBlockRenderTypes.getChunkRenderType(state) != RenderType.solid()) {
            return NON_RENDERABLE;
        }
        BakedModel model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
        if (model == null) {
            return NON_RENDERABLE;
        }
        RandomSource rand = RandomSource.create();
        FaceMaterial[] faces = new FaceMaterial[FACES];
        for (int i = 0; i < FACES; i++) {
            List<net.minecraft.client.renderer.block.model.BakedQuad> quads =
                model.getQuads(state, AllvrMesher.FACES[i], rand);
            if (quads.size() != 1) {
                return NON_RENDERABLE;
            }
            net.minecraft.client.renderer.block.model.BakedQuad quad = quads.get(0);
            if (quad.getDirection() != AllvrMesher.FACES[i]) {
                return NON_RENDERABLE;
            }
            // A world-dependent tint cannot be represented by the immutable
            // descriptor material table; route it through the generic model
            // compiler where the real BlockAndTintGetter is available.
            if (quad.isTinted()) {
                return NON_RENDERABLE;
            }
            var sprite = quad.getSprite();
            var ticker = sprite.createTicker();
            if (ticker != null) {
                ticker.close();
                return NON_RENDERABLE;
            }
            float tr = 1, tg = 1, tb = 1;
            faces[i] = new FaceMaterial(
                sprite.getU0(), sprite.getV0(),
                sprite.getU1() - sprite.getU0(), sprite.getV1() - sprite.getV0(),
                tr, tg, tb,
                0.5f / Math.max(1, sprite.contents().width()),
                0.5f / Math.max(1, sprite.contents().height()));
        }
        if (!model.getQuads(state, null, rand).isEmpty()) {
            return NON_RENDERABLE;
        }
        return new Entry(faces, true);
    }

    private AllvrRenderStateMap() {}
}
