import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.google.gson.*;

/** Complete-radius query experiment. No world, production integration or FPS inference. */
public final class NeighborQueryGpuValidation {
    static long checks;
    static final List<Object> initialization=new ArrayList<>(),firstExecutions=new ArrayList<>();
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static int buffer(long size){int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,Math.max(size,16),GL15.GL_DYNAMIC_COPY);return id;}
    static ByteBuffer read(int id,int size){var out=BufferUtils.createByteBuffer(size);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,out);return out;}
    static final Path ROOT=Path.of("").toAbsolutePath();
    static int compile()throws Exception{
        String source=Files.readString(ROOT.resolve("native/neighbor/grid.comp"));int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(shader));
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);GL20.glDeleteShader(shader);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));return program;
    }
    static float[] positions(int n,String layout){
        float[] out=new float[n*4];Random random=new Random(9415123+n);
        for(int i=0;i<n;i++){
            float x,y,z;
            if(layout.equals("coincident")){x=y=z=0;}
            else if(layout.equals("boundary")){x=(i%13-6)*.5f;y=(i/13%13-6)*.5f;z=(i/169-3)*.5f;if(i%7==0)x=Math.nextUp(x);}
            else{float spacing=layout.equals("sparse")?3:0.65f;int side=(int)Math.ceil(Math.cbrt(Math.max(1,n)));x=(i%side-side/2)*spacing;y=(i/side%side-side/2)*spacing;z=(i/(side*side)-side/2)*spacing;x+=(random.nextFloat()-.5f)*.01f;}
            out[i*4]=x;out[i*4+1]=y;out[i*4+2]=z;out[i*4+3]=Float.intBitsToFloat(i);
        }return out;
    }
    /** Independent sorted-axis sweep; no GPU hash/grid traversal or old visit budget. */
    static int[][] reference(float[] points,int q,float radius){
        int n=points.length/4;Integer[] order=new Integer[n];for(int i=0;i<n;i++)order[i]=i;Arrays.sort(order,Comparator.comparingDouble(i->points[i*4]));
        int[][] result=new int[q][];
        for(int i=0;i<q;i++){
            int[] found=new int[Math.min(n,128)];int count=0;float x=points[i*4];int lo=0,hi=n;
            float padding=(Math.abs(x)+radius)*1.e-6f;
            while(lo<hi){int mid=(lo+hi)>>>1;if(points[order[mid]*4]<x-radius-padding)lo=mid+1;else hi=mid;}
            if(n<=1000)lo=0;
            for(int o=lo;o<n&&(n<=1000||points[order[o]*4]<=x+radius+padding);o++){
                int j=order[o];if(j==i)continue;float dx=x-points[j*4],dy=points[i*4+1]-points[j*4+1],dz=points[i*4+2]-points[j*4+2];
                if((dx*dx+dy*dy)+dz*dz<=radius*radius){if(count==found.length)found=Arrays.copyOf(found,Math.max(1,found.length*2));found[count++]=j;}
            }
            found=Arrays.copyOf(found,count);Arrays.sort(found);result[i]=found;
        }return result;
    }
    static final class Fixture implements AutoCloseable {
        final int n,q,capacity,input,output,heads,next,counts,sums,hashSize,idsOffset,bytes,program,timer;
        final float[] points,seed;int[][] expected;
        long nativeHandle;
        final boolean[] seen=new boolean[4];
        String layout;
        Fixture(int n,int q,String layout,int capacity,int program,JsonObject deps)throws Exception{
            this.n=n;this.q=q;this.capacity=capacity;this.program=program;this.layout=layout;points=positions(n,layout);seed=points.clone();expected=reference(points,q,1);
            idsOffset=(16+(q+1)*8+255)&~255;bytes=idsOffset+capacity*4;
            input=buffer(n*16L);output=buffer(bytes);int hash=1;while(hash<Math.max(1,n*2))hash*=2;hashSize=hash;
            heads=buffer(hash*4L);next=buffer(n*4L);counts=buffer(q*4L);sums=buffer(((q+255)/256)*8L);timer=GL15.glGenQueries();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,points);
            if(n>0&&q>0){long begin=System.nanoTime();nativeHandle=NeighborNative.create(input,output,n,q,capacity,1,deps.getAsJsonObject("cuda_cudart").get("root").getAsString(),deps.getAsJsonObject("optix").get("root").getAsString(),ROOT.resolve("native/neighbor").toString(),deps.getAsJsonObject("cuda_nvcc").get("root").getAsString());System.out.println("INIT n="+n+" layout="+layout+" ms="+(System.nanoTime()-begin)/1e6);}
            if(nativeHandle!=0){long[] memory=NeighborNative.memory(nativeHandle);initialization.add(Map.of("n",n,"layout",layout,"bvh_scratch_bytes",memory[0],"bvh_output_bytes",memory[1],"cuda_owned_bytes",memory[2],"device_free_bytes_snapshot",memory[3],"device_total_bytes",memory[4],"gl_bytes",n*16L+bytes+hashSize*4L+n*4L+q*4L+((q+255)/256)*8L));}
        }
        void move(int frame,boolean large){
            float scale=large?4f:.2f;
            for(int i=0;i<n;i++)for(int axis=0;axis<3;axis++)points[i*4+axis]=seed[i*4+axis]+(float)Math.sin(i*.731+axis*2.13+frame*.17)*scale;
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,points);
            // Input generation/upload is a fixture operation; excluded equally from this query-only screen.
            GL11.glFinish();
        }
        void stage(int stage,int groups){GL30.glUniform1ui(GL20.glGetUniformLocation(program,"stage"),stage);if(groups>0)GL43.glDispatchCompute(groups,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
        double[] run(int backend){
            long begin=System.nanoTime();double gpu;
            if(backend==0){
                GL20.glUseProgram(program);int[] buffers={input,output,heads,next,counts,sums};for(int i=0;i<buffers.length;i++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,buffers[i]);
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,6,output,idsOffset,Math.max(4,capacity*4L));
                for(var pair:new int[][]{{GL20.glGetUniformLocation(program,"n"),n},{GL20.glGetUniformLocation(program,"q"),q},{GL20.glGetUniformLocation(program,"capacity"),capacity},{GL20.glGetUniformLocation(program,"hashMask"),hashSize-1}})GL30.glUniform1ui(pair[0],pair[1]);GL20.glUniform1f(GL20.glGetUniformLocation(program,"radius"),1);
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);stage(0,(hashSize+255)/256);stage(1,(n+255)/256);stage(2,(q+255)/256);stage(3,(q+255)/256);stage(4,1);stage(5,(q+255)/256);stage(6,(q+255)/256);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                gpu=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;
            }else gpu=NeighborNative.run(nativeHandle,backend-1);
            return new double[]{gpu,(System.nanoTime()-begin)/1e6};
        }
        void verify(int backend){
            double[] first=run(backend);if(!seen[backend]){seen[backend]=true;firstExecutions.add(Map.of("n",n,"layout",layout,"backend",backend,"gpu_ms",first[0],"completed_wall_ms",first[1]));}var result=read(output,bytes);long total=result.getLong(0),expectedTotal=0;for(int[] row:expected)expectedTotal+=row.length;
            check(total==expectedTotal,"count backend="+backend+" actual="+total+" expected="+expectedTotal);
            check(result.getLong(8)==(total>capacity?1:0),"explicit overflow");
            if(total>capacity)return;
            long previous=0;for(int i=0;i<q;i++){
                long offset=result.getLong(16+i*8),end=result.getLong(24+i*8);check(offset==previous&&end>=offset&&end<=total,"CSR offsets");
                int[] row=new int[(int)(end-offset)];for(int k=0;k<row.length;k++)row[k]=result.getInt(idsOffset+((int)offset+k)*4);Arrays.sort(row);
                check(Arrays.equals(row,expected[i]),"incomplete/duplicate candidate backend="+backend+" query="+i+" n="+n);previous=end;
            }
        }
        public void close(){if(nativeHandle!=0)NeighborNative.destroy(nativeHandle);for(int b:new int[]{input,output,heads,next,counts,sums})GL15.glDeleteBuffers(b);GL15.glDeleteQueries(timer);}
    }
    public static void main(String[] args)throws Exception{
        GLFW.glfwSetErrorCallback((code,text)->System.err.println("GLFW "+code));check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);
        long window=GLFW.glfwCreateWindow(32,32,"neighbor offline",0,0);check(window!=0,"hidden GL context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        System.load(ROOT.resolve(".gradle/neighbor-rt/build/nvrtc-builtins64_128.dll").toString());
        System.load(ROOT.resolve(".gradle/neighbor-rt/build/nvrtc64_120_0.dll").toString());
        System.load(ROOT.resolve(".gradle/neighbor-rt/build/CmiNeighborExperiment.dll").toString());JsonObject deps=JsonParser.parseString(Files.readString(ROOT.resolve(".gradle/neighbor-rt/dependencies.json"))).getAsJsonObject();
        int program=compile();boolean benchmark=Arrays.asList(args).contains("--benchmark");Path directory=ROOT.resolve("build/benchmarks/neighbor-r5");Files.createDirectories(directory);
        var metadata=new LinkedHashMap<String,Object>();metadata.put("gpu",GL11.glGetString(GL11.GL_RENDERER));metadata.put("driver",GL11.glGetString(GL11.GL_VERSION));metadata.put("kind","isolated complete-radius query only; no production chain or shaderpack competition; cannot qualify FPS");metadata.put("sdk",deps);metadata.put("seed",9415123);metadata.put("passed",false);
        metadata.put("input_upload","excluded fixture cost; move/upload completed before each pair");metadata.put("programs",Map.of("gl_compute",1,"cuda_functions",6,"optix_pipeline",1,"optix_program_groups",3));
        var hashes=new TreeMap<String,String>();try(var paths=Files.walk(ROOT.resolve("native/neighbor"))){for(Path path:paths.filter(Files::isRegularFile).toList())hashes.put(ROOT.relativize(path).toString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));}metadata.put("source_sha256",hashes);
        String name=(benchmark?"query-benchmark-":"query-validation-")+UUID.randomUUID();
        try(var raw=Files.newBufferedWriter(directory.resolve(name+".csv"))){
            raw.write("n,queries,layout,comparison,pair,side,backend,gpu_ms,completed_wall_ms\n");
            int[] sizes=benchmark?new int[]{1000,10000,50000,65536,131072}:new int[]{0,1,63,64,65,255,256,257,1000,10000,50000,65535,65536,131072};
            for(int n:sizes)for(String layout:benchmark?new String[]{"sparse","uniform","motion_small","motion_large"}:new String[]{"sparse","uniform","boundary","coincident","motion_large"}){
                int q=layout.equals("coincident")?Math.min(n,32):n;int cap=layout.equals("coincident")?Math.max(1,q*Math.max(0,n-1)):Math.max(1,n*48);
                try(var f=new Fixture(n,q,layout,cap,program,deps)){
                    for(int backend=0;backend<(n==0?1:4);backend++)f.verify(backend);
                    if(layout.startsWith("motion")&&n>0){f.move(17,layout.endsWith("large"));f.expected=reference(f.points,q,1);for(int b=0;b<4;b++)f.verify(b);}
                    if(!benchmark)continue;
                    for(int warm=0;warm<12;warm++)for(int b=0;b<4;b++)f.run(b);
                    for(String comparison:new String[]{"AA_GL","AA_CUDA","AA_RT_BUILD","AA_RT_UPDATE","AB_GL_CUDA","AB_CUDA_RT_BUILD","AB_CUDA_RT_UPDATE","AB_GL_RT_UPDATE"}){
                        int a=comparison.equals("AA_GL")||comparison.startsWith("AB_GL")?0:comparison.equals("AA_RT_BUILD")?2:comparison.equals("AA_RT_UPDATE")?3:1;
                        int b=comparison.startsWith("AA")?a:comparison.equals("AB_GL_CUDA")?1:comparison.equals("AB_CUDA_RT_BUILD")?2:3;
                        for(int pair=0;pair<64;pair++){
                            if(layout.startsWith("motion"))f.move(pair,layout.endsWith("large"));
                            for(int order=0;order<2;order++){int side=(pair+order)&1,backend=side==0?a:b;double[] time=f.run(backend);raw.write(n+","+q+","+layout+","+comparison+","+pair+","+side+","+backend+","+time[0]+","+time[1]+"\n");}
                        }
                        raw.flush();
                    }
                }System.out.println("PASS n="+n+" layout="+layout);
            }
            // A capacity rejection is observable; callers must retry/batch/reference before consuming.
            try(var overflow=new Fixture(1000,32,"coincident",16,program,deps)){for(int b=0;b<4;b++)overflow.verify(b);}
            metadata.put("checks",checks);metadata.put("passed",true);
        }finally{metadata.put("memory",initialization);metadata.put("first_execution_per_fixture",firstExecutions);GL20.glDeleteProgram(program);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();Files.writeString(directory.resolve(name+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(metadata)+"\n");}
        System.out.println("Neighbor query checks="+checks+"; raw="+directory.resolve(name+".csv"));
    }
}
