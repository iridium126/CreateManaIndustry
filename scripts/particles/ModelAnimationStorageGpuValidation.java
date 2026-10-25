import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Production MODEL movement, emission, 20Hz animation, allocation and publication. */
public class ModelAnimationStorageGpuValidation extends AllayAnimationGpuValidation {
 static int update,emit,animate,grid,block,modelPrepare,modelFilter,modelCompact;
 static void ui(int p,String n,int v){GL41.glProgramUniform1ui(p,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(p,n),v);}
 static void bool(int p,String n,boolean v){GL41.glProgramUniform1i(p,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(p,n),v?1:0);}
 static void f(int p,String n,float v){GL41.glProgramUniform1f(p,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(p,n),v);}
 static void gen(int p,String n,int v){GL41.glProgramUniform2ui(p,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(p,n),v,0);}
 static final class Rig extends Fixture {
  final AllayAnimationGpu animation=new AllayAnimationGpu();
  final int[] pools,lists,textures=new int[2];
  final int identities,damage,bake,spatial,corrections,members,stars,state,commandsIn;
  int bank,live,models,writes;boolean countWrites=true,measure,stormGrid,packagePrepared,membersPublished;int[] stageQueries;
  void mark(int stage){if(stageQueries!=null)GL33.glQueryCounter(stageQueries[stage],GL33.GL_TIMESTAMP);}
  Rig(int capacity){this(capacity,true);}
  Rig(int capacity,boolean allocateAnimation){
   super(capacity,Math.min(capacity,AllayAnimationGpu.CAPACITY),Math.min(capacity,131072));
   pools=new int[]{pool,alloc(bytes(capacity*64))};lists=new int[]{pop,alloc(bytes(32+4*(modelCap*2+stormCap)+64))};
   for(int i=0;i<2;i++){
    put(lists[i],bytes(32).putInt(12,1).putInt(16,7).putInt(24,modelCap).putInt(28,stormCap));
    textures[i]=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,lists[i]);
   }
   identities=alloc(bytes(4+capacity*8));damage=alloc(bytes(16));bake=alloc(bytes(128));
   spatial=alloc(bytes((ParticleBuffers.GRID_TABLE+capacity)*4));corrections=alloc(bytes(Math.max(64,Math.min(capacity,131072)*32)));
   members=alloc(bytes(Math.max(64,Math.min(capacity,131072)*4)));stars=alloc(bytes(1024));state=alloc(bytes(1024));commandsIn=alloc(bytes(12*4));
   if(allocateAnimation)animation.ensure(AllayAnimationGpu.CAPACITY);
   var header=headers();header.putFloat(5*16,20).putFloat(5*16+4,20);put(emit,header);
  }
  void bindingsFor(int program,boolean current){
   bindings();bind(0,current?pools[1-bank]:pools[bank]);bind(1,pools[1-bank]);bind(24,current?lists[1-bank]:lists[bank]);bind(25,lists[1-bank]);
   bind(11,bake);bind(13,spatial);bind(16,damage);bind(18,stars);bind(19,corrections);bind(21,members);bind(26,stars);bind(27,state);bind(28,state);bind(31,identities);
   animation.bindCompute(program);GL42.glBindImageTexture(2,textures[1-bank],0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);
   bool(program,"uModelMembersEnabled",true);bool(program,"uModelMembersPublish",true);bool(program,"uPopulationReadEnabled",true);gen(program,"uPopulationGeneration",7);
   bool(program,"uPopulationWriteEnabled",true);gen(program,"uPopulationWriteGeneration",7);ui(program,"uPopulationModelCapacity",modelCap);ui(program,"uPopulationStormCapacity",stormCap);
   ui(program,"uIdentityReadBase",bank*cap);ui(program,"uIdentityWriteBase",(1-bank)*cap);ui(program,"uIdentityCapacity",cap);
   ui(program,"uCapacity",cap);ui(program,"uKillEmit",-1);bool(program,"uStarfallEnabled",false);bool(program,"uDomePoseStorageEnabled",false);
   bool(program,"uAllayAnimationCountWrites",countWrites);GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"uCollisionOn"),0);
  }
  void indirect(int program,int command){GL20.glUseProgram(program);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,args);GL43.glDispatchComputeIndirect(command*12L);AllayAnimationGpu.barrier();check(GL11.glGetError()==0,"MODEL indirect dispatch");}
  void buildGrid(int program){
   GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,spatial);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
   AllayAnimationGpu.barrier();bindingsFor(program,false);indirect(program,ParticleBuffers.STORM_DISPATCH);
  }
  void begin(float dt){
   packagePrepared=membersPublished=false;mark(0);animation.beginFrame(dt);animation.prepareAllocator(modelPrepare);mark(1);bindingsFor(prepare,false);put(previous,bytes(16).putInt(0,live));ui(prepare,"uPhase",0);dispatch(prepare,1);
   bindingsFor(reset,false);dispatch(reset,1);AllayAnimationGpu.barrier();mark(2);
   if(stormGrid)buildGrid(grid);mark(3);
   bindingsFor(update,false);f(update,"uDt",dt);ui(update,"uModelPass",1);indirect(update,0);mark(4);ui(update,"uModelPass",2);indirect(update,ParticleBuffers.MODEL_DISPATCH);mark(5);
  }
  void spawn(int count,int emitter){
   bindingsFor(ModelAnimationStorageGpuValidation.emit,false);bind(4,commandsIn);
   put(commandsIn,bytes(48).putFloat(12,count).putFloat(16,emitter).putFloat(20,17));
   ui(ModelAnimationStorageGpuValidation.emit,"uEmitCount",1);ui(ModelAnimationStorageGpuValidation.emit,"uTotalSpawn",count);
   dispatch(ModelAnimationStorageGpuValidation.emit,(count+63)/64);AllayAnimationGpu.barrier();
  }
  void spawnBlock(int count,int emitter){
   bindingsFor(block,false);bind(4,commandsIn);put(commandsIn,bytes(48).putFloat(12,count).putFloat(16,emitter));
   ui(block,"uEmitterCount",1);ui(block,"uEmitterOffset",0);f(block,"uDtScale",1);f(block,"uRangeSquared",1e6f);dispatch(block,1);AllayAnimationGpu.barrier();
  }
  void overwriteWithPackages(int keep){
   bindingsFor(prepare,true);ui(prepare,"uPhase",1);ui(prepare,"uPackageStaged",0);dispatch(prepare,1);packagePrepared=true;
   var records=bytes(128);particle(records,0,0,999,999,20);particle(records,1,0,999,999,20);
   GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,pools[1-bank]);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,keep*64L,records);
   put(counter,bytes(16).putInt(0,keep+2).putInt(4,keep));
  }
  void finish(boolean commit,boolean keys){
   mark(6);bindingsFor(prepare,true);bool(prepare,"uModelMembersPublish",!packagePrepared&&!membersPublished);ui(prepare,"uPackageStaged",packagePrepared?1:0);ui(prepare,"uPhase",1);dispatch(prepare,1);mark(7);
   if(packagePrepared){for(int p:new int[]{modelFilter,modelCompact}){bindingsFor(p,true);bind(7,sorted);indirect(p,ParticleBuffers.MODEL_FILTER_DISPATCH);}bindingsFor(prepare,true);ui(prepare,"uPhase",4);dispatch(prepare,1);}
   mark(8);if(animation.tickPending()){bindingsFor(animate,true);indirect(animate,ParticleBuffers.CANDIDATE_MODEL_DISPATCH);}
   mark(9);if(keys){bindingsFor(keygen,true);ui(keygen,"uPackageStaged",packagePrepared?1:0);ui(keygen,"uModelPass",1);indirect(keygen,1);mark(10);ui(keygen,"uModelPass",2);indirect(keygen,ParticleBuffers.CANDIDATE_MODEL_DISPATCH);mark(11);}
   bindingsFor(prepare,true);ui(prepare,"uPhase",2);dispatch(prepare,1);AllayAnimationGpu.barrier();mark(12);
   if(!measure)writes=animation.allocated()?read(animation.allocatorBuffer(1-animation.allocatorReadBank()),4).getInt(0):0;
   if(commit){animation.commit();bank=1-bank;if(!measure){live=read(counter,16).getInt(0);models=read(lists[bank],32).getInt(0);}}else animation.abort();
  }
  ByteBuffer roster(){return read(lists[bank],32+models*8);}
  void remove(int rank){int slot=roster().getInt(32+rank*8);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,pools[bank]);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,slot*64L+52,new float[]{-1});}
  @Override public void close(){animation.close();for(int texture:textures)GL11.glDeleteTextures(texture);super.close();}
 }
 static void immutableAndCadence(){
  try(var r=new Rig(1024)){
   r.begin(0);r.spawn(65,1);r.finish(true,true);check(r.models==65&&r.live==65,"Initial MODEL list");check(r.writes==130,"Birth did not initialize exactly two records");
   var state0=read(r.animation.buffer(r.animation.readBank()),AllayAnimationGpu.CAPACITY*64);var list0=r.roster();int initialBank=r.animation.readBank();
   r.begin(.01f);r.finish(true,true);check(r.writes==0&&r.animation.readBank()==initialBank,"Non-tick movement published animation");
   check(read(r.animation.buffer(initialBank),state0.capacity()).equals(state0),"Non-tick frame wrote committed animation");
   var roster=r.roster();var idsBefore=read(r.identities,4+r.cap*8);int[] stable=new int[66];
   for(int rank=0;rank<r.models;rank++){int pool=roster.getInt(32+rank*8),slot=roster.getInt(36+rank*8);stable[idsBefore.getInt(4+(r.bank*r.cap+pool)*4)]=slot;}
   r.begin(.05f);r.finish(false,true);check(r.animation.readBank()==initialBank&&r.animation.tick()==0,"Aborted animation tick became visible");check(read(r.animation.buffer(initialBank),state0.capacity()).equals(state0),"Rejected animation mutated read bank");
   r.begin(.05f);r.finish(true,true);check(r.animation.tick()==1&&r.animation.readBank()!=initialBank&&r.writes==65,"Animation tick did not publish once");
   roster=r.roster();var idsAfter=read(r.identities,4+r.cap*8);for(int rank=0;rank<r.models;rank++){int pool=roster.getInt(32+rank*8),slot=roster.getInt(36+rank*8);check(stable[idsAfter.getInt(4+(r.bank*r.cap+pool)*4)]==slot,"Compaction changed animation ownership");}
   int b=r.animation.readBank();long tick=r.animation.tick();r.begin(0);r.finish(true,true);check(r.writes==0&&r.animation.tick()==tick&&r.animation.readBank()==b,"Pause advanced animation");
   r.begin(.2f);r.finish(true,true);check(r.animation.tick()==5&&r.writes==65,"Skipped ticks caused repeated animation dispatch");
   // Off-tick births in an aborted candidate may touch only slots absent from the read roster.
   var snapshot=read(r.animation.buffer(r.animation.readBank()),65*64);r.begin(0);r.spawn(17,1);r.finish(false,false);
   check(r.models==65&&read(r.animation.buffer(r.animation.readBank()),65*64).equals(snapshot),"Aborted births overwrote an existing owner");
   r.begin(0);r.spawn(17,1);r.finish(true,true);check(r.models==82&&r.writes==34,"Birth retry leaked allocator slots");
   check(r.animation.bytes()==32L*1024*1024,"Animation storage depends on main-pool capacity");
  }
 }
 static void capacity(){
  try(var r=new Rig(262208)){
   r.begin(0);r.spawn(262143,1);r.finish(true,false);check(r.models==262143,"Capacity - 1");
   r.begin(0);r.spawn(65,1);r.finish(true,false);check(r.models==262144&&r.live==262144,"MODEL hard limit or partial batch");
   r.begin(0);r.spawn(64,1);r.spawnBlock(64,1);r.spawn(64,2);r.spawn(3,0);r.finish(true,false);check(r.models==262144&&r.live==262147&&r.writes==0,"Full MODEL pool blocked ordinary particles or wrote animation");
   int oldSlot=r.roster().getInt(36);
   var poison=bytes(64);for(int i=0;i<16;i++)poison.putInt(i*4,0xdeadbeef);for(int b=0;b<2;b++){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,r.animation.buffer(b));GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,oldSlot*64L,poison);}
   r.remove(0);r.begin(0);r.spawn(1,1);r.finish(true,false);check(r.models==262143,"Candidate-only retirement was reused before commit");
   r.begin(0);r.spawn(1,1);r.finish(false,false);check(r.models==262143,"Rejected reuse escaped publication");
   r.begin(0);r.spawn(1,1);r.finish(true,false);check(r.models==262144&&r.writes==2,"Retired animation slot could not be reused");
   var roster=r.roster();var seen=new BitSet(262144);for(int i=0;i<r.models;i++){int a=roster.getInt(36+i*8);check(a>=0&&a<262144&&!seen.get(a),"Duplicate/out-of-range animation slot");seen.set(a);}check(seen.get(oldSlot),"Retired stable slot leaked");
   for(int b=0;b<2;b++){var states=read(r.animation.buffer(b),(oldSlot+1)*64);int at=oldSlot*64;check(states.getInt(at+16)==0&&states.getInt(at+20)==0&&states.getInt(at+24)==0&&states.getInt(at+32)==0&&states.getInt(at+40)==0x80000000,"Reused animation inherited previous owner state");}
  }
  try(var r=new Rig(1)){
   r.begin(0);r.spawn(1,1);r.finish(true,false);var before=read(r.animation.allocatorBuffer(r.animation.allocatorReadBank()),AllayAnimationGpu.RETIRED*4);
   r.begin(0);r.spawn(64,1);r.finish(true,false);var after=read(r.animation.allocatorBuffer(r.animation.allocatorReadBank()),AllayAnimationGpu.RETIRED*4);
   for(int i=AllayAnimationGpu.FREE*4;i<before.capacity();i++)check(before.get(i)==after.get(i),"Main-pool overflow leaked animation capacity");
  }
 }
 static void cadence(){
  for(int hz:new int[]{10,20,60,144,240,-1})try(var r=new Rig(4)){
   r.begin(0);r.spawn(1,1);r.finish(true,false);int pool=r.roster().getInt(32);
   GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,r.pools[r.bank]);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,pool*64L+16,new float[]{1,0,0});
   double t=0;int frame=0;while(t<2){float dt=(float)Math.min(2-t,hz>0?1./hz:new double[]{.007,.037,.012,.05}[frame++%4]);r.begin(dt);r.finish(true,false);t+=dt;}
   int slot=r.roster().getInt(36);var state=read(r.animation.buffer(r.animation.readBank()),(slot+1)*64);
   float amount=(state.getInt(slot*64+12)>>>16)/65535f,phase=state.getFloat(slot*64+20);
   check(Math.abs(amount-.2)<.0003,"Independent cadence changed walk amount hz="+hz+" amount="+amount);
   double expected=8-.3*(1-Math.pow(.6,40));check(Math.abs(Math.atan2(Math.sin(phase-expected),Math.cos(phase-expected)))<.004,"Independent cadence changed walk phase hz="+hz+" phase="+phase);
   check(r.animation.tick()==40&&r.animation.version()<=40,"Animation version followed render frames");
  }
 }
 static void sortPayload(){
  try(var r=new Rig(128)){
   r.begin(0);r.spawn(65,1);r.spawn(17,0);r.finish(true,true);
   var keys=read(r.sorted,65*8);var list=r.roster();int[] owner=new int[r.cap];Arrays.fill(owner,-1);for(int i=0;i<65;i++)owner[list.getInt(32+i*8)]=list.getInt(36+i*8);
   for(int i=0;i<65;i++){int pool=keys.getInt(i*8+4)>>>2,slot=keys.getInt(i*8)>>>9;check(owner[pool]==slot&&(keys.getInt(i*8)&511)==255,"MODEL sort payload lost stable animation index");}
   try(var shadow=new ModelShadowGpu(r.cap,ModelPopulationGpuValidation::source)){
    var culling=new com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling();culling.clear();
    shadow.prepare(r.pools[r.bank],r.emit,r.counter,culling,0,0,0,.35f,.6f,1,0,new float[16],new float[16],new float[4],r.lists[r.bank],7);
    var items=read(shadow.instances(),65*8);for(int i=0;i<65;i++){int pool=items.getInt(i*8+4)>>>2;check(owner[pool]==items.getInt(i*8)>>>9,"Shadow payload lost animation index");}
   }
  }
 }
 static void movementEquivalence(){
  try(var r=new Rig(257)){
   r.begin(0);r.spawn(129,1);r.spawn(127,0);r.finish(true,false);
   r.begin(.037f);var separated=read(r.pools[1-r.bank],256*64);var ids=read(r.identities,4+r.cap*8);
   int[] owners=new int[130];for(int i=0;i<256;i++){int id=ids.getInt(4+((1-r.bank)*r.cap+i)*4);if(separated.getInt(i*64+60)==1)owners[id]=i;}
   put(r.counter,bytes(16));r.bindingsFor(update,false);ui(update,"uModelPass",0);bool(update,"uAllayAnimationWriteEnabled",false);dispatch(update,5);var together=read(r.pools[1-r.bank],256*64);ids=read(r.identities,4+r.cap*8);
   for(int i=0;i<256;i++)if(together.getInt(i*64+60)==1){int id=ids.getInt(4+((1-r.bank)*r.cap+i)*4),old=owners[id];for(int k=0;k<16;k++)check(together.getInt(i*64+k*4)==separated.getInt(old*64+k*4),"MODEL-list scheduling changed physical output");}
   r.animation.abort();
  }
 }
 static void radixPayload(){
  int hist=compile("radix_hist.comp"),scan=compile("radix_scan.comp"),scatter=compile("radix_scatter.comp");
  try{for(int n:new int[]{82,65537}){
   var input=bytes(n*8);var expected=new HashSet<Long>();int modelCount=0;
   for(int i=0;i<n;i++){int type=i%4==0?1:0;if(type==0)modelCount++;int key=(((i*65537)&262143)<<9)|(type<<8)|((i*37)&255),payload=(i<<2)|type;input.putInt(i*8,key).putInt(i*8+4,payload);expected.add((Integer.toUnsignedLong(key)<<32)|Integer.toUnsignedLong(payload));}
   var commands=bytes(ParticleBuffers.INDIRECT_UINTS*4).putInt(ParticleBuffers.IDX_CNT_GHOST*4,modelCount).putInt(ParticleBuffers.IDX_CNT_ALPHA*4,n-modelCount);
   int in=buffer(input),out=buffer(bytes((n+1)*8).putLong(n*8,0x1234567887654321L)),c=buffer(commands),h=buffer(bytes(ParticleBuffers.RADIX_BINS*4)),o=buffer(bytes((ParticleBuffers.RADIX_BINS+1)*4));
   try{bind(2,c);bind(6,in);bind(7,out);bind(9,h);bind(10,o);ui(hist,"uShift",0);ui(scatter,"uShift",0);int groups=n<ParticleBuffers.SORT_GROUP_THRESHOLD?(n+63)/64:(n+1023)/1024;dispatch(hist,groups);dispatch(scan,1);dispatch(scatter,groups);
    var sorted=read(out,(n+1)*8);int last=-1;for(int i=0;i<n;i++){int key=sorted.getInt(i*8),payload=sorted.getInt(i*8+4),low=key&511;check(low>=last,"Animation bits changed radix order");last=low;check(expected.remove((Integer.toUnsignedLong(key)<<32)|Integer.toUnsignedLong(payload)),"Radix lost or duplicated animation payload");check(((low>>8)&1)==(payload&3),"MODEL/ALPHA partition changed");}check(expected.isEmpty()&&sorted.getLong(n*8)==0x1234567887654321L,"Radix wrote outside its live payload");
   }finally{for(int buffer:new int[]{in,out,c,h,o})GL15.glDeleteBuffers(buffer);}
  }}finally{for(int p:new int[]{hist,scan,scatter})deleteProgram(p);}
 }
 static void packagePressure(){
  try(var r=new Rig(128)){
   r.begin(0);r.spawn(65,1);r.spawn(17,0);r.finish(true,true);
   r.begin(0);r.overwriteWithPackages(40);r.finish(false,true);
   check(r.models==65&&r.live==82,"Aborted package overwrite changed committed population");
   r.begin(0);r.overwriteWithPackages(40);r.finish(true,true);
   check(r.models==23&&r.live==42,"Package overwrite left stale MODEL roster entries");
   check(read(r.commands,ParticleBuffers.INDIRECT_UINTS*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==23,"Package overwrite changed MODEL draw population");
   var rows=r.roster();for(int i=0;i<r.models;i++)check(rows.getInt(32+i*8)<40,"Package record entered MODEL animation update");
   r.begin(0);r.spawn(42,1);r.finish(true,true);check(r.models==65&&r.writes==84,"Package eviction leaked animation slots");
  }
 }
 static void materialExit(){
  try(var r=new Rig(128)){
   r.begin(0);r.spawn(65,1);r.finish(true,true);
   var h=headers();h.putFloat(320+7*16,0);put(r.emit,h);
   r.begin(0);r.finish(true,true);check(r.models==0&&r.live==65,"Material exit lost motion or retained MODEL members");
   var allocator=read(r.animation.allocatorBuffer(r.animation.allocatorReadBank()),AllayAnimationGpu.ALLOCATOR_WORDS*4);
   int retired=0;for(int i=0;i<8192;i++)retired+=Integer.bitCount(allocator.getInt((AllayAnimationGpu.RETIRED+i)*4));
   check(retired==65,"Material exit leaked animation ownership");
  }
 }
 // Call the production publication boundary with emission, a foreign pass, or no program current.
 static void runtimePublication()throws Exception{
  var method=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.ParticleDispatch").getDeclaredMethod("prepareModelPublication",ParticlePrograms.class,boolean.class,boolean.class);method.setAccessible(true);
  for(int scenario=0;scenario<4;scenario++)try(var r=new Rig(128)){
   if(scenario==2){var h=headers();h.putFloat(2*320+17*16+4,3).putFloat(2*320+18*16,2).putFloat(2*320+18*16+4,16).putFloat(2*320+18*16+12,6);put(r.emit,h);}
   r.begin(0);
   if(scenario==1)r.spawnBlock(65,1);else r.spawn(65,scenario==2?2:1);
   if(scenario==3)r.spawn(11,0);
   r.bindingsFor(r.prepare,true);bool(r.prepare,"uModelMembersPublish",false);
   int caller=scenario==1?block:scenario==3?0:emit;GL20.glUseProgram(caller);
   var owner=new ParticlePrograms();var field=ParticlePrograms.class.getDeclaredField("prepareDispatch");field.setAccessible(true);field.setInt(owner,r.prepare);
   method.invoke(null,owner,false,true);AllayAnimationGpu.barrier();
   check(GL11.glGetError()==0,"Runtime MODEL publication GL error");
   var roster=read(r.lists[1-r.bank],32);check(roster.getInt(0)==65,"Runtime publication lost all newly emitted MODEL members scenario="+scenario);
   check(read(r.counter,16).getInt(8)==0,"Runtime publication did not consume append cursors");
   check(roster.getInt(4)==(scenario==2?65:0),"Runtime publication lost the STORM subset");
   check(GL20.glGetUniformi(emit,GL20.glGetUniformLocation(emit,"uModelMembersEnabled"))==1,"Publication changed the emitter's MODEL admission flag");
   // Package refresh must preserve the roster already published before package staging.
   if(scenario==3){GL20.glUseProgram(emit);method.invoke(null,owner,true,false);AllayAnimationGpu.barrier();check(read(r.lists[1-r.bank],32).getInt(0)==65,"Package refresh discarded the published MODEL roster");}
   r.membersPublished=true;
   if(scenario==3){r.packagePrepared=true;GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,r.counter);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,4,new int[]{76});}
   r.finish(true,true);check(r.models==65&&r.live==(scenario==3?76:65),"Published MODELs did not enter the committed pool");
   check(read(r.commands,ParticleBuffers.INDIRECT_UINTS*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==65,"Published MODELs were not submitted for drawing");
   r.begin(.01f);r.finish(true,true);check(r.models==65&&r.live==(scenario==3?76:65),"New MODEL members disappeared on the next motion frame");
  }
 }
 static void ownerLifecycle()throws Exception{
  var gpu=new ParticleBuffers();try{
   check(gpu.init(2048,4),"Production particle owner init");
   gpu.beginComputeFrame(.01f);gpu.allayAnimation().prepareAllocator(modelPrepare);gpu.swap();gpu.commitPopulation(0);
   check(!gpu.allayAnimation().allocated(),"Non-MODEL frame allocated animation");
   float[] header=new float[80];header[28]=2;gpu.setEmitterHeader(1,header);
   gpu.beginComputeFrame(.01f);gpu.allayAnimation().prepareAllocator(modelPrepare);
   check(gpu.allayAnimation().bytes()==33554432L,"Owner used main capacity for animation");
   gpu.swap();gpu.commitPopulation(0);check(gpu.allayAnimation().tick()==0&&gpu.allayAnimation().readBank()==0,"Motion swap changed animation bank");
   gpu.beginComputeFrame(.05f);gpu.allayAnimation().prepareAllocator(modelPrepare);gpu.restoreCommittedFrame();
   check(gpu.allayAnimation().tick()==0,"Owner rollback advanced animation clock");
   gpu.beginComputeFrame(.05f);gpu.allayAnimation().prepareAllocator(modelPrepare);gpu.swap();gpu.commitPopulation(0);
   check(gpu.allayAnimation().tick()==1&&gpu.allayAnimation().version()==1,"Owner did not commit independent animation version");
   gpu.invalidateIdentities();check(gpu.populationBufferId(0)!=0,"Storm identity reset invalidated ordinary MODEL roster");
   gpu.clearParticles();check(gpu.allayAnimation().tick()==0&&gpu.allayAnimation().version()==0,"Pool reset retained animation clock");
  }finally{gpu.free();}
 }
 public static void main(String[] args)throws Exception{
  GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"MODEL animation ownership",0,0);
  try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));update=compile("update.comp");emit=compile("emit.comp");animate=compile("allay_animate.comp");block=compile("block_emit.comp");modelPrepare=compile("model_prepare.comp");modelFilter=compile("model_filter.comp");modelCompact=compile("model_compact.comp");runtimePublication();if(Arrays.asList(args).contains("--publication-only")){System.out.println("MODEL runtime publication GPU: "+checks+" assertions passed");return;}immutableAndCadence();capacity();cadence();sortPayload();movementEquivalence();radixPayload();packagePressure();materialExit();ownerLifecycle();check(GL11.glGetError()==0,"MODEL animation GL error");System.out.println("MODEL animation storage GPU: "+checks+" assertions passed");}finally{for(int p:new int[]{update,emit,animate,block,modelPrepare,modelFilter,modelCompact})if(p!=0)deleteProgram(p);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
