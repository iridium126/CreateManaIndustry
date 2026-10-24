package com.iridium126.createmanaindustry.compat.trickster.casting;

import com.iridium126.createmanaindustry.compat.hextrick.execution.*;
import com.iridium126.createmanaindustry.compat.trickster.partition.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CastChainSnapshotTest {
    static CastingOrigin origin() {
        var reference = new CircleSourceDescriptor.BlockReference(1, 2, 3, ResourceLocation.parse("hexcasting:impetus_look"), UUID.randomUUID());
        return new CastingOrigin(new CircleSourceDescriptor(ResourceLocation.parse("minecraft:overworld"), reference, reference, List.of(reference)),
                Direction.NORTH, -1, 0, -1, 4, 5, 6, UUID.randomUUID());
    }
    @Test void cachedSnapshotsTrackOnlyActualSerializedChanges() {
        var chain = new CastChain();
        var empty = chain.snapshot();
        Object owner = new Object();
        chain.retain(owner); chain.release(owner); chain.suspend();
        chain.partition(null); chain.partitionOrigin(null); chain.removeFateTarget(UUID.randomUUID()); chain.clearFateTargets();
        assertEquals(0, chain.stateVersion());
        assertSame(empty, chain.snapshot());
        var player = UUID.randomUUID();
        chain.bind(player);
        var bound = chain.snapshot();
        chain.bind(player);
        assertSame(bound, chain.snapshot());
        assertNotSame(empty, bound);
        assertTrue(empty.player().isEmpty());
        var target = UUID.randomUUID();
        var origin = origin();
        chain.addFateTarget(target, origin);
        var withTarget = chain.snapshot();
        var targets = chain.fateTargets();
        chain.addFateTarget(target, origin);
        assertSame(withTarget, chain.snapshot());
        assertSame(targets, chain.fateTargets());
        var otherOrigin = origin();
        chain.addFateTarget(target, otherOrigin);
        assertEquals(origin, withTarget.targets().getFirst().origin().orElseThrow());
        assertEquals(otherOrigin, chain.snapshot().targets().getFirst().origin().orElseThrow());
        chain.removeFateTarget(target);
        assertEquals(List.of(target), targets);
        assertTrue(chain.snapshot().targets().isEmpty());
        var beforeToggle = chain.snapshot(); chain.toggle();
        assertFalse(beforeToggle.inverted()); assertTrue(chain.snapshot().inverted());
        assertThrows(IllegalStateException.class, () -> chain.bind(UUID.randomUUID()));
    }
    @Test void nbtAndNetworkRoundTripsPreserveOrderOriginsAndPartition() {
        var chain = new CastChain();
        chain.bind(UUID.randomUUID()); chain.toggle();
        chain.addFateTarget(UUID.randomUUID(), origin()); chain.addFateTarget(UUID.randomUUID());
        chain.partition(new SavedPartition(ResourceLocation.parse("minecraft:the_nether"), new PartitionBounds(-3, -2, -1, 1, 2, 3)));
        chain.partitionOrigin(origin());
        var snapshot = chain.snapshot();
        var encoded = CastChainSnapshot.CODEC.encodeStart(NbtOps.INSTANCE, snapshot).getOrThrow();
        assertEquals(snapshot, CastChainSnapshot.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
        var buffer = Unpooled.buffer();
        try {
            CastChainSnapshot.STREAM_CODEC.encode(buffer, snapshot);
            assertEquals(snapshot, CastChainSnapshot.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
        var restored = CastChain.restore(snapshot);
        assertSame(snapshot, restored.snapshot());
        assertFalse(restored.live()); assertNull(restored.runtime());
        assertEquals(chain.fateTargets(), restored.fateTargets());
        restored.toggle(); assertTrue(snapshot.inverted()); assertFalse(restored.snapshot().inverted());
    }
    @Test void snapshotDefensivelyCopiesTargets() {
        var targets = new ArrayList<CastChainSnapshot.FateTarget>();
        targets.add(new CastChainSnapshot.FateTarget(UUID.randomUUID(), Optional.empty()));
        var snapshot = new CastChainSnapshot(UUID.randomUUID(), Optional.empty(), false, targets, Optional.empty(), Optional.empty());
        targets.clear();
        assertEquals(1, snapshot.targets().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.targets().clear());
        assertThrows(IllegalArgumentException.class, () -> new CastChainSnapshot(snapshot.id(), Optional.empty(), false,
                List.of(snapshot.targets().getFirst(), snapshot.targets().getFirst()), Optional.empty(), Optional.empty()));
    }
}
