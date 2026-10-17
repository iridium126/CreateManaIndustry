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
 public static final int DATA_WORDS=HEADER+ROSTER+4*WAVE_WORDS, STATE_WORDS=ROSTER*2+256+128;
 private final int[] words=new int[DATA_WORDS],data={0,0},state={0,0},textures={0,0};
 private final long[] versions={-1,-1},rosterVersions={-1,-1},generations={-1,-1};private long version,rosterVersion,uploadedBytes;
 private final long[] readyVersions={-1,-1};private long readyVersion;
 private final float[] centerNow=new float[12],centerPrevious=new float[12],centerTimes=new float[4];
 private final float[] headings=new float[4],previousHeadings=new float[4];
 private final int[] centerWaveIds=new int[4];
 private final int[][] previousMembers=new int[4][];private final int[] previousIds=new int[4];private int read;private IntBuffer upload;
 private final boolean[] enabled={false,false};private boolean hasWaves;
 private boolean pending,clearStates;private int domainToken=1;
 public StarfallGpuBuffers(){words[0]=domainToken;}
 public void rebuild(ClientboundStormWavePacket[] waves){
  boolean rosterChanged=false;hasWaves=false;for(var p:waves)if(p!=null)hasWaves=true;
  for(int slot=0;slot<4;slot++){var p=waves[slot];int id=p==null||p.abort()?0:p.waveId();int[] members=id==0?null:p.members();if(previousIds[slot]!=id||previousMembers[slot]!=members)rosterChanged=true;previousIds[slot]=id;previousMembers[slot]=members;if(id==0||!p.helixAttack()){int mask=words[1]&~(1<<slot);if(mask!=words[1]){words[1]=mask;readyVersion++;}}}
  Arrays.fill(words,HEADER+ROSTER,DATA_WORDS,0);if(rosterChanged){Arrays.fill(words,HEADER,HEADER+ROSTER,0);rosterVersion++;}
  for(int slot=0;slot<4;slot++){
   var p=waves[slot];if(p==null)continue;int b=HEADER+ROSTER+slot*WAVE_WORDS;
   put(b,p.waveId(),p.abort()?3:p.locked()?2:p.event(),p.columns(),p.perColumn());put(b+4,p.startSec(),p.assembleSec(),p.lockSec(),p.diveUntilSec());
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
    float begin=warn+(p.dome()?AllayDome.WARNING:AllayStarfall.WARNING);
    put(cb,p.landings()[l],p.landings()[l+1],p.landings()[l+2],begin);
    put(cb+4,begin+(p.dome()?AllayDome.closeSeconds(p.crownRadius()):p.landings()[l+3]),size,1.2f,warn);
   }
   if(p.helixAttack()){
    var h=p.helix();for(int i=0;i<AllayHelix.SAMPLES;i++)put(b+72+i*4,h.samples()[i*4],h.samples()[i*4+1],h.samples()[i*4+2],h.samples()[i*4+3]);
    for(int i=0;i<h.controls().length/3;i++)put(b+264+i*4,h.controls()[i*3],h.controls()[i*3+1],h.controls()[i*3+2],1);
    put(b+288,h.length(),h.radius(),h.speed(),h.maxSpeed());put(b+292,h.charge(),h.thrust(),h.finish(),p.diveUntilSec());
    put(b+296,h.arcLength(),h.controls().length/3,h.targetArc(),h.curvature());put(b+308,h.armA(),h.armB(),.2f,.3f);
    for(int g=0;g<2;g++){int first=g==0?0:p.groupEnds()[0];put(b+56+g*8,0,0,0,h.thrust());put(b+60+g*8,h.finish(),p.groupEnds()[g]-first,h.radius(),h.charge());}
   }
   if(rosterChanged&&!p.abort())for(int rank=0,group=0;rank<p.members().length;rank++){
    while(rank>=p.groupEnds()[group])group++;
    int first=group==0?0:p.groupEnds()[group-1];
    words[HEADER+p.members()[rank]]=AllayStarfall.pack(slot,group,rank-first);
   }
  }version++;
 }
 public void helixReady(int slot,boolean ready){int b=HEADER+ROSTER+slot*WAVE_WORDS;if(words[b+20]!=Float.floatToRawIntBits(2f))return;int mask=ready?words[1]|(1<<slot):words[1]&~(1<<slot);if(mask!=words[1]){words[1]=mask;readyVersion++;}}
 private void put(int b,float a,float c,float d,float e){words[b]=Float.floatToRawIntBits(a);words[b+1]=Float.floatToRawIntBits(c);words[b+2]=Float.floatToRawIntBits(d);words[b+3]=Float.floatToRawIntBits(e);}
 private void ensure(){if(data[0]!=0)return;upload=MemoryUtil.memAllocInt(DATA_WORDS);for(int i=0;i<2;i++){
  data[i]=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[i]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,DATA_WORDS*4L,GL15.GL_DYNAMIC_DRAW);
  state[i]=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state[i]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,STATE_WORDS*4L,GL15.GL_DYNAMIC_DRAW);
  GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
  textures[i]=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,data[i]);
 }}
 public void prepare(long generation){if(data[0]==0&&!hasWaves)return;ensure();if(clearStates){for(int buffer:state){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);}clearStates=false;}int write=1-read;
  if(versions[write]!=version){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[write]);
   if(rosterVersions[write]!=rosterVersion){upload.clear();upload.put(words,0,HEADER+ROSTER).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,upload);rosterVersions[write]=rosterVersion;uploadedBytes+=4L*(HEADER+ROSTER);}
   upload.clear();upload.put(words,HEADER+ROSTER,DATA_WORDS-HEADER-ROSTER).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,4L*(HEADER+ROSTER),upload);uploadedBytes+=4L*(DATA_WORDS-HEADER-ROSTER);versions[write]=version;
  }if(readyVersions[write]!=readyVersion){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,data[write]);upload.clear();upload.put(words[1]).flip();com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,4,upload);uploadedBytes+=4;readyVersions[write]=readyVersion;}
  generations[write]=generation;enabled[write]=hasWaves;pending=true;
  GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
 }
 public long uploadedBytes(){return uploadedBytes;}
 public boolean bind(boolean staged){int i=staged?1-read:read;boolean valid=data[i]!=0&&enabled[i]&&(staged?pending:generations[i]>=0);
  GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,DATA_BINDING,valid?data[i]:0);
  if(!staged)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,STATE_WRITE,state[read]);
  if(staged){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,STATE_READ,state[read]);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,STATE_WRITE,state[1-read]);}
  return valid;
 }
 /** Standalone validation entry; shared-pool owners use bind(staged) and their own program cache. */
 public void bind(int program,boolean staged){boolean valid=bind(staged);if(program!=0)GL41.glProgramUniform1i(program,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(program,"uStarfallEnabled"),valid?1:0);}
 public void bindTexture(int unit){boolean valid=data[read]!=0&&enabled[read]&&generations[read]>=0;GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,valid?textures[read]:0);GL13.glActiveTexture(GL13.GL_TEXTURE0);}
 public long candidateGeneration(){return pending?generations[1-read]:generations[read];}
 public long committedGeneration(){return generations[read];}
 public int committedBuffer(){return generations[read]>=0&&enabled[read]?data[read]:0;}
 public void commit(){if(pending){read=1-read;pending=false;}}
 public void abort(){pending=false;}
 public void invalidate(){words[0]=++domainToken;words[1]=0;readyVersion++;Arrays.fill(readyVersions,-1);clearStates=true;Arrays.fill(generations,-1);Arrays.fill(versions,-1);Arrays.fill(rosterVersions,-1);version++;pending=false;}
 public long bytes(){return data[0]==0?0:8L*(DATA_WORDS+STATE_WORDS);}
 @Override public void close(){for(int i=0;i<2;i++){if(data[i]!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(data[i]);if(state[i]!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(state[i]);if(textures[i]!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(textures[i]);data[i]=state[i]=textures[i]=0;}if(upload!=null){MemoryUtil.memFree(upload);upload=null;}invalidate();}


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
