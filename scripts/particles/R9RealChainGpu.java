import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
/** New force output is qualified against the original full captured chain. */
public final class R9RealChainGpu extends ModelPopulationGpuValidation {
 static void currentProductionSource(R7ChainReplayGpu.Replay replay)throws Exception{for(var entry:replay.manifest.getAsJsonObject("programs").entrySet()){var d=entry.getValue().getAsJsonObject();if(d.get("source_name").getAsString().equals("update.comp")){int p=R7ContinuationGpu.compileVariant(Files.readString(replay.root.resolve(d.get("plain").getAsString())));deleteProgram(replay.programs.put(entry.getKey(),p));}}}
 static void fixedGrid(R7ChainReplayGpu.Replay replay,java.nio.ByteBuffer[] grid,boolean capture){replay.beforeDispatch=e->{
  var in=e.getAsJsonObject("inputs");String name=replay.manifest.getAsJsonObject("programs").getAsJsonObject(in.get("program").getAsString()).get("source_name").getAsString();
  if(!name.equals("update.comp")||R7JournalHardware.bits(in.getAsJsonObject("uniforms"),"uModelPass",0)!=1)return;
  var d=in.getAsJsonObject("ssbo").getAsJsonObject("13");int buffer=replay.buffer(d.get("buffer").getAsString());GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);
  int bytes=d.get("bytes").getAsInt();if(bytes==0)bytes=replay.resources.getAsJsonObject(d.get("buffer").getAsString()).get("bytes").getAsInt()-d.get("offset").getAsInt();
  if(capture)grid[0]=R8SemanticState.range(buffer,d.get("offset").getAsLong(),bytes);else{check(grid[0].remaining()==bytes,"full grid alias range");GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,d.get("offset").getAsLong(),grid[0].duplicate());GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);}
 };}
 static Map<String,List<int[]>> records(R7ChainReplayGpu.Replay replay){
  var pub=replay.manifest.getAsJsonObject("publication");int n=read(replay.buffer(pub.get("counter").getAsString()),16).getInt(0);var pool=read(replay.buffer(pub.get("pool").getAsString()),n*64);var result=new TreeMap<String,List<int[]>>();
  for(int i=0;i<n;i++){int[] row=new int[16];for(int k=0;k<16;k++)row[k]=pool.getInt(i*64+k*4);String key=row[3]+":"+row[14]+":"+row[15];result.computeIfAbsent(key,k->new ArrayList<>()).add(row);}return result;
 }
 static double physicalBound(Map<String,List<int[]>> expected,Map<String,List<int[]>> actual){
  check(expected.keySet().equals(actual.keySet()),"fallback stable particle set");double worst=0;
  for(var entry:expected.entrySet()){var pending=new ArrayList<>(actual.get(entry.getKey()));check(pending.size()==entry.getValue().size(),"fallback identity multiplicity");for(int[] a:entry.getValue()){
   int match=-1;double error=0;for(int j=0;j<pending.size();j++){int[] b=pending.get(j);boolean okay=true;double largest=0;for(int k=0;k<16;k++){if(k<=2||k>=4&&k<=6){float x=Float.intBitsToFloat(a[k]),y=Float.intBitsToFloat(b[k]);double delta=Math.abs((double)x-y);okay&=Float.isFinite(y)&&delta<=1e-5+8*Math.max(Math.ulp(x),Math.ulp(y));largest=Math.max(largest,delta);}else okay&=a[k]==b[k];}if(okay){match=j;error=largest;break;}}
   if(match<0){System.err.println("Fallback expected="+Arrays.toString(Arrays.stream(a).mapToObj(v->Float.intBitsToFloat(v)).toArray())+" actual="+pending.stream().map(v->Arrays.toString(Arrays.stream(v).mapToObj(Float::intBitsToFloat).toArray())).toList());}
   check(match>=0,"fallback physical state exceeds 8 ULP + 1e-5, identity="+entry.getKey());pending.remove(match);worst=Math.max(worst,error);
  }}return worst;
 }
 static void measure(Path input,Path out,int policy)throws Exception{
  System.setProperty("cmi.r8.policy",Integer.toString(policy));System.clearProperty("cmi.r8.forceAudit");
  try(var production=new R7ChainReplayGpu.Replay(input,"production");var nativePath=new R7ChainReplayGpu.Replay(input,"rt-aabb");var csv=Files.newBufferedWriter(out.resolve("samples-policy-"+policy+".csv"))){
   csv.write("comparison,pair,side,path,gpu_ms,cpu_submit_ms,native_results,reference_fallbacks\n");
   R7ChainReplayGpu.Replay[][] paths={{production,production},{nativePath,nativePath},{production,nativePath}};
   String[] labels={"AA_production","AA_native","AB_production_native"};
   for(int c=0;c<3;c++)for(int pair=-8;pair<64;pair++)for(int turn=0;turn<2;turn++){
    int side=(pair+turn)&1;var replay=paths[c][side];int before=replay.hardware==null?0:replay.hardware.fallbacks,used=replay.hardware==null?0:replay.hardware.ordinaryUsed;
    double[] timing=replay.run();if(pair>=0)csv.write(labels[c]+","+pair+","+side+","+(replay.hardware==null?"production":"rt-force")+","+timing[0]+","+timing[1]+","+(replay.hardware==null?0:replay.hardware.ordinaryUsed-used)+","+(replay.hardware==null?0:replay.hardware.fallbacks-before)+"\n");
   }
  }
 }
 public static void main(String[] args)throws Exception{
  Path out=Path.of(System.getProperty("cmi.benchmark.output")),input=Path.of(System.getProperty("cmi.r7.capture"));Files.createDirectories(out);check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);R7ChainReplayGpu.contextForCapture(input);long window=GLFW.glfwCreateWindow(32,32,"R9 complete capture",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();var result=new LinkedHashMap<String,Object>();result.put("passed",false);var cases=new ArrayList<Object>();
  try{
   Map<String,Object> reference;try(var replay=new R7ChainReplayGpu.Replay(input,"complete-reference")){replay.run();reference=R8SemanticState.snapshot(replay);replay.run();check(reference.equals(R8SemanticState.snapshot(replay)),"complete reference A/A");}
   for(int policy:new int[]{83,211,339}){
    System.setProperty("cmi.r8.policy",Integer.toString(policy));System.setProperty("cmi.r8.forceAudit","true");
    try(var replay=new R7ChainReplayGpu.Replay(input,"rt-aabb")){
     replay.run();if(policy==83){var snapshot=read(replay.hardware.scratch,Math.min(4<<20,ShaderEfficiencyUpdateGpuValidation.Frame.size(replay.hardware.scratch)));byte[] payload=new byte[snapshot.remaining()];snapshot.get(payload);Files.write(out.resolve("continuation-snapshot.bin"),payload);}R8RealChainGpu.compare(reference,R8SemanticState.snapshot(replay),true);
     check(replay.hardware.fallbacks==0&&replay.hardware.forceQueries==replay.hardware.ordinaryQueries&&replay.hardware.forceQueries>0,"all real force rows verified without fallback");
     cases.add(Map.of("policy",policy,"queries",replay.hardware.forceQueries,"passed",true));
    }
   }
   System.clearProperty("cmi.r8.forceAudit");
   java.nio.ByteBuffer[] fixedGrid={null};Map<String,Object> productionReference;Map<String,List<int[]>> productionRecords;try(var production=new R7ChainReplayGpu.Replay(input,"production")){currentProductionSource(production);fixedGrid(production,fixedGrid,true);production.run();productionReference=R8SemanticState.snapshot(production);productionRecords=records(production);}
   if(!Boolean.getBoolean("cmi.r9.measure"))for(String mutation:List.of("nonce","epoch","incomplete","overflow","offset")){
    System.setProperty("cmi.r8.policy","339");System.setProperty("cmi.r9.mutation",mutation);
    try(var replay=new R7ChainReplayGpu.Replay(input,"rt-aabb")){fixedGrid(replay,fixedGrid,false);replay.run();R8RealChainGpu.compare(productionReference,R8SemanticState.snapshot(replay),true);double error=physicalBound(productionRecords,records(replay));cases.add(Map.of("mutation",mutation,"frozen_grid_order",true,"max_physical_error",error,"passed",true));}
    finally{System.clearProperty("cmi.r9.mutation");}
   }
   if(Boolean.getBoolean("cmi.r9.measure"))for(int policy:new int[]{83,211,339})measure(input,out,policy);
   result.put("actual_capture",true);result.put("input",input.toString());result.put("process_id",ProcessHandle.current().pid());result.put("measured",Boolean.getBoolean("cmi.r9.measure"));result.put("passed",true);
  }finally{System.clearProperty("cmi.r8.policy");System.clearProperty("cmi.r8.forceAudit");R8ForceOracle.close();result.put("checks",checks);result.put("cases",cases);Files.writeString(out.resolve("real-chain.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
