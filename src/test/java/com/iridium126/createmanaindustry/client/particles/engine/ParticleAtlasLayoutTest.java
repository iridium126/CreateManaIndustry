package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ParticleAtlasLayoutTest {
    private static void occupy(boolean[] pixels,int width,int height,ParticleAtlasLayout.Rect rect,int gutter) {
        for(int y=rect.y()-gutter;y<rect.y()+rect.height()+gutter;y++)for(int x=rect.x()-gutter;x<rect.x()+rect.width()+gutter;x++) {
            assertTrue(x>=0&&x<width&&y>=0&&y<height,"Native rectangle outside atlas");
            int at=y*width+x;assertFalse(pixels[at],"Native rectangle or gutter overlaps another image");pixels[at]=true;
        }
    }
    @Test void allNativeImagesAndFeatherGuttersFitWithoutOverlaps() {
        var sprite=new boolean[ParticleAtlasLayout.SPRITE_WIDTH*ParticleAtlasLayout.SPRITE_HEIGHT];
        for(int i=0;i<23;i++){
            var r=ParticleAtlasLayout.sprite(i);assertEquals(i<12?3:8,r.width());assertEquals(r.width(),r.height());
            occupy(sprite,32,27,r,0);
        }
        var model=new boolean[ParticleAtlasLayout.ALLAY_WIDTH*ParticleAtlasLayout.ALLAY_HEIGHT];
        for(int i=0;i<7;i++){
            var r=ParticleAtlasLayout.model(i);assertEquals(i==0?32:16,r.width());assertEquals(r.width(),r.height());
            assertEquals(0,r.x()%16);assertEquals(0,r.y()%16);occupy(model,64,48,r,0);
        }
        for(int i=0;i<12;i++){
            var r=ParticleAtlasLayout.feather(i);assertEquals(3,r.width());assertEquals(3,r.height());occupy(model,64,48,r,1);
        }
        assertTrue(sprite.length<64*24);assertTrue(model.length<128*64);
    }
    @Test void everyHeldItemTierMapsExactlySixteenOriginalPixels() {
        var uv=HeldItemGeometry.uvTable();
        for(int tier=1;tier<=6;tier++){
            var rect=ParticleAtlasLayout.model(tier);
            assertArrayEquals(rect.uv(64,48),Arrays.copyOfRange(uv,tier*4,tier*4+4));
            assertEquals(16,(uv[tier*4+2]-uv[tier*4])*64,.00001);
            assertEquals(16,(uv[tier*4+3]-uv[tier*4+1])*48,.00001);
        }
    }
}
