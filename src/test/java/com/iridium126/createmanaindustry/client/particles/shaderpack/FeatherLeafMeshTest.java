package com.iridium126.createmanaindustry.client.particles.shaderpack;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeatherLeafMeshTest {
    @Test
    void onlyTheCurrentSectionOwnerAndPackEpochCanReplaceAnAttachment() {
        var leaf = new BlockPos(-1, 64, 16);
        long section = SectionPos.asLong(leaf);
        var first = new FeatherLeafMesh.Face(2, 10009, 0xe4, 0x90a0b0f0);
        var replacement = new FeatherLeafMesh.Face(1, 10009, 0x39, 0x80a0b0f0);
        var oldOwner = new Object();
        var owner = new Object();
        long oldEpoch = FeatherLeafMesh.configure(true);
        try {
            FeatherLeafMesh.publish(section, oldOwner, mesh(oldEpoch, leaf, first));
            assertEquals(first, FeatherLeafMesh.face(leaf));

            long epoch = FeatherLeafMesh.configure(true);
            assertNull(FeatherLeafMesh.face(leaf));
            FeatherLeafMesh.publish(section, owner, mesh(epoch, leaf, replacement));
            FeatherLeafMesh.publish(section, oldOwner, mesh(oldEpoch, leaf, first));
            FeatherLeafMesh.remove(section, oldOwner);
            FeatherLeafMesh.release(oldEpoch);
            assertEquals(replacement, FeatherLeafMesh.face(leaf));

            FeatherLeafMesh.remove(section, owner);
            assertNull(FeatherLeafMesh.face(leaf));
            FeatherLeafMesh.release(epoch);
            FeatherLeafMesh.publish(section, owner, mesh(epoch, leaf, replacement));
            assertNull(FeatherLeafMesh.face(leaf));
            assertEquals(-1, FeatherLeafMesh.captureEpoch());
        } finally {
            FeatherLeafMesh.configure(false);
        }
    }

    private static FeatherLeafMesh.Mesh mesh(long epoch, BlockPos pos, FeatherLeafMesh.Face face) {
        var faces = new Long2ObjectOpenHashMap<FeatherLeafMesh.Face>();
        faces.put(pos.asLong(), face);
        return new FeatherLeafMesh.Mesh(epoch, faces);
    }
}
