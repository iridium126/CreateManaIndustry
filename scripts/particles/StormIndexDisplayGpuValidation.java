import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.google.gson.GsonBuilder;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.*;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** Production GL/CUDA collision lookup, ordered per-frame playback and tick-bound withdrawals. */
public class StormIndexDisplayGpuValidation extends StormCollisionGpuValidation {
    static TreeMap<Section,Page> collisionFixture(){
        var pages=new TreeMap<Section,Page>(ORDER);
        for(int i=0;i<20000;i++)pages.put(new Section(i%37-18,i/1369+5000,i/37%37-18),new Page(1,AIR,null));
        var buckets=new HashMap<Integer,Integer>();
        for(var key:bodySections()){pages.put(key,new Page(1,AIR,null));buckets.put(hash(key.x(),key.y(),key.z())&65535,0);}
        int missing=buckets.size()*8;
        for(int x=-1500000;missing>0;x++){
            int bucket=hash(x,4,0)&65535;var count=buckets.get(bucket);
            if(count!=null&&count<8){pages.put(new Section(x,4,0),new Page(1,AIR,null));buckets.put(bucket,count+1);missing--;}
        }
        return pages;
    }
    static List<Section> bodySections(){
        var result=new ArrayList<Section>();
        for(int x=-2;x<=2;x++)for(int y=0;y<=8;y++)for(int z=-2;z<=2;z++)result.add(new Section(x,y,z));
        return result;
    }
    static void glIndex(Map<Section,Page> pages)throws Exception{
        int p=compileText("bounded overflow lookup",source("chunks/storm_collision.glsl")+"""
            layout(local_size_x=64) in;
            layout(std430,binding=10) buffer Output{int values[];} result;
            layout(std430,binding=11) readonly buffer Keys{ivec4 values[];} keys;
            uniform uint uCount;
            void main(){uint i=gl_GlobalInvocationID.x;if(i<uCount)result.values[i]=cmiStormLookupPage(keys.values[i].xyz);}
            """);
        var keys=new ArrayList<>(pages.keySet());keys.add(new Section(Integer.MIN_VALUE,-1,Integer.MAX_VALUE));
        int output=buffer(bytes(keys.size()*4)),input=buffer(bytes(keys.size()*16));
        try(var gpu=new StormCollisionGpu()){
            gpu.offer(pages);long until=System.nanoTime()+15_000_000_000L;
            while(!gpu.settled()){gpu.pump(131072,500_000);GL11.glFlush();check(System.nanoTime()<until,"GL index publication deadline");Thread.sleep(1);}
            check(gpu.tableSize()<65536+pages.size(),"bounded collision index allocation");
            var values=bytes(keys.size()*16);
            for(int i=0;i<keys.size();i++){var k=keys.get(i);values.putInt(i*16,k.x()).putInt(i*16+4,k.y()).putInt(i*16+8,k.z());}
            put(input,values);gpu.bind(p);bind(10,output);bind(11,input);ui(p,"uCount",keys.size());dispatch(p,(keys.size()+63)/64);
            var result=read(output,keys.size()*4);
            for(int i=0;i<keys.size();i++)check(result.getInt(i*4)==(i+1==keys.size()?UNKNOWN:AIR),"GL bounded signed lookup "+keys.get(i));
            Object bank=((Object[])field(gpu,"banks"))[(int)field(gpu,"current")];
            var table=(ByteBuffer)field(bank,"staging");Section overflowSurface=null;
            for(int at=((int)field(bank,"mask")+1)*16;at<table.limit();at+=16){
                var k=new Section(table.getInt(at),table.getInt(at+4),table.getInt(at+8));
                overflowSurface=k;break;
            }
            check(overflowSurface!=null,"surface scan fixture selects a real overflow row");
            var replacement=new TreeMap<Section,Page>(ORDER);replacement.putAll(pages);replacement.put(overflowSurface,new Page(2,SOLID,null));
            replacement.put(new Section(overflowSurface.x(),overflowSurface.y()+1,overflowSurface.z()),new Page(2,AIR,null));gpu.offer(replacement);
            until=System.nanoTime()+15_000_000_000L;
            while(!gpu.settled()){gpu.pump(131072,500_000);GL11.glFlush();check(System.nanoTime()<until,"overflow surface replacement deadline");Thread.sleep(1);}
            int gold=compile("gold_surface.comp");
            try(var surface=new StormSurfaceGpu()){
                var center=new net.minecraft.world.phys.Vec3(overflowSurface.x()*16+8,overflowSurface.y()*16+32,overflowSurface.z()*16+8);
                surface.prepare(gold,gpu,center,2,16384);
                int packed=read(surface.buffer(),StormSurfaceGpu.BYTES).getInt(StormSurfaceCache.cell((int)center.x,(int)center.z)*4);
                check(StormSurfaceCache.height(packed)==(overflowSurface.y()+1)*16,"gold surface scans include the collision overflow rows");gpu.submitted();
            }finally{deleteProgram(gold);}
        }finally{GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);deleteProgram(p);}
    }
    static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void displayHistory()throws Exception{
        var type=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.StormProjectionGpu");
        var constructor=type.getDeclaredConstructor(String.class);constructor.setAccessible(true);Object projection=constructor.newInstance(source("storm_projection.comp"));
        var receive=type.getDeclaredMethod("receive",long.class,NativeStormSimulation.FrozenDisplay.class);receive.setAccessible(true);
        var view=type.getDeclaredMethod("playback",double.class,boolean.class);view.setAccessible(true);
        int size=STORM_DISPLAY_HEADER_BYTES+STORM_DISPLAY_RECORD_BYTES+STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES;
        int shared=buffer(bytes(size));
        try{
            for(long seq=1;seq<=3;seq++){
                var data=bytes(size);data.putInt(0,STORM_DISPLAY_MAGIC).putInt(4,ABI).putInt(8,1).putInt(12,1)
                        .putLong(16,seq).putLong(24,seq).putLong(32,1).putInt(40,1);
                int row=STORM_DISPLAY_HEADER_BYTES;
                data.putFloat(row,seq*10).putFloat(row+16,(seq-1)*10).putFloat(row+48,1).putInt(row+64,0);
                put(shared,data);final long sequence=seq;
                var lease=new NativeStormSimulation.FrozenDisplay(){
                    public int buffer(){return shared;}public long sequence(){return sequence;}public long tick(){return sequence;}
                    public long gameTime(){return 100+sequence;}public long identity(){return 1;}public void close(){}
                };
                receive.invoke(projection,1L,lease); // All three completions, without running a single GL simulation tick.
            }
            int bank=(int)field(projection,"playbackBank");int[] history=(int[])field(projection,"playback");
            var result=read(history[bank],64);
            check(result.getFloat(0)==10&&result.getFloat(16)==20&&result.getFloat(32)==30,"per-frame ingestion retains every intermediate GPU sample");
            var display=(NativeStormDisplayClock.View)view.invoke(projection,3.5,false);
            check(Math.abs(display.weights().oldest()-.5)<1e-6&&Math.abs(display.weights().previous()-.5)<1e-6,"100ms playback delay remains unchanged");
            var replacement=bytes(size);replacement.putInt(0,STORM_DISPLAY_MAGIC).putInt(4,ABI).putInt(8,1).putInt(12,1)
                    .putLong(16,1).putLong(24,1).putLong(32,1).putInt(40,1).putFloat(STORM_DISPLAY_HEADER_BYTES,1000);
            put(shared,replacement);
            var newOwner=new NativeStormSimulation.FrozenDisplay(){
                public int buffer(){return shared;}public long sequence(){return 1;}public long tick(){return 1;}
                public long gameTime(){return 101;}public long identity(){return 1;}public long epoch(){return 2;}public void close(){}
            };
            receive.invoke(projection,1L,newOwner);
            bank=(int)field(projection,"playbackBank");result=read(history[bank],64);
            check(result.getFloat(0)==1000&&result.getFloat(16)==1000&&result.getFloat(32)==1000,"new owners can restart the sequence and reuse member identity without old history");
        }finally{((AutoCloseable)projection).close();GL15.glDeleteBuffers(shared);}
    }
    static void nativeIndex(Map<Section,Page> initial)throws Exception{
        SpatialNative.load(Path.of(".gradle/particle-rt/build"));
        var createBridge=ParticleEngineGpuValidation.stormMethod("createStormDisplayBuffers",long.class,int.class);
        var create=ParticleEngineGpuValidation.stormMethod("createStormControl",int.class,long.class,int.class);
        var submit=ParticleEngineGpuValidation.stormMethod("submitStormTick",long.class,ByteBuffer.class,int.class,int.class,boolean.class);
        var poll=ParticleEngineGpuValidation.stormMethod("pollStormTick",long.class,long.class);
        var consume=ParticleEngineGpuValidation.stormMethod("consumeStormTick",long.class,long.class);
        var recycle=ParticleEngineGpuValidation.stormMethod("recycleStormDisplay",long.class,int.class,long.class,long.class);
        var diagnostics=ParticleEngineGpuValidation.stormMethod("stormControlDiagnostics",long.class);
        var close=ParticleEngineGpuValidation.stormMethod("closeStormControl",long.class);
        int members=128;
        long bridge=(long)createBridge.invoke(null,STORM_DISPLAY_HEADER_BYTES+(long)members*STORM_DISPLAY_RECORD_BYTES+
                (long)STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES,RT_SLOTS);
        long worker=(long)create.invoke(null,members,bridge,2);
        int readback=buffer(bytes(STORM_DISPLAY_HEADER_BYTES+members*STORM_DISPLAY_RECORD_BYTES));
        try{
            for(long seq=1;seq<=7;seq++){
                var changes=new LinkedHashMap<Section,Integer>();boolean reset=seq==1||seq==5||seq==6;
                if(seq==1)initial.forEach((k,p)->changes.put(k,AIR));
                if(seq==2||seq==3||seq==4)for(var k:bodySections())changes.put(k,seq==2?UNKNOWN:0);
                if(seq==6){for(var k:bodySections())changes.put(k,AIR);for(int i=0;changes.size()<STORM_TERRAIN_MAX_SECTIONS;i++)changes.put(new Section(i,5000,0),AIR);}
                if(seq==7)changes.put(new Section(-1500000,5000,1),AIR);
                int commands=seq==1?1:0,slot=(int)((seq-1)%3);
                int mixed=seq==3||seq==4?1:0;
                var packet=bytes(STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES+changes.size()*16+mixed*STORM_TERRAIN_PAGE_BYTES);
                packet.put(0,ParticleEngineGpuValidation.stormTick(seq,seq,100+seq,1,members,seq==1),0,STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES);
                packet.putInt(88,changes.size()).putLong(STORM_TERRAIN_REVISION_OFFSET,reset?-1:seq-1).putLong(STORM_TERRAIN_REVISION_OFFSET+8,seq);
                packet.putInt(92,mixed);
                int at=STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES;
                for(var entry:changes.entrySet()){var k=entry.getKey();packet.putInt(at,k.x()).putInt(at+4,k.y()).putInt(at+8,k.z()).putInt(at+12,entry.getValue());at+=16;}
                if(seq==4)for(int i=0;i<128;i++)packet.putInt(at+i*4,-1);
                check((boolean)submit.invoke(null,worker,packet,packet.capacity(),slot,true),"native tick queued "+seq);
                long until=System.nanoTime()+20_000_000_000L;int status;
                while((status=(int)poll.invoke(null,worker,seq))==1){check(System.nanoTime()<until,"native delta completion deadline");Thread.sleep(1);}
                String diagnostic=diagnostics.invoke(null,worker).toString();
                consume.invoke(null,worker,seq);
                int unknown=-1,clipped=0,population=-1;
                if(status==2){
                    GL45.glCopyNamedBufferSubData(SpatialNative.buffers(bridge,slot)[1],readback,0,0,STORM_DISPLAY_HEADER_BYTES+(long)members*STORM_DISPLAY_RECORD_BYTES);
                    var data=read(readback,STORM_DISPLAY_HEADER_BYTES+members*STORM_DISPLAY_RECORD_BYTES);
                    population=data.getInt(8);unknown=0;
                    check(data.getLong(16)==seq,"shared display sequence "+seq+" got="+data.getLong(16));
                    for(int i=0;i<members;i++){int flags=data.getInt(STORM_DISPLAY_HEADER_BYTES+i*STORM_DISPLAY_RECORD_BYTES+STORM_DISPLAY_KEY_OFFSET+12);if((flags&1)!=0)unknown++;if((flags&2)!=0)clipped++;}
                    System.out.println("native delta="+seq+" unknown="+unknown+" clipped="+clipped+" first="+data.getFloat(64)+","+data.getFloat(68)+","+data.getFloat(72)+" flags="+data.getInt(140));
                }
                long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);GL11.glFlush();
                while(!(boolean)recycle.invoke(null,worker,slot,seq,fence)){check(System.nanoTime()<until,"native display retirement");Thread.sleep(1);}GL32.glDeleteSync(fence);
                if(seq==7){check(status==4,"true section capacity is rejected");check(diagnostic.contains("limit=32768"),"capacity diagnostic includes actual limit");}
                else{
                    check(status==2,"native delta "+seq+": "+diagnostic);
                    check(population==members,"delta preserves member population");
                    if(seq==1||seq==3||seq==6)check(unknown==0,"CUDA hash overflow coverage remains exact, tick="+seq+" unknown="+unknown);
                    if(seq==2||seq==5)check(unknown==members,"withdrawals are visible before the same tick, tick="+seq);
                    if(seq==4)check(clipped>0,"mixed SOLID replacement participates in collision");
                }
            }
        }catch(Throwable failure){System.err.println("Native fixture failure: "+failure);throw failure;}
        finally{long until=System.nanoTime()+20_000_000_000L;while(!(boolean)close.invoke(null,worker)){check(System.nanoTime()<until,"native close deadline");Thread.sleep(1);}SpatialNative.closeBuffers(bridge);GL15.glDeleteBuffers(readback);}
        check(SpatialNative.ownedBytes()==0,"native terrain and display resources drain completely");
    }
    static void nativeRevisionAndPageLimit()throws Exception{
        var create=ParticleEngineGpuValidation.stormMethod("createStormControl",int.class,long.class,int.class);
        var submit=ParticleEngineGpuValidation.stormMethod("submitStormTick",long.class,ByteBuffer.class,int.class,int.class,boolean.class);
        var poll=ParticleEngineGpuValidation.stormMethod("pollStormTick",long.class,long.class);
        var consume=ParticleEngineGpuValidation.stormMethod("consumeStormTick",long.class,long.class);
        var diagnostics=ParticleEngineGpuValidation.stormMethod("stormControlDiagnostics",long.class);
        var close=ParticleEngineGpuValidation.stormMethod("closeStormControl",long.class);
        for(boolean pageLimit:new boolean[]{false,true}){
            long worker=(long)create.invoke(null,1,0L,2);
            try{
                for(long seq=1;seq<=2;seq++){
                    int rows=seq==1?(pageLimit?STORM_TERRAIN_MAX_PAGES:0):1;
                    int pages=pageLimit?1:0;
                    var packet=bytes(STORM_TICK_HEADER_BYTES+rows*16+pages*512);
                    packet.put(0,ParticleEngineGpuValidation.stormTick(seq,seq,100+seq,1,false,false),0,STORM_TICK_HEADER_BYTES);
                    packet.putInt(88,rows).putInt(92,pages).putLong(STORM_TERRAIN_REVISION_OFFSET,seq==1?-1:pageLimit?3:2).putLong(STORM_TERRAIN_REVISION_OFFSET+8,seq==1?3:4);
                    for(int i=0;i<rows;i++){int at=STORM_TICK_HEADER_BYTES+i*16;packet.putInt(at,seq==1?i:STORM_TERRAIN_MAX_PAGES).putInt(at+4,4).putInt(at+12,pageLimit?0:AIR);}
                    check((boolean)submit.invoke(null,worker,packet,packet.capacity(),-1,false),"native boundary tick queued");
                    long until=System.nanoTime()+20_000_000_000L;int status;
                    while((status=(int)poll.invoke(null,worker,seq))==1){check(System.nanoTime()<until,"native boundary tick deadline");Thread.sleep(1);}
                    String error=diagnostics.invoke(null,worker).toString();consume.invoke(null,worker,seq);
                    check(status==(seq==1?2:4),"native revision/page capacity rejection: "+error);
                    if(seq==2)check(error.contains(pageLimit?"limit=16384":"revision mismatch"),"native boundary diagnostic: "+error);
                }
            }finally{long until=System.nanoTime()+20_000_000_000L;while(!(boolean)close.invoke(null,worker)){check(System.nanoTime()<until,"native boundary close");Thread.sleep(1);}}
            check(SpatialNative.ownedBytes()==0,"native boundary test resources drain");
        }
    }
    static void productionControlQueue()throws Exception{
        var journalType=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.spatial.StormControlJournal");
        var jc=journalType.getDeclaredConstructor();jc.setAccessible(true);Object journal=jc.newInstance();
        var parameters=Class.forName(journalType.getName()+"$Parameters");var pc=parameters.getDeclaredConstructors()[0];pc.setAccessible(true);
        var command=Class.forName(journalType.getName()+"$Command");var spawn=command.getDeclaredMethod("spawn",int.class,int.class,boolean.class);spawn.setAccessible(true);
        var append=journalType.getDeclaredMethod("append",long.class,parameters,List.class);append.setAccessible(true);
        var committed=journalType.getDeclaredMethod("committed");committed.setAccessible(true);
        var type=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.spatial.NativeStormControl");
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        java.util.function.Consumer<Object> sink=x->{};
        Object control=constructor.newInstance(Path.of(".gradle/particle-rt/build"),128,journal,sink,sink,sink,sink,sink,sink);
        var service=type.getDeclaredMethod("service");service.setAccessible(true);
        var drain=type.getDeclaredMethod("drainDisplays",long.class,java.util.function.Consumer.class);drain.setAccessible(true);
        var retire=type.getDeclaredMethod("retire");retire.setAccessible(true);
        var projectionType=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.StormProjectionGpu");
        var projectionConstructor=projectionType.getDeclaredConstructor(String.class);projectionConstructor.setAccessible(true);
        Object projection=projectionConstructor.newInstance(source("storm_projection.comp"));
        var receive=projectionType.getDeclaredMethod("receive",long.class,NativeStormSimulation.FrozenDisplay.class);receive.setAccessible(true);
        var sequences=new ArrayList<Long>();
        java.util.function.Consumer<NativeStormSimulation.FrozenDisplay> accept=display->{
            sequences.add(display.sequence());try{receive.invoke(projection,1L,display);}catch(Exception e){throw new IllegalStateException(e);}
        };
        try{
            for(long seq=1;seq<=5;seq++){
                Object p=pc.newInstance(1L,true,128,19,0f,64f,0f,8f,0f,0f,0f,1f,seq*.05f,6f,false);
                append.invoke(journal,seq,p,seq==1?List.of(spawn.invoke(null,0,128,false)):List.of());
                long until=System.nanoTime()+10_000_000_000L;
                while((long)committed.invoke(journal)<seq){service.invoke(control);GL11.glFlush();check(System.nanoTime()<until,"production async control completion "+seq);Thread.sleep(1);}
                if(seq==4){
                    drain.invoke(control,1L,accept);
                    check(sequences.equals(List.of(1L,2L,3L)),"full display ring retains all samples while tick four advances without a slot");
                    drain.invoke(control,1L,accept);check(sequences.size()==3,"display samples are consumed exactly once");
                    until=System.nanoTime()+5_000_000_000L;
                    while(true){service.invoke(control);GL11.glFlush();int free=0;
                        for(Object slot:(Object[])field(control,"displaySlots"))if((long)field(slot,"sequence")==0)free++;
                        if(free>=2)break;check(System.nanoTime()<until,"production history leases retire behind GL fences");Thread.sleep(1);
                    }
                }
                if(seq==5){drain.invoke(control,1L,accept);check(sequences.equals(List.of(1L,2L,3L,5L)),"display resumes after a full-ring gap");}
            }
            var prepare=type.getDeclaredMethod("prepareInputs");prepare.setAccessible(true);
            var queued=journalType.getDeclaredMethod("queued");queued.setAccessible(true);
            var inFlight=journalType.getDeclaredMethod("inFlight");inFlight.setAccessible(true);
            var inputWorker=(java.util.concurrent.ExecutorService)field(control,"inputWorker");
            for(long seq=6;seq<=8;seq++){
                Object p=pc.newInstance(1L,true,128,19,0f,64f,0f,8f,0f,0f,0f,1f,seq*.05f,6f,false);
                append.invoke(journal,seq,p,List.of());
            }
            prepare.invoke(control); // Client tick capture prepares the complete catch-up batch.
            inputWorker.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            service.invoke(control);
            check((int)queued.invoke(journal)==0&&(int)inFlight.invoke(journal)==3,
                    "three prepacked catch-up ticks submit in one render boundary");
            Object p=pc.newInstance(1L,true,128,19,0f,64f,0f,8f,0f,0f,0f,1f,.45f,6f,false);
            append.invoke(journal,9L,p,List.of());
            prepare.invoke(control); // Shutdown also owns a possibly in-progress input pack.
        }finally{
            long until=System.nanoTime()+15_000_000_000L;
            while(!(boolean)retire.invoke(control)){GL11.glFlush();check(System.nanoTime()<until,"production retirement includes pending input pack");Thread.sleep(1);}
            ((AutoCloseable)projection).close();
        }
        check(SpatialNative.ownedBytes()==0,"production control and shared displays release all native allocations");
    }
    static void soak(int seconds,Path out)throws Exception{
        var createBridge=ParticleEngineGpuValidation.stormMethod("createStormDisplayBuffers",long.class,int.class);
        var create=ParticleEngineGpuValidation.stormMethod("createStormControl",int.class,long.class,int.class);
        var submit=ParticleEngineGpuValidation.stormMethod("submitStormTick",long.class,ByteBuffer.class,int.class,int.class,boolean.class);
        var poll=ParticleEngineGpuValidation.stormMethod("pollStormTick",long.class,long.class);
        var consume=ParticleEngineGpuValidation.stormMethod("consumeStormTick",long.class,long.class);
        var recycle=ParticleEngineGpuValidation.stormMethod("recycleStormDisplay",long.class,int.class,long.class,long.class);
        var delay=ParticleEngineGpuValidation.stormMethod("delayStormControl",long.class,int.class);
        var prepare=ParticleEngineGpuValidation.stormMethod("prepareStormDisplaySlot",long.class,int.class);
        var close=ParticleEngineGpuValidation.stormMethod("closeStormControl",long.class);
        var type=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.StormProjectionGpu");
        var constructor=type.getDeclaredConstructor(String.class);constructor.setAccessible(true);Object projection=constructor.newInstance(source("storm_projection.comp"));
        var receive=type.getDeclaredMethod("receive",long.class,NativeStormSimulation.FrozenDisplay.class);receive.setAccessible(true);
        var view=type.getDeclaredMethod("playback",double.class,boolean.class);view.setAccessible(true);
        int members=65536;
        long bridge=(long)createBridge.invoke(null,STORM_DISPLAY_HEADER_BYTES+(long)members*STORM_DISPLAY_RECORD_BYTES+
                (long)STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES,RT_SLOTS);
        long worker=(long)create.invoke(null,members,bridge,2);
        int[] buffers=new int[3];for(int i=0;i<3;i++)buffers[i]=SpatialNative.buffers(bridge,i)[1];
        long[] fences=new long[3];long submitted=0,consumed=0,retired=0,frames=0,peakBytes=0;
        long start=System.nanoTime(),end=start+seconds*1_000_000_000L,nextFrame=start,nextReport=start;
        long terrainRevision=0;boolean passed=false;
        try{
            while(System.nanoTime()<end||consumed<submitted||retired<submitted){
                long now=System.nanoTime();
                while(consumed<submitted){
                    int slot=(int)(consumed%3);long seq=consumed+1;
                    int status=(int)poll.invoke(null,worker,seq);if(status==1)break;
                    consume.invoke(null,worker,seq);consumed++;
                    if(status!=2){fences[slot]=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);GL11.glFlush();throw new AssertionError("soak native receipt "+seq);}
                    var lease=new NativeStormSimulation.FrozenDisplay(){
                        public int buffer(){return buffers[slot];}public long sequence(){return seq;}public long tick(){return seq;}
                        public long gameTime(){return 100+seq;}public long identity(){return 1;}public void close(){}
                    };
                    receive.invoke(projection,1L,lease);
                    fences[slot]=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);GL11.glFlush();
                }
                while(retired<consumed){
                    int slot=(int)(retired%3);
                    if(!(boolean)recycle.invoke(null,worker,slot,retired+1,fences[slot]))break;
                    GL32.glDeleteSync(fences[slot]);fences[slot]=0;retired++;
                }
                if(now<end&&submitted-retired<3&&submitted*50_000_000L<=now-start
                        &&(boolean)prepare.invoke(null,worker,(int)(submitted%3))){
                    long seq=submitted+1;int slot=(int)(submitted%3);
                    var changes=new LinkedHashMap<Section,Integer>();
                    if(seq==1)for(var k:bodySections())changes.put(k,AIR);
                    else if(seq%20==0)changes.put(new Section(50,4,0),switch((int)(seq/20)%3){case 0->UNKNOWN;case 1->SOLID;default->AIR;});
                    int commands=seq==1?1:0;
                    var packet=bytes(STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES+changes.size()*16);
                    packet.put(0,ParticleEngineGpuValidation.stormTick(seq,seq,100+seq,1,members,seq==1),0,STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES);
                    if(!changes.isEmpty()){
                        packet.putInt(88,changes.size()).putLong(STORM_TERRAIN_REVISION_OFFSET,seq==1?-1:terrainRevision)
                                .putLong(STORM_TERRAIN_REVISION_OFFSET+8,++terrainRevision);
                        int at=STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES;
                        for(var entry:changes.entrySet()){var k=entry.getKey();packet.putInt(at,k.x()).putInt(at+4,k.y()).putInt(at+8,k.z()).putInt(at+12,entry.getValue());at+=16;}
                    }
                    if(seq%1200==0)delay.invoke(null,worker,500);else if(seq%300==0)delay.invoke(null,worker,50);
                    check((boolean)submit.invoke(null,worker,packet,packet.capacity(),slot,false),"soak ordered submission "+seq);submitted++;
                }
                if(consumed>0)view.invoke(projection,(now-start)/50_000_000.0,false);
                peakBytes=Math.max(peakBytes,SpatialNative.ownedBytes());frames++;
                if(now>=nextReport){System.out.println("soak elapsed="+((now-start)/1_000_000_000L)+"s submitted="+submitted+" consumed="+consumed+" retired="+retired);nextReport=now+30_000_000_000L;}
                nextFrame+=1_000_000_000L/144;
                long sleep=nextFrame-System.nanoTime();if(sleep>0)java.util.concurrent.locks.LockSupport.parkNanos(Math.min(sleep,7_000_000));else nextFrame=System.nanoTime();
                check(System.nanoTime()<end+20_000_000_000L,"soak drain deadline");
            }
            passed=true;
        }catch(Throwable failure){System.err.println("Soak primary failure: "+failure);throw failure;}
        finally{
            long deadline=System.nanoTime()+20_000_000_000L;
            while(retired<submitted){
                if(consumed<submitted){int slot=(int)(consumed%3);int status=(int)poll.invoke(null,worker,consumed+1);
                    if(status!=1){consume.invoke(null,worker,consumed+1);consumed++;fences[slot]=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);GL11.glFlush();}}
                if(retired<consumed){int slot=(int)(retired%3);if(fences[slot]==0){fences[slot]=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);GL11.glFlush();}
                    if((boolean)recycle.invoke(null,worker,slot,retired+1,fences[slot])){GL32.glDeleteSync(fences[slot]);fences[slot]=0;retired++;}}
                check(System.nanoTime()<deadline,"soak cleanup deadline");Thread.sleep(1);
            }
            while(!(boolean)close.invoke(null,worker)){check(System.nanoTime()<deadline,"soak close deadline");Thread.sleep(1);}
            SpatialNative.closeBuffers(bridge);((AutoCloseable)projection).close();
            long owned=SpatialNative.ownedBytes();
            var report=new LinkedHashMap<String,Object>();report.put("passed",passed&&owned==0);report.put("seconds",(System.nanoTime()-start)/1e9);
            report.put("members",members);report.put("render_frames",frames);report.put("submitted",submitted);report.put("consumed",consumed);report.put("retired",retired);
            report.put("peak_native_bytes",peakBytes);report.put("native_bytes_after_close",owned);report.put("scope","offscreen native tick, page revisions and shared GPU playback; not client frame-time acceptance");
            Files.writeString(out.resolve("soak.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            check(owned==0,"soak native allocations drain");
        }
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(ParticleFixtureOptions.parse(args).getOrDefault("output","build/particle-engine/storm-index-display/driver"));Files.createDirectories(out);
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);
        long window=GLFW.glfwCreateWindow(32,32,"bounded Storm index and display",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        var report=new LinkedHashMap<String,Object>();report.put("passed",false);
        try{var pages=collisionFixture();glIndex(pages);displayHistory();nativeIndex(pages);nativeRevisionAndPageLimit();productionControlQueue();int seconds=Integer.parseInt(ParticleFixtureOptions.parse(args).getOrDefault("seconds","0"));if(seconds>0)soak(seconds,out);check(GL11.glGetError()==0,"GL state");report.put("passed",true);}
        finally{report.put("checks",checks);report.put("gpu",GL11.glGetString(GL11.GL_RENDERER));report.put("driver",GL11.glGetString(GL11.GL_VERSION));Files.writeString(out.resolve("index-display.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
        System.out.println("Storm index/display GPU: "+checks+" checks passed");
    }
}
