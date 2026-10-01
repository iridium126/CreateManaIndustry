package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PackageAuthorityCapabilitiesTest {
    @Test void chainReadinessRequiresInitializedFreeRuntimeAndCompleteChainProtocol() {
        assertEquals(0,PackageAuthorityClient.readyFlags(false,true));
        assertEquals(ServerboundPackagePacket.FREE_READY,PackageAuthorityClient.readyFlags(true,false));
        assertEquals(ServerboundPackagePacket.FREE_READY|ServerboundPackagePacket.CHAIN_READY,PackageAuthorityClient.readyFlags(true,true));
    }
}
