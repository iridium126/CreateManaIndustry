import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.packages.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;

/** Standalone archived three-index comparison. Never included in the mod JAR. */
public final class PackageGpuValidation {
    static boolean smoke;
    static Path directory=Path.of("build/package-index-comparison");
    static int checks;

    static String source(String name) {
        return ParticleShaderSource.loadParticle(name,path->{
            // Match ResourceManager's namespace-relative lookup in the processed runtime resources.
            try(var stream=PackagePoolGpu.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)) {
                if(stream==null)throw new IllegalArgumentException("Missing runtime resource: "+path);
                return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        });
    }

    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}

    static ByteBuffer bodies(int n){return BufferUtils.createByteBuffer(n*64);}

    static void body(ByteBuffer b,int i,float x,float y,float z,float mass){
        int p=i*64;b.putFloat(p,x).putFloat(p+4,y).putFloat(p+8,z).putFloat(p+12,mass);
        b.putFloat(p+32,.5f).putFloat(p+36,.5f).putFloat(p+40,.5f);
    }

    static ByteBuffer read(PackagePhysicsGpu gpu){
        ByteBuffer b=bodies(gpu.count());GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.stateBuffer());
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,b);return b;
    }

    static final PackageCollisionCache.Cell WORLD_AIR=new PackageCollisionCache.Cell(List.of(),.6f,0);

    static PackageCollisionCache.Cell shape(float height,float friction) {
        return new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,height,1)),friction,0);
    }

    static PackageCollisionCache.Snapshot snapshot(PackageCollisionCache.Source source) {
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);
        var section=new PackageCollisionCache.Section(0,0,0);cache.request(section);
        for(int i=0;i<3 && cache.snapshot(section)==null;i++)cache.tick(source,1);
        check(cache.snapshot(section)!=null,"immutable world fixture did not finish");return cache.snapshot(section);
    }

    static void uploadWorld(PackageCollisionGpu atlas) {
        for(int i=0;i<1000 && atlas.stats().pending()!=0;i++)atlas.pump(262144,Long.MAX_VALUE);
        check(atlas.stats().pending()==0,"world upload did not complete");
    }

    static final class ContactProbe implements AutoCloseable {
        final int capacity,tableSize,heads,links,stats,grid,probe,drive;
        final int gridCount,probeCount,driveCount,phaseLocation,floorLocation;
        ContactProbe(int capacity) {
            this.capacity=capacity;tableSize=Integer.highestOneBit(Math.max(64,capacity-1))<<1;
            heads=buffer(BufferUtils.createByteBuffer(tableSize*4));links=buffer(BufferUtils.createByteBuffer(capacity*4));
            stats=buffer(BufferUtils.createByteBuffer(32));grid=compute(source("packages/grid.comp"));
            probe=compute(source("packages/state.glsl")+"""
                layout(local_size_x=64) in;
                layout(std430,binding=6) buffer Statistics { uint stats[8]; };
                uniform float uFloor;
                shared uint sums[512];
                void main() {
                    uint i=gl_GlobalInvocationID.x,lane=gl_LocalInvocationID.x;
                    uint row[8]=uint[8](0u,0u,0u,0u,0u,0u,0u,0u);
                    if(i<uCount) {
                        Body b=src[i];bool finite=!(any(isnan(b.positionMass))||any(isinf(b.positionMass))
                            ||any(isnan(b.velocityGround))||any(isinf(b.velocityGround))
                            ||any(isnan(b.extentYaw))||any(isinf(b.extentYaw))
                            ||any(isnan(b.previousSleep))||any(isinf(b.previousSleep)));
                        row[3]=b.previousSleep.w<0?1u:0u;row[4]=finite?0u:1u;
                        row[5]=length(b.velocityGround.xyz)>1e-4?1u:0u;row[7]=1u;
                        float maximum=0.0;
                        if(finite && b.previousSleep.w>=0) {
                            ivec3 cell=cellOf(b.positionMass.xyz);
                            for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                                ivec3 neighbor=cell+ivec3(x,y,z);uint j=heads[hashCell(neighbor)];
                                while(j!=END) {
                                    Body other=src[j];
                                    if(j!=i && other.previousSleep.w>=0 && (b.positionMass.w>0 || other.positionMass.w>0)
                                            && all(equal(cellOf(other.positionMass.xyz),neighbor))) {
                                        vec3 overlap=b.extentYaw.xyz+other.extentYaw.xyz-abs(b.positionMass.xyz-other.positionMass.xyz);
                                        if(all(greaterThan(overlap,vec3(0)))) {
                                            float depth=min(overlap.x,min(overlap.y,overlap.z));maximum=max(maximum,depth);
                                            if(j>i && depth>1e-4)row[0]++;
                                        }
                                    }
                                    j=links[j];
                                }
                            }
                        }
                        row[1]=floatBitsToUint(maximum);row[2]=maximum>1e-4?1u:0u;
                        row[6]=finite?floatBitsToUint(max(0.0,uFloor-b.positionMass.y)):0u;
                    }
                    for(uint k=0u;k<8u;k++)sums[k*64u+lane]=row[k];barrier();
                    for(uint stride=32u;stride>0u;stride>>=1u) {
                        if(lane<stride)for(uint k=0u;k<8u;k++) {
                            uint p=k*64u+lane;
                            sums[p]=(k==1u || k==6u)?max(sums[p],sums[p+stride]):sums[p]+sums[p+stride];
                        }
                        barrier();
                    }
                    if(lane==0u)for(uint k=0u;k<8u;k++) {
                        if(k==1u || k==6u)atomicMax(stats[k],sums[k*64u]);else atomicAdd(stats[k],sums[k*64u]);
                    }
                }
                """);
            drive=compute("#define CMI_BODY_INPLACE 1\n"+source("packages/state.glsl")+"""
                layout(local_size_x=64) in;
                uniform float uPhase;
                void main() {
                    uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;Body b=src[i];
                    if(b.positionMass.w>0 && b.previousSleep.w>=0) {
                        float phase=uPhase+float(i/4096u)*.12;
                        b.velocityGround.x+=.08*cos(phase);b.velocityGround.z+=.06*sin(phase);src[i]=b;
                    }
                }
                """);
            for(int program:new int[]{grid,probe,drive}) {
                GL41.glProgramUniform1ui(program,GL20.glGetUniformLocation(program,"uTableMask"),tableSize-1);
                GL41.glProgramUniform1f(program,GL20.glGetUniformLocation(program,"uCellSize"),2);
            }
            gridCount=GL20.glGetUniformLocation(grid,"uCount");probeCount=GL20.glGetUniformLocation(probe,"uCount");
            driveCount=GL20.glGetUniformLocation(drive,"uCount");phaseLocation=GL20.glGetUniformLocation(drive,"uPhase");
            floorLocation=GL20.glGetUniformLocation(probe,"uFloor");
        }
        void bind(int program,PackagePhysicsGpu gpu) {
            if(gpu.count()>capacity)throw new IllegalArgumentException("Probe capacity");
            GL20.glUseProgram(program);GL30.glUniform1ui(program==grid?gridCount:program==probe?probeCount:driveCount,gpu.count());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,gpu.stateBuffer());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,heads);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,links);
        }
        ByteBuffer inspect(PackagePhysicsGpu gpu){return inspect(gpu,-1e30f);}
        ByteBuffer inspect(PackagePhysicsGpu gpu,float floor) {
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,heads);
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(-1));
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,stats);
                GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
            }
            if(gpu.count()>0) {
                bind(grid,gpu);GL43.glDispatchCompute((gpu.count()+63)/64,1,1);
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                bind(probe,gpu);GL20.glUniform1f(floorLocation,floor);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stats);GL43.glDispatchCompute((gpu.count()+63)/64,1,1);
            }
            return readBuffer(stats,32);
        }
        void drive(PackagePhysicsGpu gpu,int step) {
            if(gpu.count()==0)return;bind(drive,gpu);GL20.glUniform1f(phaseLocation,step*.15f);
            GL43.glDispatchCompute((gpu.count()+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        }
        public void close(){for(int p:new int[]{grid,probe,drive})GL20.glDeleteProgram(p);for(int b:new int[]{heads,links,stats})GL15.glDeleteBuffers(b);}
    }

    static int compute(String text) {
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+text);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            return program;
        } catch(RuntimeException failure){if(program!=0)GL20.glDeleteProgram(program);throw failure;}
        finally{GL20.glDeleteShader(shader);}
    }

    static ByteBuffer stackBodies(int n,int width,int depth,boolean staggered) {
        var b=bodies(n);
        for(int i=0;i<n;i++) {
            int layer=i/(width*depth);float shift=staggered && (layer&1)!=0?.4f:0;
            body(b,i,2+i%width*1.03125f+shift,1.5f+layer*1.03125f,2+i/width%depth*1.03125f+shift,1);
            b.putFloat(i*64+20,-1);
        }
        return b;
    }

    static int buffer(ByteBuffer bytes) {
        int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return id;
    }

    static ByteBuffer readBuffer(int id,int bytes) {
        ByteBuffer result=BufferUtils.createByteBuffer(bytes);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);return result;
    }

    static double percentile(double[] values,double fraction) {
        var sorted=values.clone();Arrays.sort(sorted);return sorted[Math.max(0,(int)Math.ceil(fraction*sorted.length)-1)];
    }

    static byte[] readBytes(PackagePhysicsGpu gpu){var buffer=read(gpu);byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;}

    static void indexBenchmark(int firstRun,int lastRun)throws Exception{
        Files.createDirectories(directory);
        Path summary=lastRun>3?directory.resolve("summary-5.csv"):directory.resolve("summary-3.csv");
        Path raw=lastRun>3?directory.resolve("samples-5.csv"):directory.resolve("samples-3.csv");
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        if(firstRun>1){rows.addAll(Files.readAllLines(directory.resolve("summary-3.csv")));samples.addAll(Files.readAllLines(directory.resolve("samples-3.csv")));}
        else{rows.add("count,scenario,index,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,gpu_buffer_bytes,index_extra_bytes,min_effective,overlap_max,terrain_penetration_max,nonfinite,quality_pass");
            samples.add("count,scenario,index,run,sample,gpu_ms,cpu_submit_ms,effective,moving,overlap_max,terrain_penetration_max,nonfinite");}
        var modes=PackagePhysicsGpu.IndexMode.values();
        for(int n:smoke?new int[]{65}:new int[]{10000,65536,131072})for(String scenario:List.of("aligned_stack","staggered_stack","continuous_force","fast_vs_stationary","moving_platform")){
            boolean platform=scenario.equals("moving_platform"),fast=scenario.equals("fast_vs_stationary"),driven=scenario.equals("continuous_force")||platform;
            var platforms=movingPlatforms(platform?1:0);var cache=movingCache(platforms);
            try(var world=new PackageCollisionGpu(448,1);var moving=new PackageMovingCollisionGpu();var probe=new ContactProbe(n)){
                movingWorld(world,!platform);moving.sync(cache.entries());moving.pump(262144,Long.MAX_VALUE);
                for(int run=firstRun;run<=lastRun;run++)for(int order=0;order<modes.length;order++){
                    var mode=modes[(run-1+order)%modes.length];String index=mode.name().toLowerCase(Locale.ROOT);
                    try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)){
                        var env=gpu.enableEnvironment(PackageGpuValidation::source);
                        var b=stackBodies(n,64,64,!scenario.equals("aligned_stack"));
                        if(fast)for(int i=0;i<n;i++){
                            int pair=i/2;body(b,i,2+(pair%32)*3-(i%2==0?.76f:0),10+(pair/2048)*1.25f,2+(pair/32%64)*1.25f,1);
                            for(int axis=0;axis<3;axis++)b.putFloat(i*64+32+axis*4,.375f);
                            b.putFloat(i*64+16,i%2==0?31:0);
                        }
                        gpu.upload(b,n);var headers=BufferUtils.createByteBuffer(n*64);
                        for(int i=0;i<n;i++){int p=i*64;headers.putLong(p,i+1).putLong(p+8,1).putLong(p+16,1).putInt(p+40,i+1).putInt(p+44,7).putFloat(p+48,5).putLong(p+56,1);}env.upload(headers,n);
                        int initial=fast?buffer(b):0,reset=fast?compute("#define CMI_BODY_INPLACE\n"+source("packages/state.glsl")+"\nlayout(std430,binding=8) readonly buffer InitialBodies { Body initial[]; };layout(local_size_x=64) in;void main(){uint i=gl_GlobalInvocationID.x;if(i<uCount)src[i]=initial[i];}"):0;
                        int countLocation=reset==0?-1:GL20.glGetUniformLocation(reset,"uCount");
                        int warmup=50,sampleCount=smoke?3:200;
                        double[] times=new double[sampleCount],cpu=new double[sampleCount];
                        int minimum=n,nonfinite=0;float overlap=0,penetration=0;boolean quality=true;
                        try{
                            for(int frame=0;frame<warmup+sampleCount;frame++){
                                if(platform){platforms[0].previous=frame==0?movingPose(0,0,0,0,1,1,1):platforms[0].current;platforms[0].current=movingPose((frame+1)*.003,0,0,0,1,1,1);cache.tick(1);}
                                int sample=frame-warmup,q=frame<warmup?0:GL15.glGenQueries();if(q!=0){GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,q);}
                                long start=System.nanoTime();
                                if(fast){GL20.glUseProgram(reset);GL30.glUniform1ui(countLocation,n);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,gpu.stateBuffer());GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,8,initial);GL43.glDispatchCompute((n+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
                                if(driven)probe.drive(gpu,frame);
                                if(platform){var views=moving.views(cache.entries(),cache.posesReady(),0,0,0);try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,mode,views);}finally{moving.endViews(views);}}
                                else try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,true,4,mode);}
                                env.capture(n);
                                if(q!=0){cpu[sample]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);times[sample]=GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(q);}
                                else GL11.glFinish();
                                env.poll(bytes->{throw new AssertionError("Benchmark unexpected environment event: n="+n+" scene="+scenario+" mode="+mode+" contact="+bytes.getInt(76)+" identity="+bytes.getLong(0)+" fire="+bytes.getInt(32)+" health="+bytes.getFloat(48));});
                                if(sample>=0){var stats=probe.inspect(gpu,1.5f);int effective=stats.getInt(28)-stats.getInt(12)-stats.getInt(16);
                                    minimum=Math.min(minimum,effective);nonfinite=Math.max(nonfinite,stats.getInt(16));overlap=Math.max(overlap,stats.getFloat(4));penetration=Math.max(penetration,stats.getFloat(24));
                                    if(stats.getFloat(4)>=.002&&!Files.exists(Path.of("build/package-"+n+"-"+scenario+"-failure.bin")))Files.write(Path.of("build/package-"+n+"-"+scenario+"-failure.bin"),readBytes(gpu));
                                    quality&=effective==n&&stats.getFloat(4)<.002&&stats.getFloat(24)<1e-4&&(!driven&&!fast||stats.getInt(20)>n*.99);
                                    samples.add(n+","+scenario+","+index+","+run+","+sample+","+times[sample]+","+cpu[sample]+","+effective+","+stats.getInt(20)+","+stats.getFloat(4)+","+stats.getFloat(24)+","+stats.getInt(16));
                                }
                            }
                            String row=n+","+scenario+","+index+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+gpu.storageBytes()+","+gpu.indexWorkspaceBytes(mode)+","+minimum+","+overlap+","+penetration+","+nonfinite+","+(quality?1:0);
                            rows.add(row);System.out.println(row);
                            check(quality,"Index benchmark quality failed: "+row);
                        }finally{if(initial!=0)GL15.glDeleteBuffers(initial);if(reset!=0)GL20.glDeleteProgram(reset);}
                    }
                }
            }
        }
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"index benchmark GL error");
        Files.createDirectories(summary.getParent());Files.createDirectories(raw.getParent());
        Files.write(summary,rows);Files.write(raw,samples);
    }

    static PackageMovingGeometry.Pose movingPose(double x,double y,double z,double angle,double sx,double sy,double sz) {
        double c=Math.cos(angle),s=Math.sin(angle);
        return new PackageMovingGeometry.Pose(c*sx,0,-s*sx,0,sy,0,s*sz,0,c*sz,x,y,z);
    }

    static final class MovingSource implements PackageMovingCollisionCache.Source {
        final PackageMovingGeometry.Key key=
            new PackageMovingGeometry.Key(0,UUID.randomUUID());
        long revision=1;boolean alive=true;
        List<PackageMovingGeometry.Box> boxes;
        PackageMovingGeometry.Pose previous=movingPose(0,0,0,0,1,1,1),current=previous;
        MovingSource(float x0,float y0,float z0,float x1,float y1,float z1){boxes=List.of(new PackageMovingGeometry.Box(x0,y0,z0,x1,y1,z1,.6f,0));}
        public PackageMovingGeometry.Key key(){return key;}
        public long revision(){return revision;}public boolean alive(){return alive;}
        public PackageMovingGeometry.Bounds bounds(){
            var b=boxes.getFirst();return new PackageMovingGeometry.Bounds(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1());
        }
        public PackageMovingGeometry.Pose pose(boolean old){return old?previous:current;}
        public PackageMovingCollisionCache.Cursor open(){
            check(Thread.currentThread()==captureOwner,"moving world fixture off owner");var rows=boxes.iterator();
            return new PackageMovingCollisionCache.Cursor(){public boolean hasNext(){return rows.hasNext();}public List<PackageMovingGeometry.Box> next(){return List.of(rows.next());}};
        }
        final Thread captureOwner=Thread.currentThread();
    }

    static PackageMovingCollisionCache movingCache(MovingSource...sources){
        var cache=new PackageMovingCollisionCache(Runnable::run,64,()->0L);
        for(var source:sources)check(cache.offer(source),"moving source admission");
        for(int i=0;i<3;i++)cache.tick(1);
        for(var entry:cache.entries())check(entry.snapshot()!=null,"moving geometry ready");return cache;
    }

    static void movingWorld(PackageCollisionGpu world,boolean staticFloor) {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)
            check(world.offer(new PackageCollisionCache.Section(x,y,z),staticFloor&&y==0?floor:air),"moving full world admission");
        uploadWorld(world);
    }

    static MovingSource[] movingPlatforms(int count) {
        var sources=new MovingSource[count];
        for(int i=0;i<count;i++){int width=count==16?4:1;float span=72f/width;
            sources[i]=new MovingSource(i%width*span-1,0,i/width*span-1,(i%width+1)*span-1,1,(i/width+1)*span-1);}
        return sources;
    }
    public static void main(String[] args)throws Exception {
        var options=Arrays.asList(args);smoke=options.contains("--smoke");
        for(String arg:args)if(arg.startsWith("--output="))directory=Path.of(arg.substring(9));
        var callback=GLFWErrorCallback.createPrint(System.err);callback.set();
        check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Package index comparison",0,0);check(window!=0,"GL context");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            indexBenchmark(options.contains("--index-benchmark-extra")?4:1,smoke?1:options.contains("--index-benchmark-extra")?5:3);
            System.out.println("Package index comparison: "+checks+" assertions passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
