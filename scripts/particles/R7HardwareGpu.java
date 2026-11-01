import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareNative;

/** Real-device R7 capability, shared-memory and exact geometric query regression. */
public final class R7HardwareGpu extends ModelPopulationGpuValidation {
    static final String[] BACKENDS={"cuda-control","rt-aabb","rt-triangle","tensor","tensor-control"};
    static Path out,source;static JsonObject deps;static final List<Object> cases=new ArrayList<>();
    // Read imported storage through a fresh GL staging buffer. Some drivers retain
    // a CPU readback shadow for repeated GetBufferSubData on an external object.
    static ByteBuffer readShared(int id,int size){int staging=buffer(bytes(size));try{GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,id);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,staging);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,size);return read(staging,size);}finally{GL15.glDeleteBuffers(staging);}}
    static final class Query implements AutoCloseable {
        final int n,q,input,output,capacity,idsOffset,bytes,requests,owners;final long nativeHandle;float[] values;int[][] expected;
        Query(float[] points,int queries,int limit)throws Exception{
            this(points,queries,limit,null,null);
        }
        Query(float[] points,int queries,int limit,float[] queryRequests,int[] queryOwners)throws Exception{
            this(points,queries,limit,queryRequests,queryOwners,0);
        }
        Query(float[] points,int queries,int limit,float[] queryRequests,int[] queryOwners,float conservativeRadius)throws Exception{
            values=points;n=values.length/4;q=queries;float radius=1;
            if(queryRequests==null){requests=owners=0;expected=NeighborQueryGpuValidation.reference(points,q,1);}else{check(queryRequests.length==q*4&&queryOwners.length==q,"actual query ABI");requests=buffer(R7HardwareGpu.bytes(Math.max(16,q*16)));owners=buffer(R7HardwareGpu.bytes(Math.max(4,q*4)));GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,requests);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,queryRequests);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,owners);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,queryOwners);expected=new int[q][];radius=0;for(int i=0;i<q;i++){radius=Math.max(radius,queryRequests[i*4+3]);int[] found=new int[n];int count=0;for(int j=0;j<n;j++){if(j==queryOwners[i])continue;float x=queryRequests[i*4]-points[j*4],y=queryRequests[i*4+1]-points[j*4+1],z=queryRequests[i*4+2]-points[j*4+2],r=queryRequests[i*4+3];if((x*x+y*y)+z*z<=r*r)found[count++]=j;}expected[i]=Arrays.copyOf(found,count);}}
            radius=Math.max(radius,conservativeRadius);int total=0;for(int[] row:expected)total=Math.addExact(total,row.length);capacity=limit<0?Math.max(1,total):limit;idsOffset=(16+(q+1)*8+255)&~255;bytes=idsOffset+capacity*4;
            input=buffer(R7HardwareGpu.bytes(Math.max(16,n*16)));output=buffer(R7HardwareGpu.bytes(bytes));putPoints(points);
            long started=System.nanoTime();String cuda=deps.getAsJsonObject("cuda_cudart").get("root").getAsString(),optix=deps.getAsJsonObject("optix").get("root").getAsString(),crt=deps.getAsJsonObject("cuda_nvcc").get("root").getAsString();nativeHandle=queryRequests==null?HardwareNative.createQuery(input,output,n,q,capacity,radius,cuda,optix,source.toAbsolutePath().toString(),crt):HardwareNative.createQueryRequests(input,output,requests,owners,n,q,capacity,radius,cuda,optix,source.toAbsolutePath().toString(),crt);cases.add(Map.of("event","initialization","points",n,"queries",q,"actual_requests",queryRequests!=null,"cpu_ms",(System.nanoTime()-started)/1e6,"memory",HardwareNative.queryMemory(nativeHandle)));
        }
        void putPoints(float[] points){values=points;GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,points);}
        double[] run(int backend,boolean refit){return HardwareNative.runQuery(nativeHandle,backend,refit);}
        void verify(int backend,boolean refit){double[] timing=run(backend,refit);ByteBuffer result=read(output,bytes);long total=0;for(int[] r:expected)total+=r.length;check(result.getLong(0)==total,"count "+BACKENDS[backend]+" n="+n+" got="+result.getLong(0)+" expected="+total);check(result.getLong(8)==(total>capacity?1:0),"overflow flag");if(total<=capacity){long previous=0;for(int i=0;i<q;i++){long offset=result.getLong(16+i*8),end=result.getLong(24+i*8);check(offset==previous&&end>=offset&&end<=total,"CSR coverage");int[] row=new int[(int)(end-offset)];for(int k=0;k<row.length;k++)row[k]=result.getInt(idsOffset+((int)offset+k)*4);Arrays.sort(row);check(Arrays.equals(row,expected[i]),"candidate set/duplicates "+BACKENDS[backend]+" n="+n+" query="+i+" expected="+expected[i].length+" actual="+row.length);previous=end;}}
            cases.add(Map.of("event","query-correctness","backend",BACKENDS[backend],"points",n,"queries",q,"pairs",total,"overflow",total>capacity,"refit",refit,"timing_ms",timing));
        }
        public void close(){HardwareNative.closeQuery(nativeHandle);GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);if(requests!=0)GL15.glDeleteBuffers(requests);if(owners!=0)GL15.glDeleteBuffers(owners);}
    }
    static void copyProbe()throws Exception{
        for(int bytes:new int[]{17,4096,1024*1024,16*1024*1024}){long h=HardwareNative.createCopy(bytes,3);cases.add(Map.of("event","copy-initialization","details",JsonParser.parseString(HardwareNative.copyInfo(h))));try{for(int round=0;round<6;round++){int slot=round%3;int[] ids=HardwareNative.copyBuffers(h,slot);byte[] expected=new byte[bytes];new Random(9417+round).nextBytes(expected);ByteBuffer upload=org.lwjgl.BufferUtils.createByteBuffer(bytes).put(expected).flip();put(ids[0],upload);int timer=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long start=System.nanoTime();check(HardwareNative.submitCopy(h,slot,bytes),"COPY slot retired");double cpu=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double elapsed=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(timer);byte[] actual=R6CaptureReplayGpu.Capture.array(readShared(ids[1],bytes));byte[] cuda=HardwareNative.readCopyCuda(h,slot,bytes);if(!Arrays.equals(expected,actual)){Files.write(out.resolve("copy-expected.bin"),expected);Files.write(out.resolve("copy-gl.bin"),actual);Files.write(out.resolve("copy-cuda.bin"),cuda);Files.write(out.resolve("copy-source-gl.bin"),R6CaptureReplayGpu.Capture.array(read(ids[0],bytes)));Files.writeString(out.resolve("copy-failure.txt"),"bytes="+bytes+" round="+round+" GL error="+GL11.glGetError()+"\n");}check(Arrays.equals(expected,actual),"D3D12 COPY -> GL bytes");check(Arrays.equals(expected,cuda),"D3D12 COPY -> CUDA bytes");check(GL11.glGetError()==0,"COPY GL state");cases.add(Map.of("event","copy-correctness","bytes",bytes,"slot",slot,"gl_interval_ms",elapsed,"copy_queue_ms",HardwareNative.copyMillis(h,slot),"cpu_submit_ms",cpu));}}finally{HardwareNative.closeCopy(h);}}
    }
    public static void main(String[] args)throws Exception{
        out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);check(!Files.exists(out.resolve("hardware.json")),"fresh output");source=out.resolve("source");Files.createDirectory(source);for(String name:new String[]{"Query.h","grid.cu","rt.cu","rt-triangle.cu","tensor.cu"})Files.copy(Path.of("native/offload",name),source.resolve(name));
        deps=JsonParser.parseString(Files.readString(Path.of(".gradle/neighbor-rt/dependencies.json"))).getAsJsonObject();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);long window=GLFW.glfwCreateWindow(32,32,"R7 hardware regression",0,0);check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        var result=new LinkedHashMap<String,Object>();result.put("passed",false);result.put("scope","hardware/copy and geometric query correctness only; not production physics or FPS qualification");result.put("gpu",GL11.glGetString(GL11.GL_RENDERER));result.put("driver",GL11.glGetString(GL11.GL_VERSION));
        try{HardwareNative.load(Path.of(".gradle/hardware-offload/build"));result.put("capabilities",JsonParser.parseString(HardwareNative.probe()));result.put("external_memory_win32",GL.getCapabilities().GL_EXT_memory_object_win32);result.put("external_semaphore_win32",GL.getCapabilities().GL_EXT_semaphore_win32);
            try{copyProbe();result.put("copy_probe",true);}catch(RuntimeException|AssertionError failure){result.put("copy_probe",false);result.put("copy_failure",failure.toString());}
            int[] counts=Arrays.stream(System.getProperty("cmi.r7.counts","0,1,15,16,17,31,32,33,63,64,65,255,256,257,1000,10000,50000,65535,65536,131072").split(",")).mapToInt(Integer::parseInt).toArray();
            for(int n:counts){float[] points=NeighborQueryGpuValidation.positions(n,"sparse");try(var q=new Query(points,Math.min(n,128),-1)){for(int b=0;b<5;b++)q.verify(b,false);q.verify(1,true);q.verify(2,true);}System.out.println("R7 exact geometry n="+n);}
            for(String layout:new String[]{"crowded","coincident","boundary"}){float[] points=NeighborQueryGpuValidation.positions(257,layout);try(var q=new Query(points,128,-1)){for(int b=0;b<5;b++)q.verify(b,false);}try(var q=new Query(points,128,1)){for(int b=0;b<5;b++)q.verify(b,false);}}
            // Every query enumerates every point in this independent CPU oracle.
            // Cover exact sphere/box faces, edge/corner projections, ULP neighbours,
            // negative coordinates and Minecraft's far-coordinate range.
            for(float origin:new float[]{0,-32,1_000_000,-30_000_000}){float[] points=new float[257*4];for(int i=0;i<257;i++){int axis=i%3;float[] p={origin,origin,origin};float d=switch(i%9){case 0->0;case 1->1;case 2->-1;case 3->Math.nextUp(1f);case 4->Math.nextDown(1f);case 5->.70710677f;case 6->-.57735026f;case 7->.5f;default->-1.01f;};p[axis]+=d;if(i%9==5)p[(axis+1)%3]+=d;if(i%9==6){p[(axis+1)%3]+=d;p[(axis+2)%3]+=d;}for(int a=0;a<3;a++)points[i*4+a]=p[a];points[i*4+3]=Float.intBitsToFloat(i);}try(var q=new Query(points,257,-1)){for(int b=0;b<5;b++)q.verify(b,false);q.verify(2,true);}cases.add(Map.of("event","proxy-boundary","origin",origin));}
            for(float offset:new float[]{0,1000000,-30000000}){float[] points=NeighborQueryGpuValidation.positions(257,"crowded"),requests=new float[257*4];int[] owners=new int[257];for(int i=0;i<257;i++)for(int a=0;a<3;a++)points[i*4+a]+=offset;for(int i=0;i<257;i++){owners[i]=256-i;for(int a=0;a<3;a++){requests[i*4+a]=points[owners[i]*4+a]+(i%7-3)*.24f;}requests[i*4+3]=i%3==0?.45f:i%3==1?1f:1.4f;}try(var q=new Query(points,257,-1,requests,owners)){for(int b=0;b<5;b++)q.verify(b,false);q.verify(2,true);}}
            if(Boolean.getBoolean("cmi.r7.fullQueries"))for(int n:new int[]{1000,10000,50000,65535,65536,131072}){float[] points=new float[n*4];for(int i=0;i<n;i++){int pair=i/2;points[i*4]=(pair%64)*3f+(i%2)*.5f;points[i*4+1]=(pair/64%64)*3f;points[i*4+2]=(pair/4096)*3f;points[i*4+3]=Float.intBitsToFloat(i);}try(var q=new Query(points,n,-1)){for(int b=0;b<5;b++)q.verify(b,false);q.verify(1,true);q.verify(2,true);}cases.add(Map.of("event","full-query-paired-islands","points",n,"queries",n));System.out.println("R7 full nonempty queries n="+n);}
            check(GL11.glGetError()==0,"GL state");result.put("passed",true);
        }finally{result.put("checks",checks);result.put("cases",cases);Files.writeString(out.resolve("hardware.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}

