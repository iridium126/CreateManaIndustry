package com.iridium126.createmanaindustry.client.particles.engine;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.server.packs.resources.ResourceManager;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import java.io.IOException;

/** Native 3x3 palette conversion with one-pixel gutters, baked once at resource load. */
public final class AllayFeatherAppearance {
    private AllayFeatherAppearance() {}
    public static int recolor(int rgba) {
        int r=rgba&255,g=rgba>>>8&255,b=rgba>>>16&255;
        double luminance=.2126*r+.7152*g+.0722*b;
        double t=Math.clamp((luminance-134.8584)/65.8498,0,1);
        int low=t<.5?0xd18329:0xe8b328;
        int high=t<.5?0xe8b328:0xfffb64;
        double f=t<.5?t*2:(t-.5)*2;
        return (rgba&0xff000000) | (int)Math.round((low&255)+((high&255)-(low&255))*f)
                | (int)Math.round((low>>>8&255)+((high>>>8&255)-(low>>>8&255))*f)<<8
                | (int)Math.round((low>>>16&255)+((high>>>16&255)-(low>>>16&255))*f)<<16;
    }
    public static void bake(NativeImage atlas, ResourceManager resources) {
        for(int frame=0;frame<12;frame++) {
            var id=CreateManaIndustry.modLoc("textures/particle/cherry_"+frame+".png");
            try(var stream=resources.open(id);var image=NativeImage.read(stream)) {
                bakeFrame(atlas,image,frame);
            } catch(IOException failure) { throw new java.io.UncheckedIOException("Cannot load feather frame "+id,failure); }
        }
    }
    public static void bakeFrame(NativeImage atlas, NativeImage image, int frame) {
        var rect=ParticleAtlasLayout.feather(frame);
        if(image.getWidth()!=rect.width()||image.getHeight()!=rect.height())throw new IllegalArgumentException("Feather source must retain its native 3x3 size");
        for(int y=-1;y<=3;y++)for(int x=-1;x<=3;x++) {
            int color=image.getPixelRGBA(Math.clamp(x,0,2),Math.clamp(y,0,2));
            atlas.setPixelRGBA(rect.x()+x,rect.y()+y,recolor(color));
        }
    }
}
