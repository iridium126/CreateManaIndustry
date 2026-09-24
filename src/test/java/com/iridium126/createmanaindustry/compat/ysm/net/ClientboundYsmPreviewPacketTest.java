package com.iridium126.createmanaindustry.compat.ysm.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientboundYsmPreviewPacketTest {
    @Test
    void imagePacketCarriesOnlyBoundedPngAndReferenceKey() {
        String key = "ab".repeat(32);
        byte[] png = {1, 2, 3, 4};
        var packet = ClientboundYsmPreviewPacket.image(key, true, png);
        png[0] = 99;
        assertEquals(key, packet.key());
        assertTrue(packet.cube());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, packet.png());
        assertThrows(IllegalArgumentException.class,
                () -> ClientboundYsmPreviewPacket.image(key, false,
                        new byte[ClientboundYsmPreviewPacket.MAX_IMAGE_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> ClientboundYsmPreviewPacket.image("not-a-key", false, new byte[] {1}));
    }

    @Test
    void errorPacketDoesNotCarryImageAndBoundsItsMessage() {
        var packet = ClientboundYsmPreviewPacket.error("cd".repeat(32), false, true, "busy".repeat(200));
        assertTrue(packet.retryable());
        assertEquals(512, packet.reason().length());
        assertEquals(0, packet.png().length);
        assertThrows(IllegalArgumentException.class,
                () -> new ClientboundYsmPreviewPacket(ClientboundYsmPreviewPacket.ERROR,
                        "cd".repeat(32), false, new byte[] {1}, false, "invalid"));
    }
}
