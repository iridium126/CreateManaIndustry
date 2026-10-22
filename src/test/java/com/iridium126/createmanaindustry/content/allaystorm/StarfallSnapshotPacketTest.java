package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarfallSnapshotPacketTest {
    private RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER); }

    @Test void snapshotAndRequestRoundTripBelowEightKiB() {
        float[] values = new float[StarfallColumnSnapshot.FLOATS];
        for (int rank = 0; rank < 64; rank++) { int offset = rank * StarfallColumnSnapshot.STRIDE; values[offset] = 4; values[offset + 1] = 2; values[offset + 4] = 6; values[offset + 10] = 1; }
        var snapshot = new StarfallColumnSnapshot(values); values[0] = 255;
        var packet = new StormColumnSnapshotPacket(ResourceLocation.parse("minecraft:overworld"), 42, 1, 2, new UUID(3, 4), 100, 5, snapshot);
        var buffer = buffer();
        try {
            StormColumnSnapshotPacket.STREAM_CODEC.encode(buffer, packet); assertTrue(buffer.readableBytes() <= 8192);
            var decoded = StormColumnSnapshotPacket.STREAM_CODEC.decode(buffer); assertEquals(packet.identity(), decoded.identity());
            assertEquals(packet.token(), decoded.token()); assertEquals(packet.source(), decoded.source()); assertEquals(packet.gameTime(), decoded.gameTime()); assertArrayEquals(snapshot.values(), decoded.snapshot().values()); assertFalse(buffer.isReadable());
            var request = new ClientboundStormColumnSnapshotRequest(packet.dimension(), packet.epoch(), packet.id(), packet.token(), packet.source(), packet.gameTime()); buffer.clear();
            ClientboundStormColumnSnapshotRequest.STREAM_CODEC.encode(buffer, request); assertEquals(request, ClientboundStormColumnSnapshotRequest.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void rejectsPartialNonfiniteAndInvalidPhases() {
        assertThrows(IllegalArgumentException.class, () -> new StarfallColumnSnapshot(new float[30]));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 3, 255}) {
            float[] values = new float[StarfallColumnSnapshot.FLOATS]; values[0] = invalid;
            assertThrows(IllegalArgumentException.class, () -> new StarfallColumnSnapshot(values));
        }
        float[] values = new float[StarfallColumnSnapshot.FLOATS]; values[4] = 33;
        assertThrows(IllegalArgumentException.class, () -> new StarfallColumnSnapshot(values));
        values[4] = 0; var snapshot = new StarfallColumnSnapshot(values); var completed = new BitSet(); completed.set(63);
        var oversized = ResourceLocation.fromNamespaceAndPath("test", "a".repeat(257));
        assertThrows(IllegalArgumentException.class, () -> new StormColumnSnapshotPacket(oversized, 1, 1, 1, new UUID(0, 1), 1, 0, snapshot));
        assertEquals(6, snapshot.completed(completed).values()[63 * StarfallColumnSnapshot.STRIDE]); assertEquals(0, snapshot.values()[63 * StarfallColumnSnapshot.STRIDE]);
    }
}
