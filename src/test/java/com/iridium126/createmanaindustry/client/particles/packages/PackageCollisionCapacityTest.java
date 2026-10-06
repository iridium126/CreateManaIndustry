package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

class PackageCollisionCapacityTest {
    @Test void deviceLimitIncludesBothVisibleAndStagingSlots(){
        long per=2L*(PackageCollisionGpu.CELL_BYTES+PackageCollisionGpu.DEFAULT_SHAPES*32L);
        assertEquals(256,PackageCollisionGpu.deviceCapacity(256,1024,Long.MAX_VALUE));
        assertEquals(1024,PackageCollisionGpu.deviceCapacity(1024,1024,Long.MAX_VALUE));
        assertEquals(682,PackageCollisionGpu.deviceCapacity(1024,1024,128L*1024*1024));
        assertEquals(1,PackageCollisionGpu.deviceCapacity(256,1024,per));
        assertEquals(0,PackageCollisionGpu.deviceCapacity(256,1024,per-1));
        assertEquals(0,PackageCollisionGpu.deviceCapacity(256,1024,0));
    }
    @Test void configIsLocalAndPreservesExistingDefault(){
        var value=ClientConfig.SPEC.getValues().get("particles.packageCollisionMaxSections");
        assertNotNull(value);assertEquals(256,((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>)value).getDefault());
        assertThrows(IllegalArgumentException.class,()->PackageCollisionGpu.deviceCapacity(0,1024,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->PackageCollisionGpu.deviceCapacity(1025,1024,Long.MAX_VALUE));
    }
}
