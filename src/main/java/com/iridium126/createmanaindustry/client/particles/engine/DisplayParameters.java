package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.*;

/** std140 display parameters, one upload per frozen publication, no per-pass reflection. */
public final class DisplayParameters {
    public static final int BINDING=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.DISPLAY_PARAMETERS_BINDING,
            BYTES=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.DISPLAY_PARAMETERS_BYTES;
    private DisplayParameters() {}
    public static String prelude(){return "#define CMI_DISPLAY_PARAMETERS_UBO 1\n#define CMI_DISPLAY_PARAMETERS_BINDING "+BINDING+"\n";}
    public static void verify(int program){
        int index=GL31.glGetUniformBlockIndex(program,"CmiDisplayParameters");
        if(index==GL31.GL_INVALID_INDEX||GL31.glGetActiveUniformBlocki(program,index,GL31.GL_UNIFORM_BLOCK_DATA_SIZE)!=BYTES)
            throw new IllegalStateException("Display std140 layout mismatch");
        GL31.glUniformBlockBinding(program,index,BINDING);
    }
    public static void write(ByteBuffer out,int capacity,int historyBase,int metaSlot,int carrierLimit,float alpha,
                             net.minecraft.world.phys.Vec3 currentDelta,net.minecraft.world.phys.Vec3 previousDelta){
        out.clear();out.putInt(capacity).putInt(historyBase).putInt(metaSlot).putInt(carrierLimit);
        out.putFloat(alpha).putFloat(0).putFloat(0).putFloat(0);
        out.putFloat((float)currentDelta.x).putFloat((float)currentDelta.y).putFloat((float)currentDelta.z).putFloat(0);
        out.putFloat((float)previousDelta.x).putFloat((float)previousDelta.y).putFloat((float)previousDelta.z).putFloat(0);
        out.flip();
    }
}
