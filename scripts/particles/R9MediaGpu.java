import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;

/** Actual HEVC lossless data round trips; CPU snapshot scope is explicit. */
public final class R9MediaGpu extends ModelPopulationGpuValidation {
 static final List<Object> rows=new ArrayList<>();
 static void test(String name,ByteBuffer data)throws Exception{test(name+"-intra",data,false);test(name+"-predictive",data,true);}
 static void test(String name,ByteBuffer data,boolean predictive)throws Exception{
  int from=buffer(data.duplicate()),to=buffer(bytes(data.capacity())),timer=GL15.glGenQueries();
  long handle=HardwareNative.createMedia(2048,data.capacity(),predictive);try{
   for(int i=-2;i<8;i++){var result=JsonParser.parseString(HardwareNative.mediaRoundTrip(handle,data,data.capacity(),i+3,91,0)).getAsJsonObject();check(result.get("valid").getAsBoolean(),"byte exact "+name);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,from);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,to);GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,data.capacity());GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double copyMs=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;if(i>=0)rows.add(Map.of("input",name,"pair",i,"media",result,"gl_copy_gpu_ms",copyMs));}
  }finally{HardwareNative.closeMedia(handle);GL15.glDeleteBuffers(from);GL15.glDeleteBuffers(to);GL15.glDeleteQueries(timer);}
  for(int fault=1;fault<=3;fault++){
   handle=HardwareNative.createMedia(2048,data.capacity(),predictive);try{
    boolean rejected=false;try{var r=JsonParser.parseString(HardwareNative.mediaRoundTrip(handle,data,data.capacity(),100,91,fault)).getAsJsonObject();rejected=!r.get("valid").getAsBoolean();}catch(IllegalStateException expected){rejected=true;}
    check(rejected,"truncated/stale/corrupt stream rejected");boolean poisoned=false;try{HardwareNative.mediaRoundTrip(handle,data,data.capacity(),101,91,0);}catch(IllegalStateException expected){poisoned=true;}check(poisoned,"failed session cannot publish old output");
   }finally{HardwareNative.closeMedia(handle);}
  }
 }
 public static void main(String[] args)throws Exception{
  Path out=Path.of(System.getProperty("cmi.benchmark.output")),capture=Path.of(System.getProperty("cmi.r7.capture"));Files.createDirectories(out);var manifest=JsonParser.parseString(Files.readString(capture.resolve("manifest.json"))).getAsJsonObject();
  check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);long window=GLFW.glfwCreateWindow(32,32,"R9 media data",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();HardwareNative.load(Path.of(".gradle/hardware-offload/build"));var result=new LinkedHashMap<String,Object>();result.put("passed",false);result.put("scope","actual capture byte payloads; codec cost and CPU snapshot transfers; not client FPS or live GPU pipeline");
  try{
   String poolName=null;long poolOffset=0;
   var programs=manifest.getAsJsonObject("programs");for(var command:manifest.getAsJsonArray("commands")){var c=command.getAsJsonObject();if(!c.has("inputs"))continue;var inputs=c.getAsJsonObject("inputs");if(programs.getAsJsonObject(inputs.get("program").getAsString()).get("source_name").getAsString().equals("update.comp")){var b=inputs.getAsJsonObject("ssbo").getAsJsonObject("0");poolName=b.get("buffer").getAsString();poolOffset=b.get("offset").getAsLong();break;}}
   check(poolName!=null,"actual particle pool");String file=manifest.getAsJsonObject("resources").getAsJsonObject(poolName).get("file").getAsString();byte[] original=Files.readAllBytes(capture.resolve(file));check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original)).equals(manifest.getAsJsonObject("sha256").get(file).getAsString()),"capture SHA");
   int length=Math.min(4<<20,original.length-Math.toIntExact(poolOffset));ByteBuffer pool=bytes(length);pool.put(original,Math.toIntExact(poolOffset),length).clear();test("actual-particle-records",pool);
   // Fixed identity order in this capture; exact metadata stays in its own lane.
   Path sequence=Path.of(System.getProperty("cmi.r9.sequence","build/benchmarks/neighbor-r8-20261101/sequence-motion-all"));var sequenceManifest=JsonParser.parseString(Files.readString(sequence.resolve("inputs.json"))).getAsJsonObject();var first=sequenceManifest.getAsJsonArray("frames").get(0).getAsJsonObject();byte[] positions=Files.readAllBytes(sequence.resolve(first.get("file").getAsString()));check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(positions)).equals(first.get("sha256").getAsString()),"sequence SHA");var points=bytes(positions.length).put(positions).clear();
   var entries=new TreeMap<Integer,int[]>();for(int i=0;i+16<=points.capacity();i+=16){int member=points.getInt(i+12);check(member>=0&&member<131072,"recorded stable identity");float x=points.getFloat(i),y=points.getFloat(i+4),z=points.getFloat(i+8);try{entries.put(member,new int[]{ConservativeCoordinates.quantize(x),ConservativeCoordinates.quantize(y),ConservativeCoordinates.quantize(z)});}catch(IllegalArgumentException ignored){}}
   check(!entries.isEmpty(),"actual quantized coordinates");ByteBuffer quantized=bytes(entries.size()*16);for(var entry:entries.entrySet()){quantized.putInt(entry.getKey());for(int value:entry.getValue())quantized.putInt(value);}quantized.clear();test("actual-identity-sorted-quantized-coordinates",quantized);
   Path continuation=Path.of(System.getProperty("cmi.r9.continuation","build/benchmarks/hardware-r9-20261103/real/continuation-snapshot.bin"));if(Files.exists(continuation)){byte[] payload=Files.readAllBytes(continuation);test("actual-continuation-snapshot",bytes(payload.length).put(payload).clear());result.put("continuation_tested",true);}else result.put("continuation_tested",false);
   var random=bytes(65536);new Random(126).nextBytes(original=new byte[random.capacity()]);random.put(original).clear();test("incompressible-byte-control",random);
   var hash=java.security.MessageDigest.getInstance("SHA-256");hash.update(pool.duplicate());result.put("particle_payload_sha256",HexFormat.of().formatHex(hash.digest()));result.put("quantized_members",entries.size());result.put("passed",true);
  }catch(IllegalStateException unavailable){result.put("unavailable_or_failed",unavailable.toString());throw unavailable;}finally{result.put("checks",checks);result.put("rows",rows);Files.writeString(out.resolve("media.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
