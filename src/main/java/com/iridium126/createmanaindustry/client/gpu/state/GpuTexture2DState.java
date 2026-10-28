package com.iridium126.createmanaindustry.client.gpu.state;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;

/** Small render boundary which restores both real bindings and vanilla's binding cache. */
public final class GpuTexture2DState implements AutoCloseable {
    @FunctionalInterface public interface SamplerBinding {void bind(int unit,int sampler);}
    private final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] units,textures,samplers,engine=new int[12];
    private final boolean samplerObjects=GL.getCapabilities().OpenGL33||GL.getCapabilities().GL_ARB_sampler_objects;
    private final SamplerBinding samplerBinding;
    private final int[] externalSamplers;
    public GpuTexture2DState(int... units){this(null,units);}
    public GpuTexture2DState(SamplerBinding samplerBinding,int... units){this(samplerBinding,units,0);}
    /** Iris clear() unbinds every sampler, including units it did not use. */
    public static GpuTexture2DState withSamplerRange(SamplerBinding binding,int samplerUnits,int... textureUnits){return new GpuTexture2DState(binding,textureUnits,samplerUnits);}
    private GpuTexture2DState(SamplerBinding samplerBinding,int[] units,int samplerUnits){
        this.samplerBinding=samplerBinding;
        this.units=java.util.Arrays.stream(units).filter(i->i>=0).distinct().toArray();
        textures=new int[this.units.length];samplers=new int[this.units.length];
        externalSamplers=new int[samplerObjects?samplerUnits:0];
        for(int i=0;i<externalSamplers.length;i++)externalSamplers[i]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,i);
        for(int i=0;i<engine.length;i++)engine[i]=RenderSystem.getShaderTexture(i);
        for(int i=0;i<this.units.length;i++){
            activate(this.units[i]);textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if(samplerObjects)samplers[i]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,this.units[i]);
        }
        RenderSystem.activeTexture(active);GL13.glActiveTexture(active);
    }
    public static void activate(int unit){RenderSystem.activeTexture(GL13.GL_TEXTURE0+unit);GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);}
    public static void bind(int unit,int texture){activate(unit);GlStateManager._bindTexture(texture);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);}
    @Override public void close(){
        for(int i=0;i<units.length;i++){
            bind(units[i],textures[i]);if(samplerObjects){GL33.glBindSampler(units[i],samplers[i]);if(samplerBinding!=null)samplerBinding.bind(units[i],samplers[i]);}
        }
        for(int i=0;i<engine.length;i++)RenderSystem.setShaderTexture(i,engine[i]);
        for(int i=0;i<externalSamplers.length;i++){GL33.glBindSampler(i,externalSamplers[i]);if(samplerBinding!=null)samplerBinding.bind(i,externalSamplers[i]);}
        RenderSystem.activeTexture(active);GL13.glActiveTexture(active);
    }
}
