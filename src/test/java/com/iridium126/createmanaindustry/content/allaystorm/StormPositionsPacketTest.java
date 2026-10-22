package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormPositionsPacketTest {
    private final ResourceLocation dimension = ResourceLocation.parse("minecraft:overworld");
    private RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
    }

    @Test void bothDirectionsPreserveFastVelocitiesAndIdentity() {
        for (float velocity : new float[]{-32, -18, -12, -8, -6, 6, 8, 12, 18, 32}) {
            var entries = new float[]{131071, 0, 123.0625f, -20, 45, velocity, -velocity, velocity};
            var buffer = buffer();
            try {
                var packet = new ServerboundStormPositionsPacket(dimension, 42, 100, entries);
                ServerboundStormPositionsPacket.STREAM_CODEC.encode(buffer, packet);
                var decoded = ServerboundStormPositionsPacket.STREAM_CODEC.decode(buffer);
                assertEquals(packet.identity(), decoded.identity());
                assertArrayEquals(entries, decoded.entries());
                buffer.clear();
                var relay = new ClientboundStormPositionsPacket(decoded.dimension(), decoded.epoch(), decoded.gameTime(), decoded.entries());
                ClientboundStormPositionsPacket.STREAM_CODEC.encode(buffer, relay);
                var received = ClientboundStormPositionsPacket.STREAM_CODEC.decode(buffer);
                assertEquals(packet.identity(), received.identity());
                assertEquals(100, received.gameTime());
                assertArrayEquals(entries, received.entries());
                assertFalse(buffer.isReadable());
            } finally { buffer.release(); }
        }
    }

    @Test void invalidInputAndOversizedWireAreRejected() {
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 32.0625f}) {
            var buffer = buffer();
            try {
                var packet = new ServerboundStormPositionsPacket(dimension, 42, 100, new float[]{0, 0, 0, 0, 0, value, 0, 0});
                assertThrows(Exception.class, () -> ServerboundStormPositionsPacket.STREAM_CODEC.encode(buffer, packet));
            } finally { buffer.release(); }
        }
        var buffer = buffer();
        try {
            buffer.writeResourceLocation(dimension); buffer.writeLong(42); buffer.writeVarLong(100); buffer.writeVarInt(257);
            assertThrows(Exception.class, () -> ServerboundStormPositionsPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void centerRoundTripPreservesIdentityAndGrowth() {
        var packet = new ClientboundStormCenterPacket(dimension, 42, 1, 2, 3, 2, 0, 65536, 100);
        var buffer = buffer();
        try {
            ClientboundStormCenterPacket.STREAM_CODEC.encode(buffer, packet);
            assertEquals(packet, ClientboundStormCenterPacket.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }
}
