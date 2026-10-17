import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Method;
import java.lang.invoke.*;
import java.net.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.particles.emitter.*;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniformCache;

/** Controlled production GPU path. Scene/world/network CPU work remains a client acceptance test. */
public final class ParticlePipelineBenchmark {
    static final Matrix4f IDENTITY=new Matrix4f();
    static final float[] MATRIX=IDENTITY.get(new float[16]);
    static final float[] FRUSTUM=new float[24];
    // Use the production primitive-key cache in both runtimes. HashMap<Integer,...>
    // would charge boxed GL names differently depending on the driver's name range.
    static final GpuUniformCache LOCATIONS=new GpuUniformCache();
    static int loc(int p,String n){return LOCATIONS.location(p,n);}
    static void ui(int p,String n,int v){GL41.glProgramUniform1ui(p,loc(p,n),v);}
    static void f(int p,String n,float v){GL41.glProgramUniform1f(p,loc(p,n),v);}
    static void v(int p,String n,float x,float y,float z){GL41.glProgramUniform3f(p,loc(p,n),x,y,z);}
    static String source(String name){return ParticleShaderSource.loadParticle(name.replaceFirst("^shaders/particles/",""),path->{
        try(var in=ParticlePipelineBenchmark.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)){
            var text=new String(Objects.requireNonNull(in,path).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            for(String phase:List.of("spawn","update"))text=text.replace("#pragma cmi_types "+phase,ParticleTypes.shaderHooks(phase));return text;
        }catch(Exception error){throw new IllegalStateException(path,error);}
    });}
    static int stage(String name,int kind){int s=GL20.glCreateShader(kind);GL20.glShaderSource(s,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(name));GL20.glCompileShader(s);
        if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(name+GL20.glGetShaderInfoLog(s));return s;}
    static int program(String name,String fragment){int p=GL20.glCreateProgram(),s=0,t=0;
        try{s=stage(name,fragment==null?GL43.GL_COMPUTE_SHADER:GL20.GL_VERTEX_SHADER);GL20.glAttachShader(p,s);if(fragment!=null){t=stage(fragment,GL20.GL_FRAGMENT_SHADER);GL20.glAttachShader(p,t);}GL20.glLinkProgram(p);
            if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(p));return p;
        }finally{if(s!=0){GL20.glDetachShader(p,s);GL20.glDeleteShader(s);}if(t!=0){GL20.glDetachShader(p,t);GL20.glDeleteShader(t);}}
    }
    static void barrier(){GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);}
    static int bound(int binding){return GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,binding);}
    static void upload(int id,ByteBuffer data){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);}
    static ByteBuffer read(int id,int bytes){barrier();var data=BufferUtils.createByteBuffer(bytes);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);return data;}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final class PathFixture implements AutoCloseable {
        final ParticleBuffers pool=new ParticleBuffers();final ParticlePrograms programs=new ParticlePrograms();
        final int count,alpha,texture;int previous=0;long generation;
        PathFixture(int count,boolean mixed)throws Exception {
            this.count=count;alpha=mixed?count/2:0;check(pool.init(Math.max(1024,count),4),"pool initialization");
            Method rebuild=ParticlePrograms.class.getDeclaredMethod("rebuildWithCompiler",java.util.function.ToIntFunction.class,java.util.function.BiFunction.class);rebuild.setAccessible(true);
            java.util.function.ToIntFunction<String> compute=n->program(n,null);java.util.function.BiFunction<String,String,Integer> graphics=ParticlePipelineBenchmark::program;
            check((boolean)rebuild.invoke(programs,compute,graphics),"program creation");programs.configureStatic(pool.capacity());
            for(int i=0;i<2;i++)pool.setEmitterHeader(i,EmitterSpec.builder().type(ParticleTypes.standard(i==0?ParticleTypes.Material.ADDITIVE:ParticleTypes.Material.ALPHA)).size(.006).gravity(0,0,0).life(10000,10000).build().packed());
            pool.uploadDirtyEmitters();pool.bindParticleRead(0);var data=BufferUtils.createByteBuffer(pool.capacity()*64);
            for(int i=0;i<count;i++){int b=i*64;data.putFloat(b,(i%128)/80f-.8f).putFloat(b+4,(i/128%128)/80f-.8f).putFloat(b+8,-.5f).putFloat(b+12,1);
                data.putFloat(b+32,1).putFloat(b+36,1).putFloat(b+40,1).putFloat(b+44,1).putFloat(b+52,10000).putInt(b+60,i<alpha?1:0);}
            upload(bound(0),data);pool.bindCounter(3,0);upload(bound(3),BufferUtils.createByteBuffer(16).putInt(0,count));
            texture=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,BufferUtils.createByteBuffer(4).putInt(0,-1));GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glViewport(0,0,128,128);GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        void prepare(int phase){GL20.glUseProgram(programs.prepareDispatch());ui(programs.prepareDispatch(),"uPhase",phase);ui(programs.prepareDispatch(),"uPackageStaged",0);GL43.glDispatchCompute(1,1,1);barrier();}
        void frame(){int slot=(previous+1)%ParticleBuffers.COUNTER_RING;generation++;pool.beginComputeFrame();pool.bindDispatchInputs(slot,previous);pool.bindPopulationRead(programs.prepareDispatch());pool.bindPopulationWrite(programs.prepareDispatch(),generation);prepare(0);
            GL20.glUseProgram(programs.reset());pool.bindPopulationWrite(programs.reset(),generation);pool.bindHit();GL43.glDispatchCompute(1,1,1);barrier();
            int update=programs.update();GL20.glUseProgram(update);pool.bindIdentities(update);pool.bindParticleRead(0);pool.bindParticleWrite(1);pool.bindEmitters(5);pool.bindCounter(3,slot);pool.bindPrevCounter(14,previous);pool.bindDamage();pool.bindGrid();pool.bindPlayers();pool.bindCorrections();pool.bindMemberMap();
            ui(update,"uKillEmit",-1);f(update,"uDt",0);GL41.glProgramUniform1i(update,loc(update,"uCollisionOn"),0);pool.dispatch(0);barrier();
            pool.bindDispatchInputs(slot,previous);prepare(1);int key=programs.keygen();GL20.glUseProgram(key);pool.bindPopulationWrite(key,generation);pool.bindParticleWrite(0);pool.bindEmitters(5);pool.bindCounter(3,slot);pool.bindIndirect(2);pool.bindOrderAdd();pool.bindOrderOpaque();pool.bindSort(7,pool.sortBuffer(0));pool.bindCarrierSink();
            ui(key,"uUpper",pool.capacity());ui(key,"uCarrierBase",pool.carrierBase());ui(key,"uCarrierLimit",pool.carrierCapacity());ui(key,"uPackageStaged",0);f(key,"uMaxDepth",128);f(key,"uDepthLogRange",7);v(key,"uCamPos",0,0,0);GL41.glProgramUniformMatrix4fv(key,loc(key,"uView"),false,MATRIX);GL41.glProgramUniform4fv(key,loc(key,"uFrustum"),FRUSTUM);pool.dispatch(1);barrier();
            pool.bindPopulationRead(programs.prepareDispatch(),true,generation);prepare(2);int input=pool.sortBuffer(0),output=pool.sortBuffer(1);
            for(int pass=0;pass<ParticleBuffers.RADIX_PASSES;pass++){
                pool.clearHist();GL20.glUseProgram(programs.radixHist());pool.bindSort(6,input);pool.bindHist();ui(programs.radixHist(),"uShift",0);pool.dispatch(2);barrier();
                GL20.glUseProgram(programs.radixScan());pool.bindHist();pool.bindOffsets();GL43.glDispatchCompute(1,1,1);barrier();
                GL20.glUseProgram(programs.radixScatter());pool.bindSort(6,input);pool.bindSort(7,output);pool.bindOffsets();ui(programs.radixScatter(),"uShift",0);pool.dispatch(2);barrier();int swap=input;input=output;output=swap;
            }
            GL20.glUseProgram(programs.capture());pool.bindCounter(3,slot);pool.bindParticleWrite(1);pool.bindEmitters(5);pool.bindHit();pool.bindIdentities(programs.capture());pool.bindSort(7,input);GL41.glProgramUniform1i(programs.capture(),loc(programs.capture(),"uMetaSlot"),pool.metaSlotIndex());GL43.glDispatchCompute(1,1,1);barrier();pool.swap();pool.commitPopulation(generation);
            draw(programs.render(),0,input);draw(programs.texturedRender(),2,input);previous=slot;pool.restoreCommittedFrame();
        }
        void draw(int program,int mode,int permutation){GL20.glUseProgram(program);pool.bindBillboardDrawInputs(mode,permutation);GL41.glProgramUniformMatrix4fv(program,loc(program,"ModelViewMat"),false,MATRIX);GL41.glProgramUniformMatrix4fv(program,loc(program,"ProjMat"),false,MATRIX);v(program,"uCamPos",0,0,0);v(program,"uCamRight",1,0,0);v(program,"uCamUp",0,1,0);f(program,"uFadeDist",100);f(program,"uGlow",1);f(program,"uAtlasCols",1);f(program,"uAtlasRows",1);GL41.glProgramUniform1i(program,loc(program,"uSprite"),1);GL41.glProgramUniform1i(program,loc(program,"uMode"),0);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,mode==0?GL11.GL_ONE:GL11.GL_ONE_MINUS_SRC_ALPHA);pool.drawIndirect(mode==0?0:5);}
        void validate(){pool.bindCounter(3,previous);check(read(bound(3),16).getInt(0)==count,"benchmark lost live work");pool.bindDrawIndirect();int commands=GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);var data=read(commands,ParticleBuffers.INDIRECT_UINTS*4);check(data.getInt(ParticleBuffers.IDX_CNT_ADD*4)==count-alpha&&data.getInt(ParticleBuffers.IDX_CNT_ALPHA*4)==alpha,"benchmark lost material draws");check(GL11.glGetError()==0,"benchmark GL error");}
        public void close(){GL20.glUseProgram(0);GL11.glDeleteTextures(texture);programs.delete();pool.free();LOCATIONS.clear();}
    }
    static double p(double[] values,double fraction){var sorted=values.clone();Arrays.sort(sorted);return sorted[Math.min(sorted.length-1,(int)Math.ceil(sorted.length*fraction)-1)];}
    record Calls(MethodHandle frame,MethodHandle validate,MethodHandle closeHandle) implements AutoCloseable {
        static Calls of(Class<?> type,int count,boolean mixed)throws Throwable {
            var constructor=type.getDeclaredConstructor(int.class,boolean.class);constructor.setAccessible(true);var target=constructor.newInstance(count,mixed);
            var lookup=MethodHandles.privateLookupIn(type,MethodHandles.lookup());
            return new Calls(lookup.findVirtual(type,"frame",MethodType.methodType(void.class)).bindTo(target),lookup.findVirtual(type,"validate",MethodType.methodType(void.class)).bindTo(target),lookup.findVirtual(type,"close",MethodType.methodType(void.class)).bindTo(target));
        }
        void run()throws Throwable {frame.invokeExact();}
        void check()throws Throwable {validate.invokeExact();}
        public void close(){try{closeHandle.invokeExact();}catch(Throwable failure){throw new IllegalStateException(failure);}}
    }
    static URLClassLoader baselineLoader(String directory)throws Exception {
        var paths=new ArrayList<URL>();paths.add(ParticlePipelineBenchmark.class.getProtectionDomain().getCodeSource().getLocation());
        for(String path:Files.readString(Path.of(directory,"classpath.txt")).trim().split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))paths.add(Path.of(path).toUri().toURL());
        return new URLClassLoader(paths.toArray(URL[]::new),ParticlePipelineBenchmark.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                synchronized(getClassLoadingLock(name)) {
                    if(name.startsWith("com.iridium126.createmanaindustry.")||name.startsWith("ParticlePipelineBenchmark")) {
                        Class<?> found=findLoadedClass(name);if(found==null)found=findClass(name);if(resolve)resolveClass(found);return found;
                    }
                    return super.loadClass(name,resolve);
                }
            }
            @Override public URL getResource(String name) {
                if(name.startsWith("assets/createmanaindustry/"))return findResource(name);
                return super.getResource(name);
            }
        };
    }
    static void compare(String directory,ArrayList<String> rows,ArrayList<String> raw,com.sun.management.ThreadMXBean bean)throws Throwable {
        rows.set(0,"scene,count,trial,variant,gpu_p50_ms,gpu_p95_ms,gpu_p99_ms,cpu_p50_ms,cpu_p95_ms,cpu_p99_ms,allocation_bytes_per_frame");
        raw.set(0,"scene,count,trial,variant,sample,gpu_ms,cpu_ms");
        try(var loader=baselineLoader(directory)) {
            Class<?> baseline=loader.loadClass("ParticlePipelineBenchmark$PathFixture");
            for(int n:new int[]{0,16384})for(boolean mixed:new boolean[]{false,true}) {
                if(n==0&&mixed)continue;String scene=n==0?"empty":mixed?"mixed":"stationary";
                try(var old=Calls.of(baseline,n,mixed);var current=Calls.of(PathFixture.class,n,mixed)) {
                    for(int jit=0;jit<20000;jit++){old.run();current.run();}GL11.glFinish();old.check();current.check();
                    for(int trial=0;trial<5;trial++)for(int offset=0;offset<2;offset++) {
                        boolean before=(trial+offset)%2==0;Calls fixture=before?old:current;String variant=before?"baseline":"current";
                        for(int warm=0;warm<100;warm++)fixture.run();GL11.glFinish();fixture.check();
                        double[] gpu=new double[40],cpu=new double[40];int timer=GL15.glGenQueries();
                        long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                        try{for(int i=0;i<40;i++){GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long start=System.nanoTime();fixture.run();cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gpu[i]=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;}}
                        finally{GL15.glDeleteQueries(timer);}allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;fixture.check();
                        // Formatting and CSV allocation are outside both CPU/allocator measurements.
                        for(int i=0;i<40;i++)raw.add(scene+","+n+","+trial+","+variant+","+i+","+gpu[i]+","+cpu[i]);
                        String row=scene+","+n+","+trial+","+variant+","+p(gpu,.5)+","+p(gpu,.95)+","+p(gpu,.99)+","+p(cpu,.5)+","+p(cpu,.95)+","+p(cpu,.99)+","+(allocated/40);rows.add(row);System.out.println(row);
                    }
                }
            }
        }
    }
    public static void main(String[] args)throws Throwable {
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(128,128,"CMI GPU pipeline benchmark",0,0);check(window!=0,"context");
        var rows=new ArrayList<String>();rows.add("scene,count,trial,gpu_p50_ms,gpu_p95_ms,gpu_p99_ms,cpu_p50_ms,cpu_p95_ms,cpu_p99_ms,allocation_bytes_per_frame");
        var raw=new ArrayList<String>();raw.add("scene,count,trial,sample,gpu_ms,cpu_ms");
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            String baseline=System.getProperty("cmi.pipeline.baseline");
            if(baseline!=null)compare(baseline,rows,raw,bean);
            else for(int n:new int[]{0,16384})for(boolean mixed:new boolean[]{false,true}){
                if(n==0&&mixed)continue;String scene=n==0?"empty":mixed?"mixed":"stationary";
                try(var fixture=new PathFixture(n,mixed)){
                    // Stabilize JVM compilation before the five timed trials (including the empty path).
                    for(int jit=0;jit<20000;jit++)fixture.frame();GL11.glFinish();fixture.validate();
                    for(int trial=0;trial<5;trial++){
                        for(int warm=0;warm<100;warm++)fixture.frame();GL11.glFinish();fixture.validate();
                        double[] gpu=new double[40],cpu=new double[40];int timer=GL15.glGenQueries();long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                        try{for(int i=0;i<40;i++){GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long start=System.nanoTime();fixture.frame();cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gpu[i]=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;raw.add(scene+","+n+","+trial+","+i+","+gpu[i]+","+cpu[i]);}}
                        finally{GL15.glDeleteQueries(timer);}allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;fixture.validate();
                        String row=scene+","+n+","+trial+","+p(gpu,.5)+","+p(gpu,.95)+","+p(gpu,.99)+","+p(cpu,.5)+","+p(cpu,.95)+","+p(cpu,.99)+","+(allocated/40);rows.add(row);System.out.println(row);
                    }
                }
            }
            Path output=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(output);Files.write(output.resolve("particle-pipeline.csv"),rows);Files.write(output.resolve("particle-pipeline-samples.csv"),raw);
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
