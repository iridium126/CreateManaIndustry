package com.iridium126.createmanaindustry.client.particles.engine;
import java.nio.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
public class SimulationReadbackGpuValidation {
    static void put(int buffer,ByteBuffer data){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);}
    static int counter(ParticleBuffers pool,int slot){pool.bindCounter(3,slot);return GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,3);}
    static ByteBuffer bytes(int n){return BufferUtils.createByteBuffer(n);}
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);long window=GLFW.glfwCreateWindow(64,64,"Simulation snapshot",0,0);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();ParticleBuffers pool=new ParticleBuffers();ParticleReadbacks readbacks=new ParticleReadbacks();
            var enabled=ParticleDiagnostics.class.getDeclaredField("enabled");enabled.setAccessible(true);boolean before=enabled.getBoolean(ParticleDiagnostics.INSTANCE);
            try{
                check(pool.init(1024,4),"Pool allocation");pool.commitPopulation(40);
                put(counter(pool,0),bytes(16).putInt(0,6));put(counter(pool,1),bytes(16).putInt(0,9));
                put(pool.populationBufferId(40),bytes(32).putInt(0,3).putInt(8,6).putInt(12,1).putInt(16,40));
                enabled.setBoolean(ParticleDiagnostics.INSTANCE,true);var slot=readbacks.freeSlot();slot.generation=41;slot.published=true;
                pool.copySnapshot(1,0,77,slot);slot.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);GL11.glFinish();
                var result=readbacks.completed();check(result==slot,"Fence completion");check(result.profile&&result.profileFrame==77,"Submission metadata");
                check(result.data.getInt(0)==9,"Output census");int at=ParticleReadbacks.PROFILE_SIM_OFFSET;
                check(result.data.getInt(at)==6&&result.data.getInt(at+16)==3&&result.data.getInt(at+28)==1&&result.data.getInt(at+32)==40,"Input population lineage");readbacks.release(result);
                enabled.setBoolean(ParticleDiagnostics.INSTANCE,false);slot=readbacks.freeSlot();pool.copySnapshot(1,0,78,slot);check(!slot.profile,"Disabled capture flag");
                check(GL11.glGetError()==0,"GL state");System.out.println("Simulation readback GPU: input/output counters, population generation, fence and disabled capture passed");
            }finally{enabled.setBoolean(ParticleDiagnostics.INSTANCE,before);readbacks.close();pool.free();}
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
