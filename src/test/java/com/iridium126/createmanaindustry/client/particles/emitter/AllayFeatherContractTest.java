package com.iridium126.createmanaindustry.client.particles.emitter;

import com.iridium126.createmanaindustry.client.allaystorm.StormPlayerGrid;
import com.iridium126.createmanaindustry.client.particles.engine.AllayFeatherAppearance;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AllayFeatherContractTest {
    @Test void oldEmptySnapshotsCannotDisableNewBirths() {
        assertFalse(AllayFeatherLayout.zeroCovers(10,11,0));
        assertFalse(AllayFeatherLayout.zeroCovers(11,11,1));
        assertTrue(AllayFeatherLayout.zeroCovers(11,11,0));
        assertTrue(AllayFeatherLayout.zeroCovers(12,11,0));
    }
    @Test void stormRateScalesWithAreaAndRadiusWeightsAreLocal() {
        assertEquals(250,AllayFeatherLayout.rate(1000,16));assertEquals(1000,AllayFeatherLayout.rate(1000,32));
        assertEquals(2000,AllayFeatherLayout.rate(1000,Math.sqrt(2048)),.001);
        assertEquals(4000,AllayFeatherLayout.rate(2000,Math.sqrt(2048)),.001);
        assertEquals(4000,AllayFeatherLayout.rate(4000,32));assertEquals(8000,AllayFeatherLayout.rate(4000,Math.sqrt(2048)),.001);
        assertEquals(0,AllayFeatherLayout.rate(1000,0));assertEquals(0,AllayFeatherLayout.rate(0,32));
        assertEquals(1,AllayFeatherLayout.weight(32,0));assertEquals(1,AllayFeatherLayout.weight(32,32));
        assertEquals(.5,AllayFeatherLayout.weight(32,48));assertEquals(0,AllayFeatherLayout.weight(32,64));
        assertEquals(1000,AllayFeatherLayout.proposals(1000,32)*7/12,.000001);
    }
    @Test void goldUsesIndependentUncollidedAdditiveTypeAndSharedAbi(){
        var gold=EmitterPresets.byName("allay_gold");assertSame(AllayGoldSpecs.GOLD,gold);
        assertEquals(ParticleTypes.Material.ADDITIVE,gold.type.material());assertEquals(9,gold.type.id());
        assertEquals(8,gold.lifeMin);assertEquals(12,gold.lifeMax);assertEquals(EmitterSpec.CollideMode.NONE,gold.collideMode);
    }
    @Test void newDefaultsDoNotRequireConfigMigration()throws Exception{
        var client=com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.SPEC;
        var field=com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.class.getDeclaredField("STORM_LOD_MAX_DISTANCE");field.setAccessible(true);
        var server=(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>)field.get(null);
        assertEquals(4096,((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>)client.getValues().get("particles.stormLodDistance")).getDefault());
        assertEquals(4096,server.getDefault());
        assertEquals(2000,((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>)client.getValues().get("particles.allayGoldMaxSpawnPerSecond")).getDefault());
        var gold=client.getSpec().get("particles.allayGoldMaxSpawnPerSecond");
        var feather=client.getSpec().get("particles.allayFeatherMaxSpawnPerSecond");
        assertTrue(((net.neoforged.neoforge.common.ModConfigSpec.ValueSpec)gold).test(4000));
        assertFalse(((net.neoforged.neoforge.common.ModConfigSpec.ValueSpec)gold).test(4001));
        assertTrue(((net.neoforged.neoforge.common.ModConfigSpec.ValueSpec)feather).test(2000));
        assertFalse(((net.neoforged.neoforge.common.ModConfigSpec.ValueSpec)feather).test(2001));
    }
    @Test void finiteOpaqueSpecKeepsParticleAbi() {
        var feather=EmitterPresets.byName("allay_feather");assertSame(AllayFeatherSpecs.FEATHER,feather);
        assertEquals(ParticleTypes.Material.OPAQUE,feather.type.material());
        assertNotEquals(ParticleTypes.standard(ParticleTypes.Material.MODEL),feather.type);
        assertEquals(60,feather.lifeMin);assertEquals(60,feather.lifeMax);
        assertEquals(EmitterPresets.CHERRY_LEAVES.sizeStart,feather.sizeStart);
        assertEquals(EmitterPresets.CHERRY_LEAVES.gravity,feather.gravity);
        assertEquals(EmitterSpec.CollideMode.REST,feather.collideMode);
        assertEquals(64,ParticleLayout.PARTICLE_BYTES);assertEquals(320,ParticleLayout.HEADER_BYTES);
        assertEquals(0,AllayFeatherLayout.BYTES%256);
    }
    @Test void everyCherryAlphaIsPreservedByAllayPalette() throws Exception {
        for(int frame=0;frame<12;frame++){
            var image=ImageIO.read(Path.of("src/main/resources/assets/createmanaindustry/textures/particle/cherry_"+frame+".png").toFile());
            assertEquals(3,image.getWidth());assertEquals(3,image.getHeight());
            for(int y=0;y<3;y++)for(int x=0;x<3;x++){
                int argb=image.getRGB(x,y),rgba=(argb&0xff00ff00)|(argb>>>16&255)|(argb&255)<<16;
                int changed=AllayFeatherAppearance.recolor(rgba);assertEquals(rgba>>>24,changed>>>24);
                if((rgba>>>24)!=0){assertTrue((changed>>>16&255)>(changed>>>8&255));assertTrue((changed>>>8&255)>(changed&255));}
            }
        }
    }
    @Test void movementExtensionDoesNotChangeExistingPlayerSnapshotPrefix() {
        var grid=new StormPlayerGrid();float[] xyz={1,2,3,4,5,6};int[] ids={12,13};float[] attacks=new float[272];attacks[0]=7;
        grid.build(xyz,xyz,xyz,ids,2,attacks);float[] prefix=Arrays.copyOf(grid.data(),grid.words());
        float[] movement={1,2,3,1.5f,4,5,6,0};grid.build(xyz,xyz,xyz,ids,2,attacks,movement);
        assertArrayEquals(prefix,Arrays.copyOf(grid.data(),prefix.length));
        assertArrayEquals(movement,Arrays.copyOfRange(grid.data(),grid.movementBase()*4,grid.words()));
    }
}
