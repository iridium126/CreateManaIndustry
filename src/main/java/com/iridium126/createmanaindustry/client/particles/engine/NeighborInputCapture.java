package com.iridium126.createmanaindustry.client.particles.engine;

import com.google.gson.GsonBuilder;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Diagnostic-only immutable input freeze, queued at the update dispatch boundary. Never a live backend. */
public final class NeighborInputCapture {
    public static final NeighborInputCapture INSTANCE=new NeighborInputCapture();
    private static final long MAX_BYTES=512L<<20;
    private volatile String requested;
    private final List<Part> parts=new ArrayList<>();
    private final Map<String,Object> manifest=new LinkedHashMap<>();
    private long fence,totalBytes;
    private String name;
    private Path validationOutput;
    private volatile boolean exporting;
    private record Part(String file,int buffer,int size){}
    private NeighborInputCapture(){}
    public synchronized boolean request(String value){
        if(value==null||!value.matches("[A-Za-z0-9_-]{1,64}")||busy()||ParticleQuerySequence.INSTANCE.busy()||ClientFrameCapture.INSTANCE.busy()||!com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareBackendRuntime.INSTANCE.productionOnly())return false;
        requested=value;return true;
    }
    public boolean busy(){return requested!=null||name!=null||exporting||ParticleChainCapture.INSTANCE.busy();}
    public void beginChain(long frame,long generation,Map<String,Object> inputs){if(requested==null||validationOutput!=null)return;String value=requested;requested=null;ClientFrameCapture.INSTANCE.invalidate("full chain capture");ParticleChainCapture.INSTANCE.start(value,frame,generation,inputs);}
    public void freeze(int program,long frame,long generation){
        if(requested==null)return;
        ClientFrameCapture.INSTANCE.invalidate("input freeze overlaps frame capture");
        name=requested+"-"+UUID.randomUUID();requested=null;totalBytes=0;manifest.clear();
        int read=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER),write=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        int pack=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] packNames={GL11.GL_PACK_ALIGNMENT,GL11.GL_PACK_ROW_LENGTH,GL12.GL_PACK_IMAGE_HEIGHT,GL11.GL_PACK_SKIP_PIXELS,GL11.GL_PACK_SKIP_ROWS,GL12.GL_PACK_SKIP_IMAGES,GL11.GL_PACK_SWAP_BYTES};
        int[] packValues=new int[packNames.length];for(int i=0;i<packNames.length;i++){packValues[i]=GL11.glGetInteger(packNames[i]);GL11.glPixelStorei(packNames[i],i==0?1:0);}
        try{
            manifest.put("schema",2);manifest.put("boundary","before GENERIC dispatch, after recovery/grid/preparation; MODEL consumes later mutations too");
            manifest.put("coverage",Map.of("preparation",false,"recovery",false,"generic_model_dispatch",true,"contacts",false,"depenetration",false,"navigation",false,"feedback_publication",false));
            manifest.put("engine_source_sha256",ParticlePrograms.INSTANCE.diagnosticSourceDigest());
            if(validationOutput==null)manifest.put("profile_context_file",ParticleDiagnostics.INSTANCE.exportActiveProfileContext());
            manifest.put("frame",frame);manifest.put("generation",generation);manifest.put("context",validationOutput==null?ParticleDiagnostics.INSTANCE.measurementContext():"detached validation fixture");
            if(validationOutput==null){var mc=Minecraft.getInstance();manifest.put("dimension",mc.level==null?"none":mc.level.dimension().location().toString());manifest.put("size",List.of(mc.getWindow().getWidth(),mc.getWindow().getHeight()));}
            manifest.put("instrumented",true);manifest.put("complete",false);
            manifest.put("replay_status","raw update inputs; continuation replay and physics validation required before backend qualification");
            manifest.put("byte_order","little-endian");
            manifest.put("emitter_vec4_stride",ParticleBuffers.VEC4_PER_EMITTER);
            var bindings=new TreeMap<String,Object>();var buffers=new HashMap<Integer,String>();
            for(int i=0;i<GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);i++){
                int id=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);if(id==0)continue;
                String file=buffers.get(id);
                if(file==null){file="buffer-"+buffers.size()+".bin";copyBuffer(id,file);buffers.put(id,file);}
                bindings.put(Integer.toString(i),Map.of("file",file,"start",GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i),"size",GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i)));
            }
            manifest.put("ssbo",bindings);
            var uniforms=new TreeMap<String,Object>();var textures=new TreeMap<String,Object>();var images=new TreeMap<String,Object>();
            int count=GL20.glGetProgrami(program,GL20.GL_ACTIVE_UNIFORMS);
            try(var stack=MemoryStack.stackPush()){
                IntBuffer size=stack.mallocInt(1),type=stack.mallocInt(1);
                for(int i=0;i<count;i++){
                    String key=GL20.glGetActiveUniform(program,i,size,type);int kind=type.get(0);
                    for(int a=0;a<size.get(0);a++){
                        String element=size.get(0)==1?key:key.replace("[0]","["+a+"]");int loc=GL20.glGetUniformLocation(program,element);if(loc<0)continue;
                        int target=samplerTarget(kind);
                        boolean image=kind==GL42.GL_UNSIGNED_INT_IMAGE_BUFFER||kind==GL42.GL_INT_IMAGE_BUFFER||kind==GL42.GL_IMAGE_BUFFER;
                        if(image){
                            int unit=GL20.glGetUniformi(program,loc),texture=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,unit);
                            uniforms.put(element,Map.of("type",kind,"value",unit));if(texture==0)continue;
                            GL13.glActiveTexture(GL13.GL_TEXTURE0);int previous=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
                            try{GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);int buffer=GL11.glGetTexLevelParameteri(GL31.GL_TEXTURE_BUFFER,0,GL31.GL_TEXTURE_BUFFER_DATA_STORE_BINDING);String file=buffers.get(buffer);
                                if(file==null){file="buffer-"+buffers.size()+".bin";copyBuffer(buffer,file);buffers.put(buffer,file);}
                                images.put(element,Map.of("file",file,"unit",unit,"format",GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT,unit),"access",GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS,unit),"offset",GL11.glGetTexLevelParameteri(GL31.GL_TEXTURE_BUFFER,0,GL43.GL_TEXTURE_BUFFER_OFFSET),"bytes",GL11.glGetTexLevelParameteri(GL31.GL_TEXTURE_BUFFER,0,GL43.GL_TEXTURE_BUFFER_SIZE)));
                            }finally{GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,previous);}
                        }else if(target!=0){int unit=GL20.glGetUniformi(program,loc);uniforms.put(element,Map.of("type",kind,"value",unit));
                            GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);int binding=target==GL31.GL_TEXTURE_BUFFER?GL31.GL_TEXTURE_BINDING_BUFFER:target==GL12.GL_TEXTURE_3D?GL12.GL_TEXTURE_BINDING_3D:GL11.GL_TEXTURE_BINDING_2D;
                            int tex=GL11.glGetInteger(binding);if(tex==0)continue;
                            if(target==GL31.GL_TEXTURE_BUFFER){int buffer=GL11.glGetTexLevelParameteri(target,0,GL31.GL_TEXTURE_BUFFER_DATA_STORE_BINDING);String file=buffers.get(buffer);
                                if(file==null){file="buffer-"+buffers.size()+".bin";copyBuffer(buffer,file);buffers.put(buffer,file);}
                                textures.put(element,Map.of("target",target,"file",file,"format",GL11.glGetTexLevelParameteri(target,0,GL11.GL_TEXTURE_INTERNAL_FORMAT),"offset",GL11.glGetTexLevelParameteri(target,0,GL43.GL_TEXTURE_BUFFER_OFFSET),"bytes",GL11.glGetTexLevelParameteri(target,0,GL43.GL_TEXTURE_BUFFER_SIZE)));
                            }else{
                                int w=GL11.glGetTexLevelParameteri(target,0,GL11.GL_TEXTURE_WIDTH),h=GL11.glGetTexLevelParameteri(target,0,GL11.GL_TEXTURE_HEIGHT),d=target==GL12.GL_TEXTURE_3D?GL11.glGetTexLevelParameteri(target,0,GL12.GL_TEXTURE_DEPTH):1;
                                int format=GL11.glGetTexLevelParameteri(target,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
                                boolean integer=kind==GL30.GL_UNSIGNED_INT_SAMPLER_3D||kind==GL30.GL_UNSIGNED_INT_SAMPLER_2D||kind==GL30.GL_INT_SAMPLER_3D||kind==GL30.GL_INT_SAMPLER_2D;
                                boolean red=integer&&(format==GL30.GL_R32UI||format==GL30.GL_R32I);
                                int pixelFormat=integer?(red?GL30.GL_RED_INTEGER:GL30.GL_RGBA_INTEGER):GL11.GL_RGBA,pixelType=integer?GL11.GL_UNSIGNED_INT:GL11.GL_FLOAT;
                                String file="texture-"+textures.size()+".bin";int bytes=checkedSize((long)w*h*d*(red?4:16));int buffer=allocate(file,bytes);
                                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,buffer);GL11.glGetTexImage(target,0,pixelFormat,pixelType,0L);
                                textures.put(element,Map.of("target",target,"file",file,"format",format,"size",List.of(w,h,d),"pixel_format",pixelFormat,"pixel_type",pixelType));
                            }
                        }else{
                            int n=components(kind);boolean floating=kind==GL11.GL_FLOAT||kind==GL20.GL_FLOAT_VEC2||kind==GL20.GL_FLOAT_VEC3||kind==GL20.GL_FLOAT_VEC4||kind==GL20.GL_FLOAT_MAT2||kind==GL20.GL_FLOAT_MAT3||kind==GL20.GL_FLOAT_MAT4;
                            if(floating){FloatBuffer data=BufferUtils.createFloatBuffer(16);GL20.glGetUniformfv(program,loc,data);var values=new ArrayList<Float>();for(int j=0;j<n;j++)values.add(data.get(j));uniforms.put(element,Map.of("type",kind,"value",values));}
                            else{IntBuffer data=BufferUtils.createIntBuffer(16);if(kind==GL11.GL_UNSIGNED_INT||kind==GL30.GL_UNSIGNED_INT_VEC2||kind==GL30.GL_UNSIGNED_INT_VEC3||kind==GL30.GL_UNSIGNED_INT_VEC4)GL30.glGetUniformuiv(program,loc,data);else GL20.glGetUniformiv(program,loc,data);var values=new ArrayList<Integer>();for(int j=0;j<n;j++)values.add(data.get(j));uniforms.put(element,Map.of("type",kind,"value_bits",values));}
                        }
                    }
                }
            }
            manifest.put("uniforms",uniforms);manifest.put("textures",textures);manifest.put("images",images);
            int length=GL20.glGetProgrami(program,GL41.GL_PROGRAM_BINARY_LENGTH);
            if(length>0){IntBuffer used=BufferUtils.createIntBuffer(1),format=BufferUtils.createIntBuffer(1);ByteBuffer binary=BufferUtils.createByteBuffer(length);GL41.glGetProgramBinary(program,used,format,binary);byte[] bytes=new byte[used.get(0)];binary.get(bytes);manifest.put("program_binary_format",format.get(0));manifest.put("program_binary_base64",Base64.getEncoder().encodeToString(bytes));}
            manifest.put("storage_bytes",totalBytes);fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            int error=GL11.glGetError();if(error!=GL11.GL_NO_ERROR||fence==0)throw new IllegalStateException("Input freeze GL failure: "+error);
        }catch(Exception failure){CreateManaIndustry.LOGGER.error("[CMI capture] freeze failed; no replay inputs published",failure);cleanup();}
        finally{GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,write);GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,pack);GL13.glActiveTexture(active);for(int i=0;i<packNames.length;i++)GL11.glPixelStorei(packNames[i],packValues[i]);}
    }
    /** Isolated driver regression entry point; never changes the production destination. */
    public void freezeForValidation(int program,Path output){validationOutput=output;freeze(program,17,42);}
    private int checkedSize(long bytes){if(bytes<=0||bytes>Integer.MAX_VALUE||totalBytes+bytes>MAX_BYTES)throw new IllegalStateException("Input freeze exceeds 512 MiB capacity; capture rejected in full");return (int)bytes;}
    private int allocate(String file,int bytes){checkedSize(bytes);int id=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,id);GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,bytes,GL15.GL_STREAM_READ);parts.add(new Part(file,id,bytes));totalBytes+=bytes;return id;}
    private void copyBuffer(int source,String file){GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);int bytes=checkedSize(GL32.glGetBufferParameteri64(GL31.GL_COPY_READ_BUFFER,GL15.GL_BUFFER_SIZE));int target=allocate(file,bytes);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,target);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,bytes);}
    private static int components(int type){return switch(type){case GL20.GL_FLOAT_VEC2,GL20.GL_INT_VEC2,GL20.GL_BOOL_VEC2,GL30.GL_UNSIGNED_INT_VEC2->2;case GL20.GL_FLOAT_VEC3,GL20.GL_INT_VEC3,GL20.GL_BOOL_VEC3,GL30.GL_UNSIGNED_INT_VEC3->3;case GL20.GL_FLOAT_VEC4,GL20.GL_INT_VEC4,GL20.GL_BOOL_VEC4,GL30.GL_UNSIGNED_INT_VEC4,GL20.GL_FLOAT_MAT2->4;case GL20.GL_FLOAT_MAT3->9;case GL20.GL_FLOAT_MAT4->16;default->1;};}
    private static int samplerTarget(int type){return switch(type){case GL31.GL_SAMPLER_BUFFER,GL31.GL_INT_SAMPLER_BUFFER,GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER->GL31.GL_TEXTURE_BUFFER;case GL20.GL_SAMPLER_3D,GL30.GL_INT_SAMPLER_3D,GL30.GL_UNSIGNED_INT_SAMPLER_3D->GL12.GL_TEXTURE_3D;case GL20.GL_SAMPLER_2D,GL30.GL_INT_SAMPLER_2D,GL30.GL_UNSIGNED_INT_SAMPLER_2D->GL11.GL_TEXTURE_2D;default->0;};}
    public void poll(){
        ParticleChainCapture.INSTANCE.poll();
        if(fence==0)return;int status=GL32.glClientWaitSync(fence,0,0);if(status==GL32.GL_TIMEOUT_EXPIRED)return;
        if(status==GL32.GL_WAIT_FAILED){CreateManaIndustry.LOGGER.error("[CMI capture] fence failed; discarded");cleanup();return;}
        int previous=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER);
        try{
            var files=new LinkedHashMap<String,byte[]>();
            for(var part:parts){GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,part.buffer);ByteBuffer mapped=GL30.glMapBufferRange(GL31.GL_COPY_READ_BUFFER,0,part.size,GL30.GL_MAP_READ_BIT);if(mapped==null)throw new IllegalStateException("Completed capture map failed");try{byte[] bytes=new byte[part.size];mapped.get(bytes);files.put(part.file,bytes);}finally{if(!GL15.glUnmapBuffer(GL31.GL_COPY_READ_BUFFER))throw new IllegalStateException("Capture storage invalid");}}
            var data=new LinkedHashMap<>(manifest);String outputName=name;Path root=validationOutput!=null?validationOutput:Minecraft.getInstance().gameDirectory.toPath().resolve("logs/cmi-neighbor-inputs");
            exporting=true;
            CompletableFuture.runAsync(()->{try{Path directory=root.resolve(outputName);Files.createDirectories(directory);var hashes=new TreeMap<String,String>();for(var entry:files.entrySet()){Files.write(directory.resolve(entry.getKey()),entry.getValue(),StandardOpenOption.CREATE_NEW);hashes.put(entry.getKey(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue())));}data.put("sha256",hashes);data.put("complete",true);Files.writeString(directory.resolve("manifest.json"),new GsonBuilder().setPrettyPrinting().create().toJson(data)+"\n",StandardOpenOption.CREATE_NEW);CreateManaIndustry.LOGGER.info("[CMI capture] saved {}",directory);}catch(Exception failure){CreateManaIndustry.LOGGER.error("[CMI capture] export incomplete (no valid manifest)",failure);}finally{exporting=false;}});
        }catch(Exception failure){CreateManaIndustry.LOGGER.error("[CMI capture] readback failed; discarded",failure);}
        finally{GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,previous);cleanup();}
    }
    private void cleanup(){if(fence!=0)GL32.glDeleteSync(fence);fence=0;for(var part:parts)GL15.glDeleteBuffers(part.buffer);parts.clear();manifest.clear();name=null;validationOutput=null;}
    public void close(){requested=null;ParticleChainCapture.INSTANCE.close();cleanup();}
}
