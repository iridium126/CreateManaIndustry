package com.iridium126.createmanaindustry.client.particles.shaderpack;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Leaf-face metadata follows the lifetime of the terrain mesh that supplied it. */
public final class FeatherLeafMesh {
    public record Face(int pass, int material, int order, int sky) {}
    public record Mesh(long epoch, Long2ObjectOpenHashMap<Face> faces) {}
    private record Section(Object owner, Mesh mesh) {}

    public static final class Build {
        public final int originX, originZ;
        public final Long2ObjectOpenHashMap<Face> faces = new Long2ObjectOpenHashMap<>();
        public BlockPos block;
        public int material;

        public Build(int originX, int originZ) {
            this.originX = originX;
            this.originZ = originZ;
        }
    }

    public interface Output {
        Mesh cmi$getLeafMesh();
        void cmi$setLeafMesh(Mesh mesh);
    }

    public static final ThreadLocal<Build> BUILD = new ThreadLocal<>();
    private static final Long2ObjectOpenHashMap<Section> SECTIONS = new Long2ObjectOpenHashMap<>();
    private static long epoch;
    private static volatile long activeEpoch = -1;

    private FeatherLeafMesh() {}

    public static long configure(boolean capture) {
        activeEpoch = -1;
        epoch++;
        SECTIONS.clear();
        activeEpoch = capture ? epoch : -1;
        return epoch;
    }

    public static long captureEpoch() { return activeEpoch; }
    public static void release(long ownerEpoch) {
        if (ownerEpoch == epoch) configure(false);
    }

    public static void publish(long section, Object owner, Mesh mesh) {
        if (mesh != null && mesh.epoch != activeEpoch) return;
        if (mesh != null && !mesh.faces.isEmpty()) {
            SECTIONS.put(section, new Section(owner, mesh));
            com.iridium126.createmanaindustry.client.particles.engine.FeatherSupportGpu.INSTANCE.meshChanged(section);
        } else {
            remove(section, owner);
        }
    }

    public static void remove(long section, Object owner) {
        Section previous = SECTIONS.get(section);
        if (previous != null && previous.owner == owner) {
            SECTIONS.remove(section);
            com.iridium126.createmanaindustry.client.particles.engine.FeatherSupportGpu.INSTANCE.meshChanged(section);
        }
    }

    public static Face face(BlockPos block) {
        Section section = SECTIONS.get(SectionPos.asLong(block));
        return section == null ? null : section.mesh.faces.get(block.asLong());
    }
}
