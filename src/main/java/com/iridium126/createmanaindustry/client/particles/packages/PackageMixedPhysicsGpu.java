package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * Independent authority domains and an optional observer domain with one body/index space. Chain bodies never
 * enter the free-body contact grid; publishing uses GPU buffer copies, not a CPU pose scan.
 * The fixed chain offset keeps body indices stable as either domain grows within its reservation.
 */
public final class PackageMixedPhysicsGpu implements AutoCloseable {
    private final PackagePhysicsGpu free,chain;
    private final PackageObserverGpu observers;
    private final int freeCapacity,chainCapacity,observerCapacity,totalCapacity,maxPackages;
    private final int[] bodies=new int[2],chains=new int[2],history=new int[2];
    private int published=-1;
    private int publishedBodyCount;
    private long version,publishedVersion=-1;
    private long freeStep;
    public long freeSimulationStep(){open();return freeStep;}
    private long freeVersion,chainVersion,publishedFreeVersion=-1,publishedChainVersion=-1;
    private long observerVersion=-1;
    private boolean closed;

    public PackageMixedPhysicsGpu(int freeCapacity,int chainCapacity,float cellSize,Function<String,String> sources) {
        this(freeCapacity,chainCapacity,0,cellSize,sources);
    }
    /** Observer reservation uses no extra generic particle slots and does not join either solver. */
    public PackageMixedPhysicsGpu(int freeCapacity,int chainCapacity,int observerCapacity,float cellSize,Function<String,String> sources) {
        this(freeCapacity,chainCapacity,observerCapacity,cellSize,sources,()->true);
    }
    /** Validation may hold completed upload banks, never waive a real GPU fence. */
    public PackageMixedPhysicsGpu(int freeCapacity,int chainCapacity,int observerCapacity,float cellSize,Function<String,String> sources,
                                  java.util.function.BooleanSupplier consumeObserverUploads) {
        if(freeCapacity<0 || chainCapacity<0 || observerCapacity<0 || freeCapacity>131072 || chainCapacity>131072 || observerCapacity>131072
                || (long)freeCapacity+chainCapacity+observerCapacity<1)
            throw new IllegalArgumentException("Mixed package capacity");
        this.freeCapacity=freeCapacity;this.chainCapacity=chainCapacity;this.observerCapacity=observerCapacity;
        totalCapacity=freeCapacity+chainCapacity+observerCapacity;
        maxPackages=Math.min(131072,totalCapacity);
        PackagePhysicsGpu nextFree=null,nextChain=null;
        PackageObserverGpu nextObservers=null;
        try {
            nextFree=new PackagePhysicsGpu(Math.max(1,freeCapacity),cellSize,sources);
            nextChain=new PackagePhysicsGpu(Math.max(1,chainCapacity),cellSize,sources);
            if(observerCapacity>0)nextObservers=new PackageObserverGpu(observerCapacity,sources,consumeObserverUploads);
            for(int bank=0;bank<2;bank++) {
                bodies[bank]=buffer((long)totalCapacity*PackagePhysicsGpu.BODY_BYTES);
                chains[bank]=buffer((long)totalCapacity*PackagePhysicsGpu.CHAIN_BYTES);
                history[bank]=buffer((long)totalCapacity*32);
            }
        }catch(RuntimeException failure) {
            if(nextFree!=null)nextFree.close();if(nextChain!=null)nextChain.close();if(nextObservers!=null)nextObservers.close();
            deleteBuffers();throw failure;
        }
        free=nextFree;chain=nextChain;observers=nextObservers;
    }
    private static int buffer(long bytes) {
        if(bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
            throw new IllegalArgumentException("Mixed package buffer exceeds device limit");
        int id=GL15.glGenBuffers();if(id==0)throw new IllegalStateException("Mixed package buffer allocation failed");
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
            if(org.lwjgl.opengl.GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)
                throw new IllegalStateException("Mixed package buffer storage allocation failed");
            return id;
        }catch(RuntimeException failure){GL15.glDeleteBuffers(id);throw failure;}
    }
    public void uploadFree(ByteBuffer data,int count) {
        open();within(count,Math.min(freeCapacity,maxPackages-chain.count()-observerCount()));free.upload(data,count);changed(false);
    }
    public void appendFree(ByteBuffer bodies,ByteBuffer emptyChains,int added) {
        open();within(added,Math.min(freeCapacity-free.count(),maxPackages-free.count()-chain.count()-observerCount()));
        free.append(bodies,emptyChains,added,false);if(added>0)changed(false);
    }
    public int nextChainBody(){open();return chain.nextBody();}
    public void recycleChain(int local){open();chain.makeReusable(local);changed(true);}
    public void writeChain(int local,ByteBuffer bodies,ByteBuffer data){open();chain.writeBody(local,bodies,data,true);changed(true);}
    public int nextFreeBody(){open();return free.nextBody();}
    public int freeLiveCount(){open();return free.liveCount();}
    public int chainLiveCount(){open();return chain.liveCount();}
    public void recycleFree(int local){open();free.makeReusable(local);changed(false);}
    public void writeFree(int local,ByteBuffer bodies,ByteBuffer chains){open();free.writeBody(local,bodies,chains,false);changed(false);}
    public void replaceFree(int first,ByteBuffer bodies,ByteBuffer emptyChains,int length) {
        open();if(first<0 || (long)first+length>free.count())throw new IllegalArgumentException("Free package replacement range");
        free.replace(first,bodies,emptyChains,length,false);if(length>0)changed(false);
    }
    public void uploadChains(ByteBuffer bodies,ByteBuffer chainData,int count) {
        open();within(count,Math.min(chainCapacity,maxPackages-free.count()-observerCount()));
        chain.upload(bodies,count);chain.uploadChains(chainData);changed(true);
    }
    public void appendChains(ByteBuffer bodies,ByteBuffer chainData,int added) {
        open();within(added,Math.min(chainCapacity-chain.count(),maxPackages-free.count()-chain.count()-observerCount()));
        chain.append(bodies,chainData,added,true);if(added>0)changed(true);
    }
    public void replaceChains(int first,ByteBuffer bodies,ByteBuffer chainData,int length) {
        open();if(first<0 || (long)first+length>chain.count())throw new IllegalArgumentException("Chain package replacement range");
        chain.replace(first,bodies,chainData,length,true);if(length>0)changed(true);
    }
    private void changed(boolean chain){version++;if(chain)chainVersion++;else freeVersion++;}
    public void activatePreparedFree(int local){open();free.activatePrepared(local);changed(false);}
    public void activatePreparedChain(int local){open();chain.activatePrepared(local);changed(true);}
    public void retireFree(int local){open();free.retire(local);changed(false);}
    public void retireChain(int local){open();chain.retire(local);changed(true);}
    private static void within(int count,int remaining) {
        if(count<0 || count>remaining)throw new IllegalArgumentException("Mixed package domain capacity");
    }
    public void stepFree(float dt) {open();free.step(dt);freeStep++;if(free.count()>0)changed(false);}
    public void applyFreeForces(PackageForceGpu.View forces,float dt){open();free.applyForces(forces,dt);if(free.count()>0&&forces.nodes()>0)changed(false);}
    public void stepFreeWorld(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations) {
        open();free.stepWorld(dt,world,supportProjection,iterations);freeStep++;if(free.count()>0)changed(false);
    }
    public void stepFreeMoving(PackageCollisionGpu.View world,int iterations,
                               List<PackageMovingCollisionGpu.View> moving) {
        open();free.stepWorldMoving(world,iterations,moving);freeStep++;if(free.count()>0)changed(false);
    }
    public void stepChains(float dt) {open();chain.stepChains(dt);if(chain.count()>0)changed(true);}
    public void stepChains(float dt,PackageChainTrackGpu tracks) {
        open();chain.stepChains(dt,tracks);if(chain.count()>0)changed(true);
    }
    public PackageObserverGpu observers(){open();if(observers==null)throw new IllegalStateException("No observer domain reserved");return observers;}
    public int observerLiveCount(){open();return observerReservations<0?observerCount():observerReservations;}
    public int observerCount(){open();return observers==null?0:observers.count();}
    private int observerReservations=-1;
    public void observerReservations(int count){open();if(count<0||count>observerCapacity)throw new IllegalArgumentException("Observer reservations");observerReservations=count;}
    public int observerRemaining(){open();int reserved=observerReservations<0?observerCount():observerReservations;return Math.min(observerCapacity-reserved,maxPackages-free.liveCount()-chain.liveCount()-reserved);}
    public int observerBodyIndex(int local){open();if(local<0 || local>=observerCount())throw new IndexOutOfBoundsException();return freeCapacity+chainCapacity+local;}
    public void sampleObservers(float time) {
        open();if(observers==null)throw new IllegalStateException("No observer domain reserved");
        if(observerRemaining()<0)throw new IllegalStateException("Observer exceeds shared package reservation");
        observers.sample(time);long next=observers.publicationVersion();
        if(observerVersion!=next){observerVersion=next;version++;}
    }

    /** Call only after both domain steps succeed; the visible bank flips after copies are enqueued. */
    public void publish() {
        open();
        if(observerCount()>0) {
            // Refuse an un-sampled new baseline instead of mixing a new count with old bodies.
            long next=observers.publicationVersion();
            if(next!=observerVersion){observerVersion=next;version++;}
            if(observerRemaining()<0)throw new IllegalStateException("Observer exceeds shared package reservation");
        }
        if(published>=0 && publishedVersion==version)return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        int next=published<0?0:published^1;
        copy(free.stateBuffer(),bodies[next],0,free.count(),PackagePhysicsGpu.BODY_BYTES);
        copy(free.historyBuffer(),history[next],0,free.count(),32);
        long chainOffset=freeCapacity;
        copy(chain.stateBuffer(),bodies[next],chainOffset,chain.count(),PackagePhysicsGpu.BODY_BYTES);
        copy(chain.chainBuffer(),chains[next],chainOffset,chain.count(),PackagePhysicsGpu.CHAIN_BYTES);
        copy(chain.historyBuffer(),history[next],chainOffset,chain.count(),32);
        if(observerCount()>0) {
            long offset=(long)freeCapacity+chainCapacity;
            copy(observers.bodyBuffer(),bodies[next],offset,observerCount(),PackageObserverGpu.BODY_BYTES);
            copy(observers.historyBuffer(),history[next],offset,observerCount(),PackageObserverGpu.HISTORY_BYTES);
        }
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        published=next;publishedVersion=version;publishedFreeVersion=freeVersion;publishedChainVersion=chainVersion;publishedBodyCount=bodyCount();
    }
    private static void copy(int source,int target,long first,int count,int stride) {
        if(count==0)return;
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,target);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,first*stride,(long)count*stride);
    }
    public int freeBodyIndex(int local) {open();if(local<0 || local>=free.count())throw new IndexOutOfBoundsException();return local;}
    public int chainBodyIndex(int local) {open();if(local<0 || local>=chain.count())throw new IndexOutOfBoundsException();return freeCapacity+local;}
    public int bodyCount(){open();return observerCount()>0?freeCapacity+chainCapacity+observerCount():chain.count()>0?freeCapacity+chain.count():free.count();}
    public int freeCount(){open();return free.count();}
    /** Current render-thread solver state for the asynchronous collision prefetch pass. */
    public int freeStateBuffer(){open();return free.stateBuffer();}
    public PackageEnvironmentGpu enableEnvironment(Function<String,String> sources){open();return free.enableEnvironment(sources);}
    public PackageEnvironmentGpu environment(){open();return free.environment();}
    public int freeCapacity(){open();return freeCapacity;}
    public int chainCapacity(){open();return chainCapacity;}
    public int chainCount(){open();return chain.count();}
    public int bodyBuffer(){ready();return bodies[published];}
    public int chainBuffer(){ready();return chains[published];}
    public int historyBuffer(){ready();return history[published];}
    public long publicationVersion(){ready();return publishedVersion;}
    public long freePublicationVersion(){ready();return publishedFreeVersion;}
    public long chainPublicationVersion(){ready();return publishedChainVersion;}
    public void source(PackagePoolGpu pool,float x,float y,float z) {
        ready();pool.source(bodyBuffer(),chainBuffer(),historyBuffer(),publishedBodyCount,x,y,z);
    }
    private void open(){if(closed)throw new IllegalStateException("Mixed package solver closed");}
    private void ready(){open();if(published<0)throw new IllegalStateException("Mixed package solver has no published state");}
    private void deleteBuffers(){
        for(int bank=0;bank<2;bank++) {
            if(bodies[bank]!=0)GL15.glDeleteBuffers(bodies[bank]);
            if(chains[bank]!=0)GL15.glDeleteBuffers(chains[bank]);
            if(history[bank]!=0)GL15.glDeleteBuffers(history[bank]);
            bodies[bank]=chains[bank]=history[bank]=0;
        }
    }
    @Override public void close() {
        if(closed)return;closed=true;free.close();chain.close();if(observers!=null)observers.close();deleteBuffers();
    }
}
