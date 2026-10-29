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
    @Test void stormRateBoundariesAndStrictHeight() {
        assertEquals(1000,AllayFeatherLayout.rate(1000,20,0,50,60,true));
        assertEquals(1000,AllayFeatherLayout.rate(1000,20,20,50,60,true));
        assertEquals(500,AllayFeatherLayout.rate(1000,20,30,50,60,true));
        assertEquals(0,AllayFeatherLayout.rate(1000,20,40,50,60,true));
        assertEquals(0,AllayFeatherLayout.rate(1000,20,20,52,60,true));
        assertEquals(0,AllayFeatherLayout.rate(1000,20,20,50,60,false));
        assertEquals(0,AllayFeatherLayout.rate(1000,0,0,0,60,true));
        assertEquals(2000,AllayFeatherLayout.rate(3000,20,20,50,60,true));
        assertEquals(0,AllayFeatherLayout.rate(0,20,20,50,60,true));
    }
    @Test void finiteOpaqueAndWhiteUncollidedSpecsKeepParticleAbi() {
        var feather=EmitterPresets.byName("allay_feather");assertSame(AllayFeatherSpecs.FEATHER,feather);
        assertEquals(ParticleTypes.Material.OPAQUE,feather.type.material());
        assertNotEquals(ParticleTypes.standard(ParticleTypes.Material.MODEL),feather.type);
        assertEquals(60,feather.lifeMin);assertEquals(60,feather.lifeMax);
        assertEquals(EmitterPresets.CHERRY_LEAVES.sizeStart,feather.sizeStart);
        assertEquals(EmitterPresets.CHERRY_LEAVES.gravity,feather.gravity);
        assertEquals(EmitterSpec.CollideMode.REST,feather.collideMode);
        assertEquals(EmitterSpec.CollideMode.NONE,AllayFeatherSpecs.SPARK.collideMode);
        for(int i=0;i<AllayFeatherSpecs.SPARK.colors.length;i+=4)
            assertArrayEquals(new float[]{1,1,1},Arrays.copyOfRange(AllayFeatherSpecs.SPARK.colors,i,i+3));
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
