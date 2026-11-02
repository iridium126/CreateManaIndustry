package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.ParticleShadowQuality;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleShadowQualityTest {
    @Test void strategiesAreIndependentAndFullIsDefault(){
        assertEquals(7,ParticleShadowQuality.values().length);
        assertEquals(ParticleShadowQuality.FULL,((ModConfigSpec.ConfigValue<?>)ClientConfig.SPEC.getValues().get("allayStorm.shaderPackShadowQuality")).getDefault());
        for(var q:ParticleShadowQuality.values()){
            assertEquals(q==ParticleShadowQuality.FULL,q.receivesShadows());
            assertEquals(q!=ParticleShadowQuality.OFF,q.castsShadows());
        }
        assertFalse(ParticleShadowQuality.COARSE.projectsFrustum());
        assertTrue(ParticleShadowQuality.FRUSTUM.projectsFrustum());
        assertTrue(ParticleShadowQuality.SHADOW_HIZ.shadowDepth());
        assertFalse(ParticleShadowQuality.SHADOW_HIZ.screenDepth());
        assertTrue(ParticleShadowQuality.SCREEN_HIZ.screenDepth());
        assertFalse(ParticleShadowQuality.SCREEN_HIZ.shadowDepth());
        assertEquals("SHADOW_HIZ+SCREEN_HIZ",ParticleShadowQuality.COMBINED_HIZ.label());
    }
    @Test void movedKeysHaveNoLegacyAliases(){
        for(String key:new String[]{"partitionMaxParticles","sameFateMaxParticles"}){
            assertNotNull(ClientConfig.SPEC.getValues().get("greatTricks."+key));
            assertNull(ClientConfig.SPEC.getValues().get("particles."+key));
        }
        assertNotNull(ClientConfig.SPEC.getValues().get("hexcasting.hexPatternGpuBudgetMiB"));
        assertNotNull(ClientConfig.SPEC.getValues().get("packages.packageGpuAuthority"));
        assertNull(ClientConfig.SPEC.getValues().get("particles.packageGpuAuthority"));
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
