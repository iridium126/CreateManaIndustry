package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormStatePacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormLifecyclePacketTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation OTHER = ResourceLocation.parse("createmanaindustry:allay_dimension");
    @Test void allActionsRoundTripIdentity() {
        var id = new StormIdentity(OTHER, Long.MIN_VALUE + 67);
        var packets = new ClientboundStormStatePacket[] {
            ClientboundStormStatePacket.ACTIVATE(id, BlockPos.ZERO, 4096, 123, true, 5, new byte[]{1, 2}, 8, 32, 20, 100),
            ClientboundStormStatePacket.UPDATE(id, BlockPos.ZERO, 4096, 123, false, 5, 8, 32, 20, 100),
            ClientboundStormStatePacket.DEACTIVATE(id), ClientboundStormStatePacket.STOP(id)
        };
        for (var p : packets) {
            var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
            try {
                ClientboundStormStatePacket.STREAM_CODEC.encode(b, p);
                var q = ClientboundStormStatePacket.STREAM_CODEC.decode(b);
                assertEquals(p.identity(), q.identity()); assertEquals(p.action(), q.action());
                assertArrayEquals(p.deadBitmap(), q.deadBitmap()); assertFalse(b.isReadable());
            } finally { b.release(); }
        }
    }
    @Test void oldDimensionCannotCleanNewDimension() {
        var old = new StormIdentity(OVERWORLD, 1);
        var active = new StormIdentity(OTHER, 2);
        assertTrue(active.acceptsLifecycle(0, OTHER, null));
        for (int action : new int[]{1, 2, 3}) assertFalse(old.acceptsLifecycle(action, OTHER, active));
        assertFalse(old.acceptsLifecycle(0, OTHER, active));
    }
    @Test void oldEpochCannotUpdateOrCleanRestartedStorm() {
        var old = new StormIdentity(OVERWORLD, 10);
        var active = new StormIdentity(OVERWORLD, 11);
        for (int action : new int[]{1, 2, 3}) {
            assertFalse(old.acceptsLifecycle(action, OVERWORLD, active));
            assertTrue(active.acceptsLifecycle(action, OVERWORLD, active));
            assertFalse(active.acceptsLifecycle(action, OVERWORLD, null));
        }
    }
}
