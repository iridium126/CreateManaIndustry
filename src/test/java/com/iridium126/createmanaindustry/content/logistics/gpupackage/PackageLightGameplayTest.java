package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PackageLightGameplayTest {
    private static PackageLightStore.Entry entry() {
        var pose=new PackageLease.Pose(0,64,0,0,0,0,0);
        return new PackageLightStore.Entry(new PackageLease.Identity(1,1),UUID.randomUUID(),
                ResourceLocation.parse("create:cardboard_package_12x12"),.75f,.75f,-1,
                new CompoundTag(),new PackageAuthorityRegion.Snapshot(pose,0));
    }

    @Test void sustainedFireContactDamagesInsteadOfRestartingTheTimer() {
        var entry=entry();assertFalse(PackageLightGameplay.burnContact(entry));
        assertEquals(100,entry.fireTicks);assertEquals(5,entry.health);
        boolean destroyed=false;
        for(int tick=0;tick<40&&!destroyed;tick++) {
            destroyed=PackageLightGameplay.burnTick(entry,true);
            if(!destroyed)destroyed=PackageLightGameplay.burnContact(entry);
        }
        assertTrue(destroyed);assertTrue(entry.fireTicks<100);
    }

    @Test void leavingFireLetsTheTimerExpireWithPeriodicDamage() {
        var entry=entry();PackageLightGameplay.burnContact(entry);
        for(int tick=0;tick<100;tick++)assertFalse(PackageLightGameplay.burnTick(entry,true));
        assertEquals(0,entry.fireTicks);assertEquals(4.25f,entry.health,.00001f);
        assertFalse(PackageLightGameplay.burnTick(entry,true));
    }

    @Test void damageResistancePreventsPeriodicDamageWithoutFreezingTheTimer() {
        var entry=entry();entry.fireTicks=20;
        for(int tick=0;tick<20;tick++)assertFalse(PackageLightGameplay.burnTick(entry,false));
        assertEquals(0,entry.fireTicks);assertEquals(5,entry.health);
    }
}
