package com.iridium126.createmanaindustry.client.packages.simulation;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;


import com.iridium126.createmanaindustry.client.packages.world.PackageCollisionGpu;
import com.iridium126.createmanaindustry.client.packages.world.PackageMovingCollisionCache;
import com.iridium126.createmanaindustry.client.packages.world.PackageMovingCollisionGpu;
import com.iridium126.createmanaindustry.client.packages.world.PackageWorldRuntime;

import com.iridium126.createmanaindustry.client.packages.feedback.PackageCpuDiagnostics;
import com.iridium126.createmanaindustry.client.packages.feedback.PackageEnvironmentGpu;
import com.iridium126.createmanaindustry.client.packages.feedback.PackageFreezeDiagnosticsGpu;

import com.iridium126.createmanaindustry.client.gpu.GpuCapabilities;

import java.nio.ByteBuffer;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/**
 * Render-thread package solver. Buffers are sidecar state, not another particle pool.
 * Caller owns GL state boundaries and the common particle-slot mapping. Optional diagnostics use fenced snapshots.
 * Runtime ownership, world coverage, durable pause and lifecycle admission are gated by PackageWorldRuntime.
 */
public final class PackagePhysicsGpu implements AutoCloseable {
    public static final int BODY_BYTES=64, CHAIN_BYTES=64, ITERATIONS=4;
    public static final float PREPARED=-2;
    public static final float RETIRED=-3;
    public static final float COLLISION_FROZEN=-4;
    private static final String[] NAMES={"predict","grid","solve","chain","history","predict_world","solve_world","world_support","support_prepare","support_jump","support_apply","solve_support","moving_prepare","moving_carry","moving_contacts","solve_moving","chain_tracked","forces","forces_framed","dynamic_sweep","fast_sweep_cells","resume","sleep_prepare","sleep_observe","moving_candidates","moving_candidates_prepare"};
    private static final String[] UNIFORMS={"uCount","uTableMask","uCellSize","uDt","uGravity","uDrag","uFriction","uChain",
            "uWorldReady","uWorldOriginSection","uWorldTableMask","uWorldSlotWords","uWorldShapeCapacity","uStaticBodies",
            null,"uCandidateBudget",null,null,"uMovingReady","uMovingSweep","uMovingFriction","uFirst","uLength","uTrackCount","uForceNodes","uForceSources","uEnvironmentReady","uCheckWorld","uFreezeDiagnostics","uFreezeStep","uSupportContactConstraints","uActiveCount","uActiveOffset","uWake","uCandidateOffset"};
    private final int[] programs=new int[NAMES.length], states=new int[2];
    private final int[][] locations=new int[NAMES.length][UNIFORMS.length];
    private final int[][] uploadedFloatBits=new int[NAMES.length][UNIFORMS.length];
    private final boolean[][] uploadedFloat=new boolean[NAMES.length][UNIFORMS.length];
    private final boolean[] boundWorldUniforms=new boolean[NAMES.length];
    private final boolean[] environmentStorage=new boolean[NAMES.length];
    private boolean stepBindings,environmentBound;private int boundState=-1,boundProgram,boundIndirect=-1;
    private final int[] uploadedActiveCounts=new int[NAMES.length];
    private final ByteBuffer activeUpload;
    private boolean activeDirty=true,sleepWake=true,sleepMode;
    private final boolean allowSleep;
    private boolean sleepForceInput,lastSleepEligible;
    private int sleepControl;
    private long sleepWorldVersion=Long.MIN_VALUE;
    /** External pose/velocity writers must invalidate the rest proof; lifecycle stays CPU-owned. */
    public void externalGpuMutation(){ensureOpen();sleepWake=true;sleepForceInput=true;}
    public int activityStatsBuffer(){ensureOpen();return sleepControl;}
    public int movingCandidateStatsBuffer(){ensureOpen();return links;}
    public long movingCandidateStatsOffset(){ensureOpen();return (long)capacity*12;}
    private final int[] uploadedCounts=new int[NAMES.length];
    private final int[] uploadedTableMasks=new int[NAMES.length];
    private static final int MIN_INDEX_BUCKETS=65536, INDEX_BUCKETS_PER_BODY=16;
    private int indexSize;
    private int heads, links, chains, history, count, current;
    private final int[] supports=new int[2];
    private int supportControl,supportContactInput;
    private int movingSupport;
    private int fastCells,longBodies;
    private PackageEnvironmentGpu environment;
    private PackageFreezeDiagnosticsGpu freezeDiagnostics;
    public PackageEnvironmentGpu enableEnvironment(Function<String,String> sources){return enableEnvironment(sources,capacity);}
    PackageEnvironmentGpu enableEnvironment(Function<String,String> sources,int historyPages){ensureOpen();if(environment==null){
        environment=new PackageEnvironmentGpu(capacity,historyPages,sources);environment.motionMirror(links,capacity*3+8);environment.freezeDiagnostics(freezeDiagnostics);
        for(int i=0;i<programs.length;i++)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(programs[i],locations[i][26],1);
    }return environment;}
    public PackageEnvironmentGpu environment(){return environment;}
    /** World-restart NeoForge rule; upload once rather than query configuration per body/tick. */
    public void climbingBounds(boolean fullBounds){
        ensureOpen();for(int program:programs)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,GL20.glGetUniformLocation(program,"uFullBoundingBoxLadders"),fullBounds?1:0);
        if(environment!=null)environment.climbingBounds(fullBounds);
    }
    public PackageFreezeDiagnosticsGpu enableFreezeDiagnostics(Function<String,String> sources){ensureOpen();if(freezeDiagnostics==null){freezeDiagnostics=new PackageFreezeDiagnosticsGpu(capacity,sources);if(environment!=null)environment.freezeDiagnostics(freezeDiagnostics);}return freezeDiagnostics;}
    public void disableFreezeDiagnostics(){if(environment!=null)environment.freezeDiagnostics(null);if(freezeDiagnostics!=null)freezeDiagnostics.close();freezeDiagnostics=null;}
    private final java.util.Map<Integer,Long> movingFrames=new java.util.HashMap<>();
    /** Conservative cheap-query budget; exhaustion switches to a complete candidate scan. */
    public static final int CANDIDATE_BUDGET=8192;
    private static final int DYNAMIC_SWEEP_BUDGET=8192;
    private final int capacity, tableSize;
    private final byte[] staticMask;
    private final boolean[] preparedMask;
    private int staticCount,liveCount;
    private final java.util.BitSet liveBodies=new java.util.BitSet(),reusableBodies=new java.util.BitSet();
    public int liveCount(){return liveCount;}
    public int nextBody(){int vacant=reusableBodies.nextSetBit(0);return vacant>=0?vacant:count<capacity?count:-1;}
    public void makeReusable(int body){
        ensureOpen();if(body<0||body>=count||liveBodies.get(body)||reusableBodies.get(body))throw new IllegalArgumentException("Body recycle fence");
        if(movingSupport!=0){GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,movingSupport);try(var stack=MemoryStack.stackPush()){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,(long)body*16,16,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}}
        reusableBodies.set(body);
        while(count>0&&reusableBodies.get(count-1)){int tail=--count;reusableBodies.clear(tail);staticCount-=staticMask[tail];staticMask[tail]=0;preparedMask[tail]=false;}
        staticBodies=staticCount>0;
    }
    public void writeBody(int body,ByteBuffer data,ByteBuffer chain,boolean chainBatch){
        if(body==count){append(data,chain,1,chainBatch);return;}
        if(!reusableBodies.get(body))throw new IllegalArgumentException("Body replacement is not recyclable");
        replace(body,data,chain,1,chainBatch);reusableBodies.clear(body);
    }
    private final float cellSize;
    private boolean closed;
    private boolean staticBodies;

    public PackagePhysicsGpu(int capacity,float cellSize,Function<String,String> sources){this(capacity,cellSize,sources,true);}
    /** Validation can compare the exact awake path without changing any gameplay setting. */
    public PackagePhysicsGpu(int capacity,float cellSize,Function<String,String> sources,boolean allowSleep) {
        this.allowSleep=allowSleep;
        if(capacity<=0 || capacity>1_048_576 || !(cellSize>0) || !Float.isFinite(cellSize))
            throw new IllegalArgumentException("Invalid package physics capacity/cell size");
        this.capacity=capacity;this.cellSize=cellSize;activeUpload=org.lwjgl.BufferUtils.createByteBuffer(capacity*4);
        java.util.Arrays.fill(uploadedActiveCounts,-1);
        java.util.Arrays.fill(uploadedCounts,-1);
        java.util.Arrays.fill(uploadedTableMasks,-1);
        staticMask=new byte[capacity];
        preparedMask=new boolean[capacity];
        tableSize=Integer.highestOneBit(Math.max(64,capacity-1))<<1;
        indexSize=Math.min(tableSize,MIN_INDEX_BUCKETS);
        if((tableSize+63)/64>GpuCapabilities.current().computeGroupsX()
                || (long)capacity*BODY_BYTES>GpuCapabilities.current().storageBlockBytes())
            throw new IllegalArgumentException("Package buffers exceed device limits");
        try {
            boolean diagnosticSupported=PackageFreezeDiagnosticsGpu.supported();
            for(int i=0;i<programs.length;i++) {
                programs[i]=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("PackagePhysicsGpu", "#version 450 core\n"+"#define CMI_ACTIVE_BODIES\n"+((i==13||i==14)?"#define CMI_MOVING_CANDIDATES\n":"")+(diagnosticSupported?"#define CMI_FREEZE_DIAGNOSTICS\n":"")+sources.apply("packages/"+NAMES[i]+".comp"));
                environmentStorage[i]=referencesStorage(programs[i],"EnvironmentStates")||referencesStorage(programs[i],"EnvironmentSamples");
                for(int j=0;j<UNIFORMS.length;j++)locations[i][j]=UNIFORMS[j]==null?-1:GL20.glGetUniformLocation(programs[i],UNIFORMS[j]);
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[i],locations[i][1],tableSize-1);
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[i],locations[i][32],(i==13||i==14)?capacity*2:capacity);
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[i],locations[i][34],capacity*2);
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(programs[i],locations[i][2],cellSize);
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[i],locations[i][15],CANDIDATE_BUDGET);
            }
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[19],locations[19][15],DYNAMIC_SWEEP_BUDGET);
            states[0]=buffer((long)capacity*BODY_BYTES);states[1]=buffer((long)capacity*BODY_BYTES);
            heads=buffer((long)indexSize*4);links=buffer((long)capacity*16+32);chains=buffer((long)capacity*CHAIN_BYTES);
            history=buffer((long)capacity*32);fastCells=buffer((long)indexSize*32);
            longBodies=buffer((long)(capacity+1)*4);
        } catch(RuntimeException failure) { close();throw failure; }
    }
    private static int buffer(long bytes) {
        int id=GL15.glGenBuffers();if(id==0)throw new IllegalStateException("Package buffer allocation failed");
        try {
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,id);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)
                throw new IllegalStateException("Package buffer storage allocation failed: "+bytes);
            return id;
        } catch(RuntimeException failure){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);throw failure;}
    }
    private static boolean referencesStorage(int program,String name){
        int block=GL43.glGetProgramResourceIndex(program,GL43.GL_SHADER_STORAGE_BLOCK,name);if(block==GL43.GL_INVALID_INDEX)return false;
        try(var stack=MemoryStack.stackPush()){
            var value=stack.mallocInt(1);GL43.glGetProgramResourceiv(program,GL43.GL_SHADER_STORAGE_BLOCK,block,stack.ints(GL43.GL_REFERENCED_BY_COMPUTE_SHADER),null,value);
            return value.get(0)!=0;
        }
    }
    public void upload(ByteBuffer bodies,int count) {
        ensureOpen();
        if(count<0 || count>capacity || bodies.remaining()!=count*BODY_BYTES || !bodies.isDirect())
            throw new IllegalArgumentException("Invalid package body upload");
        validateBodies(bodies,count);
        if(freezeDiagnostics!=null)freezeDiagnostics.reset(0,capacity);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current^1]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);this.count=count;liveCount=0;liveBodies.clear();reusableBodies.clear();
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
            boolean alive=view.getFloat(view.position()+i*BODY_BYTES+60)!=RETIRED;
            if(alive&&!liveBodies.get(index)){liveBodies.set(index);liveCount++;}
            if(!alive&&liveBodies.get(index)){liveBodies.clear(index);liveCount--;}
            activeDirty=true;sleepWake=true;
            if(replacing)staticCount-=staticMask[index];
            byte next=(byte)(alive&&view.getFloat(view.position()+i*BODY_BYTES+12)==0?1:0);
            staticMask[index]=next;staticCount+=next;
            preparedMask[index]=view.getFloat(view.position()+i*BODY_BYTES+60)==PREPARED;
        }
        staticBodies=staticCount>0;
    }
    public void uploadChains(ByteBuffer data) {
        ensureOpen();
        if(!data.isDirect() || data.remaining()!=count*CHAIN_BYTES)throw new IllegalArgumentException("Chain layout");
        validateChains(data,count);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,chains);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
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
        if(freezeDiagnostics!=null)freezeDiagnostics.reset(first,added);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*BODY_BYTES,bodies);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current^1]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*BODY_BYTES,bodies);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,chains);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*CHAIN_BYTES,chainData);
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
        if(freezeDiagnostics!=null)freezeDiagnostics.reset(first,length);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*BODY_BYTES,bodies);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current^1]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*BODY_BYTES,bodies);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,chains);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)first*CHAIN_BYTES,chainData);
        if(movingSupport!=0) {
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,movingSupport);
            try(var stack=MemoryStack.stackPush()) {
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,
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
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        try(var stack=MemoryStack.stackPush()) {
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*BODY_BYTES+60,stack.floats(0));
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current^1]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*BODY_BYTES+60,stack.floats(0));
        }
        preparedMask[body]=false;sleepWake=true;
    }
    /** Retire without moving another body's stable index. Retired bodies never enter the contact grid. */
    public void retire(int body) {
        ensureOpen();if(body<0 || body>=count)throw new IllegalArgumentException("Package retirement range");
        if(freezeDiagnostics!=null)freezeDiagnostics.reset(body,1);
        if(liveBodies.get(body)){liveBodies.clear(body);liveCount--;activeDirty=true;sleepWake=true;}
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        try(var stack=MemoryStack.stackPush()) {
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*BODY_BYTES+60,stack.floats(RETIRED));
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states[current^1]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*BODY_BYTES+60,stack.floats(RETIRED));
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
        applyForces(forces,dt,null);
    }
    /** Static world view is shared with the following physics step and supplies nozzle LOS. */
    public void applyForces(PackageForceGpu.View forces,float dt,PackageCollisionGpu.View world) {
        ensureStep(dt);java.util.Objects.requireNonNull(forces);if(count==0||forces.nodes()==0)return;
        prepareActive();sleepWake=true;sleepForceInput=true;
        int program=forces.frames()==0?17:18;
        bind(program);f(program,3,dt);
        if(world==null)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[program][8],0);else bindWorld(world,program,true);
        forces.bind(locations[program][24],locations[program][25]);dispatch();
    }
    /** World view spans all caller substeps; missing coverage marks local pause without moving that body. */
    public void stepWorld(float dt,PackageCollisionGpu.View world){stepWorld(dt,world,false,ITERATIONS);}
    /** Explicit support-projection path. The legacy overload remains Jacobi-only. */
    public void stepWorld(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations) {
        if(iterations<1 || iterations>64)throw new IllegalArgumentException("Contact iterations");
        step(dt,java.util.Objects.requireNonNull(world),supportProjection,iterations);
    }
    /** One immutable previous/current moving pose pair per successful 20 Hz step. */
    public void stepWorldMoving(PackageCollisionGpu.View world,int iterations,java.util.List<PackageMovingCollisionGpu.View> moving){
        if(iterations<1||iterations>64)throw new IllegalArgumentException("Contact iterations");
        step(.05f,java.util.Objects.requireNonNull(world),true,iterations,java.util.List.copyOf(moving));
    }
    private void step(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations){step(dt,world,supportProjection,iterations,java.util.List.of());}
    private void step(float dt,PackageCollisionGpu.View world,boolean supportProjection,int iterations,java.util.List<PackageMovingCollisionGpu.View> moving){
        ensureStep(dt);if(count==0)return;
        prepareActive();stepBindings=true;environmentBound=false;boundState=-1;boundIndirect=-1;
        try {
        java.util.Arrays.fill(boundWorldUniforms,false);
        if(freezeDiagnostics!=null)freezeDiagnostics.nextStep();
        // Allocate the optional workspace before touching the submitted generation.
        if(supportProjection)ensureSupportBuffers();
        if(!moving.isEmpty()&&movingSupport==0){movingSupport=buffer((long)capacity*16);clearMovingSupport();}

        sleepMode=allowSleep&&world!=null&&supportProjection&&count>=65&&moving.isEmpty()&&!sleepForceInput;
        if(sleepMode){
            if(sleepControl==0){sleepControl=buffer(32);clearControl(sleepControl);}
            bind(22);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,6,sleepControl);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[22][33],sleepWake||!lastSleepEligible||!world.ready()||sleepWorldVersion!=world.version()?1:0);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);PackageCpuDiagnostics.dispatch();
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
            bindIndirect(sleepControl);sleepWorldVersion=world.version();
        }
        for(var view:moving)view.claimStep(this);
        if(moving.isEmpty())clearMovingSupport();
        int predict=world==null?0:5;
        int solve=world==null?2:(supportProjection?11:6);
        if(!moving.isEmpty())solve=15;
        bind(21);f(21,3,dt);f(21,4,32f);f(21,5,(float)Math.pow(.98,dt*20));
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[21][27],world==null?0:1);
        if(world!=null)bindWorld(world,21,true);
        dispatch();
        captureHistory(3);
        for(var view:moving) {
            bind(12);view.bind(locations[12][18]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);PackageCpuDiagnostics.dispatch();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            movingCandidates(view);bind(13);view.bind(locations[13][18]);bindWorld(world,13,true);
            GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,11,movingSupport);dispatch();
        }
        if(world==null || staticBodies)buildGrid();
        bind(predict);f(predict,3,dt);f(predict,4,32f);
        f(predict,5,(float)Math.pow(.98,dt*20));
        if(world!=null)bindWorld(world,predict,true);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[predict][13],staticBodies?1:0);
        dispatch();current^=1;
        // The solver grid is built from this predicted endpoint generation. The CCD
        // query walks a bounded swept neighborhood and filters exact endpoint cells.
        buildGrid();dynamicSweep();
        movingContacts(world,moving,true,false);
        int contactIterations=iterations;
        float contactFriction=(float)Math.pow(.6,dt*20/contactIterations);
        for(int iteration=0;iteration<contactIterations;iteration++) {
            buildGrid();
            bind(solve);f(solve,6,contactFriction);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[solve][30],0);
            if(supportProjection)GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,6,supports[0]);
            if(world!=null) {
                if(iteration==0){bindWorld(world,solve,false);f(solve,3,0);}
                // Contact iterations resolve constraints, not additional elapsed time.
                // Apply world material friction only in the final contact generation.
                if(!supportProjection && iteration==contactIterations-1)f(solve,3,dt);
            }
            if(!moving.isEmpty())GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,11,movingSupport);
            dispatch();current^=1;
        }
        // Dynamic Jacobi contacts can press a root back into the platform. The
        // support forest must start from the corrected kinematic root height.
        movingContacts(world,moving,false,false);
        if(supportProjection){
            projectSupports(world,dt);
            // Raising a support root can introduce a lateral contact with an unsupported
            // falling neighbour. Resolve that new generation before publishing it.
            for(int cleanup=0;cleanup<4;cleanup++){
                buildGrid();bind(solve);f(solve,6,1);
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[solve][30],1);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,6,supportContactInput);
                if(world!=null) {
                    if(cleanup==0){bindWorld(world,solve,false);f(solve,3,0);}
                    if(cleanup==3)f(solve,3,dt);
                }
                if(!moving.isEmpty())GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,11,movingSupport);
                dispatch();current^=1;
            }
        }
        movingContacts(world,moving,false,true);
        if(environment!=null&&world!=null)environment.step(states[current],count,world,true);
        if(sleepMode){bind(23);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,6,sleepControl);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((liveCount+63)/64,1,1);PackageCpuDiagnostics.dispatch();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
        sleepWake=false;lastSleepEligible=sleepMode;sleepForceInput=false;
        }finally{stepBindings=false;sleepMode=false;boundState=-1;}
    }
    private void movingContacts(PackageCollisionGpu.View world,java.util.List<PackageMovingCollisionGpu.View> moving,boolean sweep,boolean friction) {
        for(var view:moving) {
            movingCandidates(view);bind(14);view.bind(locations[14][18]);bindWorld(world,14,true);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[14][19],sweep?1:0);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[14][20],friction?1:0);
            try(var stack=MemoryStack.stackPush()){GpuBindings.bindBases(GL43.GL_SHADER_STORAGE_BUFFER,11,stack.ints(movingSupport,history));}
            dispatch();
        }
    }
    private void prepareActive(){
        if(!activeDirty)return;
        activeUpload.clear();for(int body=liveBodies.nextSetBit(0);body>=0;body=liveBodies.nextSetBit(body+1))activeUpload.putInt(body);
        activeUpload.flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,links);
        if(activeUpload.hasRemaining())com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)capacity*4,activeUpload);
        activeDirty=false;
    }
    private static void clearControl(int buffer){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        try(var stack=MemoryStack.stackPush()){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}}
    private void movingCandidates(PackageMovingCollisionGpu.View view){
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,links);
        try(var stack=MemoryStack.stackPush()){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,(long)capacity*12,32,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        bind(24);view.bind(locations[24][18]);
        try(var stack=MemoryStack.stackPush()){GpuBindings.bindBases(GL43.GL_SHADER_STORAGE_BUFFER,11,stack.ints(movingSupport,history));}

        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((liveCount+63)/64,1,1);PackageCpuDiagnostics.dispatch();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        bind(25);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);PackageCpuDiagnostics.dispatch();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
    }
    private void bindWorld(PackageCollisionGpu.View world,int program,boolean buffers){
        if(!stepBindings||!boundWorldUniforms[program]){
            world.bind(locations[program],8,buffers);if(stepBindings)boundWorldUniforms[program]=true;
        }
    }
    private void clearMovingSupport() {
        if(movingSupport==0)return;com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,movingSupport);
        try(var stack=MemoryStack.stackPush()){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
    }
    private void dynamicSweep() {
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,fastCells);
        try(var stack=MemoryStack.stackPush()){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,longBodies);
        try(var stack=MemoryStack.stackPush()){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,4,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        bind(20);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,7,fastCells);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,8,longBodies);dispatch();
        int program=19;bind(program);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,7,fastCells);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,8,longBodies);dispatch();current^=1;
    }
    public void claimMovingFrame(PackageMovingCollisionCache.Entry entry,long frame){
        Long previous=movingFrames.get(entry.identity);if(previous!=null&&frame<=previous)throw new IllegalStateException("Moving tick pose already simulated");movingFrames.put(entry.identity,frame);
    }
    private void buildGrid(){clearHeads();bind(1);dispatch();}
    private void ensureSupportBuffers() {
        if(supportControl==0) {
            try {
                supports[0]=buffer((long)capacity*16);supports[1]=buffer((long)capacity*16);supportControl=buffer(32);
            } catch(RuntimeException failure) {
                for(int i=0;i<supports.length;i++){if(supports[i]!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(supports[i]);supports[i]=0;}
                if(supportControl!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(supportControl);supportControl=0;
                throw failure;
            }
        }
    }
    private void projectSupports(PackageCollisionGpu.View world,float dt) {
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,supportControl);
        try(var stack=MemoryStack.stackPush()){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        buildGrid();
        bind(7);
        try(var stack=MemoryStack.stackPush()){GpuBindings.bindBases(GL43.GL_SHADER_STORAGE_BUFFER,6,stack.ints(supports[1],supports[0],supportControl));}
        dispatch();bind(8);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);PackageCpuDiagnostics.dispatch();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
        bindIndirect(supportControl);
        int input=0;
        for(int span=1;span<liveCount;span<<=2) {
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(programs[9]);setCountUniform(9);
            try(var stack=MemoryStack.stackPush()){GpuBindings.bindBases(GL43.GL_SHADER_STORAGE_BUFFER,6,stack.ints(supports[input],supports[input^1]));}
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect(16);PackageCpuDiagnostics.dispatch();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);input^=1;
        }
        bind(10);bindWorld(world,10,false);f(10,3,dt);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,6,supports[input]);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,7,supports[input]);
        supportContactInput=supports[input];
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect(16);PackageCpuDiagnostics.dispatch();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public void stepChains(float dt) {
        ensureStep(dt);if(count==0)return;
        captureHistory(1);
        bind(3);f(3,3,dt);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,4,chains);
        dispatch();current^=1;
    }
    /** Shared, versioned track table and durable node candidates. One metadata row per chain body. */
    public void stepChains(float dt,PackageChainTrackGpu tracks) {
        ensureStep(dt);java.util.Objects.requireNonNull(tracks).validateStep(count);if(count==0)return;
        captureHistory(1);bind(16);f(16,3,dt);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,4,chains);tracks.bindStep(locations[16][23]);
        dispatch();current^=1;tracks.nodes(states[current],chains);
    }
    private void ensureOpen(){if(closed)throw new IllegalStateException("Package solver closed");}
    private void captureHistory(int chainMode) {
        captureHistory(chainMode,0,count);
    }
    private void captureHistory(int chainMode,int first,int length) {
        if(length==0)return;
        bind(4);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,4,chains);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,5,history);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[4][7],chainMode);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(locations[4][21],first);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(locations[4][22],length);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((length+63)/64,1,1);PackageCpuDiagnostics.dispatch();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    private void ensureStep(float dt){ensureOpen();if(!(dt>0 && dt<=.05f))throw new IllegalArgumentException("Physics substep");}
    private void bind(int index) {
        boundProgram=index;
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(programs[index]);
        if(!stepBindings||boundState!=current){
            try(MemoryStack stack=MemoryStack.stackPush()) {
                GpuBindings.bindBases(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(states[current],states[current^1],heads,links));
            }
            boundState=current;
        }
        setCountUniform(index);
        // Readiness changes only when this solver attaches its journal. Constraint
        // kernels use the Links mirror and do not need either journal SSBO binding.
        if(environment!=null&&environmentStorage[index]&&(!stepBindings||!environmentBound)){
            environment.bindStorage();environmentBound=true;
        }
        if(locations[index][28]>=0){if(freezeDiagnostics!=null)freezeDiagnostics.bind(locations[index][28],locations[index][29]);else com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(locations[index][28],0);}
    }
    private void setCountUniform(int index) {
        if(uploadedActiveCounts[index]!=liveCount){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(locations[index][31],liveCount);uploadedActiveCounts[index]=liveCount;}
        if(uploadedCounts[index]!=count){
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(locations[index][0],count);
            uploadedCounts[index]=count;
        }
        if(uploadedTableMasks[index]!=indexSize-1){
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(locations[index][1],indexSize-1);uploadedTableMasks[index]=indexSize-1;
        }
    }
    private void f(int index,int uniform,float value){
        int bits=Float.floatToRawIntBits(value);
        if(!uploadedFloat[index][uniform]||uploadedFloatBits[index][uniform]!=bits){
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1f(locations[index][uniform],value);uploadedFloatBits[index][uniform]=bits;uploadedFloat[index][uniform]=true;
        }
    }
    /** Scratch tables follow live occupancy while linked entries retain stable body IDs.
     * A fourfold occupancy hysteresis avoids reallocating around a power-of-two boundary.
     * Both tables are rebuilt before use; body slots and interpolation history never move. */
    private void ensureIndexStorage(){
        // Neighbor queries probe many cells: keep spare buckets to limit alias traversal.
        int desired=Math.min(tableSize,Integer.highestOneBit(Math.max(MIN_INDEX_BUCKETS/2,liveCount*INDEX_BUCKETS_PER_BODY-1))<<1);
        if(desired==indexSize || (desired<indexSize && liveCount>indexSize/(INDEX_BUCKETS_PER_BODY*4)))return;
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,heads);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,(long)desired*4,GL15.GL_DYNAMIC_DRAW);
        if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=(long)desired*4)
            throw new IllegalStateException("Package grid storage allocation failed");
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,fastCells);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,(long)desired*32,GL15.GL_DYNAMIC_DRAW);
        if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=(long)desired*32)
            throw new IllegalStateException("Package sweep storage allocation failed");
        indexSize=desired;
    }
    private void clearHeads() {
        ensureIndexStorage();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,heads);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(-1));
        }
    }
    private void bindIndirect(int buffer){if(boundIndirect!=buffer){GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,buffer);boundIndirect=buffer;}}
    private void dispatch(){
        PackageCpuDiagnostics.dispatch();
        if(boundProgram==13||boundProgram==14){bindIndirect(links);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect((long)capacity*12);}
        else if(sleepMode){bindIndirect(sleepControl);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect(0);}
        else com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(((boundProgram==3||boundProgram==16?count:liveCount)+63)/64,1,1);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    /** Exact allocated buffer storage, queried outside timing intervals by the benchmark. */
    public long storageBytes(){
        ensureOpen();var buffers=new java.util.HashSet<Integer>();
        for(int id:new int[]{states[0],states[1],heads,links,chains,history,fastCells,longBodies,supports[0],supports[1],supportControl,movingSupport,sleepControl})if(id!=0)buffers.add(id);
        long bytes=0;for(int id:buffers){GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,id);bytes+=GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE);}
        return bytes+(environment==null?0:environment.bytes())+(freezeDiagnostics==null?0:freezeDiagnostics.bytes());
    }
    public int stateBuffer(){ensureOpen();return states[current];}
    public int chainBuffer(){ensureOpen();return chains;}
    public int historyBuffer(){ensureOpen();return history;}
    /** Diagnostic GPU-only counters: edges, corrected bodies, rejected bodies, input count. */
    public int supportStatsBuffer(){ensureOpen();return supportControl;}
    public void rebuildIndex(){ensureOpen();prepareActive();buildGrid();}
    public int count(){return count;}
    public int staticBodyCount(){ensureOpen();return staticCount;}
    @Override public void close() {
        if(closed)return;closed=true;
        for(int p:programs)if(p!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(p);
        for(int b:states)if(b!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(b);
        if(heads!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(heads);if(links!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(links);if(chains!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(chains);
        if(history!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(history);
        for(int buffer:supports)if(buffer!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(buffer);if(supportControl!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(supportControl);
         if(movingSupport!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(movingSupport);
         if(fastCells!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(fastCells);
         if(longBodies!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(longBodies);
         if(sleepControl!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(sleepControl);
         if(environment!=null)environment.close();
         if(freezeDiagnostics!=null)freezeDiagnostics.close();
    }
}
