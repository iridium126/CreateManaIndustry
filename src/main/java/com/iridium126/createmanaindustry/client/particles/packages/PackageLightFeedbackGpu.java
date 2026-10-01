package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** GPU hash representatives and an independent nonblocking prefetch ring. Unsent work is retried. */
public final class PackageLightFeedbackGpu implements AutoCloseable {
    private final PackageLightRequests decoder=new PackageLightRequests();
    private final long epoch;
    private int missing,winners,requests;
    private PackageReadbackRing ring;
    private long sequence,submittedBytes,completed,overflow,skipped,lastLatency;
    private boolean closed;
    public PackageLightFeedbackGpu(int capacity,long epoch) {
        if(capacity<1 || capacity>131072 || epoch<=0)throw new IllegalArgumentException("Light feedback capacity/epoch");
        this.epoch=epoch;
        try{missing=buffer((long)capacity*16);winners=buffer(PackageLightRequests.BUCKETS*4L);requests=buffer(PackageLightRequests.BYTES);
            ring=new PackageReadbackRing(PackageLightRequests.BYTES);
        }catch(RuntimeException failure){close();throw failure;}
    }
    private static int buffer(long bytes){int b=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return b;}
    /** Clear GPU counters, not CPU census. Shader/copy writes must finish before buffer updates. */
    public void begin() {
        open();GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,winners);
            GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(-1));
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,requests);
            GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,PackageLightRequests.HEADER_BYTES,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(ByteBuffer)null);
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,9,stack.ints(missing,winners,requests));
        }
    }
    /** Run the representative gather before this copy. A full ring does not acknowledge requests. */
    public boolean capture() {
        open();if(!ring.submit(requests,epoch,sequence)){skipped++;return false;}
        sequence++;submittedBytes+=PackageLightRequests.BYTES;return true;
    }
    public void poll(Consumer<PackageCollisionCache.Section> consumer) {
        open();ring.poll(epoch,snapshot->{int total=decoder.consume(snapshot.bytes(),consumer);
            if(total>PackageLightRequests.MAX_REQUESTS)overflow++;completed++;
            lastLatency=System.nanoTime()-snapshot.submittedNanos();});
    }
    public long submittedBytes(){return submittedBytes;}
    public long completed(){return completed;}
    public long overflow(){return overflow;}
    public long skipped(){return skipped;}
    public long lastLatency(){return lastLatency;}
    public int pending(){open();return ring.pending();}
    private void open(){if(closed)throw new IllegalStateException("Light feedback closed");}
    @Override public void close(){if(closed)return;closed=true;if(ring!=null)ring.close();for(int b:new int[]{missing,winners,requests})if(b!=0)GL15.glDeleteBuffers(b);}
}
