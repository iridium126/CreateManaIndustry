package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import com.google.gson.*;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;

/** Explicit diagnostic backend. Admission is supplied only by a validated
 * diagnostic distribution, bound to the actual published shaders/native files.
 * Ordinary builds have no admission resource and never load native code. */
public final class HardwareBackendRuntime {
    public static final HardwareBackendRuntime INSTANCE=new HardwareBackendRuntime();
    public static final List<String> NAMES=List.of("cuda-control","rt-aabb","rt-triangle","tensor","tensor-control","copy","rt-aabb-r8-merged","rt-aabb-r8-event","rt-aabb-r8-paged","rt-aabb-r8-unsorted","rt-aabb-r8-gl-dome","rt-aabb-r8-combined","rt-aabb-r8-paged-unsorted","rt-aabb-r8-combined-unsorted","rt-triangle-r8-paged","cuda-control-r8-paged");
    public static int policyFor(String name){if(!name.contains("-r8-"))return 0;return switch(name.substring(name.indexOf("-r8-")+4)){case "merged"->1;case "event"->2;case "paged"->4;case "unsorted"->8;case "gl-dome"->16;case "combined"->7;case "paged-unsorted"->12;case "combined-unsorted"->15;default->throw new IllegalArgumentException("Unknown R8 policy: "+name);};}
    public static int primitiveFor(String name){return name.startsWith("rt-aabb")?1:name.startsWith("rt-triangle")?2:name.startsWith("cuda-control")?0:name.equals("tensor")?3:name.equals("tensor-control")?4:name.equals("copy")?5:-1;}
    private static JsonObject admission(){try(var input=HardwareBackendRuntime.class.getResourceAsStream("/cmi-r7-admission.json")){return input==null?new JsonObject():JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}catch(Exception invalid){return new JsonObject();}}
    public static List<String> qualified(){var out=new ArrayList<String>();out.add("production");var data=admission();if(data.has("backends"))for(var value:data.getAsJsonArray("backends"))if(NAMES.contains(value.getAsString()))out.add(value.getAsString());return List.copyOf(out);}
    private static String receiptDigest;
    public static String receiptIdentity(){if(receiptDigest==null){var value=admission();receiptDigest=value.size()==0?"none":sha(value.toString());}return receiptDigest;}
    public static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private volatile String requested="production";
    private String selected="production",failure="none",sourceDigest="unavailable",lastResult="inactive";
    private HardwareQueryStage stage;private HardwareUpdateSource.Variant variant;
    private HardwareCopyStage copyStage;
    private int update,domePrepare,domeSolver,scratch,scratchTexture,ordinaryPrepare;
    private int backend=-1,policy;private long nonce,updateNonce,frame,generation;
    private HardwareQueryStage.Ticket domeTicket;
    private HardwareQueryStage.Ticket modelTicket;private long modelStarted;private boolean modelSubmitted;
    private String overlap="serial",compiledDigests="unavailable";private double initializationMillis;private boolean firstNative=true;
    private final List<Integer> programs=new ArrayList<>();
    private HardwareBackendRuntime(){}
    public boolean request(String name){if(!qualified().contains(name)||NeighborInputCapture.INSTANCE.busy()||ParticleQuerySequence.INSTANCE.busy())return false;if(!name.equals("production")&&com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.particleAutoThrottle){failure="experimental fixed-count measurements require [particles] autoThrottle=false";return false;}requested=name;return true;}
    public boolean active(){return stage!=null;}
    public boolean productionOnly(){return requested.equals("production")&&selected.equals("production")&&stage==null&&copyStage==null;}
    public String identity(){return selected.equals("production")?"production-opengl":selected;}
    public void requireHealthy(){if((stage!=null||copyStage!=null)&&GL45.glGetGraphicsResetStatus()!=GL11.GL_NO_ERROR)throw new IllegalStateException("R7 GPU device lost; abort frame before publication");}
    public String description(){return "actual="+identity()+"; requested="+requested+"; transfer="+(copyStage!=null?"D3D12 COPY queue":stage==null?"OpenGL":"Win32 shared buffer/fence; current-stage worker")+"; native_abi="+(HardwareNative.loaded()?"8":"unavailable")+"; expected_native_abi=8; native_sha256="+HardwareNative.digest()+"; policy="+policy+"; query="+(stage==null?"production-gl":(policy&4)!=0?"paged-single-pass":"count-write-two-pass")+"; sort="+(stage==null?"production":(policy&8)!=0?"traversal":"pool-index")+"; dome="+(stage==null?"production-gl":(policy&16)!=0?"complete-gl":"native")+"; rebuild="+(stage==null?"production-grid":"always")+"; result="+lastResult+"; source="+sourceDigest+"; failure="+failure+"; overlap="+overlap+"; initialization_ms="+initializationMillis+"; extra_gl_programs="+(stage==null?0:7)+"; continuation_bytes="+(stage==null?0:16L+262144L*variant.strideWords()*4)+"; shared_buffer_bytes="+(copyStage!=null?192L<<20:stage==null?0:96L<<20)+"; compiled="+compiledDigests+"; retired-rings-pending="+HardwareQueryStage.retainedStages()+"/"+HardwareCopyStage.retainedStages();}
    private static int loc(int p,String n){return GL20.glGetUniformLocation(p,n);}
    private static void ui(int p,String n,int v){GL41.glProgramUniform1ui(p,loc(p,n),v);}
    private static void pair(int p,String n,long v){GL41.glProgramUniform2ui(p,loc(p,n),(int)v,(int)(v>>>32));}
    private static void enabled(int p,boolean value){GL41.glProgramUniform1i(p,loc(p,"uR7ResultsEnabled"),value?1:0);}
    private int compile(String name,String source){int id=GpuShaderCompiler.compute(name,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source);programs.add(id);return id;}
    private static String text(JsonObject data,String name){return data.get(name).getAsString();}
    /** Invoked only at a whole particle-frame boundary, before input mutations. */
    public void prepareBoundary(ParticlePrograms source){
        HardwareQueryStage.pollRetirement();HardwareCopyStage.pollRetirement();
        String wanted=requested;
        if(!wanted.equals("production")&&com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.particleAutoThrottle){requested=wanted="production";failure="autoThrottle changed; fixed-count hardware experiment stopped";}
        if(!wanted.equals(selected)||(stage!=null||copyStage!=null)&&!source.diagnosticSourceDigest().equals(sourceDigest)){
            ClientFrameCapture.INSTANCE.invalidate("R7 backend/source boundary changed");
            ParticleQuerySequence.INSTANCE.invalidate("R7 backend/source boundary changed");
            close();selected="production";
            if(!wanted.equals("production"))try{long initializing=System.nanoTime();initialize(source,wanted);initializationMillis=(System.nanoTime()-initializing)/1e6;firstNative=true;selected=wanted;failure="none";}catch(Exception|LinkageError problem){failure=problem.toString();requested="production";close();CreateManaIndustry.LOGGER.warn("[CMI R7] experimental initialization failed; production retained",problem);}
            CreateManaIndustry.LOGGER.info("[CMI R7] {}",description());
        }
        if(stage!=null&&stage.quarantined()){failure="native resources quarantined";requested="production";ClientFrameCapture.INSTANCE.invalidate(failure);close();selected="production";}
    }
    public void beginFrame(ParticlePrograms source,long frame,long generation){
        this.frame=frame;this.generation=generation;modelSubmitted=false;modelTicket=null;overlap="serial";prepareBoundary(source);
        if(stage==null)return;
        updateNonce=++nonce;stage.selectPrepare(0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,scratch);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,16,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
        GL42.glBindImageTexture(7,scratchTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);
        ui(update,"uR7Stage",1);pair(update,"uR7Generation",updateNonce);enabled(update,false);
    }
    private void initialize(ParticlePrograms source,String name)throws Exception{
                var receipt=admission();
        if(!receipt.has("native_abi")||receipt.get("native_abi").getAsInt()!=8||!receipt.has("policies")||!receipt.getAsJsonObject("policies").has(name)||receipt.getAsJsonObject("policies").get(name).getAsInt()!=policyFor(name))throw new IllegalStateException("R8 ABI/policy receipt mismatch; use production");
        if(!GL11.glGetString(GL11.GL_RENDERER).equals(text(receipt,"renderer"))||!GL11.glGetString(GL11.GL_VERSION).equals(text(receipt,"driver")))throw new IllegalStateException("R7 correctness receipt requires requalification for this GPU/driver");
        for(var entry:receipt.getAsJsonObject("classes_sha256").entrySet())try(var input=HardwareBackendRuntime.class.getResourceAsStream("/"+entry.getKey())){if(input==null||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equals(entry.getValue().getAsString()))throw new IllegalStateException("R7 compiled class qualification mismatch: "+entry.getKey());}
        String updateSource=source.committedPlain("update.comp"),domeSource=source.committedPlain("dome_contacts.comp");
        if(!sha(updateSource).equals(text(receipt,"update_plain_sha256"))||!sha(domeSource).equals(text(receipt,"dome_plain_sha256")))throw new IllegalStateException("R7 correctness receipt does not match published shader sources");
        Path game=Minecraft.getInstance().gameDirectory.toPath(),configPath=game.resolve("cmi-hardware/config.json");var config=JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();Path nativeRoot=Path.of(text(config,"native")),nativeSource=Path.of(text(config,"source"));
        for(var entry:receipt.getAsJsonObject("native_sha256").entrySet()){Path file=entry.getKey().endsWith(".dll")?nativeRoot.resolve(entry.getKey()):nativeSource.resolve(entry.getKey());String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));if(!digest.equals(entry.getValue().getAsString()))throw new IllegalStateException("R7 native qualification mismatch: "+file);}
        if(!name.equals("copy")){Path sdk=Path.of(text(config,"dependencies")).getParent();for(var entry:receipt.getAsJsonObject("sdk_sha256").entrySet()){Path file=sdk.resolve(entry.getKey());String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));if(!digest.equals(entry.getValue().getAsString()))throw new IllegalStateException("R7 SDK qualification mismatch: "+file);}}
        HardwareNative.load(nativeRoot);backend=primitiveFor(name);policy=policyFor(name);sourceDigest=source.diagnosticSourceDigest();
        if(name.equals("copy")){copyStage=new HardwareCopyStage(32<<20);return;}
        var deps=JsonParser.parseString(Files.readString(Path.of(text(config,"dependencies")))).getAsJsonObject();
        variant=HardwareUpdateSource.build(updateSource);update=compile("R7 unified hardware continuation",variant.source());var dome=HardwareDomeSource.build(domeSource);if(!sha(variant.source()).equals(text(receipt,"update_variant_sha256"))||!sha(dome.consumer()).equals(text(receipt,"dome_consumer_sha256"))||!sha(dome.prepare()).equals(text(receipt,"dome_prepare_sha256")))throw new IllegalStateException("R7 continuation variant differs from qualified source");domePrepare=compile("R7 immutable Dome queries",dome.prepare());
        int[] parts=new int[4],passes={4,0,3,5};for(int i=0;i<4;i++)parts[i]=compile("R7 Dome consumer "+passes[i],dome.consumer().replace("#define CMI_DOME_PASS 0","#define CMI_DOME_PASS "+passes[i]));domeSolver=parts[1];DomeBodyContacts.register(new DomeBodyContacts.Programs(parts[0],parts[1],parts[2],parts[3]));
        long scratchBytes=16L+262144L*variant.strideWords()*4;scratch=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,scratch);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,scratchBytes,GL15.GL_DYNAMIC_COPY);if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=scratchBytes)throw new IllegalStateException("R7 continuation allocation failed");scratchTexture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,scratchTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,scratch);
        String prepare;try(var input=HardwareBackendRuntime.class.getResourceAsStream("/assets/createmanaindustry/shaders/particles/diagnostics/hardware_prepare.comp")){prepare=new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        if(!sha(prepare).equals(text(receipt,"ordinary_prepare_sha256")))throw new IllegalStateException("R7 ordinary query preparation source mismatch");stage=new HardwareQueryStage(131072,262144,16<<20,prepare,Path.of(text(deps.getAsJsonObject("cuda_cudart"),"root")),Path.of(text(deps.getAsJsonObject("optix"),"root")),nativeSource,Path.of(text(deps.getAsJsonObject("cuda_nvcc"),"root")),true,backend);
        stage.configure(policy,64<<20);
        ordinaryPrepare=stage.prepareProgram();compiledDigests="update:"+sha(variant.source())+";dome:"+sha(dome.consumer())+";dome-prepare:"+sha(dome.prepare())+";ordinary-prepare:"+sha(prepare);sourceDigest=source.diagnosticSourceDigest();
    }
    public int updateProgram(int production){return stage==null?production:update;}
    public void submitModels(){
        if(stage==null||modelSubmitted)return;modelSubmitted=true;modelStarted=System.nanoTime();
        try{stage.selectPrepare(ordinaryPrepare);modelTicket=stage.beginBound(scratchTexture,variant.strideWords(),generation-1,updateNonce,backend,131072,262144);}
        catch(RuntimeException|LinkageError problem){failure=problem.toString();requested="production";modelTicket=null;}
    }
    public void overlapWindow(){overlap="submitted before Iris terrain; current-frame continuation at first consumer; actual overlap requires GPU timeline";}
    public void cancelModels(){modelSubmitted=false;modelTicket=null;}
    /** Called after both GENERIC and MODEL prefix dispatches. */
    public void resumeModels(ParticleBuffers buffers){
        if(stage==null)return;submitModels();HardwareQueryStage.Ticket ticket=modelTicket;boolean result=false;long started=System.nanoTime();
        try{result=stage.consume(ticket,50_000_000L);lastResult=stage.lastReason();}
        catch(RuntimeException|LinkageError problem){failure=problem.toString();requested="production";lastResult="complete reference after native failure";}
        try{GL42.glBindImageTexture(7,scratchTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);GL20.glUseProgram(update);enabled(update,result);pair(update,"uR7ResourceEpoch",stage.resourceEpoch());ui(update,"uR7Stage",2);ui(update,"uModelPass",2);buffers.dispatch(ParticleBuffers.MODEL_DISPATCH);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);}
        finally{stage.afterResume(ticket);observe("separation",ticket,result,started);modelSubmitted=false;modelTicket=null;}
    }
    private void observe(String phase,HardwareQueryStage.Ticket ticket,boolean used,long started){
        if(firstNative&&used){firstNative=false;CreateManaIndustry.LOGGER.info("[CMI R7 first native] backend={} phase={} frame={} generation={} statistics={} timing_ms={}",identity(),phase,frame,generation,java.util.Arrays.toString(stage.statistics(ticket)),java.util.Arrays.toString(stage.timing(ticket)));}if(!GpuTimingObserver.active())return;double[] trace=ticket==null?null:stage.trace(ticket);ClientFrameCapture.INSTANCE.hardware(frame,generation,phase,ticket==null?0:ticket.nonce(),stage.statistics(ticket),used,lastResult,ticket==null?-1:ticket.slot(),trace);GpuTimingObserver.sample("particle.hardware."+phase+".handoff",frame,generation,(System.nanoTime()-started)/1e6,Double.NaN);
        if(used){String[] extra={"sort","seal","signal"};for(int i=0;i<3;i++)if(trace[13+i]>=0)GpuTimingObserver.sample("particle.hardware."+phase+"."+extra[i],frame,generation,Double.NaN,trace[13+i]);}
        if(used){double[] t=stage.timing(ticket);String[] names={"native-total","worker-wall","descriptors","maintenance",(policy&4)!=0?"query-with-page-staging":"count-query","scan",(policy&4)!=0?"flatten":"write-query"};for(int i=0;i<t.length;i++)GpuTimingObserver.sample("particle.hardware."+phase+"."+names[i],frame,generation,i==1?t[i]:Double.NaN,i==1?Double.NaN:t[i]);}
        else GpuTimingObserver.sample("particle.hardware."+phase+".reference-fallback",frame,generation,0,Double.NaN);
    }
    public int domeProgram(int production){return stage==null?production:domeSolver;}
    public void prepareDome(int original,int previous,int pool,int counter,float time,float dt,int players,int poolCapacity){
        if(stage==null)return;long current=++nonce,started=System.nanoTime();boolean result=false;domeTicket=null;
        for(int p:DomeBodyContacts.related(domeSolver))enabled(p,false);
        if((policy&16)!=0){lastResult="complete GL Dome policy";return;}
        try{
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,original);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,counter);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,previous);
            StarfallGpuBuffers.INSTANCE.bind(domePrepare,true);GL41.glProgramUniform1f(domePrepare,loc(domePrepare,"uTimeSec"),time);GL41.glProgramUniform1f(domePrepare,loc(domePrepare,"uDt"),dt);GL41.glProgramUniform1i(domePrepare,loc(domePrepare,"uPlayerCount"),players);
            stage.selectPrepare(domePrepare);domeTicket=stage.beginBound(0,0,generation-1,current,backend,poolCapacity,poolCapacity);result=stage.consume(domeTicket,50_000_000L);lastResult=stage.lastReason();
        }catch(RuntimeException|LinkageError problem){failure=problem.toString();requested="production";lastResult="complete Dome reference after native failure";}
        finally{for(int p:DomeBodyContacts.related(domeSolver)){enabled(p,result);pair(p,"uR7Generation",current);pair(p,"uR7ResourceEpoch",stage.resourceEpoch());}observe("dome",domeTicket,result,started);}
    }
    public void finishDome(){if(stage!=null){stage.afterResume(domeTicket);domeTicket=null;}}
    /** Returns false before committing any different state, so callers can run
     * the exact original copy. Only explicit COPY selection enters this path. */
    public boolean copyState(long from,long to,long bytes){
        if(copyStage==null)return false;long started=System.nanoTime();boolean used=copyStage.copy(GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER),GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER),from,to,bytes,50_000_000L);lastResult=copyStage.lastReason();
        if(copyStage.faulted()){requested="production";if(GL45.glGetGraphicsResetStatus()!=GL11.GL_NO_ERROR)throw new IllegalStateException("GPU device lost during R7 COPY; abort particle frame");}
        GpuTimingObserver.sample("particle.hardware.copy.handoff",frame,generation,(System.nanoTime()-started)/1e6,Double.NaN);if(used)GpuTimingObserver.sample("particle.hardware.copy.queue",frame,generation,Double.NaN,copyStage.millis());else GpuTimingObserver.sample("particle.hardware.copy.reference-fallback",frame,generation,0,Double.NaN);return used;
    }
    public void close(){
        selected="production";
        if(copyStage!=null){copyStage.close();copyStage=null;}
        if(stage!=null){stage.close();stage=null;}if(domeSolver!=0)DomeBodyContacts.unregister(domeSolver);for(int p:programs)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(p);programs.clear();
        if(scratchTexture!=0)GL11.glDeleteTextures(scratchTexture);if(scratch!=0)GL15.glDeleteBuffers(scratch);scratch=scratchTexture=update=domePrepare=domeSolver=ordinaryPrepare=0;variant=null;backend=-1;policy=0;sourceDigest="unavailable";compiledDigests="unavailable";domeTicket=null;
    }
}
