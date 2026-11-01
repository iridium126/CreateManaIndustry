package com.iridium126.createmanaindustry.client.particles.engine;

import com.google.gson.GsonBuilder;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;

/** Bounded, opt-in, contiguous read-pool query input capture. Never a physics/FPS sample. */
public final class ParticleQuerySequence implements AutoCloseable {
    public static final ParticleQuerySequence INSTANCE=new ParticleQuerySequence();
    private static final int CAPACITY=131072,TRACE_OFFSET=256+CAPACITY*32,DOME_HEADER=TRACE_OFFSET+CAPACITY*32,DOME_OFFSET=DOME_HEADER+64,DOME_STRIDE=80,BYTES=DOME_OFFSET+CAPACITY*DOME_STRIDE;
    private static final class Slot {GpuReadbackBuffer storage;int texture;long fence,frame,generation;int index;float time,dt;}
    private Slot updating;private int tracedUpdate,tracedBase;private String traceDigest="unavailable";private final int[] savedImage=new int[6];
    private boolean imageBound;private int tracedDome,domeBase;private String domeDigest="unavailable";
    /** Only image writes are added. The original candidate, phase and exact
     * query expressions remain at their actual solve/apply sampling points. */
    public static String traceDomeSource(String source){
        source=source.replace("\r\n","\n");String point=" int owner=cmiDomeOwner(p.w);uint member=cmiStarMember(p.w);";
        String solve=" for(int x=0;x<=cells*2&&!exhausted;x++)",apply="  uint stoppedCount=CMI_STATE(state,CMI_DOME_CONTACT_ARGS+7u);";
        for(String marker:List.of(point,solve,apply))if(source.indexOf(marker)<0||source.indexOf(marker)!=source.lastIndexOf(marker))throw new IllegalStateException("Dome query trace boundary changed");
        String declarations="layout(rgba32ui,binding=7)coherent uniform uimageBuffer r7DomeQueries;uniform bool uR7DomeCapture;uniform uvec2 uR7DomeGeneration;\n";
        source=source.replace("void main(){","void main(){if(uR7DomeCapture&&uMode==4u&&gl_GlobalInvocationID.x==0u){imageStore(r7DomeQueries,"+(DOME_HEADER/16)+",uvec4(counter.live,uR7DomeGeneration,1u));}\n");
        String capture="if(uR7DomeCapture&&uMode==0u&&life.y>0.){uint m=cmiStarMember(p.w);if(m<131072u){int trace="+(DOME_OFFSET/16)+"+int(m)*5;imageStore(r7DomeQueries,trace,uvec4(floatBitsToUint(p.xyz),m));imageStore(r7DomeQueries,trace+1,uvec4(floatBitsToUint(pool.data[at+1u].xyz),i));imageStore(r7DomeQueries,trace+2,uvec4(CMI_STATE(state,m*2u+1u)&255u,uint(cmiDomeOwner(p.w)+1),1u,2u));}}\n";
        source=source.replace(point,capture+point).replace(solve," if(uR7DomeCapture&&member<131072u)imageStore(r7DomeQueries,"+(DOME_OFFSET/16)+"+int(member)*5+3,floatBitsToUint(vec4(old,reach)));\n"+solve)
            .replace(apply,"  if(uR7DomeCapture&&member<131072u)imageStore(r7DomeQueries,"+(DOME_OFFSET/16)+"+int(member)*5+4,floatBitsToUint(vec4(old,reach)));\n"+apply);
        return declarations+source;
    }
    private void restoreTraceImage(){if(imageBound){GL42.glBindImageTexture(7,savedImage[0],savedImage[1],savedImage[2]!=0,savedImage[3],savedImage[4],savedImage[5]);imageBound=false;}}
    public int domeProgram(int production){
        if(updating==null)return production;
        try{
            if(tracedDome==0||domeBase!=production){releaseDome();String plain=traceDomeSource(ParticlePrograms.INSTANCE.committedPlain("dome_contacts.comp"));domeDigest=com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareBackendRuntime.sha(plain);int[] parts=new int[4],passes={4,0,3,5};try{for(int i=0;i<4;i++)parts[i]=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("Dome actual query capture "+passes[i],"#version 450 core\n"+ParticlePrograms.commonPrelude()+plain.replace("#define CMI_DOME_PASS 0","#define CMI_DOME_PASS "+passes[i]));}catch(RuntimeException failure){for(int part:parts)if(part!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(part);throw failure;}tracedDome=parts[1];domeBase=production;DomeBodyContacts.register(new DomeBodyContacts.Programs(parts[0],parts[1],parts[2],parts[3]));}
            int[] names={GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT};for(int i=0;i<names.length;i++)savedImage[i]=GL30.glGetIntegeri(names[i],7);
            GL42.glBindImageTexture(7,updating.texture,0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_RGBA32UI);imageBound=true;
            for(int part:DomeBodyContacts.related(tracedDome)){GL41.glProgramUniform1i(part,GL20.glGetUniformLocation(part,"uR7DomeCapture"),1);long candidate=updating.generation+1;GL41.glProgramUniform2ui(part,GL20.glGetUniformLocation(part,"uR7DomeGeneration"),(int)candidate,(int)(candidate>>>32));}return tracedDome;
        }catch(RuntimeException failure){invalidate("Dome query trace: "+failure);restoreTraceImage();return production;}
    }
    public void endDome(){if(updating==null&&!imageBound)return;if(tracedDome!=0)for(int part:DomeBodyContacts.related(tracedDome))GL41.glProgramUniform1i(part,GL20.glGetUniformLocation(part,"uR7DomeCapture"),0);restoreTraceImage();}
    private void releaseDome(){if(tracedDome!=0){int[] parts=DomeBodyContacts.related(tracedDome);DomeBodyContacts.unregister(tracedDome);for(int part:parts)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(part);}tracedDome=domeBase=0;}
    /** Only captures compile this complete unified update variant; the production program is untouched. */
    public static String traceSource(String source){
        String marker="        vec3 sepW = vec3(0.0);";
        if(source.indexOf(marker)<0||source.indexOf(marker)!=source.lastIndexOf(marker))throw new IllegalStateException("R7 query boundary source changed");
        return "layout(rgba32ui,binding=7) coherent uniform uimageBuffer r7Queries;uniform bool uR7CaptureEnabled;\n"+source.replace(marker,"        uint r7Member=cmiStarMember(p0.w);if(uR7CaptureEnabled&&r7Member<131072u){int at="+(TRACE_OFFSET/16)+"+int(r7Member)*2;imageStore(r7Queries,at,floatBitsToUint(vec4(pos,separationRadius)));imageStore(r7Queries,at+1,uvec4(idx,r7Member,domeMember?2u:1u,1u));}\n"+marker);
    }
    public int updateProgram(int production){
        if(requestedName==null&&(session==null||!session.recording))return production;
        try{if(tracedUpdate==0||tracedBase!=production){if(tracedUpdate!=0)GL20.glDeleteProgram(tracedUpdate);tracedUpdate=0;String source="#version 450 core\n"+ParticlePrograms.commonPrelude()+traceSource(ParticlePrograms.INSTANCE.committedPlain("update.comp"));
            try{traceDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            tracedUpdate=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("R7 actual query capture",source);tracedBase=production;}return tracedUpdate;}
        catch(RuntimeException failure){invalidate("query trace compile: "+failure.getMessage());return production;}
    }
    private static final class Session {
        final String name,context,engineSource,profileFile,traceSource;final int requested;final Object world;final Path directory;
        final AtomicInteger jobs=new AtomicInteger();final ConcurrentSkipListMap<Integer,Map<String,Object>> frames=new ConcurrentSkipListMap<>();
        final AtomicBoolean finishing=new AtomicBoolean();volatile boolean closed;
        volatile String failure="";boolean recording=true;int captured;long lastFrame=-1,lastActivity=System.nanoTime();
        Session(String name,int requested,String context,Object world,Path root){this.name=name;this.requested=requested;this.context=context;this.world=world;directory=root.resolve(name+"-"+UUID.randomUUID());engineSource=ParticlePrograms.INSTANCE.diagnosticSourceDigest();profileFile=ParticleDiagnostics.INSTANCE.exportActiveProfileContext();traceSource=ParticleQuerySequence.INSTANCE.traceDigest;}
    }
    private final Slot[] slots={new Slot(),new Slot(),new Slot()};
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),task->{var thread=new Thread(task,"CMI query sequence export");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private volatile String requestedName;private int requestedFrames,program;private String shaderDigest="";private volatile Session session;private volatile boolean finalizing;
    private ParticleQuerySequence(){}
    public boolean busy(){return requestedName!=null||session!=null||finalizing;}
    public synchronized boolean request(String name,int frames){
        if(name==null||!name.matches("[A-Za-z0-9_-]{1,64}")||frames<16||frames>128||busy()||NeighborInputCapture.INSTANCE.busy()||ClientFrameCapture.INSTANCE.busy()||!com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareBackendRuntime.INSTANCE.productionOnly())return false;
        requestedFrames=frames;requestedName=name;return true;
    }
    private void ensure(){
        if(program==0){try(var stream=ParticleQuerySequence.class.getResourceAsStream("/assets/createmanaindustry/shaders/particles/diagnostics/query_sequence.comp")){
            byte[] source=Objects.requireNonNull(stream).readAllBytes();shaderDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
            program=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("diagnostic query sequence",new String(source,java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception failure){throw new IllegalStateException(failure);}}
        for(var slot:slots)if(slot.storage==null){slot.storage=new GpuReadbackBuffer(BYTES);int old=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);slot.texture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,slot.texture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32UI,slot.storage.id());GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,old);}
    }
    public void capture(long frame,long generation,float time,float dt){
        if(requestedName!=null&&session==null){
            var mc=Minecraft.getInstance();String name=requestedName;requestedName=null;
            session=new Session(name,requestedFrames,ParticleDiagnostics.INSTANCE.measurementContext(),mc.level,mc.gameDirectory.toPath().resolve("logs/cmi-particle-query-sequences"));
        }
        var current=session;if(current==null||!current.recording)return;
        if(current.lastFrame>=0&&frame!=current.lastFrame+1){invalidate("non-contiguous scene frame");return;}
        Slot free=null;for(var slot:slots)if(slot.fence==0){free=slot;break;}if(free==null){invalidate("GPU capture ring overflow");return;}
        int previousProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),generic=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING),copyWrite=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        int previous=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,15);long start=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,15),size=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,15);
        try{
            ensure();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_ALL_BARRIER_BITS);GL20.glUseProgram(program);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,15,free.storage.id());
            GL30.glUniform1ui(GL20.glGetUniformLocation(program,"uRecordCapacity"),CAPACITY);GL30.glUniform2ui(GL20.glGetUniformLocation(program,"uInputGeneration"),(int)(generation-1),(int)((generation-1)>>>32));
            GL30.glUniform1ui(GL20.glGetUniformLocation(program,"uPhase"),0);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GL30.glUniform1ui(GL20.glGetUniformLocation(program,"uPhase"),1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(CAPACITY/256,1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,free.storage.id());com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,TRACE_OFFSET,BYTES-TRACE_OFFSET,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
            int[] names={GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT};for(int i=0;i<names.length;i++)savedImage[i]=GL30.glGetIntegeri(names[i],7);
            GL42.glBindImageTexture(7,free.texture,0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_RGBA32UI);updating=free;imageBound=true;
            GL41.glProgramUniform1i(tracedUpdate,GL20.glGetUniformLocation(tracedUpdate,"uR7CaptureEnabled"),1);
            free.frame=frame;free.generation=generation-1;free.time=time;free.dt=dt;free.index=current.captured++;
            current.lastFrame=frame;current.lastActivity=System.nanoTime();if(current.captured==current.requested)current.recording=false;
            if(GL11.glGetError()!=0){invalidate("query sequence GL failure");endUpdate(false);}
        }catch(RuntimeException failure){invalidate("query sequence initialization/dispatch: "+failure.getMessage());endUpdate(false);}
        finally{if(size==0)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,15,previous);else GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,15,previous,start,size);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,copyWrite);GL20.glUseProgram(previousProgram);}
    }
    public void endUpdate(boolean success){
        if(updating==null)return;
        GL41.glProgramUniform1i(tracedUpdate,GL20.glGetUniformLocation(tracedUpdate,"uR7CaptureEnabled"),0);
        restoreTraceImage();if(!success)endSimulation(false);
    }
    public void endSimulation(boolean success){
        var slot=updating;if(slot==null)return;updating=null;endDome();
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        slot.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        if(!success||slot.fence==0)invalidate("query update incomplete");
    }
    public void invalidate(String reason){if(session!=null){session.failure=reason;session.recording=false;}requestedName=null;}
    public void poll(){
        var current=session;if(current==null)return;
        if(updating!=null)endSimulation(false);
        var mc=Minecraft.getInstance();if(current.world!=mc.level||!mc.isWindowActive()||mc.screen!=null)invalidate("world/menu/focus changed");
        if(!current.context.equals(ParticleDiagnostics.INSTANCE.measurementContext()))invalidate("shader context changed");
        if(System.nanoTime()-current.lastActivity>5_000_000_000L&&current.recording)invalidate("no contiguous simulation updates for five seconds");
        for(var slot:slots)if(slot.fence!=0){
            int status=GL32.glClientWaitSync(slot.fence,0,0);if(status==GL32.GL_TIMEOUT_EXPIRED)continue;
            if(status==GL32.GL_WAIT_FAILED){invalidate("sequence fence failed");GL32.glDeleteSync(slot.fence);slot.fence=0;continue;}
            ByteBuffer header=ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());slot.storage.readCompleted(0,64,header);int count=header.getInt(0),valid=header.getInt(4);
            if(valid!=1||count<0||count>CAPACITY){invalidate("sequence population unavailable/overflow");GL32.glDeleteSync(slot.fence);slot.fence=0;continue;}
            byte[] points=new byte[count*32],sparse=new byte[CAPACITY*32],bytes=new byte[64+count*64];slot.storage.readCompleted(64,points.length,ByteBuffer.wrap(points));slot.storage.readCompleted(TRACE_OFFSET,sparse.length,ByteBuffer.wrap(sparse));
            ByteBuffer packed=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()),source=ByteBuffer.wrap(points).order(ByteOrder.nativeOrder());header.clear();packed.put(header);packed.putInt(28,64);
            for(int i=0;i<count;i++){int member=source.getInt(i*32+12);if(member<0||member>=CAPACITY){invalidate("invalid query identity");continue;}packed.position(64+i*64);packed.put(points,i*32,32);packed.put(sparse,member*32,32);}
            ByteBuffer domeHeader=ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());slot.storage.readCompleted(DOME_HEADER,64,domeHeader);byte[] domeSparse=new byte[CAPACITY*DOME_STRIDE];slot.storage.readCompleted(DOME_OFFSET,domeSparse.length,ByteBuffer.wrap(domeSparse));ByteBuffer domeRecords=ByteBuffer.wrap(domeSparse).order(ByteOrder.nativeOrder());int domeCount=0;for(int m=0;m<CAPACITY;m++)if(domeRecords.getInt(m*DOME_STRIDE+40)==1)domeCount++;
            boolean domePresent=domeHeader.getInt(12)==1;if(domePresent&&domeHeader.getLong(4)!=slot.generation+1)invalidate("Dome candidate generation mismatch");
            byte[] domeBytes=new byte[64+domeCount*DOME_STRIDE];ByteBuffer domePacked=ByteBuffer.wrap(domeBytes).order(ByteOrder.nativeOrder());domePacked.putInt(0,domeCount).putInt(4,domePresent?1:0).putLong(8,slot.generation+1).putInt(16,domeHeader.getInt(0)).putInt(24,CAPACITY).putInt(28,DOME_STRIDE);for(int m=0,row=0;m<CAPACITY;m++)if(domeRecords.getInt(m*DOME_STRIDE+40)==1){domePacked.position(64+row++*DOME_STRIDE);domePacked.put(domeSparse,m*DOME_STRIDE,DOME_STRIDE);}
            final int recordedDomeCount=domeCount;int index=slot.index;long frame=slot.frame,generation=slot.generation;float time=slot.time,dt=slot.dt;GL32.glDeleteSync(slot.fence);slot.fence=0;current.jobs.incrementAndGet();
            try{writer.execute(()->{try{
                Files.createDirectories(current.directory);String file=String.format(Locale.ROOT,"frame-%04d.bin",index);Files.write(current.directory.resolve(file),bytes,StandardOpenOption.CREATE_NEW);
                String domeFile=String.format(Locale.ROOT,"frame-%04d-dome.bin",index);Files.write(current.directory.resolve(domeFile),domeBytes,StandardOpenOption.CREATE_NEW);
                var row=new LinkedHashMap<String,Object>();row.put("file",file);row.put("scene_frame",frame);row.put("input_generation",generation);row.put("time",time);row.put("dt",dt);row.put("points",count);row.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));row.put("dome",Map.of("file",domeFile,"present",domePresent,"points",recordedDomeCount,"candidate_generation",generation+1,"record_bytes",DOME_STRIDE,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(domeBytes))));current.frames.put(index,row);
            }catch(Exception failure){current.failure="sequence export: "+failure.getMessage();}finally{if(current.jobs.decrementAndGet()==0&&current.closed)finish(current);}});}catch(RejectedExecutionException overflow){current.jobs.decrementAndGet();invalidate("CPU export queue overflow");}
        }
        boolean pending=false;for(var slot:slots)pending|=slot.fence!=0;
        if(!current.recording&&!pending&&current.jobs.get()==0)finish(current);
    }
    private void finish(Session current){
        if(current.finishing.compareAndSet(false,true)){
            finalizing=true;session=null;var result=new LinkedHashMap<String,Object>();result.put("schema",2);result.put("scope","contiguous immutable read-pool points plus actual ordinary-separation query origin/radius at the local update boundary; Dome post-integration queries and full-chain initial state are NOT recorded");result.put("query_layout","point+identity 16, velocity+pool-slot 16, actual-query-origin+radius 16, uint4(pool-slot,identity,flags,position-stage=1) 16; flags 0=no query,1=separation,2=Dome excludes earlier separation");result.put("instrumented",true);result.put("complete",current.failure.isEmpty()&&current.frames.size()==current.requested);result.put("failure_reason",current.failure);result.put("context",current.context);result.put("shader_sha256",shaderDigest);result.put("requested_frames",current.requested);result.put("record_bytes",64);result.put("header_bytes",64);result.put("frames",new ArrayList<>(current.frames.values()));
            result.put("engine_source_sha256",current.engineSource);result.put("profile_context_file",current.profileFile);result.put("input_stage","after recovery, before GENERIC");result.put("backend","production-opengl");
            result.put("query_update_source_sha256",current.traceSource);
            result.put("schema",3);result.put("scope","contiguous read-pool points and local separation queries; separate immutable post-MODEL Dome points and actual solve/apply origins/radii; not full-chain initial state or FPS");result.put("dome_query_source_sha256",domeDigest);result.put("dome_layout","header uint8(count,present,candidateGenerationLo,Hi,candidateLive,reserved,capacity,stride=80); records: float3 candidate+uint identity, float3 velocity+uint candidatePoolSlot, uint4(phase,ownerPlusOne,pointValid,positionStage=2), float4(solveOrigin,reach), float4(applyOrigin,reach); zero radius means query did not execute");
            try{writer.execute(()->{try{Files.createDirectories(current.directory);Files.writeString(current.directory.resolve("manifest.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n",StandardOpenOption.CREATE_NEW);CreateManaIndustry.LOGGER.info("[CMI query sequence] saved {} complete={} failure={}",current.directory,result.get("complete"),current.failure);}catch(Exception failure){CreateManaIndustry.LOGGER.error("[CMI query sequence] manifest export failed",failure);}finally{finalizing=false;}});}catch(RejectedExecutionException failure){finalizing=false;CreateManaIndustry.LOGGER.error("[CMI query sequence] final export queue unavailable",failure);}
        }
    }
    public void close(){endUpdate(false);releaseDome();invalidate("client shutdown");var current=session;if(current!=null)current.closed=true;for(var slot:slots){if(slot.fence!=0)GL32.glDeleteSync(slot.fence);slot.fence=0;if(slot.storage!=null)slot.storage.close();slot.storage=null;if(slot.texture!=0)GL11.glDeleteTextures(slot.texture);slot.texture=0;}if(tracedUpdate!=0)GL20.glDeleteProgram(tracedUpdate);tracedUpdate=tracedBase=0;if(program!=0)GL20.glDeleteProgram(program);program=0;if(current!=null&&current.jobs.get()==0)finish(current);}
}
