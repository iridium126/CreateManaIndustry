import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;
import com.iridium126.createmanaindustry.client.particles.packages.*;

/** Real-driver light probe and immutable atlas validation; never enables gameplay takeover. */
public class PackageLightGpuValidation {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static int buffer(ByteBuffer data){int b=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data,GL15.GL_DYNAMIC_DRAW);return b;}
    static ByteBuffer read(int b,int bytes){var result=BufferUtils.createByteBuffer(bytes);GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);return result;}
    static PackageLightCache.Snapshot snapshot(long revision,int seed) {
        byte[] bytes=new byte[4096];for(int i=0;i<4096;i++){int shift=(i&1)*4;bytes[i/2]|=(byte)(((i+seed)&15)<<shift);bytes[2048+i/2]|=(byte)((((i>>>4)+seed)&15)<<shift);}
        return new PackageLightCache.Snapshot(revision,bytes);
    }
    static int program;static int[] locations;
    static void sample(PackageLightGpu.View view,int[] buffers,int count,float partial) {
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL20.glUseProgram(program);
        for(int i=0;i<4;i++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,new int[]{0,1,6,7}[i],buffers[i]);view.bind(8);
        GL30.glUniform1ui(locations[0],count);GL30.glUniform1ui(locations[1],count);GL30.glUniform1ui(locations[2],view.tableSize());GL30.glUniform1ui(locations[3],view.dataWordOffset());GL20.glUniform1f(locations[4],partial);
        if(count>0)GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    static void validate(boolean benchmark) throws Exception {
        try(var atlas=new PackageLightGpu(32)) {
            var snapshots=new HashMap<PackageCollisionCache.Section,PackageLightCache.Snapshot>();
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){var s=new PackageCollisionCache.Section(x,y,z);var data=snapshot(1,x+3*y+7*z);snapshots.put(s,data);check(atlas.offer(s,data),"admit light section");}
            check(atlas.pump(4096+27*16+4,Long.MAX_VALUE),"partial upload");check(atlas.uploadedBytes()==4096+27*16+4,"upload byte budget includes headers");check(atlas.pump(32*4096,Long.MAX_VALUE),"finish upload");check(!atlas.pump(32*4096,Long.MAX_VALUE),"reuse unchanged atlas");
            for(var s:snapshots.keySet())check(atlas.covered(s,1),"coverage");
            for(int n:new int[]{0,1,63,64,65,10000,65536,131072}) {
                var pool=BufferUtils.createByteBuffer((n+1)*64);var admission=BufferUtils.createByteBuffer((n+1)*32);var attachment=BufferUtils.createByteBuffer((n+1)*32);var out=BufferUtils.createByteBuffer((n+1)*4);
                for(int i=0;i<n;i++) {
                    int slot=n-1-i,p=slot*64,flags=(i&1)==0?0:3;if(i%13==0)flags|=4;
                    admission.putInt(i*32+16,i%19==0?0:i%17==0?n+1:slot+1).putInt(i*32+20,flags);
                    float x=i%23==0?1000:(i%3)*15.75f-15.875f,y=(i%3)*15.25f-15.75f,z=(i%5)*7.5f-15.5f;
                    pool.putFloat(p,x+.625f).putFloat(p+4,y+.5f).putFloat(p+8,z+.5f).putFloat(p+16,x).putFloat(p+20,y).putFloat(p+24,z).putInt(p+44,0x003000b0|(flags<<24));attachment.putFloat(i*32+28,.85f);
                }
                out.putInt(n*4,0x713579bd);int[] buffers={buffer(pool),buffer(admission),buffer(attachment),buffer(out)};
                try {
                    for(float partial:new float[]{0,.5f,1}) {
                        try(var view=atlas.view()){sample(view,buffers,n,partial);}var result=read(buffers[3],(n+1)*4);check(result.getInt(n*4)==0x713579bd,"output tail sentinel");
                        for(int i=0;i<n;i++) {
                            int expected=0,slot=admission.getInt(i*32+16),flags=admission.getInt(i*32+20);
                            if(slot>0 && slot<=n && (flags&4)==0) {
                                int p=(slot-1)*64,x=(int)Math.floor(pool.getFloat(p+16)*(1-partial)+pool.getFloat(p)*partial),y=(int)Math.floor(pool.getFloat(p+20)*(1-partial)+pool.getFloat(p+4)*partial+((flags&1)==0?.85f:0)),z=(int)Math.floor(pool.getFloat(p+24)*(1-partial)+pool.getFloat(p+8)*partial);
                                var data=snapshots.get(new PackageCollisionCache.Section(x>>4,y>>4,z>>4));expected=0x003000b0;if(data!=null){int l=data.light((x&15)|((z&15)<<4)|((y&15)<<8));expected=((l&15)<<4)|((l>>>4)<<20);}
                            }
                            check((result.getInt(i*4)&0x00ffffff)==expected,"native/GPU light probe "+n+"/"+i+"/"+partial);
                        }
                    }
                    check(read(buffers[0],(n+1)*64).equals(pool),"committed pool modified");check(read(buffers[1],(n+1)*32).equals(admission),"identity/admission modified");
                    if(benchmark && n>=10000)benchmark(atlas,buffers,n);
                }finally{for(int b:buffers)GL15.glDeleteBuffers(b);}
            }
        }
        try(var atlas=new PackageLightGpu(2)) {
            var zero=new PackageCollisionCache.Section(0,0,0);var views=new ArrayList<PackageLightGpu.View>();
            for(int i=1;i<=4;i++){atlas.offer(zero,snapshot(i,i));check(atlas.pump(8192,Long.MAX_VALUE),"independent bank");views.add(atlas.view());}
            atlas.offer(zero,snapshot(5,5));check(!atlas.pump(8192,Long.MAX_VALUE),"leased ring overwritten");check(atlas.skipped()==1,"ring exhaustion statistic");
            // Old banks must retain their data across new revisions, not merely their handles.
            var pool=BufferUtils.createByteBuffer(64);var admission=BufferUtils.createByteBuffer(32);admission.putInt(16,1).putInt(20,1);int[] buffers={buffer(pool),buffer(admission),buffer(BufferUtils.createByteBuffer(32)),buffer(BufferUtils.createByteBuffer(4))};
            try {
                for(int i=0;i<4;i++){sample(views.get(i),buffers,1,0);check((read(buffers[3],4).getInt(0)&0xffffff)==(((i+1)<<4)|((i+1)<<20)),"immutable bank contents");}
                for(var view:views)view.close();GL11.glFinish();check(atlas.pump(8192,Long.MAX_VALUE),"ring resume");atlas.invalidate(zero,6);check(!atlas.covered(zero,5),"stale coverage");
                int x=1;while((PackageLightGpu.hash(x,0,0)&3)!=0)x++;var collider=new PackageCollisionCache.Section(x,0,0);check(atlas.offer(collider,snapshot(1,9)),"hash collision admission");atlas.pump(8192,Long.MAX_VALUE);
                pool.putFloat(0,x*16+.1f).putFloat(16,x*16+.1f);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffers[0]);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);
                try(var view=atlas.view()){sample(view,buffers,1,0);check((read(buffers[3],4).getInt(0)&0xffffff)==((9<<4)|(9<<20)),"invalid origin truncates hash lookup");}
                check(!atlas.offer(new PackageCollisionCache.Section(99,0,0),snapshot(1,0)),"capacity overflow");check(!atlas.offer(zero,snapshot(5,0)),"stale version revived");
            }finally{for(var view:views)view.close();for(int b:buffers)GL15.glDeleteBuffers(b);}
        }
    }
    static String source(String name) {
        return ParticleShaderSource.loadParticle(name,path->{try(var stream=PackagePoolGpu.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)){if(stream==null)throw new IllegalArgumentException(path);return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
    }
    static int compute(String name) {
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),result=0;
        try{GL20.glShaderSource(shader,"#version 450 core\n"+source(name));GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));result=GL20.glCreateProgram();GL20.glAttachShader(result,shader);GL20.glLinkProgram(result);check(GL20.glGetProgrami(result,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(result));return result;}
        catch(RuntimeException|AssertionError failure){if(result!=0)GL20.glDeleteProgram(result);throw failure;}finally{GL20.glDeleteShader(shader);}
    }
    static int requestProgram,requestCountLocation;
    static boolean captureFeedback(PackageLightGpu atlas,PackageLightFeedbackGpu feedback,int[] buffers,int n) {
        feedback.begin();try(var view=atlas.view()){sample(view,buffers,n,1);}
        GL20.glUseProgram(requestProgram);GL30.glUniform1ui(requestCountLocation,n);GL43.glDispatchCompute(16,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);return feedback.capture();
    }
    static int[] feedbackBodies(int n,java.util.function.IntFunction<PackageCollisionCache.Section> sections) {
        var pool=BufferUtils.createByteBuffer(n*64);var admission=BufferUtils.createByteBuffer(n*32);
        for(int i=0;i<n;i++){var s=sections.apply(i);int p=(n-1-i)*64;
            pool.putFloat(p,s.x()*16+.25f).putFloat(p+4,s.y()*16+.25f).putFloat(p+8,s.z()*16+.25f);
            pool.putFloat(p+16,s.x()*16+.25f).putFloat(p+20,s.y()*16+.25f).putFloat(p+24,s.z()*16+.25f).putInt(p+44,0x003000b0);
            admission.putInt(i*32+16,n-i).putInt(i*32+20,1);}
        return new int[]{buffer(pool),buffer(admission),buffer(BufferUtils.createByteBuffer(n*32)),buffer(BufferUtils.createByteBuffer(n*4))};
    }
    static void feedback(boolean benchmark) throws Exception {
        requestProgram=compute("packages/light_requests.comp");requestCountLocation=GL20.glGetUniformLocation(requestProgram,"uCount");
        GL41.glProgramUniform1ui(program,GL20.glGetUniformLocation(program,"uFeedback"),1);
        try {
            for(int n:new int[]{1,63,64,65,131072})try(var atlas=new PackageLightGpu(2);var feedback=new PackageLightFeedbackGpu(n,100+n)) {
                var section=new PackageCollisionCache.Section(-3,4000,5);int[] buffers=feedbackBodies(n,i->section);atlas.pump(0,Long.MAX_VALUE);
                try {
                    check(captureFeedback(atlas,feedback,buffers,n),"feedback first snapshot");GL11.glFinish();var received=new ArrayList<PackageCollisionCache.Section>();feedback.poll(received::add);
                    check(received.equals(List.of(section)),"dense section dedup or tail failed");atlas.reserve(section,1);atlas.pump(8192,Long.MAX_VALUE);
                    captureFeedback(atlas,feedback,buffers,n);GL11.glFinish();received.clear();feedback.poll(received::add);check(received.isEmpty(),"pending key repeatedly requested");
                    atlas.offer(section,snapshot(2,7));atlas.pump(8192,Long.MAX_VALUE);captureFeedback(atlas,feedback,buffers,n);GL11.glFinish();feedback.poll(received::add);
                    var confirmed=read(buffers[3],n*4);for(int i=0;i<n;i++)check(confirmed.getInt(i*4)==(0x80000000|(7<<4)|(7<<20)),"confirmed light marker");
                    atlas.invalidate(section,3);atlas.pump(8192,Long.MAX_VALUE);captureFeedback(atlas,feedback,buffers,n);GL11.glFinish();feedback.poll(received::add);
                    check(read(buffers[3],n*4).equals(confirmed),"invalidated data replaced last confirmed light with initial metadata");
                    atlas.offer(section,snapshot(3,11));atlas.pump(8192,Long.MAX_VALUE);captureFeedback(atlas,feedback,buffers,n);GL11.glFinish();feedback.poll(received::add);
                    for(int i=0;i<n;i++)check(readOnce(buffers[3],n,i)==(0x80000000|(11<<4)|(11<<20)),"corrected light marker");
                }finally{for(int b:buffers)GL15.glDeleteBuffers(b);}
            }
            // Every missing key must eventually be represented, including hash collisions
            // and >256 outputs. Reserving accepted keys removes them from the next competition.
            int n=1024;try(var atlas=new PackageLightGpu(n);var feedback=new PackageLightFeedbackGpu(n,200)) {
                int[] buffers=feedbackBodies(n,i->new PackageCollisionCache.Section(200+i,0,0));atlas.pump(0,Long.MAX_VALUE);var received=new HashSet<PackageCollisionCache.Section>();
                try {
                    for(int attempt=0;attempt<12 && received.size()<n;attempt++) {
                        captureFeedback(atlas,feedback,buffers,n);GL11.glFinish();int before=received.size();
                        feedback.poll(s->{check(s.x()>=200 && s.x()<200+n && s.y()==0 && s.z()==0,"corrupt overflow section");check(received.add(s),"duplicate pending request");check(atlas.reserve(s,1),"pending capacity");});
                        check(received.size()>before,"hash/output overflow made no progress");atlas.pump(65536,Long.MAX_VALUE);
                    }
                    check(received.size()==n,"hash collision or capped output silently lost a section");check(feedback.overflow()>0,"overflow fixture did not exceed output capacity");
                }finally{for(int b:buffers)GL15.glDeleteBuffers(b);}
            }
            // The final valid invocation shares a group with 63 out-of-range lanes;
            // the preceding group contains only invalid/hidden candidates.
            try(var atlas=new PackageLightGpu(1);var feedback=new PackageLightFeedbackGpu(65,250)) {
                atlas.pump(0,Long.MAX_VALUE);var section=new PackageCollisionCache.Section(2,0,0);int[] buffers=feedbackBodies(65,i->section);
                try {
                    var admission=read(buffers[1],65*32);for(int i=0;i<64;i++)admission.putInt(i*32+16,i%2==0?0:66).putInt(i*32+20,5);
                    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffers[1]);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,admission);
                    captureFeedback(atlas,feedback,buffers,65);GL11.glFinish();var received=new ArrayList<PackageCollisionCache.Section>();feedback.poll(received::add);
                    check(received.equals(List.of(section)),"mixed invalid/tail lanes lost the valid request");
                    captureFeedback(atlas,feedback,buffers,0);GL11.glFinish();received.clear();feedback.poll(received::add);check(received.isEmpty(),"empty feedback retained old requests");
                }finally{for(int b:buffers)GL15.glDeleteBuffers(b);}
            }
            // Four snapshots retain different poses while later gathers overwrite the source.
            try(var atlas=new PackageLightGpu(1);var feedback=new PackageLightFeedbackGpu(1,300)) {
                atlas.pump(0,Long.MAX_VALUE);var received=new ArrayList<PackageCollisionCache.Section>();int[] buffers=feedbackBodies(1,i->new PackageCollisionCache.Section(0,0,0));
                try {
                    for(int i=0;i<5;i++){var pool=BufferUtils.createByteBuffer(64);pool.putFloat(0,i*16+.25f).putFloat(16,i*16+.25f);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffers[0]);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);check(captureFeedback(atlas,feedback,buffers,1)==(i<4),"light ring overwrote unfinished slot");}
                    GL11.glFinish();feedback.poll(received::add);check(received.equals(List.of(new PackageCollisionCache.Section(0,0,0),new PackageCollisionCache.Section(1,0,0),new PackageCollisionCache.Section(2,0,0),new PackageCollisionCache.Section(3,0,0))),"ring snapshots changed after capture");
                    check(feedback.skipped()==1 && feedback.pending()==0,"ring accounting");captureFeedback(atlas,feedback,buffers,1);GL11.glFinish();feedback.poll(received::add);check(received.getLast().x()==4,"full-ring request did not retry");
                }finally{for(int b:buffers)GL15.glDeleteBuffers(b);}
            }
            if(benchmark)feedbackBenchmark();
        }finally{GL41.glProgramUniform1ui(program,GL20.glGetUniformLocation(program,"uFeedback"),0);GL20.glDeleteProgram(requestProgram);}
    }
    static ByteBuffer correctionRead;
    static int readOnce(int buffer,int n,int i){if(i==0)correctionRead=read(buffer,n*4);return correctionRead.getInt(i*4);}
    static void feedbackBenchmark() throws Exception {
        var result=new ArrayList<String>();var samples=new ArrayList<String>();result.add("count,scenario,run,gpu_p50_ms,gpu_p95_ms,submit_p50_ms,submit_p95_ms");samples.add("count,scenario,run,sample,gpu_ms,submit_ms");
        for(int n:new int[]{10000,65536,131072})for(String scenario:List.of("known","dense_missing","distributed_missing")) {
            try(var atlas=new PackageLightGpu(32);var feedback=new PackageLightFeedbackGpu(n,1000+n)) {
                int[] buffers=feedbackBodies(n,i->new PackageCollisionCache.Section(scenario.equals("distributed_missing")?i%27:0,0,0));
                if(scenario.equals("known"))atlas.offer(new PackageCollisionCache.Section(0,0,0),snapshot(1,7));atlas.pump(65536,Long.MAX_VALUE);
                int query=GL15.glGenQueries();try {
                    for(int run=1;run<=3;run++) {
                        long end=System.nanoTime()+1_000_000_000L;int warm=0;do{captureFeedback(atlas,feedback,buffers,n);GL11.glFinish();feedback.poll(s->{});warm++;}while(warm<30 || System.nanoTime()<end);
                        double[] gpu=new double[120],cpu=new double[120];for(int i=0;i<120;i++) {
                            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long started=System.nanoTime();captureFeedback(atlas,feedback,buffers,n);cpu[i]=(System.nanoTime()-started)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gpu[i]=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;feedback.poll(s->{});
                            samples.add(n+","+scenario+","+run+","+i+","+gpu[i]+","+cpu[i]);
                        }
                        String row=n+","+scenario+","+run+","+percentile(gpu,.5)+","+percentile(gpu,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95);result.add(row);System.out.println(row);
                    }
                }finally{GL15.glDeleteQueries(query);for(int b:buffers)GL15.glDeleteBuffers(b);}
            }
        }
        Files.write(Path.of("build/package-light-feedback.csv"),result);Files.write(Path.of("build/package-light-feedback-samples.csv"),samples);
    }
    static final List<String> rows=new ArrayList<>(),raw=new ArrayList<>();
    static double percentile(double[] values,double fraction){var sorted=values.clone();Arrays.sort(sorted);return sorted[(int)Math.ceil(sorted.length*fraction)-1];}
    static void benchmark(PackageLightGpu atlas,int[] buffers,int count) {
        int query=GL15.glGenQueries();try(var view=atlas.view()) {
            for(int run=1;run<=3;run++) {
                long end=System.nanoTime()+1_000_000_000L;int warm=0;do{sample(view,buffers,count,(warm++%100)*.01f);}while(warm<30 || System.nanoTime()<end);GL11.glFinish();
                double[] gpu=new double[120],cpu=new double[120];for(int i=0;i<120;i++) {
                    GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long begin=System.nanoTime();sample(view,buffers,count,(i%100)*.01f);cpu[i]=(System.nanoTime()-begin)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gpu[i]=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;
                    raw.add(count+","+run+","+i+","+gpu[i]+","+cpu[i]);
                }
                String row=count+","+run+","+percentile(gpu,.5)+","+percentile(gpu,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95);rows.add(row);System.out.println(row);
            }
        }finally{GL15.glDeleteQueries(query);}
    }
    public static void main(String[] args) throws Exception {
        var callback=GLFWErrorCallback.createPrint(System.err);callback.set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Package light validation",0,0);check(window!=0,"context");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            String source=ParticleShaderSource.loadParticle("packages/light_sample.comp",path->{try(var stream=PackagePoolGpu.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)){if(stream==null)throw new IllegalArgumentException(path);return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
            if(Arrays.asList(args).contains("--feedback-global"))source="#define CMI_LIGHT_GLOBAL_REQUESTS\n"+source;
            int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);try{GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));}finally{GL20.glDeleteShader(shader);}
            locations=Arrays.stream(new String[]{"uCount","uCapacity","uLightTableSize","uLightDataOffset","uPartialTick"}).mapToInt(name->GL20.glGetUniformLocation(program,name)).toArray();
            rows.add("count,run,gpu_p50_ms,gpu_p95_ms,submit_p50_ms,submit_p95_ms");raw.add("count,run,sample,gpu_ms,submit_ms");boolean benchmark=Arrays.asList(args).contains("--benchmark");validate(benchmark);feedback(Arrays.asList(args).contains("--feedback-benchmark"));
            if(benchmark){Files.write(Path.of("build/package-light-sampling.csv"),rows);Files.write(Path.of("build/package-light-sampling-samples.csv"),raw);}
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error");System.out.println("Package light GPU: "+checks+" assertions passed");
        }finally{if(program!=0)GL20.glDeleteProgram(program);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
