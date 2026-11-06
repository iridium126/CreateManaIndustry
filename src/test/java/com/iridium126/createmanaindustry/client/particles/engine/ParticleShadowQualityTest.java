package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.ParticleShadowQuality;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleShadowQualityTest {
    @Test void strategiesAreIndependentAndFullIsDefault(){
        assertEquals(4,ParticleShadowQuality.values().length);
        assertEquals(ParticleShadowQuality.FULL,ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("allayStorm.shaderPackShadowQuality").getDefault());
        assertEquals(com.iridium126.createmanaindustry.infrastructure.config.StormSimulationBackend.AUTO,
                ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("allayStorm.simulationBackend").getDefault());
        assertEquals(true,ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("particles.rtCameraOcclusion").getDefault());
        assertEquals(true,ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("particles.receiveShadows").getDefault());
        for(var q:ParticleShadowQuality.values()){
            assertEquals(q!=ParticleShadowQuality.OFF,q.castsShadows());
            assertEquals(q==ParticleShadowQuality.COARSE,q.groupsStorm());
        }
    }
    @Test void movedKeysHaveNoLegacyAliases(){
        for(String key:new String[]{"partitionMaxParticles","sameFateMaxParticles"}){
            assertNotNull(ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("greatTricks."+key));
            assertNull(ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("particles."+key));
        }
        assertNotNull(ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("hexcasting.hexPatternGpuBudgetMiB"));
        assertNotNull(ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("packages.packageGpuAuthority"));
        assertNull(ClientConfig.SPEC.getValues().<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>>get("particles.packageGpuAuthority"));
    }
    @Test void qualityReloadDoesNotSplitAFrame(){
        var before=ClientConfig.shaderPackShadowQuality;
        try{
            ClientConfig.shaderPackShadowQuality=ParticleShadowQuality.COARSE;ShadowQualityFrame.begin();
            ClientConfig.shaderPackShadowQuality=ParticleShadowQuality.OFF;
            assertEquals(ParticleShadowQuality.COARSE,ShadowQualityFrame.current());
            assertTrue(ShadowQualityFrame.begin());assertEquals(ParticleShadowQuality.OFF,ShadowQualityFrame.current());
            assertFalse(ShadowQualityFrame.begin());
        }finally{ClientConfig.shaderPackShadowQuality=before;ShadowQualityFrame.begin();}
    }
    @Test void cylinderRetainsOffscreenCastersAndPartialIntersections(){
        float[] planes={1,0,0,1,-1,0,0,1,0,1,0,1,0,-1,0,1,0,0,1,1,0,0,-1,1};
        assertFalse(ShadowVolumeCulling.cylinderVisible(planes,5,0,0,1,1,0,0,1));
        assertTrue(ShadowVolumeCulling.cylinderVisible(planes,5,0,0,1,1,-1,0,0));
        assertTrue(ShadowVolumeCulling.cylinderVisible(planes,1.5f,0,0,1,1,0,0,1));
        assertTrue(ShadowVolumeCulling.cylinderVisible(planes,0,0,0,1,1,0,0,1));
        assertTrue(ShadowVolumeCulling.cylinderVisible(planes,Float.NaN,0,0,1,1,0,0,1));
    }
}
