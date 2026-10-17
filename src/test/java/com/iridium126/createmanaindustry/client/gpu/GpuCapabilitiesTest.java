package com.iridium126.createmanaindustry.client.gpu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuCapabilitiesTest {
    private static GpuCapabilities limits(long bytes,int groups,int threads) {
        return new GpuCapabilities(true,bytes,16,8,groups,threads,threads,65536,32);
    }
    @Test void bindingIndexAndPerPassBlockCountsAreIndependent() {
        var limits=limits(128L<<20,65535,1024);
        assertTrue(limits.supportsCompute(15,8,64));
        assertFalse(limits.supportsCompute(16,8,64));
        assertFalse(limits.supportsCompute(15,9,64));
        assertFalse(limits.supportsCompute(1,1,2048));
        assertTrue(limits.supportsCompute(1,1,64),"Optional large passes must not disable simpler passes");
    }
    @Test void capacityHonorsStorageAndDispatchWithoutOverflow() {
        assertEquals(1000,limits(64000,65535,1024).elementCapacity(2000000,64,64));
        assertEquals(128,limits(Long.MAX_VALUE,2,1024).elementCapacity(Integer.MAX_VALUE,64,64));
        assertEquals(Integer.MAX_VALUE,limits(Long.MAX_VALUE,Integer.MAX_VALUE,1024).elementCapacity(Integer.MAX_VALUE,64,64));
        assertEquals(0,limits(128L<<20,65535,32).elementCapacity(2000,64,64));
        assertThrows(IllegalArgumentException.class,()->limits(1,1,1).elementCapacity(-1,64,64));
    }
}
