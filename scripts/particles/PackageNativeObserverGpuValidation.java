import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;
import com.iridium126.createmanaindustry.client.particles.packages.*;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.phys.Vec3;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

/** Native packet arithmetic against Minecraft's actual codec. Blocking reads are harness-only. */
public final class PackageNativeObserverGpuValidation {
    static int checks;
    static final long EPOCH=0x3456789000000001L;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    static void exact(double a,double b,String message){check(Double.doubleToLongBits(a)==Double.doubleToLongBits(b),message+": "+a+" != "+b);}
    static String source(String name){return ParticleShaderSource.loadParticle(name,path->{
        try(var in=PackageObserverGpu.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)){
            if(in==null)throw new IllegalArgumentException(path);return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});}
    static ByteBuffer read(int id,int n){var b=BufferUtils.createByteBuffer(n);GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,b);return b;}
    static void apply(PackageObserverGpu gpu,ByteBuffer b,int n,int slots,float time,boolean compact){
        b.flip();check(gpu.tryApplyNative(b,n,slots,time,compact),"Unexpected busy native upload");GL11.glFinish();
    }
    static void valid(PackageObserverGpu gpu){check(read(gpu.controlBuffer(),16).getInt(0)==0,"Native batch rejected");}
    static int id(int i){return i%2==0?-i-1:i+1;}
    static Vec3 initial(int i){return new Vec3(29999960.000031+i%8,-31.00123+i%4,-29999970.000023+i%7);}
    static void boundaries() {
        var origin=new PackageObserverGpu.NativeOrigin(29999936,0,-29999936);
        for(int n:new int[]{0,1,63,64,65,131072})try(var mixed=new PackageMixedPhysicsGpu(0,0,n<131072?n+1:n,2,PackageNativeObserverGpuValidation::source,origin)) {
            var gpu=mixed.observers();var b=BufferUtils.createByteBuffer(n*128);Vec3[] reference=new Vec3[n];
            if(n<131072)for(int bytes:new int[]{64,144}) {
                var sentinel=BufferUtils.createByteBuffer(bytes);for(int j=0;j<bytes;j++)sentinel.put(j,(byte)0x5a);
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bytes==64?gpu.nativeStateBuffer():gpu.stateBuffer());GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)n*bytes,sentinel);
            }
            for(int i=0;i<n;i++){var v=reference[i]=initial(i);
                PackageNativeObserverPatch.baseline(b,i,id(i),i+1,0x1234567800000001L+i,EPOCH,0,v.x,v.y,v.z,.01,-.02,.03,1,.75f,17,true,0);}
            apply(gpu,b,n,n,0,false);mixed.sampleObservers(0);mixed.publish();valid(gpu);
            for(int wave=0;wave<5;wave++) {
                b=BufferUtils.createByteBuffer(n*64);float receipt=wave*.025f;
                for(int i=0;i<n;i++) {
                    short dx=(short)(i%2==0?0:-32768+wave*57),dy=(short)(wave%2==0?0:32767),dz=(short)(i%3==0?0:1234-wave*113);
                    var codec=new VecDeltaCodec();codec.setBase(reference[i]);reference[i]=codec.decode(dx,dy,dz);
                    PackageNativeObserverPatch.move(b,i,id(i),EPOCH,wave+1,dx,dy,dz,true,true,(byte)(128+wave),wave%2==0,receipt);
                }
                apply(gpu,b,n,n,receipt,true);mixed.sampleObservers(receipt);mixed.publish();valid(gpu);
                ByteBuffer actual=read(gpu.nativeStateBuffer(),n*64),state=read(gpu.stateBuffer(),n*144),body=read(mixed.bodyBuffer(),n*64);
                for(int i=0;i<n;i++) {
                    var v=reference[i];exact(actual.getDouble(i*64),v.x,"Native x");exact(actual.getDouble(i*64+8),v.y,"Native y");exact(actual.getDouble(i*64+16),v.z,"Native z");
                    check(state.getLong(i*144+16)==EPOCH&&state.getLong(i*144+24)==1,"Full native namespace");
                    check(state.getInt(i*144+44)==id(i)&&state.getLong(i*144+32)==wave+1,"Signed native ID/sequence");
                    check(state.getInt(i*144+60)==(wave%2==0?1:0),"Native ground flag");
                    check(Float.isFinite(body.getFloat(i*64))&&body.getFloat(i*64+60)==0,"Native common-pool output");
                }
            }
            b=BufferUtils.createByteBuffer(n*64);
            for(int i=0;i<n;i++)PackageNativeObserverPatch.motion(b,i,id(i),EPOCH,6,31200,-31200,0,.125f);
            apply(gpu,b,n,n,.125f,true);gpu.sample(.125f);valid(gpu);var velocities=read(gpu.nativeStateBuffer(),n*64);
            for(int i=0;i<n;i++){check(Math.abs(velocities.getDouble(i*64+32)-(.01+3.9)*.5)<1e-14,"Create motion average x");
                check(Math.abs(velocities.getDouble(i*64+40)-(-.02-3.9)*.5)<1e-14,"Create motion average y");}
            // Motion packets cannot make stale position data look fresh.
            gpu.sample(.301f);var stats=read(gpu.controlBuffer(),16);check(stats.getInt(4)==n,"Native velocity-only extended position freshness");
            gpu.retireNamespace(EPOCH,1,.301f);gpu.sample(.302f);check(read(gpu.controlBuffer(),16).getInt(8)==0,"Retired native bodies remain active");
            if(n<131072)for(int bytes:new int[]{64,144}) {
                var tail=read(bytes==64?gpu.nativeStateBuffer():gpu.stateBuffer(),(n+1)*bytes);
                for(int j=n*bytes;j<(n+1)*bytes;j++)check(tail.get(j)==(byte)0x5a,"Native tail buffer sentinel");
            }
        }
    }
    static ByteBuffer baseline(int n){var b=BufferUtils.createByteBuffer(n*128);for(int i=0;i<n;i++)
        PackageNativeObserverPatch.baseline(b,i,id(i),i+1,1,EPOCH,0,.00001,2,.00003,.01,0,0,1,.75f,179,true,0);return b;}
    static void roundingTeleportAndRebuild() {
        double[] edges={Math.nextDown(.5),.5,Math.nextUp(.5),Math.nextDown(-.5),-.5,Math.nextUp(-.5),-0.0,0.0,1024.5,-1024.5};
        try(var gpu=new PackageObserverGpu(edges.length,PackageNativeObserverGpuValidation::source,()->true,new PackageObserverGpu.NativeOrigin(0,0,0))) {
            var b=BufferUtils.createByteBuffer(edges.length*128);for(int i=0;i<edges.length;i++)
                PackageNativeObserverPatch.baseline(b,i,id(i),i+1,1,EPOCH,0,edges[i]/4096,2,edges[i]/4096,0,0,0,1,.75f,179,true,0);
            apply(gpu,b,edges.length,edges.length,0,false);
            b=BufferUtils.createByteBuffer(edges.length*64);for(int i=0;i<edges.length;i++)
                PackageNativeObserverPatch.move(b,i,id(i),EPOCH,1,(short)1,(short)0,(short)0,true,false,(byte)0,false,.01f);
            apply(gpu,b,edges.length,edges.length,.01f,true);valid(gpu);var actual=read(gpu.nativeStateBuffer(),edges.length*64);
            for(int i=0;i<edges.length;i++){
                var codec=new VecDeltaCodec();codec.setBase(new Vec3(edges[i]/4096,2,edges[i]/4096));var expected=codec.decode(1,0,0);
                exact(actual.getDouble(i*64),expected.x,"Java rounding boundary");exact(actual.getDouble(i*64+16),expected.z,"Unchanged non-grid/signed-zero axis");
            }
            var before=read(gpu.nativeStateBuffer(),edges.length*64);boolean rejected=false;
            try{gpu.rebuild(name->name.endsWith("observer_native_apply.comp")?"invalid GLSL":source(name));}catch(RuntimeException expected){rejected=true;}
            check(rejected&&before.equals(read(gpu.nativeStateBuffer(),edges.length*64)),"Failed reload lost native state");gpu.rebuild(PackageNativeObserverGpuValidation::source);
            b=BufferUtils.createByteBuffer(64);PackageNativeObserverPatch.teleport(b,0,id(0),EPOCH,2,-.123456789,3,.234567891,(byte)-128,true,.02f);
            apply(gpu,b,1,edges.length,.02f,true);gpu.sample(.17f);valid(gpu);actual=read(gpu.nativeStateBuffer(),64);
            exact(actual.getDouble(0),-.123456789,"Teleport resets exact codec base");exact(actual.getDouble(16),.234567891,"Teleport double z");
            var body=read(gpu.bodyBuffer(),64);check(Math.abs(body.getFloat(44)-180)<1e-4,"Signed native yaw / circular correction");
        }
    }
    static void atomicFailures() {
        for(String failure:List.of("epoch","entity","sequence","duplicate","slot","time","overflow","reserved","reuse"))
            try(var gpu=new PackageObserverGpu(2,PackageNativeObserverGpuValidation::source,()->true,new PackageObserverGpu.NativeOrigin(0,0,0))) {
                apply(gpu,baseline(2),2,2,0,false);
                if(failure.equals("reuse")){var release=BufferUtils.createByteBuffer(64);PackageNativeObserverPatch.release(release,1,id(1),EPOCH,1,.01f);apply(gpu,release,1,2,.01f,true);}
                ByteBuffer before=read(gpu.stateBuffer(),288),nativeBefore=read(gpu.nativeStateBuffer(),128),b=BufferUtils.createByteBuffer(128);
                for(int i=0;i<2;i++)PackageNativeObserverPatch.move(b,i,id(i),EPOCH,2,(short)1,(short)1,(short)1,true,false,(byte)0,true,.02f);
                switch(failure){case "epoch"->b.putLong(64,EPOCH+0x100000000L);case "entity"->b.putInt(84,id(1)+1);case "sequence"->b.putLong(72,0);
                    case "duplicate"->{for(int j=0;j<64;j+=4)b.putInt(64+j,b.getInt(j));}case "slot"->b.putInt(80,2);case "time"->b.putFloat(120,.03f);
                    case "overflow"->b.putInt(96,32768);case "reserved"->b.putInt(124,1);}
                apply(gpu,b,2,2,.02f,true);check(read(gpu.controlBuffer(),16).getInt(0)!=0,"Accepted invalid "+failure);
                check(before.equals(read(gpu.stateBuffer(),288))&&nativeBefore.equals(read(gpu.nativeStateBuffer(),128)),"Partial native mutation "+failure);
            }
    }
    static void delayedUploads() {
        boolean[] consume={false};try(var gpu=new PackageObserverGpu(1,PackageNativeObserverGpuValidation::source,()->consume[0],new PackageObserverGpu.NativeOrigin(0,0,0))) {
            apply(gpu,baseline(1),1,1,0,false);
            for(int i=1;i<=3;i++){var b=BufferUtils.createByteBuffer(64);PackageNativeObserverPatch.move(b,0,id(0),EPOCH,i,(short)1,(short)0,(short)0,true,false,(byte)0,false,i*.01f);apply(gpu,b,1,1,i*.01f,true);}
            var pending=BufferUtils.createByteBuffer(64);PackageNativeObserverPatch.move(pending,0,id(0),EPOCH,4,(short)1,(short)0,(short)0,true,false,(byte)0,false,.04f);pending.flip();
            var before=read(gpu.nativeStateBuffer(),64);for(int i=0;i<8;i++)check(!gpu.tryApplyNative(pending,1,1,.04f,true),"Busy ring overwritten");
            check(before.equals(read(gpu.nativeStateBuffer(),64)),"Busy upload changed pose");consume[0]=true;
            check(gpu.tryApplyNative(pending,1,1,.04f,true),"Retained relative packet cannot retry");GL11.glFinish();valid(gpu);
            var codec=new VecDeltaCodec();codec.setBase(new Vec3(.00001,2,.00003));Vec3 expected=null;
            for(int i=0;i<4;i++){expected=codec.decode(1,0,0);codec.setBase(expected);}exact(read(gpu.nativeStateBuffer(),64).getDouble(0),expected.x,"Ring retry lost relative motion");
        }
    }
    static void orderedInbox() {
        try(var gpu=new PackageObserverGpu(2,PackageNativeObserverGpuValidation::source,()->true,new PackageObserverGpu.NativeOrigin(0,0,0))) {
            apply(gpu,baseline(2),2,2,0,false);var queue=new PackageNativeObserverCommands(2,8,EPOCH);
            for(int sequence=1;sequence<=2;sequence++)for(int i=0;i<2;i++){
                var b=BufferUtils.createByteBuffer(64);PackageNativeObserverPatch.move(b,i,id(i),EPOCH,sequence,(short)1,(short)0,(short)0,true,false,(byte)0,false,.01f);
                b.flip();check(queue.offer(b),"Native inbox offer");
            }
            var release=BufferUtils.createByteBuffer(64);PackageNativeObserverPatch.release(release,1,id(1),EPOCH,3,.02f);release.flip();check(queue.offer(release),"Native inbox lifecycle offer");
            check(queue.drain((data,n,slots,time)->{boolean submitted=gpu.tryApplyNative(data,n,slots,time,true);if(submitted)GL11.glFinish();return submitted;},2,.02f,4)==5,"Native inbox GPU waves");
            gpu.sample(.02f);valid(gpu);var codec=new VecDeltaCodec();codec.setBase(new Vec3(.00001,2,.00003));var expected=codec.decode(1,0,0);codec.setBase(expected);expected=codec.decode(1,0,0);
            exact(read(gpu.nativeStateBuffer(),64).getDouble(0),expected.x,"Ordered inbox relative decode");
            check(read(gpu.stateBuffer(),288).getInt(144+40)==2,"Ordered inbox release lost");
            ByteBuffer before=read(gpu.nativeStateBuffer(),128),b=BufferUtils.createByteBuffer(128);
            PackageNativeObserverPatch.baseline(b,1,id(1),3,2,EPOCH,4,0,2,0,0,0,0,1,.75f,0,true,.03f);apply(gpu,b,1,2,.03f,false);
            check(read(gpu.controlBuffer(),16).getInt(0)!=0&&before.equals(read(gpu.nativeStateBuffer(),128)),"Retired native slot reintroduced");
        }
    }
    static final net.minecraft.resources.ResourceLocation MODEL=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test","box");
    static PackageNativeObserverController.Baseline member(int i,UUID uuid) {
        return new PackageNativeObserverController.Baseline(id(i),uuid,MODEL,1,.75f,0,i%100+.00003,2,i/100%100+.00002,0,0,0,17,true);
    }
    static int buffer(ByteBuffer data){int b=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data,GL15.GL_DYNAMIC_COPY);return b;}
    static void frame(PackageNativeObserverController controller,PackageMixedPhysicsGpu mixed,PackagePoolGpu pool,int particles,int counter,long generation,int ordinary) {
        controller.prepare(System.nanoTime());mixed.publish();mixed.source(pool,0,0,0);
        var count=BufferUtils.createByteBuffer(16);count.putInt(0,ordinary);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counter);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,count);
        pool.stage(particles,counter,7,new float[24],0,0,0);pool.commit();controller.committed(generation);GL11.glFinish();
    }
    static void controller() {
        for(int n:new int[]{0,1,65,131072})try(var mixed=new PackageMixedPhysicsGpu(0,0,Math.min(131072,Math.max(1,n+1)),2,PackageNativeObserverGpuValidation::source,new PackageObserverGpu.NativeOrigin(0,0,0));
                                             var pool=new PackagePoolGpu(Math.min(131072,Math.max(1,n+1)),1,PackageNativeObserverGpuValidation::source)) {
            var ranges=BufferUtils.createByteBuffer(16);ranges.putInt(4,3).putFloat(8,1);pool.uploadMeshes(BufferUtils.createByteBuffer(3*48),ranges,1);
            var claimed=new HashMap<Integer,UUID>();var retired=new ArrayList<UUID>();var failures=new ArrayList<String>();
            var lifecycle=new PackageNativeObserverController.Lifecycle() {
                public boolean admitted(PackageNativeObserverController.Baseline b,long epoch,long visualId,long generation,int slot){check(slot>0&&epoch==EPOCH,"Native visible admission namespace");claimed.put(b.entityId(),b.uuid());return true;}
                public void released(PackageNativeObserverController.Baseline b,long epoch,long visualId,long generation){claimed.remove(b.entityId(),b.uuid());retired.add(b.uuid());}
                public void closed(long epoch){claimed.clear();}
                public void failed(String reason){failures.add(reason);}
            };
            try(var c=new PackageNativeObserverController(mixed,pool,Map.of(MODEL,new PackageModelCache.Style(0,-1)),EPOCH,lifecycle)) {
                for(int i=0;i<n;i++)check(c.observe(member(i,new UUID(17,i)),System.nanoTime())==PackageNativeObserverController.Observation.INTRODUCED,"Native member introduction");
                int particles=buffer(BufferUtils.createByteBuffer(pool.capacity()*64)),counter=buffer(BufferUtils.createByteBuffer(16));long generation=0;
                try {
                    frame(c,mixed,pool,particles,counter,generation++,0);check(claimed.isEmpty(),"Upload/hidden admission claimed native rendering early");
                    while(c.active()<n&&generation<260)frame(c,mixed,pool,particles,counter,generation++,0);
                    check(c.active()==n&&claimed.size()==n&&failures.isEmpty(),"Native controller did not admit full capacity: "+n+" "+failures);
                    check(mixed.observerCount()==n&&pool.metadataCount()==n,"Native identity count/pool slot contract");
                    // Once admitted, native observers survive ordinary emission pressure.
                    frame(c,mixed,pool,particles,counter,generation++,n+1);check(read(counter,16).getInt(0)==pool.capacity(),"Active observer reservation overflow");
                    var admitted=read(pool.admissionBuffer(),n*32);var slots=new HashSet<Integer>();
                    for(int i=0;i<n;i++)check(admitted.getInt(i*32+16)>0&&slots.add(admitted.getInt(i*32+16)),"Native admission slot uniqueness under emission pressure");
                    if(n>0) {
                        var uuid=new UUID(17,0);check(c.observe(member(0,uuid),System.nanoTime())==PackageNativeObserverController.Observation.EXISTING,"Existing native identity reintroduced");
                        c.move(id(0),uuid,(short)1,(short)0,(short)0,true,false,(byte)0,false,System.nanoTime());
                        frame(c,mixed,pool,particles,counter,generation++,0);
                        var codec=new VecDeltaCodec();var initial=member(0,uuid);codec.setBase(new Vec3(initial.x(),initial.y(),initial.z()));exact(read(mixed.observers().nativeStateBuffer(),64).getDouble(0),codec.decode(1,0,0).x,"Native controller raw command merge");
                        // UUID reuse retires old state; a delayed old admission/release cannot clear the new owner.
                        var nextUuid=new UUID(18,0);check(c.observe(member(0,nextUuid),System.nanoTime())==(n==131072?PackageNativeObserverController.Observation.IGNORED:PackageNativeObserverController.Observation.INTRODUCED),"Reused native entity ID/capacity behavior");
                        for(int k=0;k<5;k++)frame(c,mixed,pool,particles,counter,generation++,0);
                        check((n==131072?!claimed.containsKey(id(0)):nextUuid.equals(claimed.get(id(0))))&&retired.contains(uuid),"Native UUID reuse/late old release erased new claim");
                        c.remove(id(0));for(int k=0;k<3;k++)frame(c,mixed,pool,particles,counter,generation++,0);
                        check(!claimed.containsKey(id(0))&&c.active()==n-1,"Native removal did not restore rendering");
                        check(failures.isEmpty(),"Native lifecycle fallback "+failures);
                    }
                }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
            }
            check(claimed.isEmpty(),"Native close left render claims");
        }
    }
    static void busyController() {
        boolean[] consume={false};
        try(var mixed=new PackageMixedPhysicsGpu(0,0,2,2,PackageNativeObserverGpuValidation::source,new PackageObserverGpu.NativeOrigin(0,0,0),()->consume[0]);
            var pool=new PackagePoolGpu(2,1,PackageNativeObserverGpuValidation::source)) {
            var ranges=BufferUtils.createByteBuffer(16);ranges.putInt(4,3).putFloat(8,1);pool.uploadMeshes(BufferUtils.createByteBuffer(3*48),ranges,1);
            var claimed=new HashSet<UUID>();var failures=new ArrayList<String>();
            var lifecycle=new PackageNativeObserverController.Lifecycle() {
                public boolean admitted(PackageNativeObserverController.Baseline b,long e,long id,long gen,int slot){claimed.add(b.uuid());return true;}
                public void released(PackageNativeObserverController.Baseline b,long e,long id,long gen){claimed.remove(b.uuid());}
                public void closed(long e){claimed.clear();}
                public void failed(String reason){failures.add(reason);}
            };
            try(var c=new PackageNativeObserverController(mixed,pool,Map.of(MODEL,new PackageModelCache.Style(0,-1)),EPOCH,lifecycle)) {
                var first=new UUID(19,0);c.observe(member(0,first),System.nanoTime());
                int particles=buffer(BufferUtils.createByteBuffer(128)),counter=buffer(BufferUtils.createByteBuffer(16));long gen=0;
                try {
                    for(int i=0;i<3;i++)frame(c,mixed,pool,particles,counter,gen++,0);check(claimed.contains(first),"Busy test native member not active");
                    for(int i=0;i<3;i++){c.move(id(0),first,(short)1,(short)0,(short)0,true,false,(byte)0,false,System.nanoTime());frame(c,mixed,pool,particles,counter,gen++,0);}
                    c.observe(member(1,new UUID(19,1)),System.nanoTime());long before=mixed.observers().publicationVersion();
                    frame(c,mixed,pool,particles,counter,gen++,0);check(mixed.observerCount()==1&&mixed.observers().publicationVersion()>before,"Busy baseline stopped older observer sampling");
                    c.remove(id(1));c.remove(id(0));
                    for(int i=0;i<3;i++)frame(c,mixed,pool,particles,counter,gen++,0);
                    check(claimed.isEmpty()&&c.active()==0,"Pending baseline blocked active native retirement");
                    consume[0]=true;for(int i=0;i<3;i++)frame(c,mixed,pool,particles,counter,gen++,0);
                    check(mixed.observerCount()==2&&failures.isEmpty(),"Busy native baseline/retirement retry failed "+failures);
                    var nativeState=read(mixed.observers().stateBuffer(),288);check(nativeState.getInt(40)==2&&nativeState.getInt(184)==2,"Retained native releases were lost");
                }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
            }
        }
    }
    static double percentile(double[] a,double p){var copy=a.clone();Arrays.sort(copy);return copy[(int)Math.ceil(copy.length*p)-1];}
    static void benchmark() throws Exception {
        var rows=new ArrayList<String>();rows.add("particles,repeat,samples,prepare_cpu_p50_ms,prepare_cpu_p95_ms,submit_cpu_p50_ms,submit_cpu_p95_ms,gpu_p50_ms,gpu_p95_ms,upload_bytes,pose_readback_bytes");
        var samples=new ArrayList<String>();samples.add("particles,repeat,sample,prepare_cpu_ms,submit_cpu_ms,gpu_ms");
        for(int n:new int[]{10000,65536,131072})for(int repeat=1;repeat<=3;repeat++)
            try(var mixed=new PackageMixedPhysicsGpu(0,0,n,2,PackageNativeObserverGpuValidation::source,new PackageObserverGpu.NativeOrigin(0,0,0))) {
                var gpu=mixed.observers();var b=BufferUtils.createByteBuffer(n*128);
                for(int i=0;i<n;i++)PackageNativeObserverPatch.baseline(b,i,id(i),i+1,1,EPOCH,0,i%100,2,i/100%100,0,0,0,1,.75f,0,true,0);
                apply(gpu,b,n,n,0,false);b=BufferUtils.createByteBuffer(n*64);
                for(int i=0;i<n;i++)PackageNativeObserverPatch.move(b,i,id(i),EPOCH,1,(short)32,(short)0,(short)-16,true,true,(byte)0,true,.05f);b.flip();
                double[] prepare=new double[120],submit=new double[120],timing=new double[120];int query=GL15.glGenQueries();long sequence=0;
                try {
                    // 30 warm-up waves, then 120 measured waves, independent fresh domain per repeat.
                    for(int wave=-30;wave<120;wave++) {
                        float receipt=++sequence*.05f;long began=System.nanoTime();
                        for(int i=0;i<n;i++){b.putLong(i*64+8,sequence);b.putFloat(i*64+56,receipt);}
                        double prepared=(System.nanoTime()-began)/1e6;
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);began=System.nanoTime();
                        check(gpu.tryApplyNative(b,n,n,receipt,true),"Native benchmark busy upload");mixed.sampleObservers(receipt+.025f);mixed.publish();
                        double submitted=(System.nanoTime()-began)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        double elapsed=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;
                        if(wave>=0){prepare[wave]=prepared;submit[wave]=submitted;timing[wave]=elapsed;samples.add(n+","+repeat+","+wave+","+prepared+","+submitted+","+elapsed);}
                    }
                    valid(gpu);String row=n+","+repeat+",120,"+percentile(prepare,.5)+","+percentile(prepare,.95)+","+percentile(submit,.5)+","+percentile(submit,.95)
                        +","+percentile(timing,.5)+","+percentile(timing,.95)+","+(long)n*64+",0";rows.add(row);System.out.println(row);
                }finally{GL15.glDeleteQueries(query);}
            }
        Files.write(Path.of("build/package-native-observer-gpu.csv"),rows);Files.write(Path.of("build/package-native-observer-gpu-samples.csv"),samples);
    }
    static void domainVersions() {
        try(var mixed=new PackageMixedPhysicsGpu(1,1,1,2,PackageNativeObserverGpuValidation::source,new PackageObserverGpu.NativeOrigin(0,0,0))) {
            var body=BufferUtils.createByteBuffer(64);body.putFloat(12,1).putFloat(32,.5f).putFloat(36,.5f).putFloat(40,.5f);
            mixed.uploadFree(body,1);mixed.publish();long f=mixed.freePublicationVersion(),c=mixed.chainPublicationVersion(),v=mixed.publicationVersion();
            var baseline=BufferUtils.createByteBuffer(128);PackageNativeObserverPatch.baseline(baseline,0,1,100,200,EPOCH,0,1,2,3,0,0,0,1,1,0,true,0);
            apply(mixed.observers(),baseline,1,1,0,false);mixed.sampleObservers(0);mixed.publish();
            check(mixed.publicationVersion()>v&&mixed.freePublicationVersion()==f&&mixed.chainPublicationVersion()==c,"Observer introduction dirtied physical checkpoint clocks");
            v=mixed.publicationVersion();mixed.sampleObservers(.05f);mixed.publish();
            check(mixed.publicationVersion()>v&&mixed.freePublicationVersion()==f&&mixed.chainPublicationVersion()==c,"Observer sample dirtied unchanged physical domains");
            mixed.uploadChains(body,BufferUtils.createByteBuffer(64),1);check(mixed.chainPublicationVersion()==c,"Unpublished mutation advanced checkpoint token");mixed.publish();
            check(mixed.chainPublicationVersion()>c&&mixed.freePublicationVersion()==f,"Chain mutation dirtied free checkpoint");c=mixed.chainPublicationVersion();
            mixed.replaceFree(0,body,BufferUtils.createByteBuffer(64),1);mixed.publish();
            check(mixed.freePublicationVersion()>f&&mixed.chainPublicationVersion()==c,"Free mutation dirtied chain checkpoint");f=mixed.freePublicationVersion();
            mixed.retireChain(0);mixed.publish();check(mixed.freePublicationVersion()==f&&mixed.chainPublicationVersion()>c,"Chain retirement lost domain token");
        }
    }
    public static void main(String[] args) throws Exception {
        var callback=GLFWErrorCallback.createPrint(System.err);callback.set();check(GLFW.glfwInit(),"GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Native package observer validation",0,0);check(window!=0,"Context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));
            boundaries();roundingTeleportAndRebuild();atomicFailures();delayedUploads();orderedInbox();controller();busyController();domainVersions();check(GL11.glGetError()==0,"Native GL error");
            if(Arrays.asList(args).contains("--benchmark"))benchmark();
            System.out.println("Native package observer GPU: "+checks+" assertions passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
