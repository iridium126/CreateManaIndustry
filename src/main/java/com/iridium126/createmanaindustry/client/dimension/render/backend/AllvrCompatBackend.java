package com.iridium126.createmanaindustry.client.dimension.render.backend;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.IntBuffer;
import java.util.ArrayList;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderCellKey;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

/**
 * Tier C compat floor for the ALLVR terrain pass (sodium-parity plan §7.7):
 * CPU render list + plain compact vertex/index batch drawing for contexts
 * below the merged Tier B gate (no gl_BaseInstance draw parameters, no
 * indirect-parameter MDIC) or when the Tier B compute pipeline fails in
 * session. Guarantees the near terrain stays VISIBLE; it does not chase
 * Tier B's performance ceiling (no GPU culling, no HiZ, no shadow pass).
 * <p>
 * Data plane: the packed 8-byte quad stream is EXPANDED into a per-vertex
 * VBO — 4 vertices per quad at 24 B each ({@code uvec2 quad descriptor,
 * ivec3 absolute origin, 4 B pad}), indexed by one shared z-order pattern
 * IBO covering the whole buffer (no {@code gl_BaseVertex}, so the pattern
 * must address every quad directly). Camera-relative math stays in the
 * shader ({@code ALLVR_COMPAT} branch of terrain.vsh).
 * <p>
 * Draw plane: one CPU frustum pass over the renderer's cubes (the exact
 * Gribb–Hartmann planes the GPU traversal would test), then ONE
 * {@code glMultiDrawElements} whose per-cube entries are plain
 * count/base-pointer pairs — core GL 1.4, no extensions.
 */
public final class AllvrCompatBackend {

    /** Quad capacity: 512k quads = 48 MB VBO + 12 MB IBO — the compat floor
     *  is a correctness tier, sized for one full-resolution view's worth of
     *  streamed cubes; beyond it meshes defer like the Tier B arena. */
    public static final int MAX_QUADS = 1 << 19;
    /** Upper bound of visible draw entries per frame (cube chunk count). */
    public static final int MAX_DRAW_ENTRIES = 1 << 16;

    /** Bytes per vertex (uvec2 quad + ivec3 origin + 4 B pad). */
    private static final int VERTEX_BYTES = 24;
    /** Vertices per quad. */
    private static final int VERTS_PER_QUAD = 4;
    /** Indices per quad in the shared pattern. */
    private static final int INDEX_INTS_PER_QUAD = 6;
    /** Bytes per quad in the index pattern. */
    private static final int INDEX_BYTES_PER_QUAD = INDEX_INTS_PER_QUAD * 4;

    private int vao;
    private int vbo;
    private int ibo;
    private long quadsCapacity;
    private long quadsUsed;
    /** Free quad ranges {start, size} — first-fit on alloc (same discipline
     *  as {@code AllvrBuffers}' arena so remesh churn cannot fragment it). */
    private final ArrayList<long[]> freeRanges = new ArrayList<>();

    private final IntBuffer drawCounts = BufferUtils.createIntBuffer(MAX_DRAW_ENTRIES);
    private final PointerBuffer drawPointers = BufferUtils.createPointerBuffer(MAX_DRAW_ENTRIES);

    public void ensure() {
        if (this.vao != 0) {
            return;
        }
        this.quadsCapacity = MAX_QUADS;
        this.vao = glGenVertexArrays();
        glBindVertexArray(this.vao);

        // shared relative index pattern covering the WHOLE VBO (compat draws
        // have no gl_BaseVertex: pattern entry k indexes vertices 4k..4k+3)
        int[] indices = new int[MAX_QUADS * INDEX_INTS_PER_QUAD];
        for (int q = 0; q < MAX_QUADS; q++) {
            int o = q * INDEX_INTS_PER_QUAD;
            int b = q * VERTS_PER_QUAD;
            indices[o] = b;
            indices[o + 1] = b + 1;
            indices[o + 2] = b + 2;
            indices[o + 3] = b + 2;
            indices[o + 4] = b + 1;
            indices[o + 5] = b + 3;
        }
        this.ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, this.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        // leave the IBO bound in the VAO — compat draws need it at draw time

        this.vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_QUADS * VERTS_PER_QUAD * VERTEX_BYTES, GL_DYNAMIC_DRAW);
        // aQuad (loc 0): 2×uint at offset 0
        org.lwjgl.opengl.GL30.glVertexAttribIPointer(0, 2, GL_UNSIGNED_INT, VERTEX_BYTES, 0);
        // aOrigin (loc 1): 3×int at offset 8
        org.lwjgl.opengl.GL30.glVertexAttribIPointer(1, 3, org.lwjgl.opengl.GL11.GL_INT, VERTEX_BYTES, 8);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public boolean ready() {
        return this.vao != 0;
    }

    /** Bump-pointer tail, quads (debug/stats). */
    public long quadsUsed() {
        return this.quadsUsed;
    }

    // ------------------------------------------------------------------
    // quad arena (same first-fit discipline as AllvrBuffers)
    // ------------------------------------------------------------------

    /** First-fit range of {@code size} quads; -1 when full (deferred retry). */
    public int allocRange(long size) {
        for (int i = 0; i < this.freeRanges.size(); i++) {
            long[] r = this.freeRanges.get(i);
            if (r[1] == size) {
                this.freeRanges.remove(i);
                return (int) r[0];
            }
            if (r[1] > size) {
                r[1] -= size;
                return (int) (r[0] + r[1]);
            }
        }
        if (this.quadsUsed + size > this.quadsCapacity) {
            return -1; // compat tier never grows — eviction belongs to M6
        }
        int start = (int) this.quadsUsed;
        this.quadsUsed += size;
        return start;
    }

    /** Same test as {@link #allocRange}'s success condition. */
    public boolean canFit(long size) {
        for (long[] r : this.freeRanges) {
            if (r[1] >= size) {
                return true;
            }
        }
        return this.quadsUsed + size <= this.quadsCapacity;
    }

    /** Frees a range, coalescing with neighbors and the bump-pointer tail. */
    public void freeRange(int start, int size) {
        if (size <= 0) {
            return;
        }
        long end = start + (long) size;
        for (int i = 0; i < this.freeRanges.size(); ) {
            long[] r = this.freeRanges.get(i);
            if (r[0] + r[1] == start) {
                start = (int) r[0];
                size += (int) r[1];
                this.freeRanges.remove(i);
            } else if (r[0] == end) {
                end = r[0] + r[1];
                size += (int) r[1];
                this.freeRanges.remove(i);
            } else {
                i++;
            }
        }
        if (end == this.quadsUsed) {
            this.quadsUsed = start;
        } else {
            this.freeRanges.add(new long[] {start, size});
        }
    }

    // ------------------------------------------------------------------
    // mesh publish + draw
    // ------------------------------------------------------------------

    /**
     * Expands {@code quads} into the VBO at quad offset {@code start}:
     * per quad 4 vertices of (uvec2 descriptor, ivec3 origin, pad). One
     * glBufferSubData per publish; render thread only.
     */
    public void publish(long key, int start, long[] quads) {
        int ox = AllvrRenderCellKey.minBlockX(key);
        int oy = AllvrRenderCellKey.minBlockY(key);
        int oz = AllvrRenderCellKey.minBlockZ(key);
        int[] verts = new int[quads.length * VERTS_PER_QUAD * 6];
        for (int q = 0; q < quads.length; q++) {
            long word = quads[q];
            int lo = (int) word;
            int hi = (int) (word >>> 32);
            int base = q * VERTS_PER_QUAD * 6;
            for (int c = 0; c < VERTS_PER_QUAD; c++) {
                int o = base + c * 6;
                verts[o] = lo;
                verts[o + 1] = hi;
                verts[o + 2] = ox;
                verts[o + 3] = oy;
                verts[o + 4] = oz;
                verts[o + 5] = 0;
            }
        }
        glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
        glBufferSubData(GL_ARRAY_BUFFER, (long) start * VERTS_PER_QUAD * VERTEX_BYTES, verts);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Binds the compat VAO (IBO stays bound via the VAO state). */
    public void bindForDraw() {
        glBindVertexArray(this.vao);
    }

    /** Appends one visible cube's draw entry; returns the new entry count. */
    public int appendDrawEntry(int quadStart, int quadCount, int n) {
        if (n >= MAX_DRAW_ENTRIES || quadCount <= 0 || quadStart < 0) {
            return n;
        }
        this.drawCounts.put(n, quadCount * INDEX_INTS_PER_QUAD);
        this.drawPointers.put(n, (long) quadStart * INDEX_BYTES_PER_QUAD);
        return n + 1;
    }

    /** One glMultiDrawElements over the appended entries (must follow
     *  {@link #bindForDraw} with the element buffer bound in the VAO). */
    public void draw(int n) {
        if (n <= 0) {
            return;
        }
        this.drawCounts.flip();
        this.drawPointers.flip();
        GL15.glMultiDrawElements(GL_TRIANGLES, this.drawCounts, GL_UNSIGNED_INT, this.drawPointers);
        this.drawCounts.clear();
        this.drawPointers.clear();
    }

    /** Off-departure discipline (mirror of AllvrBuffers.unbind). */
    public void unbind() {
        glBindVertexArray(0);
    }

    /** Drops every quad (level unload); GL objects persist. */
    public void reset() {
        this.quadsUsed = 0;
        this.freeRanges.clear();
    }

    public void destroy() {
        if (this.vao != 0) {
            glDeleteVertexArrays(this.vao);
            this.vao = 0;
        }
        if (this.vbo != 0) {
            glDeleteBuffers(this.vbo);
            this.vbo = 0;
        }
        if (this.ibo != 0) {
            glDeleteBuffers(this.ibo);
            this.ibo = 0;
        }
        this.reset();
        CreateManaIndustry.LOGGER.debug("[Allvr] compat backend destroyed");
    }

    public AllvrCompatBackend() {
    }
}
