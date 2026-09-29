package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PackageIdentityDataTest {
    @Test void saveReloadPreservesBothAllocationWatermarks() {
        var data=new PackageIdentityData();long id=data.identity(),epoch=data.epoch();
        var restored=PackageIdentityData.load(data.save(new CompoundTag(),RegistryAccess.EMPTY),RegistryAccess.EMPTY);
        assertTrue(restored.identity()>id);assertTrue(restored.epoch()>epoch);assertTrue(restored.isDirty());
    }
    @Test void restoredLegacyObjectsPreventReuseEvenIfSavedDataWasLost() {
        var data=PackageIdentityData.load(new CompoundTag(),RegistryAccess.EMPTY);
        data.observe(0x1234567800000001L);assertTrue(data.identity()>0x1234567800000001L);
        data.observe(7);assertTrue(data.identity()>0x1234567800000002L);
    }
    @Test void corruptNegativeCountersNeverProduceInvalidNetworkIdentities() {
        var tag=new CompoundTag();tag.putLong("NextIdentity",-1);tag.putLong("NextEpoch",Long.MIN_VALUE);
        var data=PackageIdentityData.load(tag,RegistryAccess.EMPTY);assertEquals(1,data.identity());assertEquals(1,data.epoch());
    }
}
