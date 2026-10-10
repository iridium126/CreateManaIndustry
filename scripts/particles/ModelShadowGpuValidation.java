import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDrawCulling;

/** Actual driver validation of independent MODEL shadow culling. No Minecraft world is changed. */
public class ModelShadowGpuValidation {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static String source(String name){return ParticleShaderSource.loadParticle(name,path->{try(var in=ModelShadowGpuValidation.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)){if(in==null)throw new IllegalStateException(path);return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}});}
    static ByteBuffer bytes(int n){return BufferUtils.createByteBuffer(n);}
    static int buffer(ByteBuffer data){int id=GL15.glGenBuffers();put(id,data);return id;}
    static void put(int id,ByteBuffer data){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data,GL15.GL_DYNAMIC_DRAW);}
    static ByteBuffer read(int id,int length){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);var out=bytes(length);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,out);return out;}
    static ByteBuffer headers(float material,float size,boolean carrier,boolean storm){var b=bytes(ParticleBuffers.VEC4_PER_EMITTER*16);b.putFloat(7*16,material).putFloat(5*16+8,size).putFloat(5*16+12,size).putFloat(6*16,1).putFloat(17*16+8,carrier?1:0).putFloat(18*16,storm?1:0);return b;}
    static void particle(ByteBuffer b,int i,float x,float y,float z,float multiplier,float hp){int p=i*64;b.putFloat(p,x).putFloat(p+4,y).putFloat(p+8,z).putFloat(p+12,multiplier).putFloat(p+48,1).putFloat(p+52,1).putFloat(p+56,.123f).putInt(p+60,0);if(hp<=0)b.putFloat(p+52,hp);}
    static void prepare(ModelShadowGpu gpu,int pool,int emit,int count,PackageDrawCulling cull){gpu.prepare(pool,emit,count,cull,0,0,0,.35f,.6f,1,0,new float[16],new float[16],new float[4]);}
    static int models(ModelShadowGpu gpu){return read(gpu.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4);}
    static ByteBuffer sentinel(int n){var b=bytes(n);for(int i=0;i<n;i++)b.put(i,(byte)(i*31+17));return b;}
    static void guardedOutputs(ModelShadowGpu gpu){put(gpu.instances(),sentinel((gpu.capacity()+ParticleBuffers.CARRIER_CAP+1)*8+64));put(gpu.commands(),sentinel(156+64));}
    static void outputGuards(ModelShadowGpu gpu){
        int end=(gpu.capacity()+ParticleBuffers.CARRIER_CAP+1)*8;
        var list=read(gpu.instances(),end+64);var cmd=read(gpu.commands(),220);
        for(int i=end;i<end+64;i++)check(list.get(i)==(byte)(i*31+17),"instance buffer sentinel overwritten");
        for(int i=152;i<220;i++)check(cmd.get(i)==(byte)(i*31+17),"command buffer sentinel overwritten");
    }
    static int effectiveLimit(){return (int)Math.min(Math.min(4_000_000L,GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE)/64L),GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)*64L);}
    static void validation(){
        System.out.println("MODEL effective maximum capacity: "+effectiveLimit());
        for(int n:new int[]{0,1,63,64,65,131072,effectiveLimit()})try(var gpu=new ModelShadowGpu(Math.max(1,n),ModelShadowGpuValidation::source)){
            guardedOutputs(gpu);
            var data=bytes(Math.max(1,n)*64);for(int i=0;i<n;i++)particle(data,i,i%8-4,2,i/8%8-4,1,1);
            int pool=buffer(data),emit=buffer(headers(2,.3f,true,false)),count=buffer(bytes(16).putInt(0,n)),unrelated=buffer(sentinel(4096));
            try{
                var cull=new PackageDrawCulling();cull.clear();var input=read(pool,data.capacity());var main=read(unrelated,4096);var exact=read(count,16);var emitter=read(emit,ParticleBuffers.VEC4_PER_EMITTER*16);
                // Foreign main sort, indirect and carrier ranges remain bound during shadow preparation.
                for(int binding=5;binding<8;binding++)GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,binding,unrelated,0,4096);
                prepare(gpu,pool,emit,count,cull);
                check(models(gpu)==n,"workgroup/max-capacity MODEL count");var commands=read(gpu.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4);
                check(commands.getInt(ParticleBuffers.IDX_CNT_GHOST*4)==n,"ghost not equal to body count");check(commands.getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==Math.min(n,ParticleBuffers.CARRIER_CAP),"carrier clamp");
                var instances=read(gpu.instances(),(Math.max(1,n)+ParticleBuffers.CARRIER_CAP+1)*8);var seen=new BitSet();
                for(int row=0;row<n;row++){int payload=instances.getInt(row*8+4);int slot=payload>>>2;check((payload&3)==0&&slot<n&&!seen.get(slot),"invalid/duplicate shadow instance");seen.set(slot);}
                check(instances.getInt((Math.max(1,n)+ParticleBuffers.CARRIER_CAP)*8)==n,"metadata count wrong generation");check(read(pool,data.capacity()).equals(input)&&read(unrelated,4096).equals(main)&&read(count,16).equals(exact)&&read(emit,ParticleBuffers.VEC4_PER_EMITTER*16).equals(emitter),"shadow modified source or main data");
                cull.reject();prepare(gpu,pool,emit,count,cull);check(models(gpu)==0,"CullEverything retained MODEL");outputGuards(gpu);
                cull.clear();put(count,bytes(16).putInt(0,n==0?0:n-1));prepare(gpu,pool,emit,count,cull);
                check(models(gpu)==Math.max(0,n-1),"metadata/list reused previous GPU live count");outputGuards(gpu);
            }finally{for(int id:new int[]{pool,emit,count,unrelated})GL15.glDeleteBuffers(id);}
        }
        try(var gpu=new ModelShadowGpu(4,ModelShadowGpuValidation::source)){
            var data=bytes(4*64);particle(data,0,0,0,5,1,1);particle(data,1,50,0,5,1,1);particle(data,2,150,0,0,5,1);particle(data,3,0,0,5,1,1);
            int pool=buffer(data),emit=buffer(headers(2,.3f,false,false)),count=buffer(bytes(16).putInt(0,4));
            try{
                var c=new PackageDrawCulling();c.clear();prepare(gpu,pool,emit,count,c);check(models(gpu)==3,"MODEL entity distance/large size policy");
                c.set(new float[][]{{1,0,0,-10}},1);prepare(gpu,pool,emit,count,c);check(models(gpu)==1,"shadow planes did not replace main camera planes");
                c.safe=10;c.distance=200;prepare(gpu,pool,emit,count,c);check(models(gpu)==3,"SafeZone union not preserved");c.distance=2;prepare(gpu,pool,emit,count,c);check(models(gpu)==0,"distance box must constrain safe zone");
                c.clear();c.set(new float[][]{{1,0,0,10},{-1,0,0,10},{0,1,0,10},{0,-1,0,10},{0,0,1,-1},{0,0,-1,10}},6);prepare(gpu,pool,emit,count,c);check(models(gpu)==2,"six-plane MODEL shadow frustum");
                var planes=new float[13][4];for(int i=0;i<13;i++)planes[i][3]=1000;planes[12][0]=1;planes[12][3]=-10;c.clear();c.set(planes,13);prepare(gpu,pool,emit,count,c);check(models(gpu)==1,"thirteenth plane ignored");
                put(emit,headers(1,.3f,true,false));c.clear();prepare(gpu,pool,emit,count,c);check(models(gpu)==0,"sprite casts MODEL shadow");
                put(emit,headers(2,.3f,true,true));prepare(gpu,pool,emit,count,c);check(models(gpu)==2,"storm identity used as size or header carrier");check(read(gpu.commands(),156).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==0,"storm inactive wave carried item");
                float[] wave=new float[16],target=new float[16],tier=new float[4];wave[1]=1;wave[2]=-1;wave[3]=1;target[3]=1;tier[0]=1;
                gpu.prepare(pool,emit,count,c,0,0,0,.35f,.6f,1,0,wave,target,tier);
                check(read(gpu.commands(),156).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==2,"storm active wave carrier list");
                particle(data,0,0,0,5,1,-1);put(pool,data);gpu.prepare(pool,emit,count,c,0,0,0,.35f,.6f,1,0,wave,target,tier);
                check(read(gpu.commands(),156).getInt(ParticleBuffers.IDX_CNT_CARRIER*4)==1,"storm corpse retained held item");
                put(emit,headers(2,.3f,true,false));particle(data,0,0,0,5,1,-1);put(pool,data);prepare(gpu,pool,emit,count,c);check(models(gpu)==3,"corpse size semantics diverged");
            }finally{for(int id:new int[]{pool,emit,count})GL15.glDeleteBuffers(id);}
        }
    }
    static void sizeBoundaries(){
        try(var gpu=new ModelShadowGpu(1,ModelShadowGpuValidation::source)){
            var data=bytes(64);var h=headers(2,.297f,false,false);
            int pool=buffer(data),emit=buffer(h),count=buffer(bytes(16).putInt(0,1));
            try{
                var c=new PackageDrawCulling();c.clear();
                float scale=2*.297f/ParticlePrograms.modelAboveFeet();float range=.6f*scale*64;
                particle(data,0,range-.001f,0,0,1,1);put(pool,data);prepare(gpu,pool,emit,count,c);check(models(gpu)==1,"entity distance inside");
                particle(data,0,range+.001f,0,0,1,1);put(pool,data);prepare(gpu,pool,emit,count,c);check(models(gpu)==0,"entity distance outside");
                h.putFloat(5*16+8,.01f).putFloat(5*16+12,1).putFloat(6*16,2);put(emit,h);
                particle(data,0,30,0,0,1,1);data.putFloat(48,.5f);put(pool,data);prepare(gpu,pool,emit,count,c);check(models(gpu)==1,"life easing size inside");
                data.putFloat(48,.1f);put(pool,data);prepare(gpu,pool,emit,count,c);check(models(gpu)==0,"micro MODEL entity distance");
                put(emit,headers(2,.297f,false,false));particle(data,0,0,0,0,1,1);put(pool,data);
                c.set(new float[][]{{1,0,0,-.6f}},1);prepare(gpu,pool,emit,count,c);check(models(gpu)==1,"native Allay width plus inflate(.5)");
                c.planes[3]=-.7f;prepare(gpu,pool,emit,count,c);check(models(gpu)==0,"fixed package width incorrectly used for MODEL");
            }finally{for(int id:new int[]{pool,emit,count})GL15.glDeleteBuffers(id);}
        }
    }
    static void stateBoundary(){
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        var state=new ParticleComputeState();int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        int foreign=buffer(bytes(alignment+64)),vao=GL30.glGenVertexArrays(),pbo=buffer(bytes(64));GL30.glBindVertexArray(vao);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,15,foreign,alignment,64);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,foreign);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,foreign);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,pbo);GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,17);GL13.glActiveTexture(GL13.GL_TEXTURE18);
        state.capture();check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE0,"lazy compute initialization uses foreign texture unit");GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,15,0);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,0);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,0);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0);GL30.glBindVertexArray(0);state.restore();
        check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,15)==foreign&&GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,15)==alignment&&GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,15)==64,"compute state lost SSBO range");
        check(GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING)==foreign&&GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER)==foreign,"compute state lost indirect/copy binding");
        check(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)==pbo&&GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH)==17,"compute state lost unpack state");check(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)==vao&&GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE18,"compute state lost VAO/active texture");
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0);GL15.glDeleteBuffers(foreign);GL15.glDeleteBuffers(pbo);GL30.glDeleteVertexArrays(vao);
    }
    static int vertexProgram(String vertex){
        int shader=GL20.glCreateShader(GL20.GL_VERTEX_SHADER),program=GL20.glCreateProgram();
        try{GL20.glShaderSource(shader,vertex);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,"MODEL vertex compile: "+GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(program,shader);GL30.glTransformFeedbackVaryings(program,new String[]{"gl_Position"},GL30.GL_INTERLEAVED_ATTRIBS);GL20.glLinkProgram(program);
            check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,"MODEL vertex link: "+GL20.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException|AssertionError error){GL20.glDeleteProgram(program);throw error;}finally{GL20.glDeleteShader(shader);}
    }
    static void mergedVertices()throws Exception {
        var compiler=new com.iridium126.createmanaindustry.client.particles.shaderpack.ShaderPackProgramCompiler(ModelShadowGpuValidation::source);
        var build=compiler.getClass().getDeclaredMethod("buildMergedSource",boolean.class);build.setAccessible(true);
        var injector=new com.iridium126.createmanaindustry.client.particles.shaderpack.ParticleVertexInjector();
        String pack="#version 120\nvarying vec4 probeColor;void main(){gl_Position=gl_Vertex;probeColor=gl_Color;}";
        String patched=injector.patch(pack,(String)build.invoke(compiler,false),"MODEL shadow validation");
        check(patched.contains("cmi_ShadowPass"),"MODEL injection silently failed");
        var nativeStages=net.irisshaders.iris.pipeline.transform.TransformPatcher.patchVanilla("cmi_model_validation",patched,null,null,null,
                "#version 120\nvarying vec4 probeColor;void main(){gl_FragColor=probeColor;}",net.irisshaders.iris.gl.blending.AlphaTests.ONE_TENTH_ALPHA,false,true,
                new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
        Files.writeString(Path.of("build/model-shadow-merged.vert"),patched);
        Files.writeString(Path.of("build/model-shadow-iris.vert"),nativeStages.get(net.irisshaders.iris.pipeline.transform.PatchShaderType.VERTEX));
        int linked=GL20.glCreateProgram();var shaders=new ArrayList<Integer>();
        try{for(var kind:new net.irisshaders.iris.pipeline.transform.PatchShaderType[]{net.irisshaders.iris.pipeline.transform.PatchShaderType.VERTEX,net.irisshaders.iris.pipeline.transform.PatchShaderType.FRAGMENT}){
            int shader=GL20.glCreateShader(kind==net.irisshaders.iris.pipeline.transform.PatchShaderType.VERTEX?GL20.GL_VERTEX_SHADER:GL20.GL_FRAGMENT_SHADER);shaders.add(shader);
            String stage=nativeStages.get(kind);check(stage!=null,"Iris missing MODEL stage");GL20.glShaderSource(shader,stage);GL20.glCompileShader(shader);
            check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,"Iris MODEL compile: "+GL20.glGetShaderInfoLog(shader));GL20.glAttachShader(linked,shader);
        }GL20.glLinkProgram(linked);check(GL20.glGetProgrami(linked,GL20.GL_LINK_STATUS)!=0,"Iris MODEL link: "+GL20.glGetProgramInfoLog(linked));}
        finally{for(int shader:shaders)GL20.glDeleteShader(shader);GL20.glDeleteProgram(linked);}
        int program=vertexProgram(patched),vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
        var particle=bytes(64);particle(particle,0,150,0,0,5,1);
        var geometry=bytes(ParticlePrograms.modelVertexFloats()*4);geometry.putFloat(4,16).putFloat(20,0).putFloat(24,0);
        int[] buffers={buffer(geometry),buffer(particle),buffer(headers(2,.3f,false,false)),buffer(bytes(16).putInt(8,1)),buffer(bytes(16))};
        int[] formats={GL30.GL_R32F,GL30.GL_RGBA32F,GL30.GL_RGBA32F,GL30.GL_RG32UI};int[] textures=new int[4];
        try{
            GL20.glUseProgram(program);String[] names={"cmi_Geo","cmi_Pool","cmi_Emitters","cmi_Sorted"};
            for(int i=0;i<4;i++){textures[i]=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE0+10+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,formats[i],buffers[i]);GL20.glUniform1i(GL20.glGetUniformLocation(program,names[i]),10+i);}
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"cmi_MetaSlot"),1);GL20.glUniform1f(GL20.glGetUniformLocation(program,"cmi_FadeDist"),10);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,buffers[4]);
            for(int shadow=0;shadow<2;shadow++){
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"cmi_ShadowPass"),shadow);GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_POINTS);GL31.glDrawArraysInstanced(GL11.GL_POINTS,0,1,1);GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                var out=read(buffers[4],16);check(shadow==0?out.getFloat(8)>1e8:Math.abs(out.getFloat(0)-150)<5&&Math.abs(out.getFloat(8))<5,"shadow fade bypass/main fade changed");
            }
            // Resource/source rebuild must produce both overlay variants with the same shadow flag.
            int overlay=vertexProgram(injector.patch(pack,(String)build.invoke(compiler,true),"MODEL overlay validation"));GL20.glDeleteProgram(overlay);
        }finally{GL20.glUseProgram(0);for(int id:buffers)GL15.glDeleteBuffers(id);for(int id:textures)GL11.glDeleteTextures(id);GL30.glDeleteVertexArrays(vao);GL20.glDeleteProgram(program);}
    }
    static double percentile(double[] input,double q){var copy=input.clone();Arrays.sort(copy);return copy[(int)Math.ceil(copy.length*q)-1];}
    static void benchmark()throws Exception {
        var raw=new ArrayList<String>();raw.add("mode,count,run,sample,gpu_ms,cpu_submit_ms");
        var boundary=new ParticleComputeState();
        var rows=new ArrayList<String>();rows.add("mode,capacity,count,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,extra_storage_bytes");
        for(int n:new int[]{10000,65536,131072,Math.min(2_000_000,effectiveLimit())})try(var gpu=new ModelShadowGpu(n,ModelShadowGpuValidation::source)){
            var data=bytes(n*64);for(int i=0;i<n;i++)particle(data,i,i%16-8,2,i/16%16-8,1,1);
            int pool=buffer(data),emit=buffer(headers(2,.3f,true,false)),count=buffer(bytes(16).putInt(0,n));var c=new PackageDrawCulling();c.clear();
            try{for(String mode:new String[]{"prepare","state_boundary"})for(int run=1;run<=3;run++){
                long deadline=System.nanoTime()+1_000_000_000L;int warm=0;while(warm++<30||System.nanoTime()<deadline){if(mode.equals("state_boundary"))boundary.capture();try{prepare(gpu,pool,emit,count,c);}finally{if(mode.equals("state_boundary"))boundary.restore();}GL11.glFinish();}
                int[] queries=new int[120];double[] gt=new double[120],ct=new double[120];
                try{for(int i=0;i<120;i++){queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);long start=System.nanoTime();if(mode.equals("state_boundary"))boundary.capture();try{prepare(gpu,pool,emit,count,c);}finally{if(mode.equals("state_boundary"))boundary.restore();}ct[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);}
                    for(int i=0;i<120;i++)gt[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;
                }finally{for(int id:queries)if(id!=0)GL15.glDeleteQueries(id);}
                check(models(gpu)==n,"benchmark incomplete work");for(int sample=0;sample<120;sample++)raw.add(mode+","+n+","+run+","+sample+","+gt[sample]+","+ct[sample]);String row=mode+","+n+","+n+","+run+","+percentile(gt,.5)+","+percentile(gt,.95)+","+percentile(ct,.5)+","+percentile(ct,.95)+","+gpu.bytes();rows.add(row);System.out.println(row);
            }}finally{for(int id:new int[]{pool,emit,count})GL15.glDeleteBuffers(id);}
        }
        Files.write(Path.of("build/model-shadow-benchmark.csv"),rows);Files.write(Path.of("build/model-shadow-benchmark-raw.csv"),raw);
    }
    public static void main(String[] args)throws Exception {
        var callback=GLFWErrorCallback.createPrint(System.err);callback.set();check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Model shadow validation",0,0);check(window!=0,"GL context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));validation();sizeBoundaries();stateBoundary();if(Arrays.asList(args).contains("--iris-transform"))mergedVertices();if(Arrays.asList(args).contains("--benchmark"))benchmark();check(GL11.glGetError()==GL11.GL_NO_ERROR,"shadow GL error");System.out.println("MODEL shadow GPU: "+checks+" assertions passed");}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
