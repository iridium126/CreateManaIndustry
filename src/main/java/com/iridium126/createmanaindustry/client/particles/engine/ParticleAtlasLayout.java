package com.iridium126.createmanaindustry.client.particles.engine;

import com.mojang.blaze3d.platform.NativeImage;

/** Native-resolution rectangles shared by resource composition, geometry and generated GLSL. */
public final class ParticleAtlasLayout {
    public record Rect(int x,int y,int width,int height) {
        public float[] uv(int atlasWidth,int atlasHeight) {
            return new float[]{(float)x/atlasWidth,(float)y/atlasHeight,(float)(x+width)/atlasWidth,(float)(y+height)/atlasHeight};
        }
    }
    public static final int SPRITE_WIDTH=32,SPRITE_HEIGHT=27,SPRITE_FRAMES=23;
    public static final int ALLAY_WIDTH=64,ALLAY_HEIGHT=48,ALLAY_FRAMES=7,ALLAY_MAX_MIP=4;
    public static final int FEATHER_FRAMES=12;
    private static final Rect[] SPRITES=new Rect[SPRITE_FRAMES],MODELS=new Rect[ALLAY_FRAMES],FEATHERS=new Rect[FEATHER_FRAMES];
    static {
        // Eleven 8x8 combat tiles; use their last vacant slot and a 3px strip for cherries.
        for(int i=0;i<12;i++)SPRITES[i]=i<4?new Rect(24+(i%2)*3,16+(i/2)*3,3,3):new Rect((i-4)*3,24,3,3);
        for(int i=12;i<SPRITE_FRAMES;i++)SPRITES[i]=new Rect(((i-12)%4)*8,((i-12)/4)*8,8,8);
        MODELS[0]=new Rect(0,0,32,32);
        for(int tier=1;tier<=4;tier++)MODELS[tier]=new Rect(32+((tier-1)%2)*16,((tier-1)/2)*16,16,16);
        MODELS[5]=new Rect(0,32,16,16);MODELS[6]=new Rect(16,32,16,16);
        // Six columns x two rows, each 3x3 source has a one-pixel gutter on all sides.
        for(int i=0;i<FEATHER_FRAMES;i++)FEATHERS[i]=new Rect(33+(i%6)*5,33+(i/6)*5,3,3);
    }
    private ParticleAtlasLayout() {}
    public static Rect sprite(int frame){return SPRITES[frame];}
    public static Rect model(int frame){return MODELS[frame];}
    public static Rect feather(int frame){return FEATHERS[frame];}
    /** Copies every source pixel once; resizing and cropping are never implicit. */
    public static void copy(NativeImage atlas,NativeImage source,Rect rect) {
        if(source.getWidth()!=rect.width||source.getHeight()!=rect.height)
            throw new IllegalArgumentException("Expected native particle texture "+rect.width+"x"+rect.height+", got "+source.getWidth()+"x"+source.getHeight());
        source.copyRect(atlas,0,0,rect.x,rect.y,rect.width,rect.height,false,false);
    }
    /** Constants only: no runtime UV uploads or additional texture fetches. */
    public static String shaderPrelude() {
        var out=new StringBuilder("#ifndef CMI_PARTICLE_ATLAS_LAYOUT\n#define CMI_PARTICLE_ATLAS_LAYOUT\n");
        out.append("const vec2 CMI_ALLAY_INV_SIZE=1.0/vec2(").append(ALLAY_WIDTH).append(".0,").append(ALLAY_HEIGHT).append(".0);\n");
        rects(out,"CMI_SPRITE_RECTS",SPRITES,SPRITE_WIDTH,SPRITE_HEIGHT);
        rects(out,"CMI_FEATHER_RECTS",FEATHERS,ALLAY_WIDTH,ALLAY_HEIGHT);
        return out.append("#endif\n").toString();
    }
    private static void rects(StringBuilder out,String name,Rect[] rects,int width,int height) {
        out.append("const vec4 ").append(name).append('[').append(rects.length).append("]=vec4[](");
        for(int i=0;i<rects.length;i++){
            if(i>0)out.append(',');var r=rects[i];
            out.append("vec4(").append(r.x).append(".0,").append(r.y).append(".0,").append(r.width).append(".0,").append(r.height)
                    .append(".0)/vec4(").append(width).append(".0,").append(height).append(".0,").append(width).append(".0,").append(height).append(".0)");
        }
        out.append(");\n");
    }
}
