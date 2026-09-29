package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
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
    public static final int NO_MESH=-1, CHAIN=1, FLIPPED=2;
    private static final String[] COMPUTE={"pool_select","pool_reserve","pool_import","draw_count","draw_prefix","draw_scatter"};
    private final int capacity, packageCapacity, maxMeshes;
    private final int[] programs=new int[7], admission=new int[2], commands=new int[2], instances=new int[2], attachments=new int[2];
    private final int[][] locations=new int[7][15];
    private static final String[] UNIFORMS={"uCount","uCapacity","uBodyCount","uMeshCount","uEmitter","uOrigin","uCamPos","uFrustum","ModelViewMat","ProjMat","uPartialTick","uLightingMode","uConstantAmbient","uLight0","uLight1"};
    private int metadata, selection, reservation, meshes, cursors, vertices, vao;
    private int count, meshCount, committed=-1, staged=-1, stagedCount;
    private final int[] candidateCounts=new int[2];
    private boolean closed;
    private int bodyBuffer,chainBuffer,historyBuffer,bodyCount;
    private float originX,originY,originZ;

    public PackagePoolGpu(int capacity,int maxMeshes,Function<String,String> sources) {
        if(capacity<=0 || maxMeshes<=0 || maxMeshes>4096)throw new IllegalArgumentException("Package pool limits");
        this.capacity=capacity;this.packageCapacity=Math.min(capacity,131072);this.maxMeshes=maxMeshes;
        if((packageCapacity+63L)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
                || (long)packageCapacity*META_BYTES>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
            throw new IllegalArgumentException("Package attachment exceeds device limits");
        try {
            rebuild(sources);
            metadata=buffer((long)packageCapacity*META_BYTES);selection=buffer(16L+4L*packageCapacity);reservation=buffer(16);
            meshes=buffer((long)maxMeshes*MESH_BYTES);cursors=buffer((long)maxMeshes*4);vertices=buffer(VERTEX_BYTES);
            for(int j=0;j<2;j++) {
                admission[j]=buffer((long)packageCapacity*32);commands[j]=buffer((long)maxMeshes*16);
                instances[j]=buffer((long)packageCapacity*16);attachments[j]=buffer((long)packageCapacity*32);
            }
            vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);
            int[] sizes={3,2,3,4};int offset=0;
            for(int j=0;j<4;j++) {
                GL20.glEnableVertexAttribArray(j);GL20.glVertexAttribPointer(j,sizes[j],GL11.GL_FLOAT,false,VERTEX_BYTES,offset);
                offset+=sizes[j]*4;
            }
            GL20.glEnableVertexAttribArray(4);GL33.glVertexAttribDivisor(4,1);
            GL30.glBindVertexArray(0);
        } catch(RuntimeException failure) {close();throw failure;}
    }
    /** Compile every candidate before replacing any live program. Failure leaves current programs usable. */
    public void rebuild(Function<String,String> sources) {
        ensureOpen();int[] candidate=new int[7];int[][] uniform=new int[7][UNIFORMS.length];
        try {
            for(int p=0;p<COMPUTE.length;p++)candidate[p]=link(sources.apply("packages/"+COMPUTE[p]+".comp"),null);
            candidate[6]=link(sources.apply("packages/package.vsh"),sources.apply("packages/package.fsh"));
            for(int p=0;p<candidate.length;p++)for(int j=0;j<UNIFORMS.length;j++)
                uniform[p][j]=GL20.glGetUniformLocation(candidate[p],UNIFORMS[j]);
            GL41.glProgramUniform1i(candidate[6],GL20.glGetUniformLocation(candidate[6],"uAtlas"),1);
            GL41.glProgramUniform1i(candidate[6],GL20.glGetUniformLocation(candidate[6],"uLightmap"),2);
            GL41.glProgramUniform3f(candidate[6],uniform[6][13],.16169f,.80845f,-.56594f);
            GL41.glProgramUniform3f(candidate[6],uniform[6][14],-.16169f,.80845f,.56594f);
        } catch(RuntimeException failure) {for(int p:candidate)if(p!=0)GL20.glDeleteProgram(p);throw failure;}
        for(int p=0;p<programs.length;p++) {
            if(programs[p]!=0)GL20.glDeleteProgram(programs[p]);programs[p]=candidate[p];
            System.arraycopy(uniform[p],0,locations[p],0,UNIFORMS.length);
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
        ByteBuffer v=data.duplicate().order(ByteOrder.nativeOrder());
        HashSet<String> identities=new HashSet<>();HashSet<Integer> bodies=new HashSet<>();
        for(int i=0;i<count;i++) {
            int p=v.position()+i*META_BYTES;
            long id=v.getLong(p),generation=v.getLong(p+8);
            if(id<=0 || generation<=0 || !identities.add(id+":"+generation) || !bodies.add(v.getInt(p+16)))
                throw new IllegalArgumentException("Invalid/duplicate package identity or body");
            int flags=v.getInt(p+28);if((flags&~(CHAIN|FLIPPED))!=0)throw new IllegalArgumentException("Package flags");
            for(int j=0;j<12;j++)if(j!=7 && !Float.isFinite(v.getFloat(p+32+j*4)))
                throw new IllegalArgumentException("Non-finite package metadata");
        }
        upload(metadata,data);this.count=count;
    }
    /** Geometry ranges are shared by all instances; replacing them requires a lifecycle/reload boundary. */
    public void uploadMeshes(ByteBuffer vertexData,ByteBuffer ranges,int meshCount) {
        ensureOpen();
        if(meshCount<0 || meshCount>maxMeshes || !ranges.isDirect() || !vertexData.isDirect()
                || ranges.remaining()!=meshCount*MESH_BYTES || vertexData.remaining()%VERTEX_BYTES!=0)
            throw new IllegalArgumentException("Package mesh layout");
        int vertexCount=vertexData.remaining()/VERTEX_BYTES;
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
        this.meshCount=meshCount;
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
    /** Called after ordinary update/emission. Uses the GPU counter, never the lagged CPU census. */
    public void stage(int pool,int counter,int emitter,float[] frustum,float cameraX,float cameraY,float cameraZ) {
        ensureOpen();staged=committed<0?0:committed^1;
        stagedCount=emitter>=0?count():0;candidateCounts[staged]=stagedCount;
        if(frustum.length!=24)throw new IllegalArgumentException("Frustum layout");
        clear(commands[staged]);clear(selection);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(bodyBuffer,pool,metadata,counter,chainBuffer,
                    admission[staged],reservation,selection,attachments[staged],historyBuffer));
        }
        use(0);dispatch(stagedCount);
        use(1);GL43.glDispatchCompute(1,1,1);barrier();
        use(2);GL30.glUniform1ui(locations[2][4],emitter);
        GL20.glUniform3f(locations[2][5],originX,originY,originZ);dispatch(stagedCount);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(pool,admission[staged],commands[staged],
                    instances[staged],cursors,meshes));
        }
        for(int p=3;p<6;p++) {
            use(p);GL20.glUniform3f(locations[p][6],cameraX,cameraY,cameraZ);
            GL20.glUniform4fv(locations[p][7],frustum);
            if(p==4){GL43.glDispatchCompute(1,1,1);barrier();}else dispatch(stagedCount);
        }
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT|GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
    }
    private void use(int p) {
        GL20.glUseProgram(programs[p]);
        GL30.glUniform1ui(locations[p][0],stagedCount);GL30.glUniform1ui(locations[p][1],capacity);
        GL30.glUniform1ui(locations[p][2],bodyCount);GL30.glUniform1ui(locations[p][3],meshCount);
    }
    private static void clear(int b) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);
        GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(ByteBuffer)null);
    }
    private static void barrier(){GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    private static void dispatch(int count){if(count>0){GL43.glDispatchCompute((count+63)/64,1,1);barrier();}}
    public void commit(){ensureOpen();if(staged<0)throw new IllegalStateException("No package submission");committed=staged;staged=-1;}
    public void abort(){staged=-1;}
    public void invalidate(){committed=staged=-1;}
    public void reset(){count=0;bodyBuffer=chainBuffer=historyBuffer=bodyCount=0;invalidate();}
    public int admissionBuffer(){ensureOpen();return committed<0?0:admission[committed];}
    public int admissionCount(){ensureOpen();return committed<0?0:candidateCounts[committed];}
    public int commandBuffer(){ensureOpen();return committed<0?0:commands[committed];}
    public int instanceBuffer(){ensureOpen();return committed<0?0:instances[committed];}
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
        GL20.glUseProgram(programs[6]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,attachments[committed]);
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
    private void ensureOpen(){if(closed)throw new IllegalStateException("Package pool closed");}
    @Override public void close() {
        if(closed)return;closed=true;
        for(int p:programs)if(p!=0)GL20.glDeleteProgram(p);
        for(int[] group:new int[][]{admission,commands,instances,attachments})for(int b:group)if(b!=0)GL15.glDeleteBuffers(b);
        for(int b:new int[]{metadata,selection,reservation,meshes,cursors,vertices})if(b!=0)GL15.glDeleteBuffers(b);
        if(vao!=0)GL30.glDeleteVertexArrays(vao);
    }
}
