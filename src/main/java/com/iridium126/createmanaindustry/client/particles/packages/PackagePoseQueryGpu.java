package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Small, nonblocking queries of a complete package publication. Picking reduces on the GPU;
 * pose gathering reads only requested identities. Four independent output/upload banks and
 * staging slots never overwrite a borrowed snapshot or force glBufferSubData to wait for it. */
public final class PackagePoseQueryGpu implements AutoCloseable {
    public static final int RESULT_BYTES=128,REQUEST_BYTES=32,MAX_REQUESTS=256;
    public enum Kind { PICK,POSES }
    public record Input(int bodies,int chains,int metadata,int admission,int history,int count,int bodyCount,int capacity,int attachments) {
        public Input(int bodies,int chains,int metadata,int admission,int history,int count,int bodyCount,int capacity){this(bodies,chains,metadata,admission,history,count,bodyCount,capacity,0);}
        public Input {
            if(bodies<=0 || chains<=0 || metadata<=0 || admission<=0 || history<=0 || count<0 || count>131072 || capacity<1 || count>capacity
                    || bodyCount<0 || bodyCount>393216 || attachments<0)throw new IllegalArgumentException("Package query publication");
        }
        public static Input of(PackageMixedPhysicsGpu physics,PackagePoolGpu pool) {
            return new Input(physics.bodyBuffer(),physics.chainBuffer(),pool.metadataBuffer(),pool.admissionBuffer(),physics.historyBuffer(),
                    pool.admissionCount(),physics.bodyCount(),pool.capacity(),pool.attachmentBuffer());
        }
    }
    /** Origin-local ray displacement, not a normalized direction. The segment is t in (0,1). */
    public record Ray(float x,float y,float z,float dx,float dy,float dz) {
        public Ray {
            if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(dx) || !Float.isFinite(dy)
                    || !Float.isFinite(dz) || (double)dx*dx+(double)dy*dy+(double)dz*dz==0)
                throw new IllegalArgumentException("Package query ray");
        }
    }
    public record Request(long id,long generation,int candidate,boolean allowRetired) {
        public Request {if(id<=0 || generation<=0 || candidate<0 || candidate>=131072)throw new IllegalArgumentException("Package pose identity");}
    }
    public record Result(long id,long generation,int candidate,int body,int flags,int track,
            float x,float y,float z,float yaw,float vx,float vy,float vz,float state,
            float tx,float ty,float tz,float targetYaw,float before,float progress,float rate,float reversed,
            float px,float py,float pz,float previousYaw,float ptx,float pty,float ptz,float previousTargetYaw) {
        public static final Result NONE=new Result(0,0,-1,-1,0,-1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
        public boolean present(){return id!=0;}
        public boolean chain(){return (flags&PackagePoolGpu.CHAIN)!=0;}
        public boolean retired(){return state==PackagePhysicsGpu.RETIRED;}
        /** Free query side fields preserve ground and collider height for identity-safe recovery. */
        public boolean grounded(){if(!present()||chain()||rate!=0&&rate!=1)throw new IllegalStateException("Free package ground state");return rate==1;}
        public float halfHeight(){if(!present()||chain()||!Float.isFinite(reversed)||reversed<=0||reversed>1)throw new IllegalStateException("Free package collider height");return reversed;}
    }
    public record Completed(long sequence,long submission,long submittedNanos,Kind kind,Object tag,List<Result> results) {}
    private record Pending(long sequence,long submission,Kind kind,Object tag,Input input,List<Request> requests) {}
    private static final String[] NAMES={"query_pick","query_pick_finish","query_gather"};
    private static final String[] UNIFORMS={"uCount","uBodyCount","uPoolCapacity","uFrom","uRay","uGroups","uRequests","uPickType","uBodyLimit","uPartial","uFramesAvailable"};
    private final int capacity;
    private final long epoch;
    private final int[] programs=new int[3],uploads=new int[4],outputs=new int[4];
    private final int[][] locations=new int[3][UNIFORMS.length];
    private final PackageReadbackRing ring;
    private final ArrayDeque<Pending> pending=new ArrayDeque<>(4);
    private final ByteBuffer scratch=BufferUtils.createByteBuffer(MAX_REQUESTS*REQUEST_BYTES);
    private final Thread owner=Thread.currentThread();
    private int groups;
    private long nextSequence,capturedBytes;
    private boolean closed;
    public PackagePoseQueryGpu(int capacity,long epoch,Function<String,String> sources) {
        if(capacity<1 || capacity>131072 || epoch<=0 || (capacity+63)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0))
            throw new IllegalArgumentException("Package query capacity/epoch");
        this.capacity=capacity;this.epoch=epoch;PackageReadbackRing next=null;
        try {
            rebuild(sources);groups=buffer((long)((capacity+63)/64)*16);
            for(int bank=0;bank<4;bank++){uploads[bank]=buffer((long)MAX_REQUESTS*REQUEST_BYTES);outputs[bank]=buffer((long)MAX_REQUESTS*RESULT_BYTES);}
            next=new PackageReadbackRing(MAX_REQUESTS*RESULT_BYTES);
        }catch(RuntimeException failure){if(next!=null)next.close();delete();throw failure;}
        ring=next;
    }
    public void rebuild(Function<String,String> sources) {
        open();int[] next=new int[3];int[][] nextLocations=new int[3][UNIFORMS.length];
        try{for(int i=0;i<3;i++){next[i]=compile(sources.apply("packages/"+NAMES[i]+".comp"));
            for(int u=0;u<UNIFORMS.length;u++)nextLocations[i][u]=GL20.glGetUniformLocation(next[i],UNIFORMS[u]);}}
        catch(RuntimeException failure){for(int program:next)if(program!=0)GL20.glDeleteProgram(program);throw failure;}
        for(int i=0;i<3;i++){if(programs[i]!=0)GL20.glDeleteProgram(programs[i]);programs[i]=next[i];locations[i]=nextLocations[i];}
    }
    public boolean pick(Input input,Ray ray,long submission,Object tag) {
        return pick(input,ray,input.bodyCount(),1,1,submission,tag);
    }
    /** Pick only authoritative free bodies at the same interpolation as their committed draw.
     * The body prefix excludes all native/custom observer visual identities. */
    public boolean pickFree(Input input,Ray ray,int freeBodyCount,float partial,long submission,Object tag) {
        if(freeBodyCount<0||freeBodyCount>131072||freeBodyCount>input.bodyCount()||!Float.isFinite(partial)||partial<0||partial>1)
            throw new IllegalArgumentException("Free package pick domain/interpolation");
        return pick(input,ray,freeBodyCount,0,partial,submission,tag);
    }
    private boolean pick(Input input,Ray ray,int bodyLimit,int type,float partial,long submission,Object tag) {
        validate(input,submission);Objects.requireNonNull(ray);if(ring.pending()==4)return false;
        int bank=(int)(nextSequence%4),count=(input.count+63)/64;
        bind(input,groups,outputs[bank]);use(0,input);
        GL20.glUniform3f(locations[0][3],ray.x,ray.y,ray.z);GL20.glUniform3f(locations[0][4],ray.dx,ray.dy,ray.dz);
        GL30.glUniform1ui(locations[0][7],type);GL30.glUniform1ui(locations[0][8],bodyLimit);GL20.glUniform1f(locations[0][9],partial);
        GL30.glUniform1ui(locations[0][10],input.attachments>0?1:0);
        if(count>0){GL43.glDispatchCompute(count,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
        use(1,input);GL30.glUniform1ui(locations[1][5],count);
        GL30.glUniform1ui(locations[1][7],type);GL20.glUniform3f(locations[1][3],ray.x,ray.y,ray.z);GL20.glUniform3f(locations[1][4],ray.dx,ray.dy,ray.dz);
        GL20.glUniform1f(locations[1][9],partial);GL43.glDispatchCompute(1,1,1);
        return capture(bank,submission,Kind.PICK,tag,input,List.of(),1);
    }
    public boolean poses(Input input,List<Request> requests,long submission,Object tag) {
        validate(input,submission);
        if(requests==null || requests.isEmpty() || requests.size()>MAX_REQUESTS)throw new IllegalArgumentException("Package query batch");
        var copy=List.copyOf(requests);for(var r:copy)if(r.candidate>=input.count)throw new IllegalArgumentException("Package query candidate range");
        if(ring.pending()==4)return false;
        int bank=(int)(nextSequence%4);scratch.clear().limit(copy.size()*REQUEST_BYTES);
        for(int i=0;i<copy.size();i++) {
            var r=copy.get(i);int p=i*REQUEST_BYTES;
            scratch.putLong(p,r.id).putLong(p+8,r.generation).putInt(p+16,r.candidate).putInt(p+20,r.allowRetired?1:0).putLong(p+24,0);
        }
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,uploads[bank]);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,scratch);
        bind(input,uploads[bank],outputs[bank]);use(2,input);GL30.glUniform1ui(locations[2][6],copy.size());
        GL43.glDispatchCompute((copy.size()+63)/64,1,1);
        return capture(bank,submission,Kind.POSES,tag,input,copy,copy.size());
    }
    private void validate(Input input,long submission) {
        open();Objects.requireNonNull(input);if(input.count>capacity || submission<0 || nextSequence==Long.MAX_VALUE)
            throw new IllegalArgumentException("Package query namespace/range");
    }
    private void bind(Input input,int scratch,int output) {
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,
                stack.ints(input.bodies,input.chains,input.metadata,input.admission,scratch,output,input.history,input.attachments==0?input.history:input.attachments));}
    }
    private void use(int program,Input input) {
        GL20.glUseProgram(programs[program]);GL30.glUniform1ui(locations[program][0],input.count);
        GL30.glUniform1ui(locations[program][1],input.bodyCount);GL30.glUniform1ui(locations[program][2],input.capacity);
    }
    private boolean capture(int bank,long submission,Kind kind,Object tag,Input input,List<Request> requests,int count) {
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        if(!ring.submit(outputs[bank],0,count*RESULT_BYTES,epoch,nextSequence))throw new IllegalStateException("Package query slot ownership mismatch");
        pending.addLast(new Pending(nextSequence++,submission,kind,tag,input,requests));capturedBytes+=(long)count*RESULT_BYTES;return true;
    }
    public int poll(Consumer<Completed> consumer) {
        open();Objects.requireNonNull(consumer);
        try{return ring.poll(epoch,snapshot->{
            var flight=pending.peekFirst();int count=flight==null?0:flight.kind==Kind.PICK?1:flight.requests.size();
            if(flight==null || snapshot.sequence()!=flight.sequence || snapshot.bytes().remaining()!=count*RESULT_BYTES)
                throw new IllegalStateException("Package query result namespace/length");
            var values=new ArrayList<Result>(count);var bytes=snapshot.bytes().duplicate().order(ByteOrder.nativeOrder());
            for(int i=0;i<count;i++) {
                var result=decode(bytes,bytes.position()+i*RESULT_BYTES,flight.input);
                if(result.present() && flight.kind==Kind.POSES) {
                    var r=flight.requests.get(i);
                    if(result.id!=r.id || result.generation!=r.generation || result.candidate!=r.candidate || result.retired() && !r.allowRetired)
                        throw new IllegalStateException("Package query returned another identity/lifecycle");
                }
                values.add(result);
            }
            pending.removeFirst();consumer.accept(new Completed(flight.sequence,flight.submission,snapshot.submittedNanos(),flight.kind,flight.tag,List.copyOf(values)));
        });}catch(RuntimeException failure){close();throw failure;}
    }
    static Result decode(ByteBuffer b,int p,Input input) {
        long id=b.getLong(p),generation=b.getLong(p+8);
        if(id==0){for(int j=0;j<RESULT_BYTES;j+=4)if(b.getInt(p+j)!=0)throw new IllegalStateException("Malformed empty package query");return Result.NONE;}
        int candidate=b.getInt(p+16),body=b.getInt(p+20),flags=b.getInt(p+24),track=b.getInt(p+28);
        if(id<0 || generation<=0 || candidate<0 || candidate>=input.count || body<0 || body>=input.bodyCount || (flags&~15)!=0 || (flags&8)!=0&&(flags&1)==0
                || (flags&1)!=0 && (track<0 || track>=131072) || (flags&1)==0 && track!=-1)
            throw new IllegalStateException("Package query identity/index/flags");
        for(int j=32;j<RESULT_BYTES;j+=4)if(!Float.isFinite(b.getFloat(p+j)))throw new IllegalStateException("Non-finite package query pose");
        float state=b.getFloat(p+60);
        if(state<0 && state!=PackagePhysicsGpu.RETIRED || state>=0 && (flags&4)!=0)
            throw new IllegalStateException("Package query not active/retired");
        return new Result(id,generation,candidate,body,flags,track,b.getFloat(p+32),b.getFloat(p+36),b.getFloat(p+40),b.getFloat(p+44),
                b.getFloat(p+48),b.getFloat(p+52),b.getFloat(p+56),state,b.getFloat(p+64),b.getFloat(p+68),b.getFloat(p+72),b.getFloat(p+76),
                b.getFloat(p+80),b.getFloat(p+84),b.getFloat(p+88),b.getFloat(p+92),
                b.getFloat(p+96),b.getFloat(p+100),b.getFloat(p+104),b.getFloat(p+108),
                b.getFloat(p+112),b.getFloat(p+116),b.getFloat(p+120),b.getFloat(p+124));
    }
    public int pending(){open();return ring.pending();}
    public long epoch(){return epoch;}
    public long readbackBytes(){open();return capturedBytes;}
    private static int buffer(long size) {
        int id=GL15.glGenBuffers();if(id==0)throw new IllegalStateException("Package query allocation failed");
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,size,GL15.GL_DYNAMIC_COPY);
        if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=size){GL15.glDeleteBuffers(id);throw new IllegalStateException("Package query storage failed");}return id;
    }
    private static int compile(String source) {
        if(source==null || source.isBlank())throw new IllegalArgumentException("Missing package query shader");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException failure){if(program!=0)GL20.glDeleteProgram(program);throw failure;}finally{GL20.glDeleteShader(shader);}
    }
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Package query closed/off render thread");}
    private void delete(){for(int p:programs)if(p!=0)GL20.glDeleteProgram(p);for(int b:uploads)if(b!=0)GL15.glDeleteBuffers(b);for(int b:outputs)if(b!=0)GL15.glDeleteBuffers(b);if(groups!=0)GL15.glDeleteBuffers(groups);}
    @Override public void close(){if(closed)return;closed=true;ring.close();pending.clear();delete();}
}
