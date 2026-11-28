package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.stream.IntStream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormWavePacketTest {
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
    }

    @Test void abortRoundtrip() {
        var b = buffer();
        try {
            ClientboundStormWavePacket.STREAM_CODEC.encode(b, ClientboundStormWavePacket.abort(9, 87));
            var p = ClientboundStormWavePacket.STREAM_CODEC.decode(b);
            assertTrue(p.abort());assertEquals(87, p.epoch());assertFalse(b.isReadable());
        } finally {
            b.release();
        }
    }

    @Test void clientOnlyColumnKindNeverCrossesTheWire() {
        var column = new ClientboundStormColumnPacket(Level.OVERWORLD.location(), 87, 4, false, 123,
                0, 8, 32, 36.34f, 80, 1500, 64.02f, -2300, 120, 1500, -2300, 32,
                IntStream.range(0, 64).toArray());
        var snapshot = column.snapshot();
        assertTrue(snapshot.column());assertFalse(snapshot.dome());assertFalse(snapshot.helixAttack());
        var b = buffer();
        try {
            ClientboundStormWavePacket.STREAM_CODEC.encode(b, snapshot);
            assertThrows(DecoderException.class, () -> ClientboundStormWavePacket.STREAM_CODEC.decode(b));
        } finally {
            b.release();
        }
    }

    @Test void contactPreservesEpochSampleTimeAndAbsolutePosition() {
        var p = new ServerboundStormWaveContactPacket(Level.OVERWORLD.location(), 4, 87, 241L, 12.05f,
                100000.25f, 64.5f, -100000.75f);
        var b = buffer();
        try {
            ServerboundStormWaveContactPacket.STREAM_CODEC.encode(b, p);
            assertEquals(p, ServerboundStormWaveContactPacket.STREAM_CODEC.decode(b));
            assertFalse(b.isReadable());
        } finally {
            b.release();
        }
    }
}