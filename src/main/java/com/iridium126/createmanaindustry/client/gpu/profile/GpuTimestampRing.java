package com.iridium126.createmanaindustry.client.gpu.profile;

import java.util.Arrays;
import java.util.function.DoubleConsumer;
import org.lwjgl.opengl.*;

/** Non-blocking timestamp pairs, safe inside a foreign GL_TIME_ELAPSED query. */
public final class GpuTimestampRing implements AutoCloseable {
    private final int[] queries;
    private final boolean[] pending, valid;
    private int next, oldest, count, active=-1;
    private boolean sampled;
    private double lastMs;
    private final DoubleConsumer completed;

    public GpuTimestampRing(int slots) {
        this(slots,null);
    }
    public GpuTimestampRing(int slots,DoubleConsumer completed) {
        if(slots<2)throw new IllegalArgumentException("GPU timer ring capacity");
        queries=new int[slots*2];pending=new boolean[slots];valid=new boolean[slots];
        this.completed=completed;
    }

    public boolean begin() {
        return begin(true);
    }
    public boolean begin(boolean pollCompleted) {
        if(active>=0)throw new IllegalStateException("GPU timestamp scope already active");
        if(queries[0]==0)GL15.glGenQueries(queries);
        if(pollCompleted)poll();
        if(count==pending.length)return false;
        active=next;
        GL33.glQueryCounter(queries[active*2],GL33.GL_TIMESTAMP);
        return true;
    }

    public void end(boolean publish) {
        end(publish,true);
    }
    public void end(boolean publish,boolean pollCompleted) {
        if(active<0)throw new IllegalStateException("No GPU timestamp scope");
        GL33.glQueryCounter(queries[active*2+1],GL33.GL_TIMESTAMP);
        pending[active]=true;valid[active]=publish;
        count++;next=(active+1)%pending.length;active=-1;
        if(pollCompleted)poll();
    }

    public void poll() {
        while(count>0) {
            int end=queries[oldest*2+1];
            if(GL15.glGetQueryObjecti(end,GL15.GL_QUERY_RESULT_AVAILABLE)==0)return;
            if(valid[oldest]) {
                long begin=GL33.glGetQueryObjectui64(queries[oldest*2],GL15.GL_QUERY_RESULT);
                long finish=GL33.glGetQueryObjectui64(end,GL15.GL_QUERY_RESULT);
                lastMs=Math.max(0,finish-begin)/1_000_000.0;sampled=true;
                if(completed!=null)completed.accept(lastMs);
            }
            pending[oldest]=false;valid[oldest]=false;
            oldest=(oldest+1)%pending.length;count--;
        }
    }

    public boolean sampled() { return sampled; }
    public int pending() { return count; }
    public double lastMs() { return lastMs; }
    @Override public void close() {
        for(int query:queries)if(query!=0)GL15.glDeleteQueries(query);
        Arrays.fill(queries,0);Arrays.fill(pending,false);Arrays.fill(valid,false);
        active=-1;next=oldest=count=0;sampled=false;lastMs=0;
    }
}
