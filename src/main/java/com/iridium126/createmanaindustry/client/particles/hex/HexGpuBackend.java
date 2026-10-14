package com.iridium126.createmanaindustry.client.particles.hex;

import java.util.*;
import java.nio.ByteBuffer;
import java.util.function.Function;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Shared vertex-pulling cache. Source uploads are immutable; generated points are GPU-only. */
public final class HexGpuBackend implements AutoCloseable {
    public static final int MAX_INSTANCES=4096,VERTEX_STRIDE=262144,INSTANCE_BYTES=208,JOB_BYTES=80;
    public static final class Draw {
        public HexShape shape;
        public double seed;
        public float ticks;
        public final Matrix4f transform=new Matrix4f();
        public int outerTail=-1,outerHead=-1,innerTail=-1,innerHead=-1,dotColor=-1;
        public float outer=.175f,inner=.07f,outerZ,innerZ=.01f,dotRadius;
        public boolean gradient,overrideTail;
        public float tailX,tailY;
    }
    private record Key(HexShape shape,long seed,long time,int x,int y) {}
    private static final class UploadedShape {
        // Retain the uploaded source for future cache lifecycle handling.
        @SuppressWarnings("unused")
        final HexShape shape;final int offset,bytes;int refs;
        UploadedShape(HexShape shape,int offset){this.shape=shape;this.offset=offset;bytes=shape.sources.remaining();}
    }
    private static final class Geometry {
        // Retain the geometry identity for future cache invalidation.
        @SuppressWarnings("unused")
        final Key key;final UploadedShape source;final int point,pointBytes;int pins;boolean generated;long last;
        Geometry(Key key,UploadedShape source,int point){this.key=key;this.source=source;this.point=point;pointBytes=(key.shape.points+key.shape.nodes)*16;source.refs++;}
    }
    private static final class Bank {
        int instances,jobs,commands,capacity;long bytes,fence;boolean held;
        final List<Geometry> pinned=new ArrayList<>();
        ByteBuffer data,work,draws;
    }
    private record Retired(int buffer,long bytes,long fence) {}
    private final HexGpuBudget budget;
    private final Function<String,String> sources;
    private final IdentityHashMap<HexShape,UploadedShape> uploadedShapes=new IdentityHashMap<>();
    private final LinkedHashMap<Key,Geometry> cache=new LinkedHashMap<>(16,.75f,true);
    private final TreeMap<Integer,Integer> sourceFree=new TreeMap<>(),pointFree=new TreeMap<>();
    private final List<Bank> banks=new ArrayList<>();private final List<Retired> retired=new ArrayList<>();
    private int resource,points,resourceCapacity,pointCapacity,compute,plain,vao,pointTexture,instanceTexture;
    private long clock,hits,generated,uploaded,transferred,submissions,fallbacks;private boolean closed;private String fallbackReason="none",bankReason;
    private final float[] uniformMatrix=new float[16];
    public HexGpuBackend(long bytes,Function<String,String> sources){budget=new HexGpuBudget(bytes);this.sources=sources;}
    public long allocatedBytes(){return budget.used();}public long hits(){return hits;}public long generated(){return generated;}public long uploadedBytes(){return uploaded;}public long fallbacks(){return fallbacks;}
    public long transferredBytes(){return transferred;}public long drawSubmissions(){return submissions;}public String fallbackReason(){return fallbackReason;}
    private Prepared fallback(String reason){fallbacks++;fallbackReason=reason;return null;}
    public int pointBuffer(){return points;}public int plainProgram(){return plain;}
    private int compile(String text,int kind){int s=GL20.glCreateShader(kind);GL20.glShaderSource(s,"#version 450 core\n"+text);GL20.glCompileShader(s);if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0){String error=GL20.glGetShaderInfoLog(s);GL20.glDeleteShader(s);throw new IllegalStateException(error);}return s;}
    private int program(String vertex,String fragment){int p=GL20.glCreateProgram(),v=0,f=0;try{
        v=compile(vertex,fragment==null?GL43.GL_COMPUTE_SHADER:GL20.GL_VERTEX_SHADER);GL20.glAttachShader(p,v);
        if(fragment!=null){f=compile(fragment,GL20.GL_FRAGMENT_SHADER);GL20.glAttachShader(p,f);}GL20.glLinkProgram(p);
        if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(p));return p;
    }catch(RuntimeException error){GL20.glDeleteProgram(p);throw error;}finally{if(v!=0)GL20.glDeleteShader(v);if(f!=0)GL20.glDeleteShader(f);}}
    private void initialize(){if(compute!=0)return;
        compute=program(sources.apply("hex_shared_prepare.comp"),null);
        try{plain=program(sources.apply("hex_shared.vsh"),sources.apply("hex_shared.fsh"));vao=GL30.glGenVertexArrays();pointTexture=GL11.glGenTextures();instanceTexture=GL11.glGenTextures();}
        catch(RuntimeException error){close();throw error;}
    }
    private int allocation(long bytes){if(bytes<1||bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE)||!budget.reserve(bytes))return 0;
        int id=GL15.glGenBuffers();try{GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Hex buffer allocation");return id;
        }catch(RuntimeException error){GL15.glDeleteBuffers(id);budget.release(bytes);throw error;}}
    private void free(int id,long bytes){if(id!=0){GL15.glDeleteBuffers(id);budget.release(bytes);}}
    private static boolean signaled(long fence){if(fence==0)return true;int result=GL32.glClientWaitSync(fence,0,0);return result==GL32.GL_ALREADY_SIGNALED||result==GL32.GL_CONDITION_SATISFIED;}
    private void poll(){
        for(Bank b:banks)if(b.fence!=0&&signaled(b.fence)){GL32.glDeleteSync(b.fence);b.fence=0;for(Geometry g:b.pinned)g.pins--;b.pinned.clear();}
        var it=retired.iterator();while(it.hasNext()){var r=it.next();if(signaled(r.fence)){GL32.glDeleteSync(r.fence);free(r.buffer,r.bytes);it.remove();}}
    }
    private static int take(TreeMap<Integer,Integer> free,int bytes){if(bytes==0)return 0;for(var e:free.entrySet())if(e.getValue()>=bytes){int start=e.getKey(),size=e.getValue();free.remove(start);if(size>bytes)free.put(start+bytes,size-bytes);return start;}return -1;}
    private static void release(TreeMap<Integer,Integer> free,int start,int bytes){if(bytes==0)return;
        var lo=free.lowerEntry(start);if(lo!=null&&lo.getKey()+lo.getValue()==start){start=lo.getKey();bytes+=lo.getValue();free.remove(lo.getKey());}
        var hi=free.ceilingEntry(start);if(hi!=null&&start+bytes==hi.getKey()){bytes+=hi.getValue();free.remove(hi.getKey());}free.put(start,bytes);
    }
    private boolean grow(boolean source,int bytes){int old=source?resourceCapacity:pointCapacity;int size=Math.max(4096,old);while(size-old<bytes)size=Math.multiplyExact(size,2);
        int next=allocation(size);if(next==0)return false;
        int prior=source?resource:points;if(prior!=0){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,prior);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,next);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,old);retired.add(new Retired(prior,old,GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0)));}
        if(source){resource=next;resourceCapacity=size;
            if(old==0){var permutation=BufferUtils.createByteBuffer(4096);var random=new java.util.Random(9001);random.nextDouble();random.nextDouble();random.nextDouble();int[] values=new int[256];for(int i=0;i<256;i++)values[i]=i;for(int i=0;i<256;i++){int j=i+random.nextInt(256-i),v=values[i];values[i]=values[j];values[j]=v;}for(int v:values)permutation.putFloat(v).putFloat(0).putFloat(0).putFloat(0);permutation.flip();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,resource);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,permutation);release(sourceFree,4096,size-4096);}
            else release(sourceFree,old,size-old);}else{points=next;pointCapacity=size;release(pointFree,old,size-old);}return true;
    }
    private boolean evict(){var it=cache.entrySet().iterator();while(it.hasNext()){Geometry g=it.next().getValue();if(g.pins==0&&g.last!=clock){release(pointFree,g.point,g.pointBytes);g.source.refs--;it.remove();return true;}}return false;}
    private boolean evictSource(){var it=uploadedShapes.entrySet().iterator();while(it.hasNext()){UploadedShape source=it.next().getValue();if(source.refs==0){release(sourceFree,source.offset,source.bytes);it.remove();return true;}}return false;}
    private Geometry geometry(Draw d){
        long time=d.shape.style.speed()==0?0:Float.floatToIntBits(d.ticks);
        Key key=new Key(d.shape,Double.doubleToLongBits(d.seed),time,d.overrideTail?Float.floatToIntBits(d.tailX):0,d.overrideTail?Float.floatToIntBits(d.tailY):0);
        Geometry g=cache.get(key);if(g!=null){hits++;g.last=clock;return g;}
        while(cache.size()>=MAX_INSTANCES)if(!evict())return null;
        UploadedShape source=uploadedShapes.get(d.shape);
        if(source==null){int sb=d.shape.sources.remaining(),at;
            while((at=take(sourceFree,sb))<0){if(evictSource())continue;if(evict())continue;if(!grow(true,sb+(resource==0?4096:0)))return null;}
            if(sb>0){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,resource);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,at,d.shape.sources);uploaded+=sb;}
            source=new UploadedShape(d.shape,at);uploadedShapes.put(d.shape,source);
        }
        int pb=(d.shape.points+d.shape.nodes)*16,at;
        // The source being inserted must not be recycled while acquiring its point block.
        source.refs++;
        try{while((at=take(pointFree,pb))<0){if(evict())continue;if(!grow(false,pb))return null;}
            g=new Geometry(key,source,at);g.last=clock;cache.put(key,g);return g;
        }finally{source.refs--;}
    }
    private Bank bank(int n){poll();Bank bank=null;for(Bank b:banks)if(!b.held&&b.fence==0){bank=b;break;}
        if(bank==null){if(banks.size()>=16){bankReason="all parameter banks in flight";return null;}bank=new Bank();banks.add(bank);}
        int capacity=Math.max(1,Integer.highestOneBit(Math.max(1,n-1))<<1);if(n==1)capacity=1;
        if(bank.capacity<capacity){long bytes=(long)capacity*(INSTANCE_BYTES+JOB_BYTES+16);if(bytes>budget.limit()-budget.used()+bank.bytes){bankReason="parameter bank budget";return null;}
            free(bank.instances,(long)bank.capacity*INSTANCE_BYTES);free(bank.jobs,(long)bank.capacity*JOB_BYTES);free(bank.commands,(long)bank.capacity*16);bank.instances=bank.jobs=bank.commands=bank.capacity=0;bank.bytes=0;
            int instances=0,jobs=0,commands=0;try{instances=allocation((long)capacity*INSTANCE_BYTES);jobs=allocation((long)capacity*JOB_BYTES);commands=allocation((long)capacity*16);
                if(instances==0||jobs==0||commands==0)throw new IllegalStateException("Hex bank budget invariant");
            }catch(RuntimeException|LinkageError error){free(instances,(long)capacity*INSTANCE_BYTES);free(jobs,(long)capacity*JOB_BYTES);free(commands,(long)capacity*16);throw error;}
            bank.instances=instances;bank.jobs=jobs;bank.commands=commands;bank.capacity=capacity;bank.bytes=bytes;
            bank.data=BufferUtils.createByteBuffer(capacity*INSTANCE_BYTES);bank.work=BufferUtils.createByteBuffer(capacity*JOB_BYTES);bank.draws=BufferUtils.createByteBuffer(capacity*16);
        }bank.held=true;return bank;
    }
    public final class Prepared implements AutoCloseable {
        private final Bank bank;private final int count;private boolean finished;
        private Prepared(Bank bank,int count){this.bank=bank;this.count=count;}
        public int count(){return count;}
        public int pointBase(int instance){return bank.data.getInt(instance*INSTANCE_BYTES+64);}
        public void disable(int index){bank.draws.putInt(index*16+4,0);}
        public void uploadCommands(){GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,bank.commands);GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER,0,bank.draws);transferred+=bank.draws.remaining();}
        public void bind(int program){GL13.glActiveTexture(GL13.GL_TEXTURE10);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,pointTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,points);
            GL13.glActiveTexture(GL13.GL_TEXTURE11);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,instanceTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,bank.instances);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"cmi_HexPoints"),10);GL20.glUniform1i(GL20.glGetUniformLocation(program,"cmi_HexInstances"),11);
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,bank.commands);
        }
        public void draw(){draw(false);}
        public void draw(boolean patches){GL43.glMultiDrawArraysIndirect(patches?GL40.GL_PATCHES:GL11.GL_TRIANGLES,0,count,16);submissions++;}
        @Override public void close(){if(finished)return;finished=true;bank.held=false;bank.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);}
    }
    private static void rgba(ByteBuffer b,int color){b.putFloat((color>>16&255)/255f).putFloat((color>>8&255)/255f).putFloat((color&255)/255f).putFloat((color>>>24)/255f);}
    public Prepared prepare(List<Draw> draws){if(closed||draws.isEmpty()||draws.size()>MAX_INSTANCES)return fallback(closed?"closed":draws.isEmpty()?"empty batch":"instance capacity");initialize();poll();clock++;
        Bank bank=bank(draws.size());if(bank==null)return fallback(bankReason);
        bank.data.clear();bank.work.clear();bank.draws.clear();int jobs=0;boolean success=false;var changed=new ArrayList<Geometry>();
        try{
            for(int i=0;i<draws.size();i++){
                Draw d=draws.get(i);if(d.shape==null||d.shape.vertices>=VERTEX_STRIDE)return fallback("shape vertex capacity");Geometry g=geometry(d);if(g==null)return fallback("geometry budget or pinned cache");
                g.pins++;bank.pinned.add(g);HexShape shape=d.shape;
                int layer=shape.points<=1?0:(shape.points-1)*42+60;
                int base=bank.data.position();for(int c=0;c<4;c++)for(int r=0;r<4;r++)bank.data.putFloat(d.transform.get(c,r));
                bank.data.putInt(g.point/16).putInt(shape.points).putInt(g.point/16+shape.points).putInt(shape.nodes);
                bank.data.putInt(layer).putInt(layer).putInt(shape.nodes*18).putInt(d.gradient?1:0);
                bank.data.putFloat(d.outer).putFloat(d.inner).putFloat(d.outerZ).putFloat(d.innerZ);
                rgba(bank.data,d.outerTail);rgba(bank.data,d.outerHead);rgba(bank.data,d.innerTail);rgba(bank.data,d.innerHead);rgba(bank.data,d.dotColor);
                bank.data.putFloat(d.dotRadius).putFloat(0).putFloat(0).putFloat(0);
                if(bank.data.position()-base!=INSTANCE_BYTES)throw new IllegalStateException("Hex draw ABI");
                bank.draws.putInt(shape.vertices).putInt(1).putInt(i*VERTEX_STRIDE).putInt(0);
                if(!g.generated&&!changed.contains(g)){changed.add(g);bank.work.putInt(g.source.offset/16).putInt(shape.points).putInt(g.point/16).putInt(shape.nodes);
                    bank.work.putDouble(d.seed).putDouble((double)d.ticks*shape.style.speed());
                    bank.work.putFloat(shape.style.flow()).putFloat(shape.style.hops()).putFloat((float)Math.sin((double)d.ticks*shape.style.speed())).putFloat(shape.style.variance());
                    int n=shape.bare.length/2;
                    bank.work.putInt(g.source.offset/16+shape.points*2).putInt(d.overrideTail?1:0).putInt(shape.tailStart).putInt(n>1&&shape.duplicates.contains(n-2)?Float.floatToIntBits(shape.style.readability()):0);
                    bank.work.putFloat(n>1?shape.bare[(n-2)*2]:0).putFloat(n>1?shape.bare[(n-2)*2+1]:0).putFloat(d.tailX).putFloat(d.tailY);jobs++;}

            }
            bank.data.flip();bank.work.flip();bank.draws.flip();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bank.instances);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bank.data);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bank.commands);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bank.draws);
            transferred+=bank.data.remaining()+bank.draws.remaining()+(jobs>0?bank.work.remaining():0);
            if(jobs>0){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bank.jobs);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bank.work);
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,resource);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,points);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,bank.jobs);
                GL20.glUseProgram(compute);GL43.glDispatchCompute(jobs,1,1);generated+=jobs;
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);}
            for(var g:changed)g.generated=true;success=true;
            return new Prepared(bank,draws.size());
        }finally{if(!success){for(Geometry g:bank.pinned)g.pins--;bank.pinned.clear();bank.held=false;}}
    }
    public void drawPlain(Prepared batch,Matrix4f view,Matrix4f projection){GL20.glUseProgram(plain);batch.bind(plain);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(plain,"ModelViewMat"),false,view.get(uniformMatrix));GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(plain,"ProjMat"),false,projection.get(uniformMatrix));batch.draw();}
    public boolean idle(){poll();for(Bank b:banks)if(b.fence!=0||b.held)return false;return retired.isEmpty();}
    @Override public void close(){if(closed)return;closed=true;
        for(Bank b:banks){if(b.fence!=0)GL32.glDeleteSync(b.fence);free(b.instances,(long)b.capacity*INSTANCE_BYTES);free(b.jobs,(long)b.capacity*JOB_BYTES);free(b.commands,(long)b.capacity*16);}
        for(var r:retired){GL32.glDeleteSync(r.fence);free(r.buffer,r.bytes);}retired.clear();
        free(resource,resourceCapacity);free(points,pointCapacity);if(compute!=0)GL20.glDeleteProgram(compute);if(plain!=0)GL20.glDeleteProgram(plain);if(vao!=0)GL30.glDeleteVertexArrays(vao);if(pointTexture!=0)GL11.glDeleteTextures(pointTexture);if(instanceTexture!=0)GL11.glDeleteTextures(instanceTexture);
    }
}
