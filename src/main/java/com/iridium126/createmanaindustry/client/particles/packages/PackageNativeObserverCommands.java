package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.Arrays;

/** Bounded, reusable native command inbox. Each GPU batch contains one command per member;
 * subsequent commands stay ordered without summing relative deltas or merging poses on CPU.
 * Single engine/client thread. Overflow returns false so the adapter restores native ownership;
 * busy GPU upload slots retain the exact staged batch and all following lifecycle events. */
public final class PackageNativeObserverCommands {
    @FunctionalInterface public interface Submit {
        boolean apply(ByteBuffer commands,int records,int slots,float time);
    }
    private final Thread owner=Thread.currentThread();
    private final long epoch;
    private final int capacity;
    private final ByteBuffer records,staging;
    private final int[] next,heads,tails,ready,readyIndex,stagedLocals;
    private int freeHead,readyCount,queued,staged,lastSlots;
    public PackageNativeObserverCommands(int capacity,int maxCommands,long epoch) {
        if(capacity<1||capacity>131072||maxCommands<1||maxCommands>(long)capacity*4||epoch<=0)
            throw new IllegalArgumentException("Native command inbox capacity/epoch");
        this.capacity=capacity;this.epoch=epoch;
        records=ByteBuffer.allocateDirect(maxCommands*64).order(ByteOrder.nativeOrder());
        staging=ByteBuffer.allocateDirect(Math.min(capacity,maxCommands)*64).order(ByteOrder.nativeOrder());
        next=new int[maxCommands];for(int i=0;i<maxCommands;i++)next[i]=i+1<maxCommands?i+1:-1;
        heads=new int[capacity];tails=new int[capacity];ready=new int[capacity];readyIndex=new int[capacity];
        stagedLocals=new int[Math.min(capacity,maxCommands)];Arrays.fill(heads,-1);Arrays.fill(tails,-1);Arrays.fill(readyIndex,-1);
    }
    /** Does not consume the caller buffer; copies raw words only. Old epochs are rejected. */
    public boolean offer(ByteBuffer command) {
        thread();
        if(command==null||command.remaining()!=64||command.order()!=ByteOrder.nativeOrder())throw new IllegalArgumentException("Native inbox command");
        int p=command.position(),local=command.getInt(p+16);
        if(command.getLong(p)!=epoch)return false;
        if(local<0||local>=capacity)throw new IllegalArgumentException("Native inbox destination");
        if(freeHead<0)return false;
        int index=freeHead;freeHead=next[index];next[index]=-1;
        for(int j=0;j<64;j+=8)records.putLong(index*64+j,command.getLong(p+j));
        if(heads[local]<0){heads[local]=index;readyIndex[local]=readyCount;ready[readyCount++]=local;}
        else next[tails[local]]=index;
        tails[local]=index;queued++;return true;
    }
    /** A false submission never dequeues a relative packet. No waits, pose reads or GL polling. */
    public int drain(Submit submit,int slots,float time,int waveBudget) {
        thread();if(submit==null||slots<lastSlots||slots>capacity||!Float.isFinite(time)||time<0||waveBudget<0)
            throw new IllegalArgumentException("Native inbox submission");
        lastSlots=slots;
        int consumed=0;
        for(int wave=0;wave<waveBudget&&queued>0;wave++) {
            if(staged==0) {
                staging.clear();
                for(int i=0;i<readyCount&&staged<stagedLocals.length;i++) {
                    int local=ready[i];if(local>=slots)continue;
                    stagedLocals[staged++]=local;int p=heads[local]*64;
                    for(int j=0;j<64;j+=8)staging.putLong(records.getLong(p+j));
                }
                staging.flip();
                // Baselines may be prepared in bounded prefixes over several frames. Never
                // submit a command before that member's baseline, nor block older members.
                if(staged==0)break;
            }
            // A callback sees a reusable view; it must copy/enqueue before returning true.
            int position=staging.position(),limit=staging.limit();
            boolean accepted;
            try{accepted=submit.apply(staging,staged,slots,time);}
            finally{staging.limit(limit);staging.position(position);}
            if(!accepted)break;
            for(int i=0;i<staged;i++) {
                int local=stagedLocals[i],index=heads[local];heads[local]=next[index];next[index]=freeHead;freeHead=index;queued--;
                if(heads[local]<0){tails[local]=-1;int at=readyIndex[local],last=ready[--readyCount];
                    ready[at]=last;readyIndex[last]=at;readyIndex[local]=-1;}
            }
            consumed+=staged;staged=0;
        }
        return consumed;
    }
    public int queued(){thread();return queued;}
    private void thread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native inbox off client/engine thread");}
}
