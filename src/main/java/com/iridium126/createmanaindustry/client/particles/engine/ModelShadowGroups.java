package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.*;

/** Shadow-only, unculled role lists. Front=body, back=outside; original population ABI is untouched. */
public final class ModelShadowGroups implements AutoCloseable {
    public static final int BINDING=30, HEADER_BYTES=64;
    private final int[] buffers=new int[2];
    private final long[] generations={-1,-1};
    private final ByteBuffer header=ByteBuffer.allocateDirect(HEADER_BYTES).order(ByteOrder.nativeOrder());
    private int capacity;
    private long candidate=-1;
    private boolean wasEnabled;
    private final org.joml.Matrix4f lightInverse=new org.joml.Matrix4f();
    private final org.joml.Vector3f ray=new org.joml.Vector3f();

    public static boolean requested(){
        return ShadowQualityFrame.current().groupsStorm() && ClientConfig.shaderPackIntegration
                && AllayStormRuntime.INSTANCE.active()
                && CreateManaIndustry.IRISVEIL_ACTIVE && top.leonx.irisveil.IrisVeilCompat.isShaderPackInUse();
    }
    public void begin(int program,int bank,int modelCapacity,long generation,StormShadowSnapshot snapshot,float[] planes,net.minecraft.world.phys.Vec3 camera){
        boolean enabled=requested()&&snapshot!=null&&snapshot.matches(generation);
        // Prediction only: a stale light direction can miss an optimization, never authorize a reject.
        // The shadow prepare shader retests the committed cylinder with the actual current light.
        if(enabled&&planes!=null){lightInverse.set(net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW).invert().transformDirection(ray.set(0,0,-1));ray.normalize();
            enabled=!ShadowVolumeCulling.cylinderVisible(planes,(float)(snapshot.x()-camera.x),(float)(snapshot.y()-camera.y)+(snapshot.low()+snapshot.high())*.5f,
                    (float)(snapshot.z()-camera.z),snapshot.radius(),(snapshot.high()-snapshot.low())*.5f,ray.x,ray.y,ray.z);}
        beginCandidate(program,bank,modelCapacity,generation,snapshot,enabled);
    }
    /** Policy is resolved before this resource transaction; failed candidates never alter the read bank. */
    private void beginCandidate(int program,int bank,int modelCapacity,long generation,StormShadowSnapshot snapshot,boolean enabled){
        if(bank<0||bank>1)throw new IllegalArgumentException("Shadow group bank");
        generations[bank]=-1;candidate=-1;
        enabled=enabled&&snapshot!=null&&snapshot.matches(generation);
        if(!enabled){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,GpuUniforms.loc(program,"uShadowGroupsWrite"),0);wasEnabled=false;return;}
        if(modelCapacity<1)throw new IllegalArgumentException("Shadow group capacity");
        float radius=snapshot.radius(),low=snapshot.low(),high=snapshot.high();
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,GpuUniforms.loc(program,"uShadowGroupsWrite"),enabled?1:0);
        wasEnabled=enabled;
        if(!enabled)return;
        if(capacity!=modelCapacity){close();capacity=modelCapacity;
            for(int i=0;i<2;i++){buffers[i]=GL15.glGenBuffers();GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffers[i]);
                GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,HEADER_BYTES+8L*capacity,GL15.GL_DYNAMIC_COPY);}}
        header.clear();header.putInt(0).putInt(0).putInt(capacity).putInt(1);
        header.putLong(generation).putLong(0);
        header.putFloat((float)snapshot.x()).putFloat((float)snapshot.y()).putFloat((float)snapshot.z()).putFloat(radius);
        header.putFloat(low).putFloat(high).putFloat(0).putFloat(0).flip();
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffers[bank]);GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,header);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,BINDING,buffers[bank]);candidate=generation;
    }
    public void commit(int bank,long generation){generations[bank]=candidate==generation?generation:-1;candidate=-1;}
    public int buffer(int bank,long generation){return requested()?publishedBuffer(bank,generation):0;}
    private int publishedBuffer(int bank,long generation){return generations[bank]==generation?buffers[bank]:0;}
    public long bytes(){return capacity==0?0:2L*(HEADER_BYTES+capacity*8L);}
    @Override public void close(){for(int i=0;i<2;i++){if(buffers[i]!=0)GpuBufferNames.delete(buffers[i]);buffers[i]=0;generations[i]=-1;}capacity=0;candidate=-1;}
}
