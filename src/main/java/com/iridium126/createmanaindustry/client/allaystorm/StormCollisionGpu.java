package com.iridium126.createmanaindustry.client.allaystorm;

import java.nio.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Independent bit atlas, versioned replacement pages and four nonblocking table banks. */
public final class StormCollisionGpu implements AutoCloseable {
    private record Resident(Page page,int slot) {}
    private static final class Bank {int buffer,texture,mask,progress,rows;long fence,serial;ByteBuffer staging;final BitSet references=new BitSet();}
    private final Bank[] banks={new Bank(),new Bank(),new Bank(),new Bank()};
    private final Map<Section,Resident> residents=new HashMap<>();
    private final LinkedHashMap<Section,Page> pending=new LinkedHashMap<>();
    private final BitSet occupied=new BitSet();
    private final ByteBuffer pageUpload=MemoryUtil.memAlloc(BYTES).order(ByteOrder.nativeOrder());
    private int atlas,capacity=8192,current=-1,building=-1;
    private long serial,published=-1,uploaded,overruns,lastUpload;
    private void ensure(){
        if(atlas!=0)return;
        atlas=texture(capacity);
        for(var bank:banks){bank.buffer=GL15.glGenBuffers();bank.texture=GL11.glGenTextures();}
    }
    private static int texture(int pages){
        int depth=((pages+1023)/1024)*8;
        if(depth>GL11.glGetInteger(GL12.GL_MAX_3D_TEXTURE_SIZE))throw new IllegalStateException("Storm collision atlas exceeds device 3D texture limit");
        int texture=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL12.GL_TEXTURE_3D,texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL42.glTexStorage3D(GL12.GL_TEXTURE_3D,1,GL30.GL_R32UI,128,128,depth);
        if(GL11.glGetError()!=GL11.GL_NO_ERROR){com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(texture);throw new IllegalStateException("Storm collision atlas allocation failed");}
        return texture;
    }
    public void offer(Map<Section,Page> ready){
        boolean removed=residents.keySet().removeIf(key->!ready.containsKey(key));pending.keySet().removeIf(key->!ready.containsKey(key));if(removed)serial++;
        for(var item:ready.entrySet()){
            var old=residents.get(item.getKey());if(old!=null&&old.page.revision()==item.getValue().revision())continue;
            Page page=item.getValue();
            if(page.kind()<0){residents.put(item.getKey(),new Resident(page,page.kind()));pending.remove(item.getKey());serial++;}
            else pending.put(item.getKey(),page);
        }
    }
    private boolean complete(Bank bank){
        if(bank.fence==0)return true;
        int status=GL32.glClientWaitSync(bank.fence,0,0);
        if(status==GL32.GL_ALREADY_SIGNALED||status==GL32.GL_CONDITION_SATISFIED){GL32.glDeleteSync(bank.fence);bank.fence=0;return true;}
        if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Storm collision table fence failed");
        return false;
    }
    private void reclaim(){
        var keep=new BitSet();for(var resident:residents.values())if(resident.slot>=0)keep.set(resident.slot);
        for(int i=0;i<banks.length;i++){var bank=banks[i];if(i==current||i==building||!complete(bank))keep.or(bank.references);else bank.references.clear();}
        occupied.and(keep);
    }
    private void startTable(){
        if(building>=0||published==serial)return;
        for(int i=0;i<banks.length;i++)if(i!=current&&complete(banks[i])){
            var bank=banks[i];var rows=new HashMap<Section,Integer>();bank.references.clear();
            residents.forEach((key,resident)->{rows.put(key,resident.slot);if(resident.slot>=0)bank.references.set(resident.slot);});
            bank.rows=rows.size();int[] table=table(rows);bank.staging=MemoryUtil.memAlloc(table.length*4).order(ByteOrder.nativeOrder());bank.staging.asIntBuffer().put(table);
            bank.mask=table.length/4-1;bank.serial=serial;bank.progress=0;
            try{
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,bank.buffer);GpuTransfers.allocate(GL31.GL_TEXTURE_BUFFER,bank.staging.capacity(),GL15.GL_STREAM_DRAW);
                GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,bank.texture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32I,bank.buffer);
                if(GL11.glGetError()!=GL11.GL_NO_ERROR)throw new IllegalStateException("Storm collision page-table allocation failed");
            }catch(RuntimeException|LinkageError failure){MemoryUtil.memFree(bank.staging);bank.staging=null;throw failure;}
            building=i;return;
        }
    }
    public void pump(int byteBudget,long nanos){
        long began=System.nanoTime();ensure();if(settled()){lastUpload=0;return;}
        reclaim();startTable();int left=byteBudget;
        if(building>=0){
            var bank=banks[building];int n=Math.min(left/2,bank.staging.capacity()-bank.progress);n-=n%16;
            if(n>0){var part=bank.staging.duplicate();part.position(bank.progress).limit(bank.progress+n);GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,bank.buffer);GpuTransfers.upload(GL31.GL_TEXTURE_BUFFER,bank.progress,part);bank.progress+=n;left-=n;uploaded+=n;}
            if(bank.progress==bank.staging.capacity()){MemoryUtil.memFree(bank.staging);bank.staging=null;published=bank.serial;current=building;building=-1;}
        }
        while(left>=BYTES&&!pending.isEmpty()&&System.nanoTime()-began<nanos){
            var iterator=pending.entrySet().iterator();var item=iterator.next();int slot=occupied.nextClearBit(0);
            if(slot>=capacity){
                int old=atlas,next=texture(Math.multiplyExact(capacity,2));
                GL43.glCopyImageSubData(old,GL12.GL_TEXTURE_3D,0,0,0,0,next,GL12.GL_TEXTURE_3D,0,0,0,0,128,128,((capacity+1023)/1024)*8);
                com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(old);atlas=next;capacity*=2;
            }
            occupied.set(slot);pageUpload.clear();pageUpload.asIntBuffer().put(item.getValue().words());
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL12.GL_TEXTURE_3D,atlas);
            com.iridium126.createmanaindustry.client.particles.engine.ParticleGLUtil.prepareClientUpload();
            GpuTransfers.texture3d(GL12.GL_TEXTURE_3D,0,(slot&31)*4,((slot>>>5)&31)*4,(slot>>>10)*8,4,4,8,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,pageUpload);
            residents.put(item.getKey(),new Resident(item.getValue(),slot));iterator.remove();serial++;left-=BYTES;uploaded+=BYTES;
        }
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);lastUpload=System.nanoTime()-began;if(lastUpload>nanos)overruns++;
    }
    public void bind(int program){
        for(int part:com.iridium126.createmanaindustry.client.particles.engine.DomeBodyContacts.related(program))bindSingle(part);
    }
    private void bindSingle(int program){
        boolean ready=current>=0;
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"uStormCollisionOn"),ready?1:0);
        GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uStormCollisionRevision"),ready?(int)banks[current].serial:0);
        if(!ready)return;
        GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL12.GL_TEXTURE_3D,atlas);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,banks[current].texture);
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"uStormCollisionAtlas"),1);
        GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"uStormCollisionTable"),2);
        GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uStormCollisionMask"),banks[current].mask);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }
    public void submitted(){if(current<0)return;var bank=banks[current];if(bank.fence!=0)GL32.glDeleteSync(bank.fence);bank.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);}
    public int ready(){return current<0?0:banks[current].rows;}
    public boolean settled(){return pending.isEmpty()&&building<0&&published==serial;}
    public long uploaded(){return uploaded;}
    public long bytes(){long bytes=(long)capacity*BYTES;for(var bank:banks)bytes+=(long)(bank.mask+1)*16;return atlas==0?0:bytes;}
    public String stats(){return "gpuReady="+ready()+" pages="+occupied.cardinality()+" bytes="+bytes()+" uploadBytes="+uploaded+" uploadNs="+lastUpload+" overruns="+overruns;}
    public String profilingReport(){return "ready="+ready()+"; pages="+occupied.cardinality()+"; memory="+com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.bytes(bytes())
            +"; uploaded total="+com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.bytes(uploaded)
            +String.format(java.util.Locale.ROOT,"; upload latest=%.3f ms",lastUpload/1e6)+(overruns==0?"":"; overruns="+overruns);}
    @Override public void close(){
        for(var bank:banks){if(bank.fence!=0)GL32.glDeleteSync(bank.fence);if(bank.staging!=null)MemoryUtil.memFree(bank.staging);if(bank.buffer!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(bank.buffer);if(bank.texture!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(bank.texture);}
        if(atlas!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(atlas);MemoryUtil.memFree(pageUpload);
    }
}
