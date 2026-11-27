package com.iridium126.createmanaindustry.client.allaystorm;

import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormBlastPacket;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Sparse section masks over the published collision atlas. No replacement terrain pages are needed. */
public final class StormTerrainEdits implements AutoCloseable {
    private static final int TEXTURE_UNIT = 6;
    public record Snapshot(long revision, int[] words) {
        public int bytes() { return words.length * Integer.BYTES; }
        public void write(ByteBuffer out) { for (int word : words) out.putInt(word); }
    }
    public static final Snapshot EMPTY = new Snapshot(0, new int[4]);
    private final Map<Section, int[]> masks = new LinkedHashMap<>();
    private long received, revision;
    private StormIdentity stream;
    private Snapshot snapshot = EMPTY;
    private int texture, buffer, capacity;
    private long uploaded = -1;

    void accept(ClientboundStormBlastPacket packet, StormCollisionCache cache) {
        if(!packet.identity().equals(stream)){
            stream=packet.identity();
            received=0;
        }
        if (packet.revision() <= received) return;
        received = packet.revision();
        for (var group : packet.groups()) {
            var section = new Section(group.x(), group.y(), group.z());
            if (!cache.retainForEdit(section)) continue;
            int[] words = masks.computeIfAbsent(section, ignored -> new int[WORDS]);
            for (short cell : group.cells()) words[cell >>> 5] |= 1 << (cell & 31);
            revision++;
        }
    }

    void restored(BlockPos pos) {
        var key = new Section(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        var words = masks.get(key);
        if (words == null) return;
        int cell = ClientboundStormBlastPacket.pack(pos), bit = 1 << (cell & 31);
        if ((words[cell >>> 5] & bit) == 0) return;
        words[cell >>> 5] &= ~bit;
        if (empty(words)) masks.remove(key);
        revision++;
    }

    boolean covers(BlockPos pos) {
        var words = masks.get(new Section(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        if (words == null) return false;
        int cell = ClientboundStormBlastPacket.pack(pos);
        return (words[cell >>> 5] & (1 << (cell & 31))) != 0;
    }
    boolean covers(Section section) { return masks.containsKey(section); }

    void forget(Section section) {
        if(masks.remove(section)!=null)revision++;
    }

    void forgetColumn(int x,int z) {
        if(masks.keySet().removeIf(section->section.x()==x&&section.z()==z))revision++;
    }

    void retire(StormCollisionCache cache, boolean published) {
        for (var it = masks.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            var section = entry.getKey();
            if (!cache.requested(section)) {
                it.remove();
                revision++;
                continue;
            }
            if (!published) continue;
            Page page = cache.fresh(section);
            if (page == null || page.kind() == UNKNOWN || page.kind() == SOLID) continue;
            var mask = entry.getValue();
            boolean changed = false;
            for (int word = 0; word < WORDS; word++) {
                int retained = page.kind() == AIR ? 0 : mask[word] & page.words()[word];
                changed |= retained != mask[word];
                mask[word] = retained;
            }
            if (empty(mask)) it.remove();
            if (changed) revision++;
        }
    }

    private static boolean empty(int[] words) {
        for (int word : words) if (word != 0) return false;
        return true;
    }

    public Snapshot snapshot() {
        if (snapshot.revision == revision) return snapshot;
        return snapshot = pack(revision,masks);
    }

    public static Snapshot pack(long revision,Map<Section,int[]> masks) {
        if (masks.isEmpty()) return new Snapshot(revision, new int[]{0, 0, 0, (int) revision});
        var sections = new LinkedHashMap<Section, Integer>();
        for (var section : masks.keySet()) sections.put(section, sections.size());
        var index = table(sections);
        int base = 4 + index.words().length;
        var words = new int[base + masks.size() * WORDS];
        words[0] = index.primaryRows();
        words[1] = index.overflow();
        words[2] = masks.size();
        words[3] = (int) revision;
        System.arraycopy(index.words(), 0, words, 4, index.words().length);
        masks.forEach((section, mask) -> System.arraycopy(mask, 0, words, base + sections.get(section) * WORDS, WORDS));
        return new Snapshot(revision, words);
    }

    void prepare() {
        if (masks.isEmpty()) return;
        var source = snapshot();
        if (uploaded == source.revision()) return;
        if (buffer == 0) {
            buffer = GL45.glCreateBuffers();
            texture = GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
        }
        if (capacity < source.bytes()) {
            capacity = Math.max(4096, Integer.highestOneBit(source.bytes() - 1) << 1);
            GL45.glNamedBufferData(buffer, capacity, GL15.GL_DYNAMIC_COPY);
            GL45.glTextureBuffer(texture, GL30.GL_R32UI, buffer);
        }
        var staging = MemoryUtil.memAlloc(source.bytes()).order(ByteOrder.nativeOrder());
        try {
            source.write(staging);
            GpuReplayCommands.namedBufferSubData(buffer, 0, staging.flip());
        } finally {
            MemoryUtil.memFree(staging);
        }
        uploaded = source.revision();
    }

    public void bind(int program,long baseRevision) {
        boolean enabled = texture != 0 && !masks.isEmpty() && uploaded == revision;
        GpuUniforms.setIntUniform(program, "uStormTerrainEdited", enabled ? 1 : 0);
        GpuUniforms.uniform1ui(program, GpuUniforms.loc(program,"uStormCollisionRevision"),
                (int) baseRevision ^ (int) revision * 0x9e3779b9);
        if (enabled) GpuBindings.texture(TEXTURE_UNIT, GL31.GL_TEXTURE_BUFFER, texture);
    }

    @Override public void close() {
        GpuObjectNames.deleteTexture(texture);
        GpuBufferNames.delete(buffer);
        buffer = texture = capacity = 0;
        uploaded = -1;
        masks.clear();
    }
}
