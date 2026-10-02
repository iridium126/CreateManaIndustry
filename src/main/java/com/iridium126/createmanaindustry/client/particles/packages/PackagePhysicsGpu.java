package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/**
 * Render-thread package solver. Buffers are sidecar state, not another particle pool.
 * Caller owns GL state boundaries and the common particle-slot mapping. There are no readbacks here.
 * Runtime ownership, world coverage, native recovery and lifecycle admission are gated by PackageWorldRuntime.
 */
public final class PackagePhysicsGpu implements AutoCloseable {
    public static final int BODY_BYTES=64, CHAIN_BYTES=64, ITERATIONS=4;
    public static final float PREPARED=-2;
    public static final float RETIRED=-3;
    public static final float COLLISION_FROZEN=-4;
    private static final String[] NAMES={"predict","grid","solve","chain","history","predict_world","solve_world",
            "world_support","support_prepare","support_jump","support_apply","solve_support",
            "range_insert","range_scan","range_add","range_scatter","range_guard",
            "predict_range","predict_world_range","solve_range","solve_world_range","solve_support_range","world_support_range","range_budget",
            "grid_counted","linked_guard","predict_counted","predict_world_counted","solve_counted","solve_world_counted","solve_support_counted",
            "moving_prepare","moving_carry","moving_contacts","solve_moving","solve_moving_range","solve_moving_counted","chain_tracked","forces","forces_framed",
            "velocity_capture","dynamic_sweep","dynamic_sweep_range"};
    private static final String[] UNIFORMS={"uCount","uTableMask","uCellSize","uDt","uGravity","uDrag","uFriction","uChain",
            "uWorldReady","uWorldOriginSection","uWorldTableMask","uWorldSlotWords","uWorldShapeCapacity","uStaticBodies",
            "uRangeGrid","uCandidateBudget","uIndexLength","uScanTable","uMovingReady","uMovingSweep","uMovingFriction","uFirst","uLength","uTrackCount","uForceNodes","uForceSources"};
    private final int[] programs=new int[NAMES.length], states=new int[2];
    private final int[][] locations=new int[NAMES.length][UNIFORMS.length];
    private int heads, links, chains, history, count, current;
    private final int[] supports=new int[2];
    private int supportControl;
    private int rangeHeads,rangeBodies,bodySlots;
    private int[] rangeLengths,rangeSums,rangeOffsets;
    private boolean rangeGrid;
    private boolean boundedGrid;
    private int countedHeads;
    private int movingSupport;
    private int stepVelocity;
    private final java.util.IdentityHashMap<PackageMovingCollisionCache.Entry,long[]> movingFrames=new java.util.IdentityHashMap<>();
    public enum IndexMode { LINKED, EXACT_RANGES, BOUNDED_LINKED }
    /** Conservative total candidates across all queried cells; exceeding it requests local handback. */
    public static final int CANDIDATE_BUDGET=512;
    private static final int DYNAMIC_SWEEP_BUDGET=8192;
    private final int capacity, tableSize,rangeTableSize;
    private final byte[] staticMask;
    private final boolean[] preparedMask;
    private int staticCount;
    private final float cellSize;
    private boolean closed;
    private boolean staticBodies;

    public PackagePhysicsGpu(int capacity, float cellSize, Function<String,String> sources) {
        if(capacity<=0 || capacity>1_048_576 || !(cellSize>0) || !Float.isFinite(cellSize))
            throw new IllegalArgumentException("Invalid package physics capacity/cell size");
        this.capacity=capacity;this.cellSize=cellSize;
        staticMask=new byte[capacity];
        preparedMask=new boolean[capacity];
        tableSize=Integer.highestOneBit(Math.max(64,capacity-1))<<1;
        rangeTableSize=tableSize*2;
        if((rangeTableSize+63)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
                || (long)capacity*BODY_BYTES>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
            throw new IllegalArgumentException("Package buffers exceed device limits");
        try {
            for(int i=0;i<programs.length;i++) {
                programs[i]=compile(sources.apply("packages/"+NAMES[i]+".comp"));
                for(int j=0;j<UNIFORMS.length;j++)locations[i][j]=GL20.glGetUniformLocation(programs[i],UNIFORMS[j]);
                GL41.glProgramUniform1ui(programs[i],locations[i][1],((i>=12 && i<=23)||i==35?rangeTableSize:tableSize)-1);
                GL41.glProgramUniform1f(programs[i],locations[i][2],cellSize);
                GL41.glProgramUniform1ui(programs[i],locations[i][15],CANDIDATE_BUDGET);
            }
            GL41.glProgramUniform1ui(programs[41],locations[41][15],DYNAMIC_SWEEP_BUDGET);
            GL41.glProgramUniform1ui(programs[42],locations[42][15],DYNAMIC_SWEEP_BUDGET);
            states[0]=buffer((long)capacity*BODY_BYTES);states[1]=buffer((long)capacity*BODY_BYTES);
            heads=buffer((long)tableSize*4);links=buffer((long)capacity*4);chains=buffer((long)capacity*CHAIN_BYTES);
            history=buffer((long)capacity*32);stepVelocity=buffer((long)capacity*16);
        } catch(RuntimeException failure) { close();throw failure; }
    }
    private static int buffer(long bytes) {
        int id=GL15.glGenBuffers();if(id==0)throw new IllegalStateException("Package buffer allocation failed");
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)
                throw new IllegalStateException("Package buffer storage allocation failed: "+bytes);
            return id;
        } catch(RuntimeException failure){GL15.glDeleteBuffers(id);throw failure;}
    }
    private static int compile(String source) {
        if(source==null || source.isBlank())throw new IllegalArgumentException("Missing package shader");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER), program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            return program;
        } catch(RuntimeException failure) { if(program!=0)GL20.glDeleteProgram(program);throw failure; }
        finally { GL20.glDeleteShader(shader); }
    }
    public void upload(ByteBuffer bodies,int count) {
        ensureOpen();
        if(count<0 || count>capacity || bodies.remaining()!=count*BODY_BYTES || !bodies.isDirect())
            throw new IllegalArgumentException("Invalid package body upload");
        validateBodies(bodies,count);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);this.count=count;
        staticCount=0;trackStatic(bodies,0,count,false);
        clearMovingSupport();
        movingFrames.clear();
        captureHistory(0);
    }
    private void validateBodies(ByteBuffer bodies,int count) {
        ByteBuffer view=bodies.duplicate().order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<count;i++) {
            int p=view.position()+i*BODY_BYTES;
            for(int j=0;j<16;j++)if(!Float.isFinite(view.getFloat(p+j*4)))
                throw new IllegalArgumentException("Non-finite body");
            for(int j=0;j<3;j++) {
                float extent=view.getFloat(p+32+j*4);
                if(!(extent>0 && extent<=cellSize*.5f))throw new IllegalArgumentException("Collider needs subdivision");
                if(Math.abs(view.getFloat(p+j*4)/cellSize)>100_000_000)
                    throw new IllegalArgumentException("Body requires a local region origin");
            }
            if(view.getFloat(p+12)<0)throw new IllegalArgumentException("Negative inverse mass");
        }
    }
    private void trackStatic(ByteBuffer bodies,int first,int length,boolean replacing) {
        ByteBuffer view=bodies.duplicate().order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<length;i++) {
            int index=first+i;
            if(replacing)staticCount-=staticMask[index];
            byte next=(byte)(view.getFloat(view.position()+i*BODY_BYTES+12)==0?1:0);
            staticMask[index]=next;staticCount+=next;
            preparedMask[index]=view.getFloat(view.position()+i*BODY_BYTES+60)==PREPARED;
        }
        staticBodies=staticCount>0;
    }
    public void uploadChains(ByteBuffer data) {
        ensureOpen();
        if(!data.isDirect() || data.remaining()!=count*CHAIN_BYTES)throw new IllegalArgumentException("Chain layout");
        validateChains(data,count);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,chains);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
        captureHistory(2);
    }
    private static void validateChains(ByteBuffer data,int count) {
        ByteBuffer view=data.duplicate().order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<count;i++) {
            int p=view.position()+i*CHAIN_BYTES;
            for(int j=0;j<16;j++)if(!Float.isFinite(view.getFloat(p+j*4)))
                throw new IllegalArgumentException("Non-finite chain");
            if(view.getFloat(p+12)<0 || view.getFloat(p+28)<0)
                throw new IllegalArgumentException("Negative chain radius/length");
        }
    }
    /** Appends bodies without reuploading or resetting the previous simulation and interpolation history.
     * A batch is homogeneous: chain records initialize target positions only for chain batches. */
    public void append(ByteBuffer bodies,ByteBuffer chainData,int added,boolean chainBatch) {
        ensureOpen();
        if(added<0 || added>capacity-count || !bodies.isDirect() || !chainData.isDirect()
                || bodies.remaining()!=added*BODY_BYTES || chainData.remaining()!=added*CHAIN_BYTES)
            throw new IllegalArgumentException("Package physics append layout/capacity");
        validateBodies(bodies,added);
        validateChains(chainData,added);
        if(added==0)return;
        int first=count;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*BODY_BYTES,bodies);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,chains);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*CHAIN_BYTES,chainData);
        // Publish the new range only after both buffers have been populated. Old history survives.
        count+=added;trackStatic(bodies,first,added,false);
        captureHistory(chainBatch?2:0,first,added);
    }
    /** Rebase a final server checkpoint without resetting unrelated bodies or their interpolation. */
    public void replace(int first,ByteBuffer bodies,ByteBuffer chainData,int length,boolean chainBatch) {
        ensureOpen();
        if(first<0 || length<0 || (long)first+length>count || !bodies.isDirect() || !chainData.isDirect()
                || bodies.remaining()!=length*BODY_BYTES || chainData.remaining()!=length*CHAIN_BYTES)
            throw new IllegalArgumentException("Package physics replacement range");
        validateBodies(bodies,length);
        validateChains(chainData,length);
        if(length==0)return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*BODY_BYTES,bodies);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,chains);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*CHAIN_BYTES,chainData);
        if(movingSupport!=0) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,movingSupport);
            try(var stack=MemoryStack.stackPush()) {
                GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,
                        (long)first*16,(long)length*16,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
            }
        }
        trackStatic(bodies,first,length,true);
        captureHistory(chainBatch?2:0,first,length);
    }
    /** Unfreeze only a prepared checkpoint after its matching ACTIVE/admission confirmation.
     * The next publication exposes this change; unrelated bodies and history are untouched. */
    public void activatePrepared(int body) {
        ensureOpen();
        if(body<0 || body>=count || !preparedMask[body])
            throw new IllegalArgumentException("Package body is not prepared");
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        try(var stack=MemoryStack.stackPush()) {
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*BODY_BYTES+60,stack.floats(0));
        }
        preparedMask[body]=false;
    }
    /** Retire without moving another body's stable index. Retired bodies never enter the contact grid. */
    public void retire(int body) {
        ensureOpen();if(body<0 || body>=count)throw new IllegalArgumentException("Package retirement range");
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        try(var stack=MemoryStack.stackPush()) {
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*BODY_BYTES+60,stack.floats(RETIRED));
        }
        preparedMask[body]=false;
        staticCount-=staticMask[body];staticMask[body]=0;staticBodies=staticCount>0;
    }
    /** Swept world collision plus Jacobi contacts; missing world data freezes a body for retry. */
    public void step(float dt) {
        step(dt,null,false,ITERATIONS);
    }
    /** Free domain only. In-place per-body velocities; the subsequent step handles sweeps/contacts.
     * No pose readback, float atomics, particle ABI changes, or work for an empty force scene. */
    public void applyForces(PackageForceGpu.View forces,float dt) {
        ensureStep(dt);java.util.Objects.requireNonNull(forces);if(count==0||forces.nodes()==0)return;
        int program=forces.frames()==0?38:39;
        bind(program);f(program,3,dt);forces.bind(locations[program][24],locations[program][25]);dispatch();
    }
    /** World view spans all caller substeps; missing coverage marks local handback without moving that body. */
    public void stepWorld(float dt,PackageCollisionGpu.View world){stepWorld(dt,world,false,ITERATIONS);}
    /** Explicit support-projection path. The legacy overload remains Jacobi-only. */
    public void stepWorld(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations) {
        if(iterations<1 || iterations>64)throw new IllegalArgumentException("Contact iterations");
        step(dt,java.util.Objects.requireNonNull(world),supportProjection,iterations);
    }
    /** Exact-cell ranges and bounded queries are experimental, never selected by production callers. */
    public void stepWorld(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations,boolean rangedIndex) {
        stepWorld(dt,world,supportProjection,iterations,rangedIndex?IndexMode.EXACT_RANGES:IndexMode.LINKED);
    }
    public void stepWorld(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations,IndexMode indexMode) {
        if(iterations<1 || iterations>64)throw new IllegalArgumentException("Contact iterations");
        step(dt,java.util.Objects.requireNonNull(world),supportProjection,iterations,java.util.Objects.requireNonNull(indexMode));
    }
    /** Kinematic scene path. One previous/current pose pair spans exactly one 20 Hz step.
     * Caller closes the scene after submission; package runtime admission owns readiness gating. */
    public void stepWorldMoving(PackageCollisionGpu.View world,int iterations,IndexMode mode,
                                java.util.List<PackageMovingCollisionGpu.View> moving) {
        if(iterations<1||iterations>64)throw new IllegalArgumentException("Contact iterations");
        step(.05f,java.util.Objects.requireNonNull(world),true,iterations,java.util.Objects.requireNonNull(mode),java.util.List.copyOf(moving));
    }
    private void step(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations) {
        step(dt,world,supportProjection,iterations,IndexMode.LINKED);
    }
    private void step(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations,IndexMode indexMode) {
        step(dt,world,supportProjection,iterations,indexMode,java.util.List.of());
    }
    private void step(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations,IndexMode indexMode,
                      java.util.List<PackageMovingCollisionGpu.View> moving) {
        ensureStep(dt);if(count==0)return;
        // Allocate the optional workspace before touching the submitted generation.
        if(supportProjection)ensureSupportBuffers();
        if(!moving.isEmpty()&&movingSupport==0){movingSupport=buffer((long)capacity*16);clearMovingSupport();}
        movingFrames.keySet().removeIf(entry->entry.poseFrame==0);
        for(var view:moving)view.claimStep(this);
        if(moving.isEmpty())clearMovingSupport();
        prepareIndex(indexMode);
        int predict=rangeGrid?(world==null?17:18):(boundedGrid?(world==null?26:27):(world==null?0:5));
        int solve=rangeGrid?(world==null?19:(supportProjection?21:20)):
                (boundedGrid?(world==null?28:(supportProjection?30:29)):(world==null?2:(supportProjection?11:6)));
        if(!moving.isEmpty())solve=rangeGrid?35:(boundedGrid?36:34);
        captureStepVelocity();captureHistory(3);
        for(var view:moving) {
            bind(31);view.bind(locations[31][18]);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            bind(32);view.bind(locations[32][18]);world.bind(locations[32],8,true);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,11,movingSupport);dispatch();current^=1;
        }
        if(world==null || staticBodies)buildGrid();
        bind(predict);f(predict,3,dt);f(predict,4,32f);
        f(predict,5,(float)Math.pow(.98,dt*20));
        if(world!=null){world.bind(locations[predict],8,true);GL20.glUniform1i(locations[predict][13],staticBodies?1:0);}
        dispatch();current^=1;
        // The solver grid is built from this predicted endpoint generation. The CCD
        // query walks a bounded swept neighborhood and filters exact endpoint cells.
        buildGrid(false);dynamicSweep();
        movingContacts(world,moving,true,false);
        for(int iteration=0;iteration<iterations;iteration++) {
            buildGrid(!boundedGrid);
            bind(solve);f(solve,6,(float)Math.pow(.6,dt*20/iterations));
            if(world!=null && iteration==0){world.bind(locations[solve],8,false);f(solve,3,dt);}
            if(!moving.isEmpty())GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,11,movingSupport);
            dispatch();current^=1;
        }
        // Dynamic Jacobi contacts can press a root back into the platform. The
        // support forest must start from the corrected kinematic root height.
        movingContacts(world,moving,false,false);
        if(supportProjection)projectSupports(world,dt);
        movingContacts(world,moving,false,true);
    }
    private void movingContacts(PackageCollisionGpu.View world,java.util.List<PackageMovingCollisionGpu.View> moving,boolean sweep,boolean friction) {
        for(var view:moving) {
            bind(33);view.bind(locations[33][18]);world.bind(locations[33],8,true);GL20.glUniform1i(locations[33][19],sweep?1:0);
            GL20.glUniform1i(locations[33][20],friction?1:0);
            try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,11,stack.ints(movingSupport,history));}
            dispatch();current^=1;
        }
    }
    private void clearMovingSupport() {
        if(movingSupport==0)return;GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,movingSupport);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
    }
    private void captureStepVelocity() {
        bind(40);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stepVelocity);dispatch();
    }
    private void dynamicSweep() {
        int program=rangeGrid?42:41;bind(program);
        if(rangeGrid)GL30.glUniform1ui(locations[program][1],rangeTableSize-1);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stepVelocity);dispatch();current^=1;
    }
    void claimMovingFrame(PackageMovingCollisionCache.Entry entry,long frame) {
        long[] previous=movingFrames.get(entry);
        if(previous!=null&&frame<=previous[0])throw new IllegalStateException("Moving tick pose already simulated");
        if(previous==null)movingFrames.put(entry,new long[]{frame});else previous[0]=frame;
    }
    private void ensureRangeBuffers() {
        if(rangeHeads!=0)return;
        java.util.ArrayList<Integer> lengths=new java.util.ArrayList<>();
        for(int length=(rangeTableSize+127)/128;;length=(length+127)/128) {
            lengths.add(length);if(length==1)break;
        }
        rangeLengths=lengths.stream().mapToInt(Integer::intValue).toArray();
        rangeSums=new int[rangeLengths.length];rangeOffsets=new int[rangeLengths.length-1];
        try {
            rangeHeads=buffer((long)rangeTableSize*16);rangeBodies=buffer((long)capacity*4);bodySlots=buffer((long)capacity*4);
            for(int i=0;i<rangeSums.length;i++)rangeSums[i]=buffer((long)rangeLengths[i]*4);
            for(int i=0;i<rangeOffsets.length;i++)rangeOffsets[i]=buffer((long)rangeLengths[i]*4);
        } catch(RuntimeException failure){deleteRangeBuffers();throw failure;}
    }
    private void deleteRangeBuffers() {
        if(rangeHeads!=0)GL15.glDeleteBuffers(rangeHeads);if(rangeBodies!=0)GL15.glDeleteBuffers(rangeBodies);
        if(bodySlots!=0)GL15.glDeleteBuffers(bodySlots);rangeHeads=rangeBodies=bodySlots=0;
        if(rangeSums!=null)for(int b:rangeSums)if(b!=0)GL15.glDeleteBuffers(b);
        if(rangeOffsets!=null)for(int b:rangeOffsets)if(b!=0)GL15.glDeleteBuffers(b);
        rangeSums=rangeOffsets=rangeLengths=null;
    }
    private void buildGrid() {
        buildGrid(true);
    }
    private void buildGrid(boolean guard) {
        if(boundedGrid) {
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,countedHeads);
            try(var stack=MemoryStack.stackPush()) {
                GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,(long)tableSize*4,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(-1));
                GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,(long)tableSize*4,(long)tableSize*4,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
            }
            bind(24);dispatch();if(guard){bind(25);dispatch();current^=1;}return;
        }
        if(!rangeGrid){clearHeads();bind(1);dispatch();return;}
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,rangeHeads);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,
                GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        bind(12);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,bodySlots);dispatch();
        scanRanges(rangeTableSize,true,bodySlots,bodySlots,rangeSums[0]);
        for(int i=0;i<rangeOffsets.length;i++)
            scanRanges(rangeLengths[i],false,rangeSums[i],rangeOffsets[i],rangeSums[i+1]);
        for(int i=rangeOffsets.length-2;i>=0;i--)addRangeOffsets(rangeLengths[i],false,rangeOffsets[i],rangeOffsets[i+1]);
        if(rangeOffsets.length>0)addRangeOffsets(rangeTableSize,true,bodySlots,rangeOffsets[0]);
        bind(15);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,bodySlots);dispatch();
        bind(23);GL43.glDispatchCompute((rangeTableSize+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        bind(16);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,bodySlots);dispatch();current^=1;
    }
    private void scanRanges(int length,boolean table,int input,int output,int sums) {
        bind(13);GL30.glUniform1ui(locations[13][16],length);GL20.glUniform1i(locations[13][17],table?1:0);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stack.ints(input,output,sums));}
        GL43.glDispatchCompute((length+127)/128,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    private void addRangeOffsets(int length,boolean table,int output,int parent) {
        bind(14);GL30.glUniform1ui(locations[14][16],length);GL20.glUniform1i(locations[14][17],table?1:0);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stack.ints(output,parent));}
        GL43.glDispatchCompute((length+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    private void ensureSupportBuffers() {
        if(supportControl==0) {
            try {
                supports[0]=buffer((long)capacity*16);supports[1]=buffer((long)capacity*16);supportControl=buffer(32);
            } catch(RuntimeException failure) {
                for(int i=0;i<supports.length;i++){if(supports[i]!=0)GL15.glDeleteBuffers(supports[i]);supports[i]=0;}
                if(supportControl!=0)GL15.glDeleteBuffers(supportControl);supportControl=0;
                throw failure;
            }
        }
    }
    private void projectSupports(PackageCollisionGpu.View world,float dt) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,supportControl);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        buildGrid();
        bind(rangeGrid?22:7);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stack.ints(supports[1],supports[0],supportControl));}
        dispatch();bind(8);GL43.glDispatchCompute(1,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,supportControl);
        int input=0;
        for(int span=1;span<count;span<<=1) {
            GL20.glUseProgram(programs[9]);GL30.glUniform1ui(locations[9][0],count);
            try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stack.ints(supports[input],supports[input^1]));}
            GL43.glDispatchComputeIndirect(16);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);input^=1;
        }
        bind(10);world.bind(locations[10],8,false);f(10,3,dt);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,supports[input]);
        GL43.glDispatchComputeIndirect(16);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public void stepChains(float dt) {
        ensureStep(dt);if(count==0)return;
        prepareIndex(IndexMode.LINKED);
        captureHistory(1);
        bind(3);f(3,3,dt);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,chains);
        dispatch();current^=1;
    }
    /** Shared, versioned track table and durable node candidates. One metadata row per chain body. */
    public void stepChains(float dt,PackageChainTrackGpu tracks) {
        ensureStep(dt);java.util.Objects.requireNonNull(tracks).validateStep(count);if(count==0)return;
        captureHistory(1);bind(37);f(37,3,dt);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,chains);tracks.bindStep(locations[37][23]);
        dispatch();current^=1;tracks.nodes(states[current],chains);
    }
    private void ensureOpen(){if(closed)throw new IllegalStateException("Package solver closed");}
    private void captureHistory(int chainMode) {
        captureHistory(chainMode,0,count);
    }
    private void captureHistory(int chainMode,int first,int length) {
        if(length==0)return;
        bind(4);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,chains);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,5,history);
        GL20.glUniform1i(locations[4][7],chainMode);
        GL30.glUniform1ui(locations[4][21],first);
        GL30.glUniform1ui(locations[4][22],length);
        GL43.glDispatchCompute((length+63)/64,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    private void ensureStep(float dt){ensureOpen();if(!(dt>0 && dt<=.05f))throw new IllegalArgumentException("Physics substep");}
    private void bind(int index) {
        GL20.glUseProgram(programs[index]);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(states[current],states[current^1],
                    rangeGrid?rangeHeads:(boundedGrid?countedHeads:heads),rangeGrid?rangeBodies:links));
        }
        GL30.glUniform1ui(locations[index][0],count);
    }
    private void prepareIndex(IndexMode mode) {
        if(mode==IndexMode.EXACT_RANGES)ensureRangeBuffers();
        if(mode==IndexMode.BOUNDED_LINKED && countedHeads==0)countedHeads=buffer((long)tableSize*8);
        rangeGrid=mode==IndexMode.EXACT_RANGES;boundedGrid=mode==IndexMode.BOUNDED_LINKED;
    }
    private void f(int index,int uniform,float value){GL20.glUniform1f(locations[index][uniform],value);}
    private void clearHeads() {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,heads);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(-1));
        }
    }
    private void dispatch(){GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    public int stateBuffer(){ensureOpen();return states[current];}
    public int chainBuffer(){ensureOpen();return chains;}
    public int historyBuffer(){ensureOpen();return history;}
    /** Diagnostic GPU-only counters: edges, corrected bodies, rejected bodies, input count. */
    public int supportStatsBuffer(){ensureOpen();return supportControl;}
    /** Validation only: exact-cell table (representative+1,count,start,count/overflow), grouped body indices. */
    public int rangeTableBuffer(){ensureOpen();return rangeHeads;}
    public int rangeBodyBuffer(){ensureOpen();return rangeBodies;}
    public int rangeTableSize(){return rangeTableSize;}
    /** Internal diagnostic/experiment entry. Does not advance time or rearrange body state. */
    public void rebuildRangeIndex(){rebuildIndex(IndexMode.EXACT_RANGES);}
    public void rebuildIndex(IndexMode mode){ensureOpen();prepareIndex(java.util.Objects.requireNonNull(mode));buildGrid();}
    public long indexWorkspaceBytes(IndexMode mode){return mode==IndexMode.EXACT_RANGES?rangeWorkspaceBytes():(mode==IndexMode.BOUNDED_LINKED?(long)tableSize*8:0);}
    public long rangeWorkspaceBytes(){
        long scanWords=0;
        for(int length=(rangeTableSize+127)/128;;length=(length+127)/128){scanWords+=length;if(length==1)break;}
        return (long)rangeTableSize*16+(long)capacity*8+scanWords*8-4;
    }
    public int count(){return count;}
    public int staticBodyCount(){ensureOpen();return staticCount;}
    @Override public void close() {
        if(closed)return;closed=true;
        for(int p:programs)if(p!=0)GL20.glDeleteProgram(p);
        for(int b:states)if(b!=0)GL15.glDeleteBuffers(b);
        if(heads!=0)GL15.glDeleteBuffers(heads);if(links!=0)GL15.glDeleteBuffers(links);if(chains!=0)GL15.glDeleteBuffers(chains);
        if(history!=0)GL15.glDeleteBuffers(history);
        for(int buffer:supports)if(buffer!=0)GL15.glDeleteBuffers(buffer);if(supportControl!=0)GL15.glDeleteBuffers(supportControl);
        deleteRangeBuffers();
         if(countedHeads!=0)GL15.glDeleteBuffers(countedHeads);
         if(movingSupport!=0)GL15.glDeleteBuffers(movingSupport);
         if(stepVelocity!=0)GL15.glDeleteBuffers(stepVelocity);
    }
}
