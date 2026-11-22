package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.*;

/** CompositeRenderer unbinds every sampler and texture unit, including external lightmaps.
 * Save only the unit range Iris can touch; this scope is used once around native replay. */
public final class SkyTextureState implements AutoCloseable {
    private static final int[] TARGETS={GL11.GL_TEXTURE_1D,GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_3D,GL13.GL_TEXTURE_CUBE_MAP,
            GL30.GL_TEXTURE_1D_ARRAY,GL30.GL_TEXTURE_2D_ARRAY,GL31.GL_TEXTURE_BUFFER,GL32.GL_TEXTURE_2D_MULTISAMPLE,GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY,GL31.GL_TEXTURE_RECTANGLE};
    private static final int[] BINDINGS={GL11.GL_TEXTURE_BINDING_1D,GL11.GL_TEXTURE_BINDING_2D,GL12.GL_TEXTURE_BINDING_3D,GL13.GL_TEXTURE_BINDING_CUBE_MAP,
            GL30.GL_TEXTURE_BINDING_1D_ARRAY,GL30.GL_TEXTURE_BINDING_2D_ARRAY,GL31.GL_TEXTURE_BINDING_BUFFER,GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE,GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY,GL31.GL_TEXTURE_BINDING_RECTANGLE};
    private int active;
    private final int[][] textures;
    private final int[] samplers,engine=new int[12];
    private final int[] cubes;
    private final boolean multiBind;
    public SkyTextureState(){this(SamplerLimits.get().getMaxTextureUnits());}
    // Standalone GL harnesses have vanilla's 12 tracked units before Iris's cache-expansion mixin.
    SkyTextureState(int units){
        textures=new int[units][TARGETS.length];samplers=new int[units];
        cubes=GL.getCapabilities().OpenGL40||GL.getCapabilities().GL_ARB_texture_cube_map_array?new int[units]:null;
        multiBind=GL.getCapabilities().glBindTextures!=0;
        capture();
    }

    SkyTextureState capture(){
        active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for(int i=0;i<engine.length;i++)engine[i]=RenderSystem.getShaderTexture(i);
        for(int unit=0;unit<textures.length;unit++){
            GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
            for(int t=0;t<TARGETS.length;t++)textures[unit][t]=GL11.glGetInteger(BINDINGS[t]);
            samplers[unit]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,unit);
            if(cubes!=null)cubes[unit]=GL11.glGetInteger(GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY);
        }
        GL13.glActiveTexture(active);
        return this;
    }
    @Override public void close(){
        ProgramSamplers.clearActiveSamplers();
        if(multiBind)GL44.nglBindTextures(0,textures.length,0);
        for(int unit=0;unit<textures.length;unit++){
            RenderSystem.activeTexture(GL13.GL_TEXTURE0+unit);GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
            // Force the tracked 2D cache through a known binding, including DSA Iris paths.
            GlStateManager._bindTexture(0);
            if(multiBind){
                if(textures[unit][1]!=0)GlStateManager._bindTexture(textures[unit][1]);
                for(int t=0;t<TARGETS.length;t++)if(t!=1&&textures[unit][t]!=0)
                    GL11.glBindTexture(TARGETS[t],textures[unit][t]);
                if(cubes!=null&&cubes[unit]!=0)GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY,cubes[unit]);
            }else{
                for(int t=0;t<TARGETS.length;t++)GL11.glBindTexture(TARGETS[t],textures[unit][t]);
                if(cubes!=null)GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY,cubes[unit]);
                GlStateManager._bindTexture(textures[unit][1]);
            }
            IrisRenderSystem.bindSamplerToUnit(unit,samplers[unit]);
        }
        for(int i=0;i<engine.length;i++)RenderSystem.setShaderTexture(i,engine[i]);
        RenderSystem.activeTexture(active);GL13.glActiveTexture(active);
    }
}
