package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.function.LongToIntFunction;
import org.lwjgl.opengl.*;

/** Four independent write banks. A busy bank is never overwritten and a view never waits. */
public final class PackageForceGpu implements AutoCloseable {
    private static final int FLAGS=GL30.GL_MAP_WRITE_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
    private static final int BYTES=(PackageForceScene.MAX_SOURCES*2-1)*PackageForceScene.NODE_BYTES
            +PackageForceScene.MAX_SOURCES*(PackageForceScene.SOURCE_BYTES+PackageForceScene.FRAME_BYTES);
    private static final class Bank {int buffer;ByteBuffer mapped;long fence;boolean leased;}
    private final Bank[] banks=new Bank[4];
    private final LongToIntFunction poll;
    private boolean closed;
    private long uploadedBytes;
    public PackageForceGpu(){this(f->GL32.glClientWaitSync(f,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0));}
    public PackageForceGpu(LongToIntFunction poll){
        this.poll=java.util.Objects.requireNonNull(poll);
        try{if(BYTES>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalStateException("Force device capacity");
            for(int i=0;i<4;i++){Bank b=new Bank();banks[i]=b;b.buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b.buffer);
                GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER,BYTES,FLAGS);b.mapped=GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,0,BYTES,FLAGS);
                if(b.mapped==null)throw new IllegalStateException("Package force upload mapping");}}
        catch(RuntimeException failure){close();throw failure;}
    }
    public View view(PackageForceScene.Snapshot snapshot,long tick) {
        var view=tryView(snapshot,tick);if(view==null)throw new IllegalStateException("All package force banks busy");return view;
    }
    public View tryView(PackageForceScene.Snapshot snapshot,long tick) {
        if(closed)throw new IllegalStateException("Package force uploader closed");
        if(snapshot==null||tick<snapshot.tick()||tick-snapshot.tick()>1)throw new IllegalStateException("Package force snapshot exceeded two tick budget");
        for(Bank b:banks){if(b.fence!=0){int result=poll.applyAsInt(b.fence);if(result==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Package force fence failed");
                if(result!=GL32.GL_TIMEOUT_EXPIRED){GL32.glDeleteSync(b.fence);b.fence=0;}}
            if(b.fence==0&&!b.leased){b.mapped.clear();b.mapped.put(snapshot.data());uploadedBytes+=snapshot.data().remaining();b.leased=true;return new View(b,snapshot.nodes(),snapshot.sources(),snapshot.frames());}}
        return null;
    }
    public final class View implements AutoCloseable {
        private final Bank bank;private final int nodes,sources,frames;private boolean ended;
        private View(Bank bank,int nodes,int sources,int frames){this.bank=bank;this.nodes=nodes;this.sources=sources;this.frames=frames;}
        public int nodes(){return nodes;}
        public int frames(){return frames;}
        public void bind(int location,int sourcesLocation){if(ended||closed)throw new IllegalStateException("Package force view closed");
            GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,bank.buffer);GL30.glUniform1ui(location,nodes);if(sourcesLocation>=0)GL30.glUniform1ui(sourcesLocation,sources);}
        @Override public void close(){if(ended)return;ended=true;bank.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);bank.leased=false;
            if(bank.fence==0)throw new IllegalStateException("Package force upload fence unavailable");}
    }
    public long uploadedBytes(){return uploadedBytes;}
    @Override public void close(){if(closed)return;closed=true;for(Bank b:banks)if(b!=null){if(b.fence!=0)GL32.glDeleteSync(b.fence);if(b.buffer!=0)GL15.glDeleteBuffers(b.buffer);}}
}
