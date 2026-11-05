package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants;
import com.iridium126.createmanaindustry.client.gpu.buffer.*;
import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Shared tick inputs for dedicated kernels; one upload for all simulation owners. */
public final class SimulationParameters implements AutoCloseable {
    private final ByteBuffer bytes=BufferUtils.createByteBuffer(GpuResourceConstants.SIMULATION_PARAMETERS_BYTES);
    private int buffer;
    public static void verify(int program){
        int index=GL31.glGetUniformBlockIndex(program,"CmiSimulationParameters");
        if(index==GL31.GL_INVALID_INDEX||GL31.glGetActiveUniformBlocki(program,index,GL31.GL_UNIFORM_BLOCK_DATA_SIZE)!=GpuResourceConstants.SIMULATION_PARAMETERS_BYTES)
            throw new IllegalStateException("Simulation std140 layout mismatch");
        GL31.glUniformBlockBinding(program,index,GpuResourceConstants.SIMULATION_PARAMETERS_BINDING);
    }
    public void upload(int capacity,float dt,int players,boolean collision){
        if(buffer==0){buffer=GL45.glCreateBuffers();GL45.glNamedBufferData(buffer,bytes.capacity(),GL15.GL_DYNAMIC_DRAW);
            if(GL45.glGetNamedBufferParameteri64(buffer,GL15.GL_BUFFER_SIZE)!=bytes.capacity())throw new IllegalStateException("Simulation parameter allocation failed");}
        var storm=AllayStormRuntime.INSTANCE;
        bytes.clear();bytes.putInt(capacity).putInt(storm.killEmitId()).putInt(players).putInt(collision?1:0);
        bytes.putFloat(dt).putFloat(storm.omegaNow()).putFloat(storm.rotPhase()).putFloat(storm.convPhase());
        bytes.putFloat(storm.convRate()).putFloat(0).putFloat(0).putFloat(0);bytes.flip();
        GpuReplayCommands.namedBufferSubData(buffer,0,bytes);
        GpuBindings.bindBase(GL31.GL_UNIFORM_BUFFER,GpuResourceConstants.SIMULATION_PARAMETERS_BINDING,buffer);
    }
    @Override public void close(){GpuBufferNames.delete(buffer);buffer=0;}
}
