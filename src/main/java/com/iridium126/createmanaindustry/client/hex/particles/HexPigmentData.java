package com.iridium126.createmanaindustry.client.hex.particles;

import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.client.ClientTickCounter;
import at.petrak.hexcasting.common.items.pigment.*;
import java.nio.FloatBuffer;

/** Per-spray immutable parameters. Existing particles retain RGB, never a transient palette reference. */
public final class HexPigmentData {
    public static final int WORDS=com.iridium126.createmanaindustry.client.particles.emitter.ParticleLayout.PARAMETER_WORDS, MAX_COLORS=32;
    private final float[] words;
    private HexPigmentData(float[] words){this.words=words;}

    public static HexPigmentData palette(int[] colors,float time,float period) {
        if(colors==null||colors.length==0||colors.length>MAX_COLORS||!Float.isFinite(time)||period<=0)return null;
        float[] data=new float[WORDS];
        data[0]=colors.length==1?1:2;data[1]=colors.length;data[2]=time/period;data[3]=time;
        data[4]=data[5]=data[6]=.1f;
        for(int i=0;i<colors.length;i++)data[8+i]=Float.intBitsToFloat(colors[i]|0xff000000);
        return new HexPigmentData(data);
    }

    public static HexPigmentData nativePigment(FrozenPigment pigment) {
        var item=pigment.item().getItem();var type=item.getClass();
        var provider=pigment.getColorProvider();
        // A substituted provider must use the original handler rather than an approximate wheel.
        String owner="at.petrak.hexcasting.common.items.pigment."+type.getSimpleName()+"$MyColorProvider";
        if(!provider.getClass().getName().equals(owner))return null;
        float time=ClientTickCounter.getTotal();
        if(type==ItemUUIDPigment.class&&provider instanceof HexColorPalette palette)
            return palette(palette.cmi$palette(),time,400);
        if(type==ItemPridePigment.class&&((Object)((ItemPridePigment)item).type) instanceof HexColorPalette palette)
            return palette(palette.cmi$palette(),time,400);
        if(type==ItemDyePigment.class)return palette(new int[]{((ItemDyePigment)item).getDyeColor().getTextColor()},time,400);
        if(type==ItemAmethystPigment.class)return palette(new int[]{0xffab65eb},time,400);
        if(type==ItemAmethystAndCopperPigment.class)
            return palette(new int[]{0xff54398a,0xffcfa0f3,0xfffecbe6,0xffcfa0f3,0xffe77c56},time,600);
        return null;
    }

    /** Compatibility input for the public sampled-wheel API; native packet ingress does not use it. */
    public static HexPigmentData wheel(float[] rgba) {
        if(rgba==null||rgba.length!=32)return null;
        float[] data=new float[WORDS];System.arraycopy(rgba,0,data,8,32);
        for(float v:rgba)if(!Float.isFinite(v))return null;
        return new HexPigmentData(data);
    }

    public void write(FloatBuffer output,float fuzz,float spread) {
        int start=output.position();output.put(words);
        output.put(start+40,fuzz).put(start+41,spread);
    }
}
