import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Restores the exact schema-1 update boundary. Never labels these samples a full chain. */
public final class R6CaptureReplayGpu extends ModelPopulationGpuValidation {
    record Storage(int master,int working,int originalBytes){}
    static void saveOverlapDirections(Path out)throws Exception{
        String helper=Files.readString(Path.of("build/benchmarks/neighbor-r6/baseline/source/src/main/resources/assets/createmanaindustry/shaders/particles/chunks/storm_separation.glsl"));
        String text="#version 450 core\nlayout(local_size_x=64)in;layout(std430,binding=0)buffer Output{vec4 values[];};\n"+helper+"\nvoid main(){uint i=gl_GlobalInvocationID.x;if(i<131072u)values[i]=vec4(cmiStormSeparationDelta(vec3(0),i,i+1u),0);}";
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,text);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));int p=GL20.glCreateProgram();GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(shader);
        int data=buffer(bytes(131072*16));bind(0,data);dispatch(p,2048);Files.write(out.resolve("separation-deltas.bin"),Capture.array(read(data,131072*16)));GL15.glDeleteBuffers(data);deleteProgram(p);
    }
    static ByteBuffer load(Path file)throws Exception{byte[] bytes=Files.readAllBytes(file);return BufferUtils.createByteBuffer(bytes.length).put(bytes).flip();}
    static void textureBuffer(JsonObject d,int buffer){if(d.has("bytes")&&d.get("bytes").getAsLong()>0)GL43.glTexBufferRange(GL31.GL_TEXTURE_BUFFER,d.get("format").getAsInt(),buffer,d.get("offset").getAsLong(),d.get("bytes").getAsLong());else GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,d.get("format").getAsInt(),buffer);}
    static final class Capture implements AutoCloseable{
        final JsonObject manifest;final Path root;final Map<String,Storage> storage=new LinkedHashMap<>();final Map<String,Integer> textures=new LinkedHashMap<>();
        final int live,models,grid,emit,input,trace,traceTexture;final long generation;final R6Grid packed;final R6IndexOrder order;final int timer=GL15.glGenQueries();
        Capture(Path manifestFile,String variant,int capacityOverride)throws Exception{
            root=manifestFile.getParent();manifest=JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();check(manifest.get("schema").getAsInt()<=2,"legacy update-boundary snapshot required; use R7ChainReplayGpu for schema-3 full journals");check(manifest.get("complete").getAsBoolean(),"committed input");
            for(var e:manifest.getAsJsonObject("sha256").entrySet()){try(var stream=Files.newInputStream(root.resolve(e.getKey()))){var h=MessageDigest.getInstance("SHA-256");byte[] block=new byte[1048576];int n;while((n=stream.read(block))!=-1)h.update(block,0,n);check(HexFormat.of().formatHex(h.digest()).equals(e.getValue().getAsString()),"input checksum "+e.getKey());}}
            live=load(root.resolve(binding(14).get("file").getAsString())).order(ByteOrder.nativeOrder()).getInt(0);
            models=load(root.resolve(binding(24).get("file").getAsString())).order(ByteOrder.nativeOrder()).getInt(0);
            var v=JsonParser.parseString(Files.readString(Path.of(System.getProperty("cmi.r6.candidates"),variant,"variant.json"))).getAsJsonObject();
            String gridFile=binding(13).get("file").getAsString();int original=(int)Files.size(root.resolve(gridFile));packed=new R6Grid(original,capacityOverride<0?live:capacityOverride,v.get("buckets").getAsInt(),v.get("cell").getAsFloat(),v.get("packed").getAsBoolean());
            order=new R6IndexOrder(Math.max(1,models),variant.contains("space")?1:variant.contains("phase")?2:0);
            for(var e:manifest.getAsJsonObject("sha256").entrySet())if(e.getKey().startsWith("buffer-")){
                ByteBuffer data=load(root.resolve(e.getKey()));int master=buffer(data),working=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,working);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,e.getKey().equals(gridFile)?packed.bytes:data.remaining(),GL15.GL_DYNAMIC_COPY);
                storage.put(e.getKey(),new Storage(master,working,data.remaining()));
            }
            grid=id(13);emit=id(5);input=id(0);generation=manifest.get("generation").getAsLong()-1;
            for(var e:manifest.getAsJsonObject("textures").entrySet()){
                var d=e.getValue().getAsJsonObject();int target=d.get("target").getAsInt(),tex=GL11.glGenTextures();GL11.glBindTexture(target,tex);
                if(target==GL31.GL_TEXTURE_BUFFER)textureBuffer(d,storage.get(d.get("file").getAsString()).working);
                else{var dimensions=d.getAsJsonArray("size");ByteBuffer data=load(root.resolve(d.get("file").getAsString()));GL12.glTexImage3D(target,0,d.get("format").getAsInt(),dimensions.get(0).getAsInt(),dimensions.get(1).getAsInt(),dimensions.get(2).getAsInt(),0,d.get("pixel_format").getAsInt(),d.get("pixel_type").getAsInt(),data);GL11.glTexParameteri(target,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(target,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);}
                textures.put(e.getKey(),tex);
            }
            for(var e:manifest.getAsJsonObject("images").entrySet()){
                var d=e.getValue().getAsJsonObject();int tex=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,tex);textureBuffer(d,storage.get(d.get("file").getAsString()).working);textures.put(e.getKey(),tex);
            }
            trace=buffer(bytes(131072*(Boolean.getBoolean("cmi.r6.trace")?192:64)));traceTexture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,traceTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32UI,trace);
        }
        JsonObject binding(int i){return manifest.getAsJsonObject("ssbo").getAsJsonObject(Integer.toString(i));}
        int id(int i){return storage.get(binding(i).get("file").getAsString()).working;}
        void reset(){for(var s:storage.values()){GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,s.master);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,s.working);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,s.originalBytes);}GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,trace);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);}
        void bindings(int program){
            for(var e:manifest.getAsJsonObject("ssbo").entrySet()){var d=e.getValue().getAsJsonObject();int i=Integer.parseInt(e.getKey()),id=storage.get(d.get("file").getAsString()).working;long start=d.get("start").getAsLong(),size=d.get("size").getAsLong();if(size==0)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,id);else GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,id,start,size);}
            for(var e:manifest.getAsJsonObject("textures").entrySet()){var d=e.getValue().getAsJsonObject();int unit=manifest.getAsJsonObject("uniforms").getAsJsonObject(e.getKey()).get("value").getAsInt();GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(d.get("target").getAsInt(),textures.get(e.getKey()));}
            for(var e:manifest.getAsJsonObject("images").entrySet()){var d=e.getValue().getAsJsonObject();GL42.glBindImageTexture(d.get("unit").getAsInt(),textures.get(e.getKey()),0,false,0,d.get("access").getAsInt(),d.get("format").getAsInt());}
            GL42.glBindImageTexture(7,traceTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);packed.configure(program,generation,1);
        }
        void uniforms(int p){for(var e:manifest.getAsJsonObject("uniforms").entrySet()){
            int at=GL20.glGetUniformLocation(p,e.getKey());if(at<0)continue;var d=e.getValue().getAsJsonObject();int type=d.get("type").getAsInt();JsonElement element=d.has("value_bits")?d.get("value_bits"):d.get("value");JsonArray values=element.isJsonArray()?element.getAsJsonArray():new JsonArray();if(!element.isJsonArray())values.add(element);
            int n=values.size();boolean floats=type==GL11.GL_FLOAT||type>=GL20.GL_FLOAT_VEC2&&type<=GL20.GL_FLOAT_VEC4||type>=GL20.GL_FLOAT_MAT2&&type<=GL20.GL_FLOAT_MAT4;
            if(floats){float[] a=new float[n];for(int i=0;i<n;i++)a[i]=values.get(i).getAsFloat();switch(type){case GL20.GL_FLOAT_MAT2->GL41.glProgramUniformMatrix2fv(p,at,false,a);case GL20.GL_FLOAT_MAT3->GL41.glProgramUniformMatrix3fv(p,at,false,a);case GL20.GL_FLOAT_MAT4->GL41.glProgramUniformMatrix4fv(p,at,false,a);default->{switch(n){case 1->GL41.glProgramUniform1fv(p,at,a);case 2->GL41.glProgramUniform2fv(p,at,a);case 3->GL41.glProgramUniform3fv(p,at,a);case 4->GL41.glProgramUniform4fv(p,at,a);default->throw new IllegalStateException("uniform lanes");}}}}
            else{int[] a=new int[n];for(int i=0;i<n;i++)a[i]=values.get(i).getAsInt();boolean unsigned=type==GL11.GL_UNSIGNED_INT||type>=GL30.GL_UNSIGNED_INT_VEC2&&type<=GL30.GL_UNSIGNED_INT_VEC4;if(unsigned)switch(n){case 1->GL41.glProgramUniform1uiv(p,at,a);case 2->GL41.glProgramUniform2uiv(p,at,a);case 3->GL41.glProgramUniform3uiv(p,at,a);case 4->GL41.glProgramUniform4uiv(p,at,a);}else switch(n){case 1->GL41.glProgramUniform1iv(p,at,a);case 2->GL41.glProgramUniform2iv(p,at,a);case 3->GL41.glProgramUniform3iv(p,at,a);case 4->GL41.glProgramUniform4iv(p,at,a);}}
        }}
        double[] run(int p,boolean useGrid){reset();bindings(p);GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long begin=System.nanoTime();if(useGrid){order.prepare(models,manifest.getAsJsonObject("uniforms").getAsJsonObject("uStarfallEnabled").getAsJsonArray("value_bits").get(0).getAsInt()!=0);packed.prepare(input,emit,grid,live,generation,1);}bindings(p);ui(p,"uModelPass",1);dispatch(p,Math.max(1,(live+63)/64));ui(p,"uModelPass",2);dispatch(p,(models+63)/64);double cpu=(System.nanoTime()-begin)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double gpu=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;return new double[]{gpu,cpu};}
        void save(Path out)throws Exception{
            Files.write(out.resolve("output.bin"),array(read(id(1),Math.min(live+1024,manifest.getAsJsonObject("uniforms").getAsJsonObject("uCapacity").getAsJsonArray("value_bits").get(0).getAsInt())*64)));Files.write(out.resolve("counter.bin"),array(read(id(3),16)));Files.write(out.resolve("queries.bin"),array(read(trace,131072*64)));
            ByteBuffer sets=bytes(16*1024*1024);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,trace);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,131072*64L,sets);Files.write(out.resolve("adjacency.bin"),array(sets));
        }
        static byte[] array(ByteBuffer b){byte[] out=new byte[b.remaining()];b.get(out);return out;}
        public void close(){for(var s:storage.values()){GL15.glDeleteBuffers(s.master);GL15.glDeleteBuffers(s.working);}textures.values().forEach(GL11::glDeleteTextures);GL11.glDeleteTextures(traceTexture);GL15.glDeleteBuffers(trace);GL15.glDeleteQueries(timer);packed.close();order.close();}
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("samples.csv")),"fresh output required");check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(32,32,"R6 real update replay",0,0);check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        String variant=System.getProperty("cmi.r6.variant","prune");boolean trace=Boolean.getBoolean("cmi.r6.trace"),aa=Boolean.getBoolean("cmi.r6.aa");String candidates=System.getProperty("cmi.r6.candidates");Path captures=Path.of(System.getProperty("cmi.r6.captures"));int samples=Integer.getInteger("cmi.r6.samples",64);
        try(var raw=Files.newBufferedWriter(out.resolve("samples.csv"))){raw.write("capture,scope,variant,pair,side,gpu_ms,cpu_submit_ms\n");
            if(trace)saveOverlapDirections(out);
            List<Path> manifests;try(var paths=Files.walk(captures)){manifests=paths.filter(p->p.getFileName().toString().equals("manifest.json")).sorted().toList();}
            for(Path manifest:manifests){String name=manifest.getParent().getFileName().toString();System.setProperty("cmi.efficiency.sourceRoot",Path.of(candidates,"production"+(trace?"-trace":"")).toString());int baseline=compile("update.comp");System.setProperty("cmi.efficiency.sourceRoot",Path.of(candidates,(aa?"production":variant)+(trace?"-trace":"")).toString());int candidate=compile("update.comp");
                try(var c=new Capture(manifest,variant,Integer.getInteger("cmi.r6.capacity",-1))){c.uniforms(baseline);c.uniforms(candidate);c.reset();c.bindings(candidate);c.order.verify(c.id(24),c.models,c.manifest.getAsJsonObject("uniforms").getAsJsonObject("uStarfallEnabled").getAsJsonArray("value_bits").get(0).getAsInt()!=0);c.run(baseline,false);Path directory=out.resolve(name);Files.createDirectories(directory);
                    if(trace){Path b=directory.resolve("production");Files.createDirectories(b);c.save(b);c.run(candidate,!aa);Path a=directory.resolve(variant);Files.createDirectories(a);c.save(a);}
                    for(int warm=0;warm<12;warm++)c.run(warm%2==0?baseline:candidate,warm%2!=0&&!aa);
                    for(int pair=0;pair<samples;pair++)for(int order=0;order<2;order++){int side=(pair+order)&1;double[] result=c.run(side==0?baseline:candidate,side!=0&&!aa);raw.write(name+",update-dispatch+candidate-preparation,"+(aa?"AA":variant)+","+pair+","+side+","+result[0]+","+result[1]+"\n");}raw.flush();check(GL11.glGetError()==0,"GL state");System.out.println("Replayed "+name+" "+variant);
                }finally{deleteProgram(baseline);deleteProgram(candidate);}
            }Files.writeString(out.resolve("complete.txt"),"scope=update-dispatch+candidate-preparation; NOT full chain or physics qualification\nchecks="+checks+"\n");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
