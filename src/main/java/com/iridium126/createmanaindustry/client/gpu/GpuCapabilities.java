package com.iridium126.createmanaindustry.client.gpu;

import org.lwjgl.opengl.*;

/** Immutable device limits for the calling GL context. No bindings or business
 * requirements are cached here; each owner checks only the passes it creates. */
public record GpuCapabilities(boolean openGl45,long storageBlockBytes,int storageBindings,int computeStorageBlocks,
        int computeGroupsX,int computeLocalX,int computeInvocations,int textureBufferTexels,int textureUnits) {
    private record Context(GLCapabilities gl,GpuCapabilities limits) {}
    private static final ThreadLocal<Context> cached=new ThreadLocal<>();

    public static GpuCapabilities current() {
        var gl=GL.getCapabilities();var previous=cached.get();
        if(previous!=null&&previous.gl()==gl)return previous.limits();
        var limits=gl.OpenGL43?new GpuCapabilities(gl.OpenGL45,
                GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE),
                GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS),
                GL11.glGetInteger(GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS),
                GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0),
                GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE,0),
                GL11.glGetInteger(GL43.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS),
                GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE),
                GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)):
                new GpuCapabilities(false,0,0,0,0,0,0,0,0);
        cached.set(new Context(gl,limits));return limits;
    }

    public boolean supportsCompute(int highestBinding,int blocks,int localX) {
        if(highestBinding<0||blocks<1||localX<1)throw new IllegalArgumentException("GPU pass requirements");
        return openGl45&&highestBinding<storageBindings&&blocks<=computeStorageBlocks
                &&localX<=computeLocalX&&localX<=computeInvocations;
    }

    /** Bound element counts before multiplying sizes; long intermediates prevent wraparound. */
    public int elementCapacity(int requested,int elementBytes,int localX) {
        if(requested<0||elementBytes<1||localX<1)throw new IllegalArgumentException("GPU capacity layout");
        if(!openGl45||localX>computeLocalX||localX>computeInvocations)return 0;
        return (int)Math.max(0,Math.min((long)requested,Math.min(storageBlockBytes/elementBytes,(long)computeGroupsX*localX)));
    }
}
