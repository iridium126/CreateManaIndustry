package com.iridium126.createmanaindustry.client.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall;
import com.iridium126.createmanaindustry.content.allaystorm.AllayDome;
import com.iridium126.createmanaindustry.content.allaystorm.AllayHelix;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.IntBuffer;
import java.util.Arrays;
/** A roster snapshot and identity-keyed phase state travel with each committed simulation. */
public final class StarfallGpuBuffers implements AutoCloseable {
 public static final StarfallGpuBuffers INSTANCE=new StarfallGpuBuffers();
 public static final int DATA_BINDING=26, STATE_READ=27, STATE_WRITE=28, HEADER=16, WAVE_WORDS=312, ROSTER=131072;
 public static final int GAUSSIAN=HEADER+ROSTER+68*WAVE_WORDS, GAUSSIAN_WORDS=4*4096*3, HOME_ACK=GAUSSIAN+GAUSSIAN_WORDS, DOME_DATA=HOME_ACK+ROSTER/32, GATHER_SOURCE=DOME_DATA+com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.WORDS, GATHER_SOURCE_WORDS=16+2*1536, DOME_FLOW_MAP=GATHER_SOURCE+GATHER_SOURCE_WORDS, DOME_FLOW_SOURCE=DOME_FLOW_MAP+12288, HELIX_GATHER_FRAMES=DOME_FLOW_SOURCE+4*66*2, HELIX_FRAME_WORDS=4+9*32, NAV_MEMBER_BY_RANK=HELIX_GATHER_FRAMES+4*HELIX_FRAME_WORDS, HELIX_RECOVERY=NAV_MEMBER_BY_RANK+4*12288, DATA_WORDS=HELIX_RECOVERY+4*20, COLUMN_STATE=ROSTER*2+256+128+64, COLUMN_ARC=COLUMN_STATE+64*64*16, COLUMN_ARC_WORDS=ColumnGpuLayout.WORDS, COMPLETIONS=COLUMN_ARC+64*64*COLUMN_ARC_WORDS, COMPLETION_WORDS=4*384+64*2, GATHER_STREAK=COMPLETIONS+COMPLETION_WORDS, GATHER_COUNTS=GATHER_STREAK+ROSTER, GATHER_READY=GATHER_COUNTS+16, GATHER_ELIGIBLE=GATHER_READY+4*384, GATHER_BLOCKED=GATHER_ELIGIBLE+4*384, HELIX_BLOCK_WINDOW=GATHER_BLOCKED+ROSTER, HELIX_BLOCK_WORDS=4*4096*4, DOME_JOIN=HELIX_BLOCK_WINDOW+HELIX_BLOCK_WORDS, DOME_CONTACT_DELTA=DOME_JOIN+4*12288*8, DOME_CONTACT_QUEUE=DOME_CONTACT_DELTA+ROSTER*4, DOME_CONTACT_ARGS=DOME_CONTACT_QUEUE+ROSTER, STATE_CORE_WORDS=DOME_CONTACT_ARGS+8;
 /** Two packed words per stable member, per bank: yaw/pitch direction and arm blend/speed/flags.
  * Gather windows reuse the join record with a high-bit identity tag; LOCK initializes a fresh join. */
 public static final int DOME_POSE=STATE_CORE_WORDS, DOME_POSE_WORDS=ROSTER*2, DOME_FLOW_STATE=DOME_POSE+DOME_POSE_WORDS, DOME_FLOW_WORDS=4*66*2, DOME_GATHER_WINDOW=DOME_JOIN;
 public static final int DOME_PATH_BASE=DOME_FLOW_STATE+DOME_FLOW_WORDS, DOME_PATH_RECORD_WORDS=12, DOME_PATH_RECORDS=ROSTER,
  DOME_PATH_REQUESTS=DOME_PATH_BASE+DOME_PATH_RECORDS*DOME_PATH_RECORD_WORDS, DOME_PATH_CONTROL=DOME_PATH_REQUESTS+ROSTER/32,
  DOME_PATH_TASKS=DOME_PATH_CONTROL+32, DOME_PATH_TASK_WORDS=1048, DOME_PATH_TASK_COUNT=128,
  NAV_EXTRA=DOME_PATH_TASKS+DOME_PATH_TASK_COUNT*DOME_PATH_TASK_WORDS, NAV_EXTRA_WORDS=16, DEPEN_MASK=NAV_EXTRA+ROSTER*NAV_EXTRA_WORDS, DEPEN_CONTROL=DEPEN_MASK+ROSTER/32, DEPEN_JOBS=DEPEN_CONTROL+16, STATE_WORDS=DEPEN_JOBS+16;
 public static final long DOME_PATH_BYTES=8L*(STATE_WORDS-DOME_PATH_BASE)+12;
 private int domePathDispatch;
 public int domePathDispatchBuffer(){return domePathDispatch;}
 public static final long DOME_POSE_BYTES=2L*DOME_POSE_WORDS*Integer.BYTES;
 private final int[] words=new int[DATA_WORDS],data={0,0},state={0,0},textures={0,0},stateTextures={0,0};
 private final long[] versions={-1,-1},rosterVersions={-1,-1},generations={-1,-1},gaussianVersions={-1,-1};private long version,rosterVersion,gaussianVersion,uploadedBytes;
 private final long[] ackVersions={-1,-1};private long ackVersion;
 private final int[] returningOwners=new int[ROSTER];
 public void trackReturn(ClientboundStormWavePacket packet,long[] ranks){var mask=java.util.BitSet.valueOf(ranks);for(int rank=mask.nextSetBit(0);rank>=0&&rank<packet.members().length;rank=mask.nextSetBit(rank+1)){int member=packet.members()[rank];returningOwners[member]=packet.waveId();words[HOME_ACK+member/32]&=~(1<<(member&31));}ackVersion++;}
 public void arrived(int id,int member){if(member<0||member>=ROSTER||returningOwners[member]!=id)return;int at=HOME_ACK+member/32,bit=1<<(member&31);if((words[at]&bit)==0){words[at]|=bit;ackVersion++;}}
 private final java.util.Map<Integer,java.util.BitSet> completed=new java.util.HashMap<>();
 private final int[] columnOwners=new int[64],columnRestoreTokens=new int[64];
 private final boolean[] columnWaiting=new boolean[64];
 public void columnWaiting(int slot,int id,boolean waiting){if(columnOwners[slot-4]==id&&columnWaiting[slot-4]==waiting)return;columnOwners[slot-4]=id;columnWaiting[slot-4]=waiting;words[HEADER+ROSTER+slot*WAVE_WORDS+26]=Float.floatToRawIntBits(waiting?1:0);version++;}
 public boolean columnWaiting(int slot,int id){return columnOwners[slot-4]==id&&columnWaiting[slot-4];}
 public void columnRestoreToken(int slot,int id,int token){if(columnOwners[slot-4]==id&&columnRestoreTokens[slot-4]==token)return;columnOwners[slot-4]=id;columnRestoreTokens[slot-4]=token;words[HEADER+ROSTER+slot*WAVE_WORDS+27]=token;version++;}
 public void completed(int id,long[] ranks){completed.computeIfAbsent(id,k->new java.util.BitSet()).or(java.util.BitSet.valueOf(ranks));rosterVersion++;version++;java.util.Arrays.fill(previousIds,-1);}
 private final float[] lastAxes=new float[4*3],lastSides=new float[4*3],oldAxes=new float[4*3],axisTimes=new float[4];private final int[] axisIds=new int[4];
 private final int[] gaussianIds=new int[4];private final int[][] gaussianMembers=new int[4][];
 private final long[] readyVersions={-1,-1};private long readyVersion;
 private final float[] centerNow=new float[68*3],centerPrevious=new float[68*3],centerTimes=new float[68];
 private final float[] headings=new float[68],previousHeadings=new float[68];
 private final int[] centerWaveIds=new int[68];
 private final int[][] previousMembers=new int[68][];private final int[] previousIds=new int[68];private int read;private IntBuffer upload;
 private final boolean[] enabled={false,false};private boolean hasWaves;
 private boolean pending,clearStates;private int domainToken=1;
 public StarfallGpuBuffers(){words[0]=domainToken;words[3]=Float.floatToRawIntBits(com.iridium126.createmanaindustry.content.allaystorm.StormMotion.INTERCEPT);resetFlowSource();}
 private void resetFlowSource(){for(int f=0;f<4*66;f++){words[DOME_FLOW_SOURCE+f*2]=0;words[DOME_FLOW_SOURCE+f*2+1]=Float.floatToRawIntBits(Float.POSITIVE_INFINITY);}}
 public static int completionOffset(int slot){return slot<4?slot*384:1536+(slot-4)*2;}
 private boolean domeLoaded;private final boolean[] domeUploaded={false,false};
 private int allocatedStateWords;
 private double preciseDomeClock=Double.NaN;
 private final ClientboundStormWavePacket[] referencePackets=new ClientboundStormWavePacket[4];
 private final ClientboundStormWavePacket[][] referenceBanks={new ClientboundStormWavePacket[4],new ClientboundStormWavePacket[4]};
 private final com.iridium126.createmanaindustry.content.allaystorm.HelixGatherRecovery[][] recoveries={new com.iridium126.createmanaindustry.content.allaystorm.HelixGatherRecovery[4],new com.iridium126.createmanaindustry.content.allaystorm.HelixGatherRecovery[4]};
 private final boolean[][] recoveryDirty={{true,true,true,true},{true,true,true,true}};
 private static com.iridium126.createmanaindustry.content.allaystorm.HelixGatherFrame.Sample referenceSample(ClientboundStormWavePacket p,float seconds){var selected=p.gatherFrames()[0];for(var frame:p.gatherFrames()){if(frame.seconds()>seconds)break;selected=frame;}return selected.sample(seconds);}
 private void prepareRecovery(int write){
  for(int slot=0;slot<4;slot++){
   var p=referencePackets[slot];var old=referenceBanks[read][slot];var recovery=recoveries[read][slot];
   if(p==null||old==null||p.waveId()!=old.waveId()||p.phase()>=3)recovery=null;
   else if(Double.isFinite(preciseDomeClock)){
    float elapsed=(float)(preciseDomeClock-p.startSec());if(elapsed< -52428.8f)elapsed+=104857.6f;else if(elapsed>52428.8f)elapsed-=104857.6f;
    float seconds=Math.max(0,elapsed-.25f);var previous=old.gatherFrames()[old.gatherFrames().length-1];var latest=p.gatherFrames()[p.gatherFrames().length-1];
    if(previous.version()!=latest.version()&&seconds>previous.seconds()+.251f){
     var before=referenceSample(old,seconds);if(recovery!=null)before=recovery.apply(before,seconds);
     recovery=com.iridium126.createmanaindustry.content.allaystorm.HelixGatherRecovery.between(seconds,before,referenceSample(p,seconds));
    }
   }
   if(recoveryDirty[write][slot]||recovery!=recoveries[write][slot]){float[] values=recovery==null?new float[20]:recovery.words();upload.clear();for(float value:values)upload.put(Float.floatToRawIntBits(value));upload.flip();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[write]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(HELIX_RECOVERY+slot*20)*4L,upload);uploadedBytes+=80;recoveryDirty[write][slot]=false;}
   recoveries[write][slot]=recovery;referenceBanks[write][slot]=p;
  }
 }
 private final int[][] flowBankIds={new int[4],new int[4]};
 private final int[] domeGatherIds=new int[4];private final float[] domeLocalLocks={Float.NaN,Float.NaN,Float.NaN,Float.NaN};
 /** Local transition clock; identity, deadline and server combat remain authoritative. */
 public float domeLockSec(int slot){return Float.intBitsToFloat(words[HEADER+ROSTER+slot*WAVE_WORDS+6]);}
 private final float[][] domeElapsed={new float[4],new float[4]};private final boolean[] domeClockValid={false,false};
 /** Sample the render clock before narrowing it to the legacy world float. */
 public void clock(double seconds){preciseDomeClock=seconds;}
 private void bindDomeClock(int program,int bank){int flag=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uDomePreciseClock");if(flag<0)return;GL41.glProgramUniform1i(program,flag,domeClockValid[bank]?1:0);if(domeClockValid[bank])GL41.glProgramUniform4fv(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uDomeElapsed"),domeElapsed[bank]);}

 private int domeKeyBuffer,domeKeyTexture,domeKeyUploaded;private boolean domeKeyFailed;
 public String domeResourceStatus(){return "domeKeys="+domeKeyUploaded+"/"+(com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.available()?com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.keyCount()*2:0)+" failed="+(domeKeyFailed||com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.keysFailed());}
 public boolean domeReady(){return words[2]!=0;}
 private void ensureDomeKeys(){
  if(domeKeyFailed)return;
  if(!com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.keysReady()){if(domeLoaded)com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.preloadKeys();return;}
  int[] keys=com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.keys();
  if(domeKeyUploaded==keys.length)return;
  if(domeKeyBuffer==0){
   if(keys.length>GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE)){domeKeyFailed=true;throw new IllegalStateException("Dome trajectory exceeds device texture-buffer capacity");}
   domeKeyBuffer=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,domeKeyBuffer);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,keys.length*4L,GL15.GL_STATIC_DRAW);
   if(GL32.glGetBufferParameteri64(GL31.GL_TEXTURE_BUFFER,GL15.GL_BUFFER_SIZE)!=keys.length*4L){domeKeyFailed=true;throw new IllegalStateException("Dome trajectory GPU allocation failed");}
   domeKeyTexture=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,domeKeyTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,domeKeyBuffer);
  }
  // Immutable assets are prewarmed incrementally, outside simulation; no whole-asset publish spike.
  int count=Math.min(131072,keys.length-domeKeyUploaded);upload.clear();upload.put(keys,domeKeyUploaded,count).flip();GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,domeKeyBuffer);GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER,domeKeyUploaded*4L,upload);domeKeyUploaded+=count;uploadedBytes+=count*4L;
  if(domeKeyUploaded==keys.length){words[2]=1;readyVersion++;}
 }
 public boolean navigationReady(){return allocatedStateWords>=STATE_WORDS&&data[0]!=0;}
 public boolean hasDome(){for(int slot=0;slot<4;slot++)if(words[HEADER+ROSTER+slot*WAVE_WORDS+20]==Float.floatToRawIntBits(1f)&&previousIds[slot]!=0)return true;return false;}
 public void rebuild(ClientboundStormWavePacket[] waves){
  for(int slot=0;slot<4;slot++){var p=slot<waves.length?waves[slot]:null;referencePackets[slot]=p!=null&&p.helixAttack()&&p.gatherFrames().length>0?p:null;}
  boolean rosterChanged=false;hasWaves=false;for(var p:waves)if(p!=null)hasWaves=true;
  for(int slot=0;slot<68;slot++){var p=slot<waves.length?waves[slot]:null;int id=p==null||p.abort()?0:p.waveId();int[] members=id==0?null:p.members();if(previousIds[slot]!=id||!Arrays.equals(previousMembers[slot],members))rosterChanged=true;previousIds[slot]=id;previousMembers[slot]=members;if(slot<4&&(id==0||!p.helixAttack())){int mask=words[1]&~(1<<slot);if(mask!=words[1]){words[1]=mask;readyVersion++;}}}
  Arrays.fill(words,GATHER_SOURCE,GATHER_SOURCE+GATHER_SOURCE_WORDS,0);
  Arrays.fill(words,HEADER+ROSTER,GAUSSIAN,0);if(rosterChanged){Arrays.fill(words,HEADER,HEADER+ROSTER,0);rosterVersion++;}
  resetFlowSource();
  for(int slot=0;slot<68;slot++){
   var p=slot<waves.length?waves[slot]:null;if(p==null){if(slot<4){domeGatherIds[slot]=0;domeLocalLocks[slot]=Float.NaN;}continue;}int b=HEADER+ROSTER+slot*WAVE_WORDS;
   put(b,p.waveId(),p.abort()?3:p.locked()?2:p.event(),p.columns(),p.perColumn());float lock=p.lockSec();if(slot<4){
    if(!p.dome()||p.abort()){domeGatherIds[slot]=0;domeLocalLocks[slot]=Float.NaN;}
    else if(!p.locked()){domeGatherIds[slot]=p.waveId();domeLocalLocks[slot]=Float.NaN;}
    else if(domeGatherIds[slot]==p.waveId()){
     if(Float.isNaN(domeLocalLocks[slot])&&Double.isFinite(preciseDomeClock)){double elapsed=preciseDomeClock-p.startSec();if(elapsed< -52428.8)elapsed+=104857.6;else if(elapsed>52428.8)elapsed-=104857.6;domeLocalLocks[slot]=(float)(p.startSec()+elapsed);}
     if(!Float.isNaN(domeLocalLocks[slot]))lock=domeLocalLocks[slot];
    }
   }put(b+4,p.startSec(),p.assembleSec(),lock,p.diveUntilSec());
   put(b+8,p.crownX(),p.crownY(),p.crownZ(),p.crownRadius());int end=p.path().length-3;
   put(b+12,p.path()[end],p.path()[end+1],p.path()[end+2],p.waveSeed());put(b+16,p.swordTier(),p.abort()?p.lockSec():-1,0,0);
   put(b+20,p.attackType(),p.phase(),p.orientation(),p.direction());
   int ci=slot*3;float[] next={p.centerX(),p.centerY(),p.centerZ()};
   if(centerWaveIds[slot]!=p.waveId()||p.locked()){previousHeadings[slot]=p.orientation();for(int k=0;k<3;k++)centerPrevious[ci+k]=next[k];}
   else if(centerTimes[slot]!=p.centerSec()){previousHeadings[slot]=headings[slot];for(int k=0;k<3;k++)centerPrevious[ci+k]=centerNow[ci+k];}
   for(int k=0;k<3;k++)centerNow[ci+k]=next[k];
   headings[slot]=p.orientation();put(b+16,p.swordTier(),p.abort()?p.lockSec():-1,previousHeadings[slot],0);
   centerTimes[slot]=p.centerSec();centerWaveIds[slot]=p.waveId();
   put(b+24,p.centerX(),p.centerY(),p.centerZ(),p.dome()?AllayDome.closeSeconds(p.crownRadius()):0);
   put(b+28,centerPrevious[ci],centerPrevious[ci+1],centerPrevious[ci+2],p.centerSec());
   for(int i=0;i<p.path().length/3;i++)put(b+32+i*4,p.path()[i*3],p.path()[i*3+1],p.path()[i*3+2],1);
   for(int c=0;c<p.columns();c++){
    int l=c*4,cb=b+56+c*8,first=c==0?0:p.groupEnds()[c-1],size=p.groupEnds()[c]-first;
    float warn=p.dome()?AllayDome.warningStart(p.lockSec(),p.crownRadius(),c):p.lockSec()+c*AllayStarfall.COLUMN_GAP;
    float begin=warn+(p.dome()?0:AllayStarfall.WARNING);
    put(cb,p.landings()[l],p.landings()[l+1],p.landings()[l+2],begin);
    put(cb+4,begin+(p.dome()?AllayDome.closeSeconds(p.crownRadius()):p.landings()[l+3]),size,1.2f,warn);
   }
   if(p.column()){
    put(b+20,3,p.phase(),0,0);
    put(b+24,p.landings()[3],(readyColumns[slot-4]?1:0),0,0);
     if(columnOwners[slot-4]!=p.waveId()){columnOwners[slot-4]=p.waveId();columnWaiting[slot-4]=false;columnRestoreTokens[slot-4]=0;}
     words[b+26]=Float.floatToRawIntBits(columnWaiting[slot-4]?1:0);words[b+27]=columnRestoreTokens[slot-4];
    put(b+56,p.landings()[0],p.landings()[1],p.landings()[2],p.lockSec());
    put(b+60,p.landings()[3],64,4,p.startSec());
   }
   if(slot<4)for(int rank=0;rank<p.members().length;rank++)words[NAV_MEMBER_BY_RANK+slot*12288+rank]=p.members()[rank];
   if(p.dome()){for(int flow=0;flow<66;flow++){int at=DOME_FLOW_SOURCE+(slot*66+flow)*2;words[at]=p.waveId();float pause=p.gatherFlows().pausedAt(flow);words[at+1]=Float.floatToRawIntBits(pause<0?Float.POSITIVE_INFINITY:pause);}}
   if(p.dome()&&!domeLoaded){if(!com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.available())throw new IllegalStateException("Dome template unavailable");for(int i=0;i<com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.WORDS;i++)words[DOME_DATA+i]=Float.floatToRawIntBits(com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.word(i));for(int rank=0;rank<12288;rank++)words[DOME_FLOW_MAP+rank]=com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.flowIndex(rank);domeLoaded=true;gaussianVersion++;}
   if(p.helixAttack()){
    int reference=HELIX_GATHER_FRAMES+slot*HELIX_FRAME_WORDS;words[reference]=Float.floatToRawIntBits(p.gatherFrames().length);
    for(int frame=0;frame<p.gatherFrames().length;frame++){float[] values=p.gatherFrames()[frame].words();for(int k=0;k<values.length;k++)words[reference+4+frame*32+k]=Float.floatToRawIntBits(values[k]);}

    if(words[3]!=Float.floatToRawIntBits(com.iridium126.createmanaindustry.content.allaystorm.StormMotion.HELIX_LIMIT)){words[3]=Float.floatToRawIntBits(com.iridium126.createmanaindustry.content.allaystorm.StormMotion.HELIX_LIMIT);readyVersion++;}
    if(gaussianIds[slot]==0){
     if(!com.iridium126.createmanaindustry.content.allaystorm.HelixTemplate.available())throw new IllegalStateException("Helix template resource unavailable");
     for(int rank=0;rank<4096;rank++)for(int axis=0;axis<3;axis++)words[GAUSSIAN+(slot*4096+rank)*3+axis]=Float.floatToRawIntBits(com.iridium126.createmanaindustry.content.allaystorm.HelixTemplate.coordinate(rank,axis));
     gaussianIds[slot]=p.waveId();gaussianMembers[slot]=p.members();gaussianVersion++;
    }
    var h=p.helix();var axis=AllayHelix.axis(h);int ai=slot*3;
    boolean fresh=axisIds[slot]!=p.waveId();
    if(fresh||axisTimes[slot]!=p.centerSec())for(int k=0;k<3;k++)oldAxes[ai+k]=fresh?(float)(k==0?axis.x:k==1?axis.y:axis.z):lastAxes[ai+k];
    axisIds[slot]=p.waveId();axisTimes[slot]=p.centerSec();
    var previousAxis=new net.minecraft.world.phys.Vec3(oldAxes[ai],oldAxes[ai+1],oldAxes[ai+2]);
    var previousSide=new net.minecraft.world.phys.Vec3(lastSides[ai],lastSides[ai+1],lastSides[ai+2]);
    var side=previousSide.subtract(axis.scale(previousSide.dot(axis)));
    if(fresh||side.lengthSqr()<1e-8)side=(Math.abs(axis.y)<.9?new net.minecraft.world.phys.Vec3(0,1,0):new net.minecraft.world.phys.Vec3(1,0,0)).cross(axis);side=side.normalize();
    if(fresh||p.locked())previousAxis=axis;
    put(b+300,(float)previousAxis.x,(float)previousAxis.y,(float)previousAxis.z,1);
    put(b+304,(float)side.x,(float)side.y,(float)side.z,0);
    lastAxes[ai]=(float)axis.x;lastAxes[ai+1]=(float)axis.y;lastAxes[ai+2]=(float)axis.z;lastSides[ai]=(float)side.x;lastSides[ai+1]=(float)side.y;lastSides[ai+2]=(float)side.z;
    for(int i=0;i<AllayHelix.SAMPLES;i++)put(b+72+i*4,h.samples()[i*4],h.samples()[i*4+1],h.samples()[i*4+2],h.samples()[i*4+3]);
    for(int i=0;i<h.controls().length/3;i++)put(b+264+i*4,h.controls()[i*3],h.controls()[i*3+1],h.controls()[i*3+2],1);
    put(b+288,h.length(),h.radius(),h.speed(),h.maxSpeed());put(b+292,h.charge(),h.thrust(),h.finish(),p.diveUntilSec());
    put(b+296,h.arcLength(),h.controls().length/3,h.targetArc(),h.curvature());put(b+308,h.axisX(),h.axisY(),h.axisZ(),com.iridium126.createmanaindustry.content.allaystorm.HelixTemplate.VERSION);
    for(int g=0;g<2;g++){int first=g==0?0:p.groupEnds()[0];put(b+56+g*8,0,0,0,h.thrust());put(b+60+g*8,h.finish(),p.groupEnds()[g]-first,h.radius(),h.charge());}
   }
   var finished=completed.get(p.waveId());words[b+19]=Float.floatToRawIntBits(p.members().length-(finished==null?0:finished.cardinality()));
   if(slot<4&&!p.locked()&&!p.abort()&&(p.dome()||p.helixAttack()))for(int rank=0;rank<p.members().length;rank++)if(finished==null||!finished.get(rank)){words[GATHER_SOURCE+slot*4+1]++;words[GATHER_SOURCE+16+1536+slot*384+rank/32]|=1<<(rank&31);}
   if(rosterChanged&&!p.abort())for(int rank=0,group=0;rank<p.members().length;rank++){
    while(rank>=p.groupEnds()[group])group++;
    int first=group==0?0:p.groupEnds()[group-1];
    if(finished!=null&&finished.get(rank))continue;
    words[HEADER+p.members()[rank]]=p.column()?com.iridium126.createmanaindustry.content.allaystorm.StarfallColumnRules.assignment(slot,rank):AllayStarfall.pack(slot,group,rank-first);
   }
  }completed.keySet().removeIf(id->{for(var p:waves)if(p!=null&&p.waveId()==id)return false;return true;});version++;
 }
 private final boolean[] readyColumns=new boolean[64];
 public void columnReady(int slot,boolean ready){
  if(slot<4||slot>=68)return;readyColumns[slot-4]=ready;
  int word=HEADER+ROSTER+slot*WAVE_WORDS+25,bits=Float.floatToRawIntBits(ready?1:0);
  if(words[word]!=bits){words[word]=bits;version++;}
 }
 public void helixReady(int slot,boolean ready){int b=HEADER+ROSTER+slot*WAVE_WORDS;if(words[b+20]!=Float.floatToRawIntBits(2f))return;int mask=ready?words[1]|(1<<slot):words[1]&~(1<<slot);if(mask!=words[1]){words[1]=mask;readyVersion++;}}
 private void put(int b,float a,float c,float d,float e){words[b]=Float.floatToRawIntBits(a);words[b+1]=Float.floatToRawIntBits(c);words[b+2]=Float.floatToRawIntBits(d);words[b+3]=Float.floatToRawIntBits(e);}
 private void ensure(){if(data[0]!=0)return;allocatedStateWords=STATE_WORDS;Arrays.fill(domeUploaded,false);upload=MemoryUtil.memAllocInt(DATA_WORDS);for(int i=0;i<2;i++){
  data[i]=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[i]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,DATA_WORDS*4L,GL15.GL_DYNAMIC_DRAW);
  state[i]=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state[i]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,allocatedStateWords*4L,GL15.GL_DYNAMIC_DRAW);
  GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
  textures[i]=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,data[i]);
  stateTextures[i]=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,stateTextures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,state[i]);
 }}
 private void ensureDomeStateCapacity(){
  if(allocatedStateWords>=STATE_WORDS)return;
  int scratch=GL15.glGenBuffers();try{
   GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,scratch);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL31.GL_COPY_WRITE_BUFFER,allocatedStateWords*4L,GL15.GL_STREAM_COPY);
   for(int buffer:state){GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,buffer);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,scratch);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,allocatedStateWords*4L);
    GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,buffer);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL31.GL_COPY_WRITE_BUFFER,(STATE_WORDS)*4L,GL15.GL_DYNAMIC_DRAW);GL43.glClearBufferData(GL31.GL_COPY_WRITE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);
    GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,scratch);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,allocatedStateWords*4L);
   }allocatedStateWords=STATE_WORDS;
   for(int i=0;i<2;i++){GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,stateTextures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,state[i]);}
  }finally{com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(scratch);}
  GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
 }
 public void prepare(long generation){if(data[0]==0&&!hasWaves&&!StormCollisionRuntime.active()&&!com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.keysReady())return;
  // Rejected frames may not reach the publication barrier. Buffer clears and
  // bank copies must wait for their earlier shader-storage writes as well.
  GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
  ensure();ensureDomeStateCapacity();ensureDomeKeys();if(clearStates){for(int buffer:state){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);}clearStates=false;}int write=1-read;
  if(versions[write]!=version){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[write]);
   if(rosterVersions[write]!=rosterVersion){upload.clear();upload.put(words,0,HEADER+ROSTER).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,upload);rosterVersions[write]=rosterVersion;uploadedBytes+=4L*(HEADER+ROSTER);upload.clear();upload.put(words,NAV_MEMBER_BY_RANK,4*12288).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,NAV_MEMBER_BY_RANK*4L,upload);uploadedBytes+=16L*12288;}
   if(gaussianVersions[write]!=gaussianVersion){gaussianVersions[write]=gaussianVersion;upload.clear();upload.put(words,GAUSSIAN,GAUSSIAN_WORDS).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,4L*GAUSSIAN,upload);uploadedBytes+=4L*GAUSSIAN_WORDS;}
   upload.clear();upload.put(words,HEADER+ROSTER,GAUSSIAN-HEADER-ROSTER).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,4L*(HEADER+ROSTER),upload);uploadedBytes+=4L*(GAUSSIAN-HEADER-ROSTER);
   upload.clear();upload.put(words,GATHER_SOURCE,GATHER_SOURCE_WORDS).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,GATHER_SOURCE*4L,upload);uploadedBytes+=4L*GATHER_SOURCE_WORDS;
   upload.clear();upload.put(words,DOME_FLOW_SOURCE,DOME_FLOW_WORDS).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,DOME_FLOW_SOURCE*4L,upload);uploadedBytes+=4L*DOME_FLOW_WORDS;upload.clear();upload.put(words,HELIX_GATHER_FRAMES,4*HELIX_FRAME_WORDS).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,HELIX_GATHER_FRAMES*4L,upload);uploadedBytes+=16L*HELIX_FRAME_WORDS;versions[write]=version;
  }prepareRecovery(write);if(readyVersions[write]!=readyVersion){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[write]);upload.clear();upload.put(words,1,3).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,4,upload);uploadedBytes+=12;readyVersions[write]=readyVersion;}
  if(domeLoaded&&!domeUploaded[write]){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[write]);upload.clear();upload.put(words,DOME_DATA,GATHER_SOURCE-DOME_DATA).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,DOME_DATA*4L,upload);uploadedBytes+=4L*(GATHER_SOURCE-DOME_DATA);upload.clear();upload.put(words,DOME_FLOW_MAP,12288).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,DOME_FLOW_MAP*4L,upload);uploadedBytes+=4L*12288;domeUploaded[write]=true;}
  if(ackVersions[write]!=ackVersion){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[write]);upload.clear();upload.put(words,HOME_ACK,ROSTER/32).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,HOME_ACK*4L,upload);ackVersions[write]=ackVersion;uploadedBytes+=ROSTER/8;}
  GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state[write]);
  // A rejected frame may reuse its generation. Invalidate recovery scratch
  // before any consumer; live curves/metadata are rebuilt or copied from read.
  GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,COLUMN_ARC*4L,(COMPLETIONS-COLUMN_ARC)*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
  GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,COMPLETIONS*4L,COMPLETION_WORDS*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
  GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,data[write]);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,state[write]);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,GATHER_SOURCE*4L,GATHER_COUNTS*4L,GATHER_SOURCE_WORDS*4L);
  if(allocatedStateWords>=STATE_WORDS){

   GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,state[read]);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,DOME_FLOW_STATE*4L,DOME_FLOW_STATE*4L,DOME_FLOW_WORDS*4L);
   GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,DOME_PATH_BASE*4L,DOME_PATH_BASE*4L,(STATE_WORDS-DOME_PATH_BASE)*4L);
   GL43.glClearBufferSubData(GL31.GL_COPY_WRITE_BUFFER,GL30.GL_R32UI,(DOME_PATH_CONTROL+24)*4L,8,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);
   for(int slot=0;slot<4;slot++){int id=words[DOME_FLOW_SOURCE+slot*132];if(flowBankIds[read][slot]!=id||clearStates){upload.clear();upload.put(words,DOME_FLOW_SOURCE+slot*132,132).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL31.GL_COPY_WRITE_BUFFER,(DOME_FLOW_STATE+slot*132)*4L,upload);uploadedBytes+=528;}flowBankIds[write][slot]=id;}
  }
  domeClockValid[write]=Double.isFinite(preciseDomeClock);if(domeClockValid[write])for(int slot=0;slot<4;slot++){int base=HEADER+ROSTER+slot*WAVE_WORDS;double elapsed=preciseDomeClock-Float.intBitsToFloat(words[base+4]);if(elapsed< -52428.8)elapsed+=104857.6;else if(elapsed>52428.8)elapsed-=104857.6;domeElapsed[write][slot]=(float)elapsed;}
  generations[write]=generation;enabled[write]=hasWaves||StormCollisionRuntime.returning();pending=true;
  GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
 }
 public long uploadedBytes(){return uploadedBytes;}
 public void bindPathDispatch(){if(domePathDispatch==0){domePathDispatch=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,domePathDispatch);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,12,GL15.GL_DYNAMIC_COPY);}GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,29,domePathDispatch);}
 public boolean bind(boolean staged){int i=staged?1-read:read;boolean valid=data[i]!=0&&enabled[i]&&(staged?pending:generations[i]>=0);
  GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,DATA_BINDING,valid?data[i]:0);
  if(!staged)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,STATE_WRITE,state[read]);
  if(staged){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,STATE_READ,state[read]);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,STATE_WRITE,state[1-read]);}
  return valid;
 }
 /** Standalone validation entry; shared-pool owners use bind(staged) and their own program cache. */
 public void bind(int program,boolean staged){bind(staged);bindUniforms(program,staged);}
 /** Set per-program values after the candidate bank has already been bound. */
 public void bindUniforms(int program,boolean staged){int i=staged?1-read:read;boolean valid=data[i]!=0&&enabled[i]&&(staged?pending:generations[i]>=0);if(program!=0){
  bindFrame(program,staged);
  GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStarfallEnabled"),valid?1:0);
  GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uDomePoseStorageEnabled"),allocatedStateWords>=STATE_WORDS?1:0);
  int location=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"cmiDomeKeys");if(location>=0){GL13.glActiveTexture(GL13.GL_TEXTURE0+15);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,domeKeyTexture);GL41.glProgramUniform1i(program,location,15);GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"cmi_DomePoseBase"),DOME_POSE);GL13.glActiveTexture(GL13.GL_TEXTURE0);}
  bindDomeClock(program,staged?1-read:read);bindPoseState(program);
 }}
 public void bindFrame(int program,boolean staged){
  GL41.glProgramUniform1ui(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormStateBase"),0);
  GL41.glProgramUniform1ui(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormFrame"),((int)generations[staged?1-read:read]&0x3fffffff)+1);
 }
 /** Native MODEL, shadow and GLSL 330 Iris all read the same committed bank. */
 public void bindPose(int program){bindDomeClock(program,read);bindPoseState(program);}
 private void bindPoseState(int program){GL41.glProgramUniform1ui(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStormStateBase"),0);int location=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"cmi_DomePose");if(location<0)return;
  int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);bindPoseTexture(15);GL41.glProgramUniform1i(program,location,15);GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"cmi_DomePoseBase"),DOME_POSE);GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uDomePoseEnabled"),poseReady()?1:0);GL13.glActiveTexture(active);
 }
 private boolean poseReady(){return allocatedStateWords>=DOME_POSE+DOME_POSE_WORDS&&generations[read]>=0;}
 public void bindPoseTexture(int unit){int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,poseReady()?stateTextures[read]:0);GL13.glActiveTexture(active);}
 public boolean committedPoseReady(){return poseReady();}
 public void bindTexture(int unit){boolean valid=data[read]!=0&&enabled[read]&&generations[read]>=0;GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,valid?textures[read]:0);GL13.glActiveTexture(GL13.GL_TEXTURE0);}
 public long candidateGeneration(){return pending?generations[1-read]:generations[read];}
 public int candidateStateBuffer(){return state[pending?1-read:read];}
 public long committedGeneration(){return generations[read];}
 public int committedBuffer(){return generations[read]>=0&&enabled[read]?data[read]:0;}
 public void commit(){if(pending){read=1-read;pending=false;}}
 public void abort(){pending=false;}
 public void invalidate(){Arrays.fill(referencePackets,null);for(int bank=0;bank<2;bank++){Arrays.fill(referenceBanks[bank],null);Arrays.fill(recoveries[bank],null);Arrays.fill(recoveryDirty[bank],true);}words[3]=Float.floatToRawIntBits(com.iridium126.createmanaindustry.content.allaystorm.StormMotion.INTERCEPT);Arrays.fill(columnOwners,0);Arrays.fill(columnRestoreTokens,0);Arrays.fill(columnWaiting,false);preciseDomeClock=Double.NaN;for(var ids:flowBankIds)Arrays.fill(ids,0);resetFlowSource();Arrays.fill(domeGatherIds,0);Arrays.fill(domeLocalLocks,Float.NaN);Arrays.fill(domeClockValid,false);Arrays.fill(axisIds,0);Arrays.fill(returningOwners,0);Arrays.fill(words,HOME_ACK,DOME_DATA,0);ackVersion++;completed.clear();Arrays.fill(lastAxes,0);Arrays.fill(lastSides,0);Arrays.fill(gaussianIds,0);Arrays.fill(gaussianMembers,null);Arrays.fill(gaussianVersions,-1);words[0]=++domainToken;words[1]=0;words[2]=domeKeyBuffer!=0&&domeKeyUploaded==com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.keyCount()*2?1:0;Arrays.fill(readyColumns,false);readyVersion++;Arrays.fill(readyVersions,-1);clearStates=true;Arrays.fill(generations,-1);Arrays.fill(versions,-1);Arrays.fill(rosterVersions,-1);version++;pending=false;}
 public long bytes(){return data[0]==0?0:8L*(DATA_WORDS+allocatedStateWords)+(domeKeyBuffer==0?0:8L*com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.keyCount())+(domePathDispatch==0?0:12);}
 @Override public void close(){if(domePathDispatch!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(domePathDispatch);domePathDispatch=0;if(domeKeyTexture!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(domeKeyTexture);if(domeKeyBuffer!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(domeKeyBuffer);domeKeyTexture=0;domeKeyBuffer=0;domeKeyUploaded=0;domeKeyFailed=false;for(int i=0;i<2;i++){if(data[i]!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(data[i]);if(state[i]!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(state[i]);if(textures[i]!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(textures[i]);if(stateTextures[i]!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(stateTextures[i]);data[i]=state[i]=textures[i]=stateTextures[i]=0;}if(upload!=null){MemoryUtil.memFree(upload);upload=null;}invalidate();}


 private final float[] waves=new float[16],targets=new float[16],tiers=new float[4],path=new float[96];
 public void resetUniforms(){Arrays.fill(waves,0);Arrays.fill(targets,0);Arrays.fill(tiers,0);}
 public void waveUniform(int slot,float seed,float start,float until,int id,float tier) {
  if(slot<0||slot>=4)throw new IllegalArgumentException("Wave slot");
  int b=slot*4;waves[b]=seed;waves[b+2]=start;waves[b+3]=until;targets[b+3]=id;tiers[slot]=tier;
 }
 public void uploadUniforms(int program) {
  com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setVec4ArrayUniform(program,"uWave",waves);
  com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setVec4ArrayUniform(program,"uWaveTarget",targets);
  com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setFloatArrayUniform(program,"uWaveTier",tiers);
  com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.setVec4ArrayUniform(program,"uWavePath",path);
 }
}
