package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PackageLightRestorationTest {
    @Test void nativeModeRetainsReadinessForLiveEnableButRejectsOldGpuMutations(){
        assertTrue(PackageAuthorityManager.acceptsClientAction(false,com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket.CAPABILITIES));
        for(int action:new int[]{com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket.ENVIRONMENT,
                com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket.CONTROL_BATCH,
                com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket.HEARTBEAT}){
            assertFalse(PackageAuthorityManager.acceptsClientAction(false,action));assertTrue(PackageAuthorityManager.acceptsClientAction(true,action));
        }
    }
    @Test void ordinaryFailuresCannotInvokeTheEntityRestorationFactory() throws Exception {
        for(String name:java.util.List.of("PackageAuthorityManager","PackageAuthorityManager$EntityTarget","PackageChainAuthorityManager","PackageLightGameplay")){
            var type=new org.objectweb.asm.tree.ClassNode();
            try(var input=getClass().getClassLoader().getResourceAsStream("com/iridium126/createmanaindustry/content/logistics/gpupackage/"+name+".class")){
                assertNotNull(input);new org.objectweb.asm.ClassReader(input).accept(type,0);
            }
            for(var method:type.methods)for(var instruction:method.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call){
                if(call.owner.endsWith("/PackageLightRestoration")&&call.name.equals("restore"))assertEquals("pumpRestoration",method.name);
                assertFalse(call.owner.equals("com/simibubi/create/content/logistics/box/PackageEntity")&&call.name.equals("<init>"),"Entity construction outside the disable-only factory");
            }
        }
    }
    @Test void restorationOverlaysConfirmedFieldsWithoutMutatingTheStoredNbt(){
        var data=new CompoundTag();data.putString("IntegrationData","keep");data.putFloat("Health",5);
        var box=new CompoundTag();box.putString("id","create:cardboard_package_12x12");box.putInt("count",1);data.put("Box",box);
        var p=new PackageLease.Pose(-64.5,70000.25,128.5,1024,-32,8,-90);
        var e=new PackageLightStore.Entry(new PackageLease.Identity(7,3),UUID.randomUUID(),ResourceLocation.parse("create:cardboard_package_12x12"),.75f,.75f,-1,data,new PackageAuthorityRegion.Snapshot(p,PackageAuthorityRegion.GROUNDED));
        e.health=2.75f;e.fireTicks=87;e.portalCooldown=123;e.insertionDelay=9;e.tossedBy=UUID.randomUUID();
        var restored=PackageLightRestoration.entityData(e);
        assertEquals(e.uuid,restored.getUUID("UUID"));assertEquals(data.getCompound("Box"),restored.getCompound("Box"));
        assertEquals("keep",restored.getString("IntegrationData"));assertEquals(70000.25,restored.getList("Pos",Tag.TAG_DOUBLE).getDouble(1));
        assertEquals(51.2,restored.getList("Motion",Tag.TAG_DOUBLE).getDouble(0));assertEquals(-1.6,restored.getList("Motion",Tag.TAG_DOUBLE).getDouble(1));
        assertEquals(-90,restored.getList("Rotation",Tag.TAG_FLOAT).getFloat(0));assertTrue(restored.getBoolean("OnGround"));
        assertEquals(2.75f,restored.getFloat("Health"));assertEquals(87,restored.getShort("Fire"));assertEquals(123,restored.getInt("PortalCooldown"));
        assertEquals(9,restored.getInt("CMIPackageInsertionDelay"));assertEquals(e.tossedBy,restored.getUUID("CMIPackageTossedBy"));
        assertFalse(e.data.hasUUID("UUID"));assertEquals(5,e.data.getFloat("Health"));
        e.state=new PackageAuthorityRegion.Snapshot(p,0);e.tossedBy=null;
        var other=PackageLightRestoration.entityData(e);assertFalse(other.getBoolean("OnGround"));assertFalse(other.hasUUID("CMIPackageTossedBy"));
    }
}
