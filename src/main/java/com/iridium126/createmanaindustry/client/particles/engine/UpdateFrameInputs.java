package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers;
import org.lwjgl.opengl.*;

/** CPU-direct layout candidate. No device copies or readback; not selected by the production kernel. */
public final class UpdateFrameInputs implements AutoCloseable {
    public record Layout(int bakeBytes,int playerBytes,int damageBytes,int alignment,int playerOffset,int damageOffset,int bytes) {
        public static Layout of(int bake,int players,int damage,int alignment){
            if(bake<16||players<16||damage<16||(bake&15)!=0||(players&15)!=0||(damage&3)!=0||alignment<16||alignment%16!=0)
                throw new IllegalArgumentException("Frame input sizes/alignment");
            int playerOffset=align(bake,alignment),damageOffset=align(Math.addExact(playerOffset,players),alignment);
            return new Layout(bake,players,damage,alignment,playerOffset,damageOffset,align(Math.addExact(damageOffset,damage),16));
        }
        private static int align(int bytes,int alignment){return Math.multiplyExact(Math.floorDiv(Math.addExact(bytes,alignment-1),alignment),alignment);}
    }
    private final int buffer;
    private final Layout layout;
    private final ByteBuffer staging;
    private boolean closed;
    public UpdateFrameInputs(Layout layout){
        this.layout=layout;staging=ByteBuffer.allocateDirect(layout.bytes()).order(ByteOrder.nativeOrder());
        buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,layout.bytes(),GL15.GL_STREAM_DRAW);
    }
    public Layout layout(){return layout;}
    public int buffer(){return buffer;}
    /** Inputs remain owned by the caller; positions and limits are not mutated. */
    public void stage(ByteBuffer bake,ByteBuffer players,ByteBuffer damage){
        if(bake.remaining()!=layout.bakeBytes()||players.remaining()!=layout.playerBytes()||damage.remaining()!=layout.damageBytes())
            throw new IllegalArgumentException("Frame input range changed");
        staging.put(0,bake,bake.position(),bake.remaining());
        staging.put(layout.playerOffset(),players,players.position(),players.remaining());
        staging.put(layout.damageOffset(),damage,damage.position(),damage.remaining());
    }
    public void upload(){if(closed)throw new IllegalStateException("Frame inputs closed");GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,staging);}
    public void bind(int binding){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,binding,buffer);}
    /** Other stages keep their existing block ABI using aligned views. */
    public void bindViews(int bake,int players,int damage){
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,bake,buffer,0,layout.bakeBytes());
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,players,buffer,layout.playerOffset(),layout.playerBytes());
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,damage,buffer,layout.damageOffset(),layout.damageBytes());
    }
    @Override public void close(){if(closed)return;closed=true;GpuBufferNames.delete(buffer);}
}
