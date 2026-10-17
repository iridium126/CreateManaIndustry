import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec2;
import com.iridium126.createmanaindustry.client.hex.pattern.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;
import at.petrak.hexcasting.client.render.RenderLib;
import at.petrak.hexcasting.client.ClientTickCounter;

/** The same immutable descriptors/programs/allocator used by world, shadow and UI consumers. */
public class HexSharedGpuValidation {
    static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    static String source(String n){return ParticleShaderSource.loadParticle(n,path->{try(var in=HexSharedGpuValidation.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(path,e);}});}
    static List<Vec2> positions(String signature){var pattern=at.petrak.hexcasting.api.casting.math.HexPattern.fromAngleString(signature,at.petrak.hexcasting.api.casting.math.HexDir.values()[0],false);return pattern.toLines(1,Vec2.ZERO);}
    static void parity()throws Exception {
        try(var gpu=new HexGpuBackend(16L<<20,HexSharedGpuValidation::source)){
            for(String sig:new String[]{"","qwe","wwwww","qqqqqqqq","qweadqwead"})for(boolean ui:new boolean[]{false,true}){
                var raw=positions(sig);var pattern=at.petrak.hexcasting.api.casting.math.HexPattern.fromAngleString(sig,at.petrak.hexcasting.api.casting.math.HexDir.values()[0],false);
                var dup=RenderLib.findDupIndices(pattern.positions());var style=ui?new HexShape.Style(10,2.5f,.1f,.9f,.2f,.8f):HexShape.Style.ORBIT;
                var shape=new HexShape(raw,dup,style,true,ui);
                for(int seed:new int[]{1,1234567890,-1234567890})for(int ticks:new int[]{0,100,10000000}){
                    ClientTickCounter.ticksInGame=ticks;ClientTickCounter.partialTicks=0;
                    var expected=RenderLib.makeZappy(raw,dup,style.hops(),style.variance(),style.speed(),style.flow(),style.readability(),style.lastLength(),seed);
                    check(shape.points==expected.size(),"native topology count "+sig+" UI="+ui+" got="+shape.points+" expected="+expected.size());
                    var d=new HexGpuBackend.Draw();d.shape=shape;d.seed=seed;d.ticks=ticks;
                    try(var batch=gpu.prepare(List.of(d))){check(batch!=null,"geometry allocation");int start=batch.pointBase(0);var out=BufferUtils.createByteBuffer((shape.points+shape.nodes)*16);GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.pointBuffer());GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)start*16,out);
                        for(int i=0;i<expected.size();i++)check(Math.hypot(expected.get(i).x-out.getFloat(i*16),expected.get(i).y-out.getFloat(i*16+4))<.002,"native noise mismatch "+sig+" / "+seed+" / "+ticks+" point="+i);
                        long count=gpu.generated();try(var again=gpu.prepare(List.of(d))){check(again!=null&&gpu.generated()==count,"same generation regenerated for another pass");}
                    }
                    check(gpu.allocatedBytes()<=(16L<<20),"budget exceeded");GL11.glFinish();
                }
            }
        }
    }
    static void tailAndVersions(){
        try(var gpu=new HexGpuBackend(16L<<20,HexSharedGpuValidation::source)){
            var original=new ArrayList<>(List.of(new Vec2(0,0),new Vec2(1,0),new Vec2(0,0),new Vec2(.5f,.5f)));
            var duplicates=Set.of(0,2);var style=new HexShape.Style(10,2.5f,.1f,.9f,.2f,1);
            var cached=new HexShape(original,duplicates,style,false);
            var d=new HexGpuBackend.Draw();d.shape=cached;d.seed=12;d.ticks=123;d.overrideTail=true;
            int oldBase;
            ClientTickCounter.ticksInGame=123;ClientTickCounter.partialTicks=0;
            try(var old=gpu.prepare(List.of(d))){check(old!=null,"held pass");oldBase=old.pointBase(0);
                d.tailX=1.7f;d.tailY=-.7f;original.set(3,new Vec2(d.tailX,d.tailY));
                long uploads=gpu.uploadedBytes();try(var current=gpu.prepare(List.of(d))){check(current!=null&&current.pointBase(0)!=oldBase,"in-flight geometry overwritten by another time/endpoint");
                    check(gpu.uploadedBytes()==uploads,"mouse endpoint rebuilt/uploaded immutable descriptors");
                    var expected=RenderLib.makeZappy(original,duplicates,10,2.5f,.1f,.9f,.2f,1,12);
                    var out=BufferUtils.createByteBuffer((cached.points+cached.nodes)*16);GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.pointBuffer());GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,current.pointBase(0)*16L,out);
                    for(int i=0;i<expected.size();i++)check(Math.hypot(expected.get(i).x-out.getFloat(i*16),expected.get(i).y-out.getFloat(i*16+4))<.002,"WIP tail/readability mismatch "+i);
                }
            }
        }
    }
    static void pixels(){
        int fbo=GL30.glGenFramebuffers(),tex=GL11.glGenTextures();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_CULL_FACE);
        try(var gpu=new HexGpuBackend(16L<<20,HexSharedGpuValidation::source)){
            var d=new HexGpuBackend.Draw();d.shape=new HexShape(List.of(new Vec2(-.5f,0),new Vec2(.5f,0)),null,new HexShape.Style(1,0,0,0,0,1),true);d.outer=.1f;d.inner=.05f;d.dotRadius=.1f;d.outerTail=d.outerHead=0xffff0000;d.innerTail=d.innerHead=0xff00ff00;d.dotColor=0xff0000ff;d.innerZ=0;
            try(var batch=gpu.prepare(List.of(d))){check(batch!=null,"pixel batch");GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);gpu.drawPlain(batch,new Matrix4f(),new Matrix4f());
                var p=BufferUtils.createByteBuffer(4);GL11.glReadPixels(32,32,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,p);check((p.get(1)&255)>200,"inner line pixel");GL11.glReadPixels(16,32,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,p);check((p.get(2)&255)>200,"native six-sided node pixel");
            }
            GL11.glFinish();check(gpu.allocatedBytes()<(16L<<20),"pixel budget");
        }finally{GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(fbo);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(tex);}
    }
    static int graphics(String vertex,String fragment){int p=GL20.glCreateProgram();var ids=new ArrayList<Integer>();try{for(int i=0;i<2;i++){int shader=GL20.glCreateShader(i==0?GL20.GL_VERTEX_SHADER:GL20.GL_FRAGMENT_SHADER);ids.add(shader);GL20.glShaderSource(shader,i==0?vertex:fragment);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));GL20.glAttachShader(p,shader);}GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));return p;}finally{for(int shader:ids)GL20.glDeleteShader(shader);}}
    static final class NativeDrawer implements at.petrak.hexcasting.client.render.VCDrawHelper,AutoCloseable {
        final com.mojang.blaze3d.vertex.ByteBufferBuilder storage=new com.mojang.blaze3d.vertex.ByteBufferBuilder(65536);
        final int vao=GL30.glGenVertexArrays(),vbo=GL15.glGenBuffers(),program=graphics("#version 450 core\nlayout(location=0)in vec3 position;layout(location=1)in vec4 color;out vec4 rgba;void main(){gl_Position=vec4(position,1);rgba=color;}","#version 450 core\nin vec4 rgba;out vec4 fragment;void main(){fragment=rgba;}");
        float z;long uploaded,draws;
        NativeDrawer(){at.petrak.hexcasting.api.mod.HexConfig.setClient((at.petrak.hexcasting.api.mod.HexConfig.ClientConfigAccess)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{at.petrak.hexcasting.api.mod.HexConfig.ClientConfigAccess.class},(o,m,a)->m.getReturnType()==boolean.class?false:null));}
        public com.mojang.blaze3d.vertex.VertexConsumer vcSetupAndSupply(com.mojang.blaze3d.vertex.VertexFormat.Mode mode){return new com.mojang.blaze3d.vertex.BufferBuilder(storage,mode,com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);}
        public void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc,int color,Vec2 point,Vec2 uv,Matrix4f matrix){vc.addVertex(matrix,point.x,point.y,z).setColor(color);}
        public void vcEndDrawer(com.mojang.blaze3d.vertex.VertexConsumer vc){try(var mesh=((com.mojang.blaze3d.vertex.BufferBuilder)vc).buildOrThrow()){
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);var bytes=mesh.vertexBuffer();uploaded+=bytes.remaining();draws++;
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,bytes,GL15.GL_STREAM_DRAW);GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,16,0);GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,4,GL11.GL_UNSIGNED_BYTE,true,16,12);
            GL11.glDrawArrays(mesh.drawState().mode().asGLMode,0,mesh.drawState().vertexCount());
        }storage.clear();}
        void render(HexGpuBackend.Draw d,List<Vec2> raw,boolean dots){var style=d.shape.style;var noisy=RenderLib.makeZappy(raw,d.shape.duplicates,style.hops(),style.variance(),style.speed(),style.flow(),style.readability(),style.lastLength(),d.seed);
            z=d.outerZ;RenderLib.drawLineSeq(d.transform,noisy,d.outer*2,d.outerTail,d.outerHead,this);z=d.innerZ;RenderLib.drawLineSeq(d.transform,noisy,d.inner*2,d.innerTail,d.innerHead,this);
            if(dots){z=1;for(int i=0;i<d.shape.nodes;i++)RenderLib.drawSpot(d.transform,raw.get(i),d.dotRadius,d.dotColor,this);}}
        public void close(){storage.close();GL15.glDeleteBuffers(vbo);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteVertexArray(vao);GL20.glDeleteProgram(program);}
    }
    static ByteBuffer image(){var b=BufferUtils.createByteBuffer(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);return b;}
    static void nativePixels(){
        int fbo=GL30.glGenFramebuffers(),texture=GL11.glGenTextures();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_CULL_FACE);
        try(var gpu=new HexGpuBackend(16L<<20,HexSharedGpuValidation::source);var nativeDraw=new NativeDrawer()){
            for(boolean translucent:new boolean[]{false,true})for(boolean overlap:new boolean[]{false,true})for(boolean gradient:new boolean[]{false,true})for(boolean dots:new boolean[]{false,true}){
                net.minecraft.client.gui.screens.Screen.CONTROL=gradient;
                var raw=overlap?List.of(new Vec2(-.6f,-.2f),new Vec2(.2f,-.2f),new Vec2(.4f,.4f),new Vec2(-.6f,-.2f),new Vec2(.2f,-.2f)):List.of(new Vec2(-.6f,-.2f),new Vec2(.2f,-.2f),new Vec2(.4f,.4f));var d=new HexGpuBackend.Draw();d.shape=new HexShape(raw,overlap?Set.of(0,1,3,4):Set.of(),new HexShape.Style(5,.65f,.1f,.2f,0,1),true,dots);d.seed=12;d.ticks=100;
                d.outer=.07f;d.inner=.03f;d.innerZ=.01f;d.dotRadius=.07f;d.gradient=gradient;d.outerTail=0xffbc1432;d.outerHead=0xff13cd56;d.innerTail=0xffee8a99;d.innerHead=0xff8affbb;d.dotColor=0xffabcdef;
                if(translucent){GL11.glEnable(GL11.GL_BLEND);GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);d.outerTail&=0xc8ffffff;d.outerHead&=0xc8ffffff;d.innerTail&=0xc8ffffff;d.innerHead&=0xc8ffffff;d.dotColor&=0xc8ffffff;}else GL11.glDisable(GL11.GL_BLEND);
                ClientTickCounter.ticksInGame=100;ClientTickCounter.partialTicks=0;GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);nativeDraw.render(d,raw,dots);var expected=image();
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);try(var batch=gpu.prepare(List.of(d))){check(batch!=null,"golden image batch");gpu.drawPlain(batch,new Matrix4f(),new Matrix4f());}var actual=image();int bad=0;
                for(int i=0;i<expected.capacity();i+=4){int max=0;for(int c=0;c<4;c++)max=Math.max(max,Math.abs((expected.get(i+c)&255)-(actual.get(i+c)&255)));if(max>2)bad++;}
                check(bad<=12,"native caps/gradient/node image mismatch: "+bad+" pixels / gradient="+gradient+" dots="+dots+" alpha="+translucent+" overlap="+overlap);GL11.glFinish();
            }
        }finally{GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(fbo);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(texture);}
    }
    static void iris(){
        String pack="#version 120\nvarying vec4 probe;void main(){gl_Position=ftransform();probe=gl_Color;}";
        String block="#version 400 compatibility\n"+source("chunks/hex_shared_vertex.glsl")+"\nvoid main(){cmiHexVertex();}";
        var patched=new com.iridium126.createmanaindustry.client.particles.shaderpack.ParticleVertexInjector().patchStrict(pack,block,"cmi_hex_validation",Map.of());
        var stages=net.irisshaders.iris.pipeline.transform.TransformPatcher.patchVanilla("cmi_hex_validation",patched,null,null,null,"#version 120\nvarying vec4 probe;void main(){gl_FragColor=probe;}",net.irisshaders.iris.gl.blending.AlphaTests.NON_ZERO_ALPHA,false,true,new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,false,false),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
        int program=graphics(stages.get(net.irisshaders.iris.pipeline.transform.PatchShaderType.VERTEX),stages.get(net.irisshaders.iris.pipeline.transform.PatchShaderType.FRAGMENT));GL20.glDeleteProgram(program);
    }
    static double percentile(double[] values,double p){var copy=values.clone();Arrays.sort(copy);return copy[Math.min(copy.length-1,(int)(copy.length*p))];}
    static void benchmark(boolean longPatterns)throws Exception {
        var rows=new ArrayList<String>();var rawRows=new ArrayList<String>();rows.add("pass,count,run,path,cpu_p50_ms,cpu_p95_ms,thread_p95_ms,allocation_p95_bytes,gpu_p50_ms,gpu_p95_ms,uploaded_bytes,draw_submissions,allocated_bytes");rawRows.add("pass,count,run,path,sample,cpu_ms,thread_ms,allocation_bytes,gpu_ms");
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        int fbo=GL30.glGenFramebuffers(),texture=GL11.glGenTextures();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_CULL_FACE);
        net.minecraft.client.gui.screens.Screen.CONTROL=false;
        try(var nativeDraw=new NativeDrawer()){
            for(String pass:List.of("world","shadow","preview","spell_ui"))for(int n:longPatterns?new int[]{100}:new int[]{10,100,1000})try(var gpu=new HexGpuBackend(16L<<20,HexSharedGpuValidation::source)){
                var bare=positions(longPatterns?"qweadqwead".repeat(5):"qweadqwead");boolean ui=pass.equals("spell_ui");var shape=new HexShape(bare,Set.of(),ui?new HexShape.Style(10,2.5f,.1f,.2f,.2f,1):HexShape.Style.ORBIT,true,ui);
                var draws=new ArrayList<HexGpuBackend.Draw>();for(int i=0;i<n;i++){var d=new HexGpuBackend.Draw();d.shape=shape;d.seed=i;d.transform.scale(.1f);d.dotRadius=ui?2:0;d.outer=ui?2.5f:.175f;d.inner=ui?1:.07f;d.innerZ=0;draws.add(d);}
                var boundary=new HexPassState();
                for(int run=1;run<=3;run++)for(String path:List.of("native","gpu")){
                    long deadline=System.nanoTime()+1_000_000_000L;int warm=0;while(warm++<30||System.nanoTime()<deadline){int ticks=warm;ClientTickCounter.ticksInGame=ticks;for(var d:draws)d.ticks=ticks;
                        if(path.equals("native"))for(var d:draws)nativeDraw.render(d,bare,ui);else try(var state=boundary.capture();var batch=gpu.prepare(draws)){check(batch!=null,"100/simple/default budget warm fallback "+pass+" / "+n);gpu.drawPlain(batch,new Matrix4f(),new Matrix4f());}GL11.glFinish();}
                    int count=120;var cpu=new double[count];var thread=new double[count];var bytes=new double[count];var gt=new double[count];var queries=new int[count];long beforeUpload=gpu.transferredBytes()+gpu.uploadedBytes(),beforeNativeUpload=nativeDraw.uploaded,beforeDraws=nativeDraw.draws;
                    try{for(int i=0;i<count;i++){
                        int ticks=10000+run*1000+i;ClientTickCounter.ticksInGame=ticks;for(var d:draws)d.ticks=ticks;
                        queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);long a=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),t=bean.getCurrentThreadCpuTime(),start=System.nanoTime();
                        if(path.equals("native"))for(var d:draws)nativeDraw.render(d,bare,ui);else try(var state=boundary.capture();var batch=gpu.prepare(draws)){check(batch!=null,"default-budget steady fallback "+pass+" / "+n);gpu.drawPlain(batch,new Matrix4f(),new Matrix4f());}
                        cpu[i]=(System.nanoTime()-start)/1e6;thread[i]=(bean.getCurrentThreadCpuTime()-t)/1e6;bytes[i]=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-a;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);GL11.glFinish(); // Test-only drain outside the measured submission interval.
                    }for(int i=0;i<count;i++){gt[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;rawRows.add(pass+","+n+","+run+","+path+","+i+","+cpu[i]+","+thread[i]+","+bytes[i]+","+gt[i]);}}
                    finally{for(int q:queries)if(q!=0)GL15.glDeleteQueries(q);}
                    String row=pass+","+n+","+run+","+path+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+percentile(thread,.95)+","+percentile(bytes,.95)+","+percentile(gt,.5)+","+percentile(gt,.95)+","+(path.equals("gpu")?gpu.transferredBytes()+gpu.uploadedBytes()-beforeUpload:nativeDraw.uploaded-beforeNativeUpload)+","+(path.equals("gpu")?count:nativeDraw.draws-beforeDraws)+","+gpu.allocatedBytes();rows.add(row);System.out.println(row);
                }
            }
        }finally{GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(fbo);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(texture);}
        String stem=longPatterns?System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/hex-shared-long-benchmark":System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/hex-shared-benchmark";Files.write(Path.of(stem+".csv"),rows);Files.write(Path.of(stem+"-samples.csv"),rawRows);
    }
    static void budget(){
        try(var gpu=new HexGpuBackend(1L<<20,HexSharedGpuValidation::source)){
            var p=new ArrayList<Vec2>();for(int i=0;i<1500;i++)p.add(new Vec2(i,i%2));var d=new HexGpuBackend.Draw();d.shape=new HexShape(p,null,new HexShape.Style(10,2.5f,.1f,.2f,0,1),true);
            check(gpu.prepare(List.of(d))==null,"unsupported/over-budget shape did not fall back");check(gpu.allocatedBytes()<=(1L<<20),"overflow allocation escaped budget");
        }
        try(var gpu=new HexGpuBackend(1L<<20,HexSharedGpuValidation::source)){
            var shape=new HexShape(positions("qwead".repeat(40)),Set.of(),HexShape.Style.ORBIT,true,false);check(shape.vertices<HexGpuBackend.VERTEX_STRIDE,"budget fixture exceeded vertex capacity");var draws=new ArrayList<HexGpuBackend.Draw>();
            for(int i=0;i<100;i++){var d=new HexGpuBackend.Draw();d.shape=shape;d.seed=i;draws.add(d);}check(gpu.prepare(draws)==null,"valid geometry escaped hard 1 MiB budget");check(gpu.allocatedBytes()<=(1L<<20),"hard allocation cap");
            var d=draws.get(0);try(var recovered=gpu.prepare(List.of(d))){check(recovered!=null,"failed batch left bank/resources pinned");}
        }
        try(var gpu=new HexGpuBackend(16L<<20,HexSharedGpuValidation::source)){
            var shape=new HexShape(positions("qwe"),Set.of(),HexShape.Style.ORBIT,true,false);var d=new HexGpuBackend.Draw();d.shape=shape;var held=new ArrayList<HexGpuBackend.Prepared>();
            try{for(int i=0;i<16;i++){var batch=gpu.prepare(List.of(d));check(batch!=null,"held bank "+i);held.add(batch);}check(gpu.prepare(List.of(d))==null,"bank backpressure waited or reused a held bank");}finally{held.forEach(HexGpuBackend.Prepared::close);}
            GL11.glFinish();for(int n:new int[]{1,63,64,65,4096})try(var batch=gpu.prepare(Collections.nCopies(n,d))){check(batch!=null&&batch.count()==n,"instance boundary "+n);}check(gpu.prepare(List.of())==null,"empty batch");
        }
        var accounting=new HexGpuBudget(1024);check(accounting.reserve(1000)&&!accounting.reserve(25),"hard budget");accounting.release(1000);check(accounting.used()==0,"budget leak");
    }
    static void state()throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();GL20.glUseProgram(0);GL30.glBindVertexArray(0);var boundary=new HexPassState();int buffer=GL15.glGenBuffers(),two=GL11.glGenTextures(),tbo=GL11.glGenTextures(),fbo=GL30.glGenFramebuffers();
        int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT),high=GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS)-1;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,(long)alignment*4,GL15.GL_DYNAMIC_COPY);GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,2,buffer,alignment,alignment);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+high);GL11.glBindTexture(GL11.GL_TEXTURE_2D,two);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,tbo);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,buffer);
        GL11.glViewport(7,9,17,19);com.mojang.blaze3d.systems.RenderSystem.enableScissor(2,4,13,15);com.mojang.blaze3d.systems.RenderSystem.colorMask(true,false,true,false);GL11.glColorMask(true,false,true,false);GL30.glColorMaski(1,false,true,false,true);
        GL30.glEnablei(GL11.GL_BLEND,1);GL40.glBlendFuncSeparatei(1,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_ZERO,GL11.GL_ONE);GL40.glBlendEquationSeparatei(1,GL14.GL_FUNC_REVERSE_SUBTRACT,GL14.GL_FUNC_ADD);GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,5);
        try(var gpu=new HexGpuBackend(16L<<20,HexSharedGpuValidation::source)){
            try(var saved=boundary.capture()){
                GL11.glViewport(1,2,3,4);boolean rejected=false;try{boundary.capture();}catch(IllegalStateException expected){rejected=true;}
                check(rejected,"nested capture overwrote its active boundary");
                var d=new HexGpuBackend.Draw();d.shape=new HexShape(positions("qwe"),Set.of(),HexShape.Style.ORBIT,true,false);try(var batch=gpu.prepare(List.of(d))){check(batch!=null,"state batch");GL20.glUseProgram(gpu.plainProgram());batch.bind(gpu.plainProgram());}
                HexPassState.nativeState();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glViewport(0,0,1,1);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glColorMask(false,true,false,true);GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,3);GL40.glBlendFuncSeparatei(1,GL11.GL_ZERO,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ZERO);GL40.glBlendEquationSeparatei(1,GL14.GL_FUNC_ADD,GL14.GL_FUNC_SUBTRACT);
                GL13.glActiveTexture(GL13.GL_TEXTURE0+high);GL11.glBindTexture(GL11.GL_TEXTURE_2D,0);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,0);
            }
            check(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==0&&GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)==0,"framebuffer restoration");int[] v=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,v);check(Arrays.equals(v,new int[]{7,9,17,19}),"viewport restoration");GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,v);check(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)&&Arrays.equals(v,new int[]{2,4,13,15}),"scissor restoration");
            check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE0+high&&GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==two&&GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER)==tbo,"high sampler/TBO restoration");
            check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,2)==buffer&&GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,2)==alignment&&GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,2)==alignment,"nonzero SSBO range restoration");
            check(GL11.glGetInteger(GL40.GL_PATCH_VERTICES)==5&&GL30.glIsEnabledi(GL11.GL_BLEND,1)&&GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB,1)==GL11.GL_ONE&&GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_RGB,1)==GL14.GL_FUNC_REVERSE_SUBTRACT,"indexed blend/tessellation restoration");var mask=BufferUtils.createByteBuffer(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);check(mask.get(0)!=0&&mask.get(1)==0&&mask.get(2)!=0&&mask.get(3)==0,"global color mask restoration");GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,1,mask);check(mask.get(0)==0&&mask.get(1)!=0&&mask.get(2)==0&&mask.get(3)!=0,"indexed color mask restoration");check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==0&&GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)==0,"program/VAO restoration");
        }finally{com.mojang.blaze3d.systems.RenderSystem.disableScissor();com.mojang.blaze3d.systems.RenderSystem.colorMask(true,true,true,true);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,0);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,0);GL15.glDeleteBuffers(buffer);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(two);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(tbo);GL30.glDeleteFramebuffers(fbo);GL13.glActiveTexture(GL13.GL_TEXTURE0);GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,3);}
    }
    public static void main(String[] args)throws Exception {
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long w=GLFW.glfwCreateWindow(64,64,"Hex shared validation",0,0);check(w!=0,"GL context");try{GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));parity();tailAndVersions();pixels();nativePixels();budget();state();if(Arrays.asList(args).contains("--iris"))iris();if(Arrays.asList(args).contains("--benchmark"))benchmark(false);if(Arrays.asList(args).contains("--long-benchmark"))benchmark(true);check(GL11.glGetError()==0,"GL error");System.out.println("Shared Hex GPU: "+checks+" assertions passed");}finally{GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();}
    }
}
