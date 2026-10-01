package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/**
 * Imports GPU package poses into the engine's ordinary 64-byte pool, then groups box and
 * rig instances on the GPU. Admission/commands/attachments use the same commit boundary
 * as the pool. No GPU counts are read by the CPU and no particle index is a network identity.
 */
public final class PackagePoolGpu implements AutoCloseable {
    public static final int META_BYTES=80, VERTEX_BYTES=48, MESH_BYTES=16;
    public static final int NO_MESH=-1, CHAIN=1, FLIPPED=2, HIDDEN=4, FRAMED=8, HANDBACKABLE=16;
    public static final int ATTACHMENT_BYTES=176;
    public enum DrawPass { GBUFFER, SHADOW }
    private static final class PassState {int commands,instances,pool;long publication=-1;}
    private final PassState[] drawPasses=new PassState[DrawPass.values().length];
    private final int[] publishedPools=new int[2];
    private long publication;
    private final int[] passTextures=new int[3],passTextureBuffers=new int[3];
    private final int textureUnits,textureTexels;
    private boolean sampledLightingEnabled;
    private static final String[] COMPUTE={"pool_select","pool_reserve","pool_import","draw_count","draw_prefix","draw_scatter"};
    private final int capacity, packageCapacity, maxMeshes;
    private final int[] programs=new int[12], admission=new int[2], commands=new int[2], instances=new int[2], attachments=new int[2];
    private static final String[] UNIFORMS={"uCount","uCapacity","uBodyCount","uMeshCount","uEmitter","uOrigin","uCamPos","uFrustum","ModelViewMat","ProjMat","uPartialTick","uLightingMode","uConstantAmbient","uLight0","uLight1","uLightTableSize","uLightDataOffset","uSampledLighting","uFeedback","uFrustumCount","uCullBounds","uFrameCount"};
    private final int[][] locations=new int[12][UNIFORMS.length];
    private final int[] uniformCounts=new int[12],uniformBodyCounts=new int[12],uniformFrameCounts=new int[12];
    private int sampledLight;
    private PackageLightGpu lightSource;
    private PackageLightFeedbackGpu lightFeedback;
    private long lightEpoch;
    private int metadata, selection, reservation, meshes, cursors, vertices, vertexAttributes, vao;
    private int count, meshCount, committed=-1, staged=-1, stagedCount;
    private final int[] candidateCounts=new int[2];
    private boolean closed;
    private int bodyBuffer,chainBuffer,historyBuffer,bodyCount,frameBuffer,frameCount;
    private PackageChainFramesGpu.View frameView;
    private record Identity(long id,long generation) {}
    private Set<Identity> identities=new HashSet<>();
    private Set<Integer> bodyIndices=new HashSet<>();
    private final byte[] candidateFlags;
    private float originX,originY,originZ;

    public PackagePoolGpu(int capacity,int maxMeshes,Function<String,String> sources) {
        if(capacity<=0 || maxMeshes<=0 || maxMeshes>4096)throw new IllegalArgumentException("Package pool limits");
        this.capacity=capacity;this.packageCapacity=Math.min(capacity,131072);this.maxMeshes=maxMeshes;
        textureUnits=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);textureTexels=GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
        candidateFlags=new byte[packageCapacity];
        if((packageCapacity+63L)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
                || (long)packageCapacity*ATTACHMENT_BYTES>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
            throw new IllegalArgumentException("Package attachment exceeds device limits");
        try {
            rebuild(sources);
            metadata=buffer((long)packageCapacity*META_BYTES);selection=buffer(16L+4L*packageCapacity);reservation=buffer(16);
            meshes=buffer((long)maxMeshes*MESH_BYTES);cursors=buffer((long)maxMeshes*8);vertices=buffer(VERTEX_BYTES);
            sampledLight=buffer((long)packageCapacity*4);clear(sampledLight);
            for(int j=0;j<2;j++) {
                admission[j]=buffer((long)packageCapacity*32);commands[j]=buffer((long)maxMeshes*16);
                instances[j]=buffer((long)packageCapacity*16);attachments[j]=buffer((long)packageCapacity*ATTACHMENT_BYTES);
            }
            vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);
            int[] sizes={3,2,3,4};int offset=0;
            for(int j=0;j<4;j++) {
                GL20.glEnableVertexAttribArray(j);GL20.glVertexAttribPointer(j,sizes[j],GL11.GL_FLOAT,false,VERTEX_BYTES,offset);
                offset+=sizes[j]*4;
            }
            GL20.glEnableVertexAttribArray(4);GL33.glVertexAttribDivisor(4,1);
            vertexAttributes=buffer(PackageMeshAttributes.BYTES);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertexAttributes);
            for(int j=5;j<8;j++) {
                GL20.glEnableVertexAttribArray(j);
                GL20.glVertexAttribPointer(j,j==5?3:j==6?4:2,GL11.GL_FLOAT,false,PackageMeshAttributes.BYTES,j==5?0:j==6?12:28);
            }
            GL30.glBindVertexArray(0);
        } catch(RuntimeException failure) {close();throw failure;}
    }
    /** Compile every candidate before replacing any live program. Failure leaves current programs usable. */
    public void rebuild(Function<String,String> sources) {
        ensureOpen();int[] candidate=new int[programs.length];int[][] uniform=new int[programs.length][UNIFORMS.length];
        try {
            for(int p=0;p<COMPUTE.length;p++)candidate[p]=link(sources.apply("packages/"+COMPUTE[p]+".comp"),null);
            candidate[6]=link(sources.apply("packages/package.vsh"),sources.apply("packages/package.fsh"));
            candidate[7]=link(sources.apply("packages/light_sample.comp"),null);
            candidate[8]=link(sources.apply("packages/light_requests.comp"),null);
            for(int p=9;p<12;p++)candidate[p]=link("#define CMI_SPLIT_DRAW\n"+sources.apply("packages/"+COMPUTE[p-6]+".comp"),null);
            for(int p=0;p<candidate.length;p++)for(int j=0;j<UNIFORMS.length;j++)
                uniform[p][j]=GL20.glGetUniformLocation(candidate[p],UNIFORMS[j]);
            for(int p=0;p<candidate.length;p++) {
                if(uniform[p][1]>=0)GL41.glProgramUniform1ui(candidate[p],uniform[p][1],capacity);
                if(uniform[p][3]>=0)GL41.glProgramUniform1ui(candidate[p],uniform[p][3],meshCount);
            }
            GL41.glProgramUniform1i(candidate[6],GL20.glGetUniformLocation(candidate[6],"uAtlas"),1);
            GL41.glProgramUniform1i(candidate[6],GL20.glGetUniformLocation(candidate[6],"uLightmap"),2);
            GL41.glProgramUniform3f(candidate[6],uniform[6][13],.16169f,.80845f,-.56594f);
            GL41.glProgramUniform3f(candidate[6],uniform[6][14],-.16169f,.80845f,.56594f);
        } catch(RuntimeException failure) {for(int p:candidate)if(p!=0)GL20.glDeleteProgram(p);throw failure;}
        for(int p=0;p<programs.length;p++) {
            if(programs[p]!=0)GL20.glDeleteProgram(programs[p]);programs[p]=candidate[p];
            System.arraycopy(uniform[p],0,locations[p],0,UNIFORMS.length);
            uniformCounts[p]=uniformBodyCounts[p]=uniformFrameCounts[p]=-1;
        }
    }
    private static int buffer(long bytes) {
        int b=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return b;
    }
    private static int shader(String source,int kind) {
        if(source==null || source.isBlank())throw new IllegalArgumentException("Missing package shader");
        int s=GL20.glCreateShader(kind);
        GL20.glShaderSource(s,"#version 450 core\n"+source);GL20.glCompileShader(s);
        if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0) {
            String log=GL20.glGetShaderInfoLog(s);GL20.glDeleteShader(s);throw new IllegalStateException(log);
        }
        return s;
    }
    private static int link(String primary,String fragment) {
        int a=shader(primary,fragment==null?GL43.GL_COMPUTE_SHADER:GL20.GL_VERTEX_SHADER),b=0,p=0;
        try {
            if(fragment!=null)b=shader(fragment,GL20.GL_FRAGMENT_SHADER);
            p=GL20.glCreateProgram();GL20.glAttachShader(p,a);if(b!=0)GL20.glAttachShader(p,b);GL20.glLinkProgram(p);
            if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
            return p;
        } catch(RuntimeException failure) {if(p!=0)GL20.glDeleteProgram(p);throw failure;}
        finally {GL20.glDeleteShader(a);if(b!=0)GL20.glDeleteShader(b);}
    }
    /** Mutation-time upload; callers reuse staging storage. Stable identities and body indices must be unique. */
    public void uploadMetadata(ByteBuffer data,int count) {
        ensureOpen();
        if(count<0 || count>packageCapacity || !data.isDirect() || data.remaining()!=count*META_BYTES)
            throw new IllegalArgumentException("Package metadata layout");
        Set<Identity> nextIdentities=new HashSet<>();Set<Integer> nextBodies=new HashSet<>();
        validateMetadata(data,count,nextIdentities,nextBodies);
        upload(metadata,data);this.count=count;
        // Full replacement can recycle candidate indices for different stable identities.
        // Append/visibility changes keep the existing lifetime's confirmed light instead.
        clear(sampledLight);
        ByteBuffer flags=data.duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<count;i++)candidateFlags[i]=(byte)flags.getInt(flags.position()+i*META_BYTES+28);
        identities=nextIdentities;bodyIndices=nextBodies;
    }
    /** Adds stable package identities without retransmitting or revalidating the existing population. */
    public void appendMetadata(ByteBuffer data,int added) {
        ensureOpen();
        if(added<0 || added>packageCapacity-count || !data.isDirect() || data.remaining()!=added*META_BYTES)
            throw new IllegalArgumentException("Package metadata append layout/capacity");
        Set<Identity> newIdentities=new HashSet<>();Set<Integer> newBodies=new HashSet<>();
        validateMetadata(data,added,newIdentities,newBodies);
        for(Identity identity:newIdentities)if(identities.contains(identity))
            throw new IllegalArgumentException("Duplicate existing package identity");
        for(int body:newBodies)if(bodyIndices.contains(body))
            throw new IllegalArgumentException("Duplicate existing package body");
        if(added==0)return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,metadata);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)count*META_BYTES,data);
        ByteBuffer flags=data.duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<added;i++)candidateFlags[count+i]=(byte)flags.getInt(flags.position()+i*META_BYTES+28);
        count+=added;identities.addAll(newIdentities);bodyIndices.addAll(newBodies);
    }
    /** A prepared candidate reserves a real slot while Create still owns and renders it. */
    public void setHidden(int candidate,boolean hidden) {
        ensureOpen();
        if(candidate<0 || candidate>=count || staged>=0)throw new IllegalArgumentException("Package visibility transition outside committed generation");
        int previous=Byte.toUnsignedInt(candidateFlags[candidate]);
        int next=hidden?previous|HIDDEN:previous&~HIDDEN;
        if(next==previous)return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,metadata);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)candidate*META_BYTES+28,stack.ints(next));
        }
        candidateFlags[candidate]=(byte)next;
    }
    /** Mark only a visibly active free authority as eligible for frozen handback rendering. */
    public void setHandbackable(int candidate,boolean handbackable) {
        ensureOpen();
        if(candidate<0 || candidate>=count || staged>=0)throw new IllegalArgumentException("Package handback transition outside committed generation");
        int previous=Byte.toUnsignedInt(candidateFlags[candidate]);
        int next=handbackable?previous|HANDBACKABLE:previous&~HANDBACKABLE;
        if(next==previous)return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,metadata);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)candidate*META_BYTES+28,stack.ints(next));
        }
        candidateFlags[candidate]=(byte)next;
    }
    private static void validateMetadata(ByteBuffer data,int count,Set<Identity> identities,Set<Integer> bodies) {
        ByteBuffer v=data.duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<count;i++) {
            int p=v.position()+i*META_BYTES;
            long id=v.getLong(p),generation=v.getLong(p+8);
            int body=v.getInt(p+16);
            if(id<=0 || generation<=0 || body<0 || !identities.add(new Identity(id,generation)) || !bodies.add(body))
                throw new IllegalArgumentException("Invalid/duplicate package identity or body");
            int flags=v.getInt(p+28);if((flags&~(CHAIN|FLIPPED|HIDDEN|FRAMED|HANDBACKABLE))!=0 || (flags&FRAMED)!=0&&(flags&CHAIN)==0)throw new IllegalArgumentException("Package flags");
            for(int j=0;j<12;j++)if(j!=7 && !Float.isFinite(v.getFloat(p+32+j*4)))
                throw new IllegalArgumentException("Non-finite package metadata");
        }
    }
    /** Geometry ranges are shared by all instances; replacing them requires a lifecycle/reload boundary. */
    public void uploadMeshes(ByteBuffer vertexData,ByteBuffer ranges,int meshCount) {
        uploadMeshes(vertexData,ranges,meshCount,PackageMeshAttributes.build(vertexData,ranges,meshCount,null));
    }
    public void uploadMeshes(ByteBuffer vertexData,ByteBuffer ranges,int meshCount,ByteBuffer attributes) {
        ensureOpen();
        if(meshCount<0 || meshCount>maxMeshes || !ranges.isDirect() || !vertexData.isDirect()
                || ranges.remaining()!=meshCount*MESH_BYTES || vertexData.remaining()%VERTEX_BYTES!=0)
            throw new IllegalArgumentException("Package mesh layout");
        int vertexCount=vertexData.remaining()/VERTEX_BYTES;
        if(!attributes.isDirect() || attributes.remaining()!=(long)vertexCount*PackageMeshAttributes.BYTES)
            throw new IllegalArgumentException("Package shaderpack attributes layout");
        ByteBuffer r=ranges.duplicate().order(ByteOrder.nativeOrder());
        for(int m=0;m<meshCount;m++) {
            int p=r.position()+m*MESH_BYTES,first=r.getInt(p),n=r.getInt(p+4);
            float radius=r.getFloat(p+8);
            if(first<0 || n<0 || n%3!=0 || (long)first+n>vertexCount || !Float.isFinite(radius) || radius<0)
                throw new IllegalArgumentException("Package mesh range");
        }
        upload(meshes,ranges);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertexData,GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertexAttributes);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,attributes,GL15.GL_STATIC_DRAW);
        this.meshCount=meshCount;
        for(int p=0;p<programs.length;p++)if(locations[p][3]>=0)GL41.glProgramUniform1ui(programs[p],locations[p][3],meshCount);
        invalidate();
    }
    private static void upload(int buffer,ByteBuffer bytes) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes);
    }
    public void source(int bodyBuffer,int chainBuffer,int historyBuffer,int bodyCount,float x,float y,float z) {
        ensureOpen();
        if(bodyBuffer<=0 || chainBuffer<=0 || historyBuffer<=0 || bodyCount<0 || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            throw new IllegalArgumentException("Package GPU source");
        this.bodyBuffer=bodyBuffer;this.chainBuffer=chainBuffer;this.historyBuffer=historyBuffer;this.bodyCount=bodyCount;
        originX=x;originY=y;originZ=z;
    }
    public boolean hasInput(){return !closed && count>0 && meshCount>0 && bodyBuffer>0;}
    public boolean needsStage(){return hasInput() || (!closed && committed>=0 && candidateCounts[committed]>0);}
    public int count(){return hasInput()?count:0;}
    public int metadataCount(){ensureOpen();return count;}
    /** Immutable identities/body indices; lifecycle flags may be newer than the draw bank.
     * Queries must also validate the committed admission identity/flags. */
    public int metadataBuffer(){ensureOpen();return metadata;}
    /** Candidate-indexed prefix plus optional parent extension from the committed generation. */
    public int attachmentBuffer(){ensureOpen();return committed<0?attachments[0]:attachments[committed];}
    /** Caller owns an immutable 96B/track source until the import has finished. No per-package
     * CPU matrices, count readback or extra render sampler. Missing frames reject admission. */
    public void chainFrames(int buffer,int count){
        ensureOpen();if(count<0||count>131072||count>0&&buffer<=0)throw new IllegalArgumentException("Chain frame source");
        sealFrames();
        frameBuffer=count==0?0:buffer;frameCount=count;
    }
    public void chainFrames(PackageChainFramesGpu.View view) {
        ensureOpen();java.util.Objects.requireNonNull(view);
        int buffer=view.buffer(),count=view.count();chainFrames(buffer,count);frameView=view;
    }
    private void sealFrames(){if(frameView!=null){var view=frameView;frameView=null;view.close();}}
    public boolean sourceMatches(int bodies,int chains,int history,int count,float x,float y,float z) {
        ensureOpen();return bodyBuffer==bodies && chainBuffer==chains && historyBuffer==history
                && bodyCount==count && originX==x && originY==y && originZ==z;
    }
    /** Called after ordinary update/emission. Uses the GPU counter, never the lagged CPU census. */
    public void stage(int pool,int counter,int emitter,float[] frustum,float cameraX,float cameraY,float cameraZ) {
        try{stagePrepared(pool,counter,emitter,frustum,cameraX,cameraY,cameraZ);}
        finally{sealFrames();}
    }
    private void stagePrepared(int pool,int counter,int emitter,float[] frustum,float cameraX,float cameraY,float cameraZ) {
        ensureOpen();staged=committed<0?0:committed^1;
        stagedCount=emitter>=0?count():0;candidateCounts[staged]=stagedCount;
        publishedPools[staged]=pool;
        if(frustum.length!=24)throw new IllegalArgumentException("Frustum layout");
        clear(commands[staged]);clear(selection);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(bodyBuffer,pool,metadata,counter,chainBuffer,
                    admission[staged],reservation,selection,attachments[staged],historyBuffer));
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,10,frameBuffer==0?metadata:frameBuffer);
        }
        use(0);dispatch(stagedCount);
        use(1);GL43.glDispatchCompute(1,1,1);barrier();
        use(2);GL30.glUniform1ui(locations[2][4],emitter);
        GL20.glUniform3f(locations[2][5],originX,originY,originZ);dispatch(stagedCount);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(pool,admission[staged],commands[staged],
                    instances[staged],cursors,meshes,attachments[staged]));
        }
        for(int p=3;p<6;p++) {
            use(p);GL20.glUniform3f(locations[p][6],cameraX,cameraY,cameraZ);
            GL20.glUniform4fv(locations[p][7],frustum);
            if(p==4){GL43.glDispatchCompute(1,1,1);barrier();}else dispatch(stagedCount);
        }
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT|GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
    }
    private void use(int p) {
        use(p,stagedCount);
    }
    private void use(int p,int count) {
        GL20.glUseProgram(programs[p]);
        if(locations[p][0]>=0 && uniformCounts[p]!=count){GL30.glUniform1ui(locations[p][0],count);uniformCounts[p]=count;}
        if(locations[p][2]>=0 && uniformBodyCounts[p]!=bodyCount){GL30.glUniform1ui(locations[p][2],bodyCount);uniformBodyCounts[p]=bodyCount;}
        if(locations[p][21]>=0 && uniformFrameCounts[p]!=frameCount){GL30.glUniform1ui(locations[p][21],frameCount);uniformFrameCounts[p]=frameCount;}
    }
    private static void clear(int b) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);
        GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(ByteBuffer)null);
    }
    private static void barrier(){GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    private static void dispatch(int count){if(count>0){GL43.glDispatchCompute((count+63)/64,1,1);barrier();}}
    public void commit(){ensureOpen();if(staged<0)throw new IllegalStateException("No package submission");long next=Math.incrementExact(publication);committed=staged;staged=-1;publication=next;}
    public void abort(){sealFrames();staged=-1;}
    public void invalidate(){committed=staged=-1;for(var pass:drawPasses)if(pass!=null)pass.publication=-1;}
    public void reset(){sealFrames();count=0;bodyBuffer=chainBuffer=historyBuffer=bodyCount=frameBuffer=frameCount=0;lightSource(null);clear(sampledLight);identities.clear();bodyIndices.clear();invalidate();}
    public void lightSource(PackageLightGpu source){ensureOpen();if(lightSource==source)return;
        if(lightFeedback!=null)lightFeedback.close();lightFeedback=null;lightSource=null;clear(sampledLight);
        if(source!=null){long next=Math.incrementExact(lightEpoch);lightFeedback=new PackageLightFeedbackGpu(packageCapacity,next);lightEpoch=next;lightSource=source;}}
    public void pollLightRequests(java.util.function.Consumer<PackageCollisionCache.Section> consumer){ensureOpen();if(lightFeedback!=null)lightFeedback.poll(consumer);}
    public String lightFeedbackReport(){ensureOpen();return lightFeedback==null?"off":"readback="+lightFeedback.submittedBytes()+"B, pending="+lightFeedback.pending()
        +", skipped="+lightFeedback.skipped()+", overflow="+lightFeedback.overflow()+", latency="+lightFeedback.lastLatency()/1e6+"ms";}
    public int admissionBuffer(){ensureOpen();return committed<0?0:admission[committed];}
    public int admissionCount(){ensureOpen();return committed<0?0:candidateCounts[committed];}
    public int capacity(){ensureOpen();return capacity;}
    public int commandBuffer(){ensureOpen();return committed<0?0:commands[committed];}
    public int instanceBuffer(){ensureOpen();return committed<0?0:instances[committed];}
    public int committedPoolBuffer(){ensureOpen();return committed<0?0:publishedPools[committed];}
    /** GPU-only material split/cull of the exact committed generation. Shadow culling policy
     * comes from Iris's shadow-caster frustum, never the main camera's command stream. Does
     * not import, reserve, update physics, change admission or publish any gameplay result. */
    public boolean preparePass(DrawPass kind,int pool,float[] frustum,float cameraX,float cameraY,float cameraZ) {
        return preparePass(kind,pool,frustum,6,-1,-1,cameraX,cameraY,cameraZ);
    }
    public boolean preparePass(DrawPass kind,int pool,float[] frustum,int planes,float distance,float safe,float cameraX,float cameraY,float cameraZ) {
        ensureOpen();java.util.Objects.requireNonNull(kind);
        if(frustum==null || planes<0 || planes>PackageDrawCulling.MAX_PLANES || frustum.length<planes*4 || frustum.length%4!=0
                || frustum.length>PackageDrawCulling.MAX_PLANES*4 || !Float.isFinite(distance) || !Float.isFinite(safe)
                || !Float.isFinite(cameraX) || !Float.isFinite(cameraY) || !Float.isFinite(cameraZ))
            throw new IllegalArgumentException("Package draw camera/frustum");
        for(float plane:frustum)if(!Float.isFinite(plane))throw new IllegalArgumentException("Nonfinite package draw frustum");
        if(committed<0 || meshCount==0)return false;
        if(pool!=publishedPools[committed])throw new IllegalArgumentException("Package draw uses another pool generation");
        var pass=drawPasses[kind.ordinal()];
        if(pass==null) {
            pass=new PassState();drawPasses[kind.ordinal()]=pass;
            pass.commands=buffer((long)maxMeshes*32);pass.instances=buffer((long)packageCapacity*16);
        }
        pass.publication=-1;
        clear(pass.commands);
        try(var stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(pool,admission[committed],pass.commands,pass.instances,cursors,meshes,attachments[committed]));
        }
        int count=candidateCounts[committed];
        for(int p=9;p<12;p++) {
            use(p,count);GL20.glUniform3f(locations[p][6],cameraX,cameraY,cameraZ);GL20.glUniform4fv(locations[p][7],frustum);
            if(locations[p][19]>=0)GL30.glUniform1ui(locations[p][19],planes);
            if(locations[p][20]>=0)GL20.glUniform2f(locations[p][20],distance,safe);
            if(p==10){GL43.glDispatchCompute(1,1,1);barrier();}else dispatch(count);
        }
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT|GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
        pass.pool=pool;pass.publication=publication;return true;
    }
    private PassState prepared(DrawPass kind) {
        ensureOpen();java.util.Objects.requireNonNull(kind);var pass=drawPasses[kind.ordinal()];
        if(committed<0 || pass==null || pass.publication!=publication || pass.pool!=publishedPools[committed])
            throw new IllegalStateException("Package draw pass not prepared for committed generation");
        return pass;
    }
    public int passCommandBuffer(DrawPass kind){return prepared(kind).commands;}
    public int passInstanceBuffer(DrawPass kind){return prepared(kind).instances;}
    public int meshCount(){ensureOpen();return meshCount;}
    /** Three TBO views (RGBA32F pool, RGBA32F attachments, R32UI sampled light) let Iris's
     * injected vertex source avoid colliding with foreign SSBO bindings. Caller reserves the
     * sampler units and restores/unbinds them at its external boundary, then applies its shader. */
    public boolean bindPassTbos(DrawPass kind,int firstUnit,float partialTick) {
        var pass=prepared(kind);
        if(!Float.isFinite(partialTick) || firstUnit<0 || firstUnit>textureUnits-3 || (long)capacity*4>textureTexels || (long)packageCapacity*11>textureTexels)
            throw new IllegalArgumentException("Package TBO sampler/device capacity");
        sampleLight(pass.pool,partialTick);
        for(int i=0;i<3;i++) {
            int buffer=i==0?pass.pool:i==1?attachments[committed]:sampledLight;
            if(passTextures[i]==0)passTextures[i]=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
            if(passTextureBuffers[i]!=buffer) {
                GL45.glTextureBuffer(passTextures[i],i==2?GL30.GL_R32UI:GL30.GL_RGBA32F,buffer);passTextureBuffers[i]=buffer;
            }
        }
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        try(var stack=MemoryStack.stackPush()){GL44.glBindTextures(firstUnit,stack.ints(passTextures));}
        return sampledLightingEnabled;
    }
    public void unbindPassTbos(int firstUnit) {
        ensureOpen();if(firstUnit<0 || firstUnit>textureUnits-3)throw new IllegalArgumentException("Package TBO sampler range");
        try(var stack=MemoryStack.stackPush()){GL44.glBindTextures(firstUnit,stack.ints(0,0,0));}
    }
    /** Caller owns the merged shader/texture/state boundary. Layer 0 is ground solid; 1 is
     * chain cutoutMipped (box and rig). Counts remain indirect, with no CPU census guard. */
    public void drawPreparedLayer(DrawPass kind,int layer) {
        if(layer<0 || layer>1)throw new IllegalArgumentException("Package material layer");
        drawPrepared(kind,layer,1,false);
    }
    /** Both layers can share one multi-draw when their program/state/IDs agree. */
    public void drawPrepared(DrawPass kind,int firstLayer,int layers,boolean tessellation) {
        if(firstLayer<0 || layers<1 || firstLayer+layers>2)throw new IllegalArgumentException("Package draw layer range");
        var pass=prepared(kind);GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,pass.instances);
        GL30.glVertexAttribIPointer(4,2,GL11.GL_UNSIGNED_INT,8,0L);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,pass.commands);
        com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.drawCall();
        GL43.glMultiDrawArraysIndirect(tessellation?GL40.GL_PATCHES:GL11.GL_TRIANGLES,(long)firstLayer*meshCount*16,meshCount*layers,16);
    }
    public void lighting(boolean flywheel,boolean constantAmbient,Vector3fc light0,Vector3fc light1) {
        ensureOpen();
        GL41.glProgramUniform1i(programs[6],locations[6][11],flywheel?1:0);
        GL41.glProgramUniform1i(programs[6],locations[6][12],constantAmbient?1:0);
        float a=light0.length(),b=light1.length();
        if(!(a>0 && b>0) || !Float.isFinite(a) || !Float.isFinite(b))return;
        GL41.glProgramUniform3f(programs[6],locations[6][13],light0.x()/a,light0.y()/a,light0.z()/a);
        GL41.glProgramUniform3f(programs[6],locations[6][14],light1.x()/b,light1.y()/b,light1.z()/b);
    }
    public void draw(int pool,Matrix4fc view,Matrix4fc projection,float x,float y,float z,float partialTick) {
        ensureOpen();if(committed<0 || meshCount==0)return;
        sampleLight(pool,partialTick);
        GL20.glUseProgram(programs[6]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,attachments[committed]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,7,sampledLight);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(locations[6][8],false,view.get(stack.mallocFloat(16)));
            GL20.glUniformMatrix4fv(locations[6][9],false,projection.get(stack.mallocFloat(16)));
        }
        GL20.glUniform3f(locations[6][6],x,y,z);GL20.glUniform1f(locations[6][10],Math.clamp(partialTick,0,1));
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,instances[committed]);
        GL30.glVertexAttribIPointer(4,2,GL11.GL_UNSIGNED_INT,8,0L);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,commands[committed]);
        com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.drawCall();
        GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES,0L,meshCount,16);
    }
    /** Once per package per draw boundary, including shadow/alternate interpolation generations. */
    private void sampleLight(int pool,float partialTick) {
        try(var light=lightSource==null?null:lightSource.view()) {
            sampledLightingEnabled=light!=null;
            GL41.glProgramUniform1i(programs[6],locations[6][17],light==null?0:1);
            if(light==null || candidateCounts[committed]==0)return;
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            use(7,candidateCounts[committed]);
            try(MemoryStack stack=MemoryStack.stackPush()) {
                GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(pool,admission[committed]));
                GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stack.ints(attachments[committed],sampledLight));
            }
            light.bind(8);
            GL30.glUniform1ui(locations[7][15],light.tableSize());GL30.glUniform1ui(locations[7][16],light.dataWordOffset());
            GL20.glUniform1f(locations[7][10],Math.clamp(partialTick,0,1));
            GL30.glUniform1ui(locations[7][18],lightFeedback==null?0:1);
            if(lightFeedback!=null)lightFeedback.begin();dispatch(candidateCounts[committed]);
            if(lightFeedback!=null){GL20.glUseProgram(programs[8]);GL30.glUniform1ui(locations[8][0],candidateCounts[committed]);
                GL43.glDispatchCompute(PackageLightRequests.BUCKETS/64,1,1);barrier();lightFeedback.capture();}
        }
    }
    private void ensureOpen(){if(closed)throw new IllegalStateException("Package pool closed");}
    @Override public void close() {
        if(closed)return;sealFrames();closed=true;
        if(lightFeedback!=null)lightFeedback.close();
        for(int p:programs)if(p!=0)GL20.glDeleteProgram(p);
        for(int[] group:new int[][]{admission,commands,instances,attachments})for(int b:group)if(b!=0)GL15.glDeleteBuffers(b);
        for(var pass:drawPasses)if(pass!=null){if(pass.commands!=0)GL15.glDeleteBuffers(pass.commands);if(pass.instances!=0)GL15.glDeleteBuffers(pass.instances);}
        for(int texture:passTextures)if(texture!=0)GL11.glDeleteTextures(texture);
        for(int b:new int[]{metadata,selection,reservation,meshes,cursors,vertices,vertexAttributes,sampledLight})if(b!=0)GL15.glDeleteBuffers(b);
        if(vao!=0)GL30.glDeleteVertexArrays(vao);
    }
}
