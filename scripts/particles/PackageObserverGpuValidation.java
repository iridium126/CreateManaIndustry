import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;
import com.iridium126.createmanaindustry.client.particles.packages.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

/** Real-driver observer validation. Blocking reads/timers belong to this offline harness only. */
public final class PackageObserverGpuValidation {
    static int checks;
    static final long EPOCH=0x3456789000000001L,STREAM=0x4567890100000001L;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void near(float value,float expected,String message){check(Float.isFinite(value)&&Math.abs(value-expected)<4e-5f,message+": "+value+" != "+expected);}
    static String source(String name) {
        return ParticleShaderSource.loadParticle(name,path->{try(var input=PackageObserverGpu.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)) {
            if(input==null)throw new IllegalArgumentException(path);return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}});
    }
    static PackageLease.Identity identity(int i){return new PackageLease.Identity(0x1234567800000001L+i,0x2345678900000001L+i);}
    static int serverIndex(int i){return 65536+3*i;}
    static PackageDeltaCodec.Quantized state(int i,boolean moving){return new PackageDeltaCodec.Quantized((i%60)*4096+410,20480,(i/60%60)*4096+820,
            (short)(moving?8:0),(short)0,(short)(moving?-4:0),(short)-16384,1);}
    static ByteBuffer patches(int n){return BufferUtils.createByteBuffer(n*PackageObserverGpu.PATCH_BYTES);}
    static ByteBuffer baselines(int n,boolean moving) {
        var data=patches(n);for(int i=0;i<n;i++){data.position(i*128);PackageObserverPatch.baseline(data,i,serverIndex(i),identity(i),EPOCH,STREAM,0,state(i,moving),-64,128,64,1,.75f,0);}
        data.position(0);return data;
    }
    static ByteBuffer read(int buffer,int bytes) {
        var result=BufferUtils.createByteBuffer(bytes);GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);return result;
    }
    static int buffer(ByteBuffer data){int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data,GL15.GL_DYNAMIC_COPY);return id;}
    static void stats(PackageObserverGpu gpu,int active,int stale) {
        var result=read(gpu.controlBuffer(),16);check(result.getInt(0)==0,"GPU patch error "+result.getInt(0));
        check(result.getInt(4)==stale,"stale count");check(result.getInt(8)==active,"active count");check(result.getInt(12)==0,"control sentinel");
    }
    static void boundaries() {
        for(int n:new int[]{0,1,63,64,65,127,128,129,10000,65536,131072}) {
            int capacity=Math.max(1,n+(n<131072?1:0));
            try(var gpu=new PackageObserverGpu(capacity,PackageObserverGpuValidation::source)) {
                ByteBuffer sentinel=BufferUtils.createByteBuffer(144);for(int i=0;i<144;i++)sentinel.put(i,(byte)0x5a);
                if(capacity>n){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.stateBuffer());GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)n*144,sentinel);}
                gpu.apply(baselines(n,true),n,n,0);gpu.sample(0);stats(gpu,n,0);
                gpu.sample(.025f);stats(gpu,n,0);var bodies=read(gpu.bodyBuffer(),capacity*64);var history=read(gpu.historyBuffer(),capacity*32);var states=read(gpu.stateBuffer(),capacity*144);
                for(int i=0;i<n;i++) {
                    var q=state(i,true);int b=i*64,s=i*144,h=i*32;
                    near(bodies.getFloat(b),-64+q.x()/4096f+.0125f,"predicted x");near(bodies.getFloat(b+4),128+q.y()/4096f+.375f,"centre y");
                    near(bodies.getFloat(b+8),64+q.z()/4096f-.00625f,"predicted z");near(bodies.getFloat(b+44),270,"decoded circular yaw");
                    check(bodies.getFloat(b+60)==0,"live body lifecycle");
                    for(int j=0;j<3;j++){near(history.getFloat(h+j*4),bodies.getFloat(b+j*4),"history endpoint");near(history.getFloat(h+16+j*4),bodies.getFloat(b+j*4),"history target");}
                    near(history.getFloat(h+12),bodies.getFloat(b+44),"history yaw");
                    check(states.getLong(s)==identity(i).id()&&states.getLong(s+8)==identity(i).generation(),"full identity/stride");
                    check(states.getLong(s+16)==EPOCH&&states.getLong(s+24)==STREAM,"full epoch/stream");
                    check(states.getInt(s+40)==1&&states.getInt(s+44)==serverIndex(i),"index namespace");
                    check(states.getInt(s+48)==q.x()&&states.getInt(s+64)==q.vx()&&states.getInt(s+76)==q.yaw(),"exact wire state");
                }
                if(capacity>n)for(int i=0;i<144;i++)check(states.get(n*144+i)==(byte)0x5a,"tail state sentinel");
                if(capacity>n)for(int i=n*64;i<capacity*64;i++)check(bodies.get(i)==0,"tail output sentinel");
                gpu.sample(.101f);stats(gpu,0,n);var expired=read(gpu.bodyBuffer(),n*64);
                for(int i=0;i<n;i++)check(expired.getFloat(i*64+60)==-1,"expired moving state must retire");
                long version=gpu.publicationVersion();int body=gpu.bodyBuffer();gpu.sample(.101f);
                check(gpu.publicationVersion()==version&&gpu.bodyBuffer()==body,"unchanged sample republished");
            }
        }
    }
    static ByteBuffer delta(int local,long sequence,int mask,PackageDeltaCodec.Quantized state,float time) {
        var data=patches(1);PackageObserverPatch.delta(data,local,identity(local),EPOCH,STREAM,sequence,
                new PackageDeltaCodec.Entry(serverIndex(local),mask,state),time);return data;
    }
    static void mergeAndCorrection() {
        try(var gpu=new PackageObserverGpu(2,PackageObserverGpuValidation::source)) {
            gpu.apply(baselines(1,true),1,1,0);gpu.sample(.05f);var before=read(gpu.bodyBuffer(),64);var q=state(0,true);
            var moved=new PackageDeltaCodec.Quantized(q.x()+410,q.y(),q.z(),q.vx(),q.vy(),q.vz(),q.yaw(),q.flags());
            gpu.apply(delta(0,1,PackageDeltaCodec.POSITION,moved,.05f),1,1,.05f);
            boolean refused=false;try{gpu.bodyBuffer();}catch(IllegalStateException pending){refused=true;}check(refused,"un-sampled patch exposed old body/count");
            gpu.sample(.05f);var current=read(gpu.bodyBuffer(),64);
            near(current.getFloat(0),before.getFloat(0),"position correction jumps at receipt");
            gpu.sample(.075f);current=read(gpu.bodyBuffer(),64);
            float from=before.getFloat(0)+.5f*.025f,target=-64+moved.x()/4096f+.5f*.025f;
            near(current.getFloat(0),(from+target)*.5f,"midpoint correction");
            gpu.sample(.1f);near(read(gpu.bodyBuffer(),64).getFloat(0),-64+moved.x()/4096f+.025f,"final correction");
            var stop=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0);
            gpu.apply(delta(0,2,PackageDeltaCodec.VELOCITY,stop,.1f),1,1,.1f);gpu.sample(.1f);stats(gpu,1,0);
            var states=read(gpu.stateBuffer(),144);check(states.getInt(48)==moved.x()&&states.getInt(52)==moved.y(),"velocity rewrote exact position");
            check(states.getInt(64)==0&&states.getInt(76)==q.yaw()&&states.getInt(60)==q.flags(),"velocity overwrote other fields");
            gpu.sample(.2f);near(read(gpu.bodyBuffer(),64).getFloat(0),-64+moved.x()/4096f,"stop retained extrapolated offset");stats(gpu,1,0);
            var flags=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,2);
            gpu.apply(delta(0,3,PackageDeltaCodec.FLAGS,flags,.2f),1,1,.2f);gpu.sample(.25f);stats(gpu,1,0);
            check(read(gpu.stateBuffer(),144).getInt(60)==2,"independent sleeping flag");
            var yaw=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)16384,0);
            float previousYaw=read(gpu.bodyBuffer(),64).getFloat(44);
            gpu.apply(delta(0,4,PackageDeltaCodec.YAW,yaw,.25f),1,1,.25f);gpu.sample(.25f);
            near(read(gpu.bodyBuffer(),64).getFloat(44),previousYaw,"yaw correction jumped");gpu.sample(.3f);
            float finalYaw=read(gpu.bodyBuffer(),64).getFloat(44);near((finalYaw%360+360)%360,90,"yaw correction end");
            gpu.apply(delta(0,5,PackageDeltaCodec.RELEASE,q,.3f),1,1,.3f);gpu.sample(.35f);stats(gpu,0,0);
            check(read(gpu.bodyBuffer(),64).getFloat(60)==-3,"retirement still visible");check(read(gpu.stateBuffer(),144).getInt(40)==2,"retired identity lost");
            gpu.sample(3600);stats(gpu,0,0);
        }
        try(var gpu=new PackageObserverGpu(1,PackageObserverGpuValidation::source)) {
            gpu.apply(baselines(1,false),1,1,0);gpu.sample(3600);stats(gpu,1,0);near(read(gpu.bodyBuffer(),64).getFloat(0),-64+state(0,false).x()/4096f,"stationary member needs no repeated pose");
        }
    }
    static void atomicFailures() {
        for(String failure:List.of("generation","epoch","stream","duplicate","slot","flags","velocity","sequence","time","reuse"))try(var gpu=new PackageObserverGpu(3,PackageObserverGpuValidation::source)) {
            gpu.apply(baselines(2,false),2,2,0);gpu.sample(0);
            if(failure.equals("reuse")){gpu.apply(delta(1,1,PackageDeltaCodec.RELEASE,state(1,false),.01f),1,2,.01f);gpu.sample(.01f);}
            var before=read(gpu.stateBuffer(),3*144);var data=patches(2);
            data.position(0);PackageObserverPatch.delta(data,0,identity(0),EPOCH,STREAM,2,new PackageDeltaCodec.Entry(serverIndex(0),1,state(2,false)),.02f);
            data.position(128);PackageObserverPatch.delta(data,1,identity(1),EPOCH,STREAM,2,new PackageDeltaCodec.Entry(serverIndex(1),1,state(3,false)),.02f);
            int p=128;
            switch(failure) {
                case "generation"->data.putLong(p+8,identity(1).generation()+0x100000000L);
                case "epoch"->data.putLong(p+16,EPOCH+0x100000000L);
                case "stream"->data.putLong(p+24,STREAM+0x100000000L);
                case "duplicate"->{for(int i=0;i<128;i+=4)data.putInt(p+i,data.getInt(i));}
                case "slot"->data.putInt(p+32,2);
                case "flags"->{data.putInt(p+40,8);data.putInt(p+60,4);}
                case "velocity"->{data.putInt(p+40,2);data.putInt(p+64,32769);}
                case "sequence"->data.putLong(p+112,0);
                case "time"->data.putFloat(p+92,.04f);
                case "reuse"->PackageObserverPatch.baseline(data,1,serverIndex(1),identity(1),EPOCH,STREAM,2,state(1,false),-64,128,64,1,.75f,.02f);
            }
            data.position(0);gpu.apply(data,2,2,.02f);
            check(read(gpu.controlBuffer(),16).getInt(0)!=0,"invalid batch accepted: "+failure);
            check(before.equals(read(gpu.stateBuffer(),3*144)),"partially applied batch: "+failure);
            gpu.apply(delta(0,3,1,state(4,false),.03f),1,2,.03f);check(before.equals(read(gpu.stateBuffer(),3*144)),"sticky failure erased: "+failure);
        }
    }
    static void sequenceAndRebuild() {
        try(var gpu=new PackageObserverGpu(1,PackageObserverGpuValidation::source)) {
            var initial=baselines(1,false);initial.putLong(112,0xffffffffL);gpu.apply(initial,1,1,0);gpu.sample(0);
            gpu.apply(delta(0,0x100000000L,8,new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,2),.01f),1,1,.01f);gpu.sample(.01f);stats(gpu,1,0);
            check(read(gpu.stateBuffer(),144).getLong(32)==0x100000000L,"sequence low-word rollover");
            boolean failed=false;try{gpu.rebuild(name->name.endsWith("observer_retire.comp")?"deliberately invalid GLSL":source(name));}catch(RuntimeException expected){failed=true;}
            check(failed,"bad shader reload accepted");gpu.sample(.02f);stats(gpu,1,0); // old complete bundle is retained
            gpu.rebuild(PackageObserverGpuValidation::source);gpu.sample(.03f);stats(gpu,1,0);
            check(read(gpu.stateBuffer(),144).getLong(32)==0x100000000L,"rebuild changed identities/sequence");
        }
    }
    static void namespaceRetirement() {
        int n=65;
        try(var gpu=new PackageObserverGpu(n+1,PackageObserverGpuValidation::source)) {
            var data=baselines(n,false);
            for(int i=0;i<n;i++) {
                // Neighbours share low words; high epoch/stream words must also match.
                if(i%3==1)data.putLong(i*128+16,EPOCH+0x100000000L);
                if(i%3==2)data.putLong(i*128+24,STREAM+0x100000000L);
            }
            var sentinel=BufferUtils.createByteBuffer(144);for(int i=0;i<144;i++)sentinel.put(i,(byte)0x5a);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.stateBuffer());
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,n*144L,sentinel);
            gpu.apply(data,n,n,0);gpu.sample(0);var before=read(gpu.stateBuffer(),(n+1)*144);
            gpu.retireNamespace(EPOCH,STREAM,.01f);gpu.sample(.01f);stats(gpu,43,0);
            var after=read(gpu.stateBuffer(),(n+1)*144);var bodies=read(gpu.bodyBuffer(),n*64);
            for(int i=0;i<n;i++) {
                check(after.getInt(i*144+40)==(i%3==0?2:1),"retire matched partial namespace");
                check(bodies.getFloat(i*64+60)==(i%3==0?-3:0),"retire body lifecycle");
                for(int j=0;j<144;j++)if(j<40 || j>=44)check(before.get(i*144+j)==after.get(i*144+j),"retire changed identity/pose");
            }
            for(int j=0;j<144;j++)check(after.get(n*144+j)==(byte)0x5a,"retire tail sentinel");
            gpu.retireNamespace(EPOCH,STREAM,.02f);gpu.sample(.02f);stats(gpu,43,0);
            check(after.equals(read(gpu.stateBuffer(),(n+1)*144)),"duplicate close mutated states");
        }
    }
    static void referenceMerges() {
        int n=129;var expected=new PackageDeltaCodec.Quantized[n];var random=new Random(918273);
        try(var gpu=new PackageObserverGpu(n,PackageObserverGpuValidation::source)) {
            gpu.apply(baselines(n,true),n,n,0);for(int i=0;i<n;i++)expected[i]=state(i,true);
            var data=patches(n);
            for(int step=1;step<=50;step++) {
                float receipt=step*.025f;
                for(int i=0;i<n;i++) {
                    int mask=1+random.nextInt(15);
                    var q=new PackageDeltaCodec.Quantized(random.nextInt(262144),random.nextInt(262144),random.nextInt(262144),
                            (short)(random.nextInt(4097)-2048),(short)(random.nextInt(4097)-2048),(short)(random.nextInt(4097)-2048),
                            (short)random.nextInt(65536),random.nextInt(4));
                    var change=new PackageDeltaCodec.Entry(serverIndex(i),mask,q);expected[i]=PackageDeltaCodec.merge(expected[i],change);
                    data.position(i*128);PackageObserverPatch.delta(data,i,identity(i),EPOCH,STREAM,step,change,receipt);
                }
                data.position(0);
                if((step&1)==0)gpu.apply(data,n,n,receipt);
                else {
                    var packed=BufferUtils.createByteBuffer(n*PackageObserverGpu.COMPACT_BYTES);
                    for(int i=0;i<n;i++) {
                        int p=i*128;var value=new PackageDeltaCodec.Quantized(data.getInt(p+48),data.getInt(p+52),data.getInt(p+56),
                                (short)data.getInt(p+64),(short)data.getInt(p+68),(short)data.getInt(p+72),(short)data.getInt(p+76),data.getInt(p+60));
                        packed.position(i*PackageObserverGpu.COMPACT_BYTES);PackageObserverPatch.compactDelta(packed,i,EPOCH,STREAM,step,new PackageDeltaCodec.Entry(serverIndex(i),data.getInt(p+40),value),receipt);
                    }
                    packed.position(0);gpu.applyCompact(packed,n,n,receipt);
                }
                gpu.sample(receipt+.01f);stats(gpu,n,0);
                var states=read(gpu.stateBuffer(),n*144);
                for(int i=0;i<n;i++) {
                    var q=expected[i];int p=i*144;
                    check(states.getInt(p+48)==q.x()&&states.getInt(p+52)==q.y()&&states.getInt(p+56)==q.z()&&states.getInt(p+60)==q.flags(),"CPU/GPU exact position/flags merge");
                    check(states.getInt(p+64)==q.vx()&&states.getInt(p+68)==q.vy()&&states.getInt(p+72)==q.vz()&&states.getInt(p+76)==q.yaw(),"CPU/GPU exact velocity/yaw merge");
                }
            }
        }
    }
    static void delayedUploads() {
        var consume=new java.util.concurrent.atomic.AtomicBoolean(false);
        try(var gpu=new PackageObserverGpu(2,PackageObserverGpuValidation::source,consume::get)) {
            check(gpu.tryApply(baselines(1,false),1,1,0),"first upload slot");
            for(int sequence=1;sequence<=3;sequence++)check(gpu.tryApply(delta(0,sequence,1,state(sequence,false),sequence*.01f),1,1,sequence*.01f),"independent upload slot");
            GL11.glFinish();gpu.sample(.03f);var before=read(gpu.stateBuffer(),2*144);long publication=gpu.publicationVersion();
            var release=delta(0,4,PackageDeltaCodec.RELEASE,state(0,false),.04f);
            var compactRelease=BufferUtils.createByteBuffer(PackageObserverGpu.COMPACT_BYTES);
            PackageObserverPatch.compactDelta(compactRelease,0,EPOCH,STREAM,4,new PackageDeltaCodec.Entry(serverIndex(0),PackageDeltaCodec.RELEASE,state(0,false)),.04f);
            check(!gpu.tryApply(release,1,2,.04f),"borrowed slot overwritten");
            check(gpu.count()==1&&gpu.publicationVersion()==publication,"full upload ring advanced count/publication");
            check(before.equals(read(gpu.stateBuffer(),2*144)),"refused upload changed state");
            for(int frame=0;frame<8;frame++)check(!(frame%2==0?gpu.tryApply(release,1,1,.04f):gpu.tryApplyCompact(compactRelease,1,1,.04f)),"delayed upload silently lost retention");
            consume.set(true);GL11.glFinish();check(gpu.tryApplyCompact(compactRelease,1,1,.04f),"completed upload slot not reusable");
            gpu.sample(.05f);stats(gpu,0,0);check(read(gpu.stateBuffer(),144).getInt(40)==2,"retained release event not applied");
        }
    }
    static void mixedImport() {
        try(var mixed=new PackageMixedPhysicsGpu(2,2,2,2,PackageObserverGpuValidation::source);
            var reference=new PackageMixedPhysicsGpu(2,2,2,PackageObserverGpuValidation::source);
            var bridge=new PackagePoolGpu(2,1,PackageObserverGpuValidation::source)) {
            var free=BufferUtils.createByteBuffer(64);free.putFloat(0,-64+state(0,true).x()/4096f).putFloat(4,133.375f).putFloat(8,64+state(0,true).z()/4096f)
                    .putFloat(12,1).putFloat(32,.5f).putFloat(36,.375f).putFloat(40,.5f);
            mixed.uploadFree(free,1);reference.uploadFree(free,1);
            mixed.observers().apply(baselines(1,true),1,1,0);
            boolean refused=false;try{mixed.publish();}catch(IllegalStateException pending){refused=true;}check(refused,"mixed accepted un-sampled observer state");
            mixed.sampleObservers(.025f);mixed.stepFree(.05f);mixed.publish();reference.stepFree(.05f);reference.publish();
            check(read(mixed.bodyBuffer(),64).equals(read(reference.bodyBuffer(),64)),"observer participated in authority contact grid");
            check(mixed.observerBodyIndex(0)==4&&mixed.bodyCount()==5,"observer changed existing domain offsets");
            var ranges=BufferUtils.createByteBuffer(16);ranges.putInt(4,3).putFloat(8,1);bridge.uploadMeshes(BufferUtils.createByteBuffer(3*48),ranges,1);
            var metadata=BufferUtils.createByteBuffer(160);
            metadata.putLong(0,999).putLong(8,1).putInt(16,0).putInt(20,0).putInt(24,-1);
            metadata.putLong(80,identity(0).id()).putLong(88,identity(0).generation()).putInt(96,4).putInt(100,0).putInt(104,-1);
            bridge.uploadMetadata(metadata,2);mixed.source(bridge,0,0,0);
            int particles=buffer(BufferUtils.createByteBuffer(128)),counter=buffer(BufferUtils.createByteBuffer(16));
            try {
                bridge.stage(particles,counter,7,new float[24],0,0,0);bridge.commit();
                check(read(counter,16).getInt(0)==2,"observer used extra generic particle slots");
                var admission=read(bridge.admissionBuffer(),64);int slot=admission.getInt(48)-1;
                check(slot>=0&&slot<2&&slot!=admission.getInt(16)-1,"observer generic slot not unique");
                var pool=read(particles,128);var body=read(mixed.bodyBuffer(),5*64);
                near(pool.getFloat(slot*64),body.getFloat(4*64),"observer pose import");near(pool.getFloat(slot*64+4),body.getFloat(4*64+4)-.375f,"observer feet import");
                for(int j=0;j<3;j++)near(pool.getFloat(slot*64+j*4),pool.getFloat(slot*64+16+j*4),"double interpolation in particle layout");
                long version=mixed.publicationVersion();mixed.sampleObservers(.05f);mixed.publish();check(mixed.publicationVersion()>version,"sample-only motion not published");
                check(read(mixed.bodyBuffer(),5*64).getFloat(4*64)>body.getFloat(4*64),"observer prediction frozen in shared source");
            }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
        }
    }
    static void asynchronousFeedback() {
        try(var gpu=new PackageObserverGpu(2,PackageObserverGpuValidation::source);
            var feedback=new PackageObserverFeedbackGpu(EPOCH)) {
            gpu.apply(baselines(1,true),1,1,0);gpu.sample(0);
            check(feedback.capture(gpu),"first observer feedback");
            check(!feedback.capture(gpu)&&feedback.pending()==1,"duplicate publication captured");
            for(int i=1;i<4;i++){gpu.sample(i*.01f);check(feedback.capture(gpu),"independent observer control staging");}
            var addition=patches(1);PackageObserverPatch.baseline(addition,1,serverIndex(1),identity(1),EPOCH,STREAM,0,state(1,false),-64,128,64,1,.75f,.04f);
            gpu.apply(addition,1,2,.04f);gpu.sample(.04f);
            for(int frame=0;frame<8;frame++)check(!feedback.capture(gpu),"full control ring overwritten");
            check(feedback.pending()==4&&feedback.latest()==null,"unconsumed feedback published");
            check(feedback.overdue(System.nanoTime()+200_000_000L),"delayed feedback not overdue");
            check(feedback.readbackBytes()==64&&feedback.skipped()==8,"feedback counters include refused snapshots");
            var received=new ArrayList<PackageObserverFeedbackGpu.Status>();GL11.glFinish();
            check(feedback.poll(received::add)==4,"delayed control copies not consumed");
            for(int i=0;i<4;i++) {
                var result=received.get(i);check(result.epoch()==EPOCH&&result.publication()==i+1,"feedback publication identity");
                check(result.slots()==1&&result.active()==1&&result.stale()==0&&!result.needsFallback(),"old feedback used new slot count");
            }
            check(!feedback.overdue(System.nanoTime()+200_000_000L),"empty feedback remains overdue");
            check(feedback.capture(gpu),"refused publication was acknowledged");GL11.glFinish();feedback.poll(received::add);
            check(feedback.latest().slots()==2&&feedback.latest().active()==2,"retained control publication not retried");
            try(var other=new PackageObserverGpu(1,PackageObserverGpuValidation::source)) {
                other.apply(baselines(1,false),1,1,0);other.sample(0);boolean refused=false;
                try{feedback.capture(other);}catch(IllegalArgumentException changed){refused=true;}
                check(refused,"different source reused feedback namespace");
            }
            gpu.sample(.151f);check(feedback.capture(gpu),"stale publication not captured");GL11.glFinish();feedback.poll(received::add);
            check(feedback.latest().stale()==1&&feedback.latest().active()==1&&feedback.latest().needsFallback(),"GPU prediction expiry missing from feedback");
            var bad=delta(0,1,1,state(0,false),.16f);bad.putLong(8,identity(0).generation()+1);
            gpu.apply(bad,1,2,.16f);gpu.sample(.16f);check(feedback.capture(gpu),"invalid batch not captured");GL11.glFinish();feedback.poll(received::add);
            check(feedback.latest().rejected(),"GPU rejection missing from feedback");
            gpu.sample(.17f);check(feedback.capture(gpu),"old namespace copy");
            feedback.invalidate(EPOCH+1);check(feedback.pending()==0&&feedback.latest()==null,"old namespace feedback retained");
            GL11.glFinish();check(feedback.poll(received::add)==0,"late invalidated copy dispatched");
            try(var replacement=new PackageObserverGpu(1,PackageObserverGpuValidation::source)) {
                replacement.apply(baselines(1,false),1,1,0);replacement.sample(0);
                check(feedback.capture(replacement),"new namespace reused old publication token");GL11.glFinish();feedback.poll(received::add);
                check(feedback.latest().epoch()==EPOCH+1&&feedback.latest().publication()==1&&!feedback.latest().needsFallback(),"new namespace mixed old rejection");
            }
            check(feedback.completed()==8&&feedback.readbackBytes()==144,"feedback lifetime accounting");
        }
    }
    static void compactParity() {
        for(int n:new int[]{1,63,64,65,131072})try(var full=new PackageObserverGpu(n,PackageObserverGpuValidation::source);
            var compact=new PackageObserverGpu(n,PackageObserverGpuValidation::source)) {
            var initial=baselines(n,true);full.apply(initial,n,n,0);compact.apply(initial,n,n,0);
            var expanded=patches(n);var packed=BufferUtils.createByteBuffer(n*PackageObserverGpu.COMPACT_BYTES);
            for(int step=1;step<=16;step++) {
                int mask=step==16?PackageDeltaCodec.RELEASE:step;long sequence=(1L<<40)+step;float receipt=step*.01f;
                for(int i=0;i<n;i++) {
                    var value=new PackageDeltaCodec.Quantized((i%60)*4096+step,20480,820,(short)-32768,(short)0,(short)0,(short)(step%2==0?-32768:32767),step%4);
                    var change=new PackageDeltaCodec.Entry(serverIndex(i),mask,value);
                    expanded.position(i*128);PackageObserverPatch.delta(expanded,i,identity(i),EPOCH,STREAM,sequence,change,receipt);
                    packed.position(i*PackageObserverGpu.COMPACT_BYTES);PackageObserverPatch.compactDelta(packed,i,EPOCH,STREAM,sequence,change,receipt);
                }
                expanded.position(0);packed.position(0);full.apply(expanded,n,n,receipt);compact.applyCompact(packed,n,n,receipt);
                full.sample(receipt+.005f);compact.sample(receipt+.005f);stats(full,step==16?0:n,0);stats(compact,step==16?0:n,0);
                check(read(full.stateBuffer(),n*144).equals(read(compact.stateBuffer(),n*144)),"compact/full exact state mismatch");
                check(read(full.bodyBuffer(),n*64).equals(read(compact.bodyBuffer(),n*64)),"compact/full pose mismatch");
                check(read(full.historyBuffer(),n*32).equals(read(compact.historyBuffer(),n*32)),"compact/full interpolation mismatch");
            }
        }
        for(int bad=0;bad<11;bad++)try(var gpu=new PackageObserverGpu(3,PackageObserverGpuValidation::source)) {
            gpu.apply(baselines(2,false),2,3,0);var before=read(gpu.stateBuffer(),3*144);var data=BufferUtils.createByteBuffer(2*PackageObserverGpu.COMPACT_BYTES);
            for(int i=0;i<2;i++){data.position(i*PackageObserverGpu.COMPACT_BYTES);PackageObserverPatch.compactDelta(data,i,EPOCH,STREAM,1,new PackageDeltaCodec.Entry(serverIndex(i),15,state(i,true)),.01f);}
            int p=PackageObserverGpu.COMPACT_BYTES;
            switch(bad) {
                case 0->data.putLong(p,EPOCH+(1L<<32));case 1->data.putLong(p+8,STREAM+(1L<<32));
                case 2->data.putInt(p+16,3);case 3->data.putInt(p+20,serverIndex(1)+1);
                case 4->data.putInt(p+24,32);case 5->data.putInt(p+24,0);case 6->data.putFloat(p+28,Float.NaN);
                case 7->data.putLong(p+64,0);case 8->data.putInt(p+44,4);case 9->data.putInt(p+16,0).putInt(p+20,serverIndex(0));
                case 10->data.putInt(p+16,2);
            }
            data.position(0);gpu.applyCompact(data,2,3,.01f);gpu.sample(.01f);
            check(read(gpu.controlBuffer(),16).getInt(0)!=0,"invalid compact namespace/field accepted "+bad);
            check(before.equals(read(gpu.stateBuffer(),3*144)),"invalid compact batch published valid prefix "+bad);
        }
    }
    static final ResourceLocation DIM=ResourceLocation.parse("minecraft:overworld"),MODEL=ResourceLocation.parse("create:cardboard_package_10x8");
    static final PackageRegion REGION=new PackageRegion(-1,2,1);
    static PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member(int i,long tick,boolean moving) {
        return new PackageObserverFeed.Member<>(serverIndex(i),identity(i),1,1,state(i,moving),
                new ClientboundPackageObserverPacket.Visual(i,new UUID(123,i),MODEL,1,.75f),tick);
    }
    static ClientboundPackageObserverPacket packet(long sequence,int flags,long tick,List<PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual>> members,
                                                   List<PackageDeltaCodec.Entry> changes,long... times) {
        var result=new ClientboundPackageObserverPacket(DIM,REGION,EPOCH,1,STREAM,sequence,flags,members,changes,tick,new PackageObserverTimes(times));
        var bytes=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {ClientboundPackageObserverPacket.STREAM_CODEC.encode(bytes,result);result=ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes);
            check(bytes.readableBytes()==0,"observer wire consumed");return result;
        }finally{bytes.release();}
    }
    static final class ObserverLifecycle implements PackageObserverGpuController.Lifecycle {
        int uploaded,retired,namespaces,failed;int available=131072;
        public int availableSlots(){return available;}
        public boolean supports(PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member){return true;}
        public void uploaded(PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member,int local){uploaded++;}
        public void retired(PackageLease.Identity identity,int local){retired++;}
        public void namespaceRetired(PackageRegion region,long epoch,long stream){namespaces++;}
        public void fallback(String reason){failed++;}
    }
    static void recycledObservers(){
        try(var gpu=new PackageObserverGpu(4,PackageObserverGpuValidation::source)){
            var events=new ObserverLifecycle();try(var controller=new PackageObserverGpuController(DIM,Set.of(REGION),gpu,EPOCH,0,0,0,events)){
                for(int life=0;life<64;life++){
                    long tick=100+life*2,now=life*100_000_000L;
                    var baseline=packet(life*2L,life==0?3:2,tick,List.of(member(life,tick,false)),List.of());
                    check(controller.enqueue(baseline,now,0),"observer reuse baseline queued");GL11.glFinish();controller.prepare(now,8);
                    check(controller.healthy()&&gpu.count()==1,"observer reuse grew its body namespace");
                    var stored=read(gpu.stateBuffer(),144);check(stored.getLong(0)==identity(life).id()&&stored.getLong(8)==identity(life).generation(),"observer reuse retained an old identity");
                    if(life>0){controller.enqueue(packet(life*2L-1,2,tick-1,List.of(),List.of(new PackageDeltaCodec.Entry(serverIndex(life-1),16,state(life-1,false))),tick-1),now,0);controller.prepare(now,8);check(controller.healthy(),"late observer retirement affected new lifetime");}
                    controller.enqueue(packet(life*2L+1,2,tick+1,List.of(),List.of(new PackageDeltaCodec.Entry(serverIndex(life),16,state(life,false))),tick+1),now+50_000_000L,0);
                    controller.prepare(now+50_000_000L,8);GL11.glFinish();stats(gpu,0,0);controller.recycle(0);
                    check(controller.availableLocalSlots()==4,"observer retired slot was not reclaimable");
                }
                check(events.uploaded==64&&events.retired==64&&events.failed==0,"observer reuse duplicated or lost lifecycle");
            }
        }
    }
    static void controller() {
        try(var gpu=new PackageObserverGpu(4,PackageObserverGpuValidation::source)) {
            var events=new ObserverLifecycle();
            try(var controller=new PackageObserverGpuController(DIM,Set.of(REGION),gpu,EPOCH,0,0,0,events)) {
                check(controller.enqueue(packet(0,3,100,List.of(member(0,100,true)),List.of()),0,0),"observer wire baseline queued");
                check(controller.prepare(0,8)==1&&controller.healthy(),"observer baseline controller failed");controller.committed(1);
                var output=read(gpu.bodyBuffer(),64);near(output.getFloat(0),-64+state(0,true).x()/4096f,"server-origin import");
                var stop=new PackageDeltaCodec.Entry(serverIndex(0),2,state(0,false));
                check(controller.enqueue(packet(1,2,101,List.of(),List.of(stop),101),50_000_000,0),"observer stop queued");
                GL11.glFinish();check(controller.prepare(50_000_000,8)==1&&controller.healthy(),"observer stop controller failed");
                controller.committed(2);check(events.uploaded==1&&events.retired==0,"pose delta changed lifecycle");
                var finalState=read(gpu.stateBuffer(),144);check(finalState.getInt(64)==0&&finalState.getInt(48)==state(0,true).x(),"controller merged/overwrote pose on CPU");
                // Full introduction and a same-packet delta need different GPU mutation sequences.
                check(controller.enqueue(packet(2,2,101,List.of(member(1,101,false)),List.of(new PackageDeltaCodec.Entry(serverIndex(1),8,state(1,false))),101),51_000_000,0),"same packet introduction queued");
                GL11.glFinish();check(controller.prepare(51_000_000,8)==1&&controller.healthy(),"same packet introduction/delta failed");stats(gpu,2,0);
                check(events.uploaded==2,"resource introduction duplicated");
                var close=packet(0,4,102,List.of(),List.of());controller.enqueue(close,100_000_000,0);
                GL11.glFinish();check(controller.prepare(100_000_000,8)==1&&controller.healthy(),"exact namespace close failed");stats(gpu,0,0);
                check(events.namespaces==1,"namespace retirement missing");
                controller.enqueue(close,101_000_000,0);check(controller.prepare(101_000_000,8)==1&&events.namespaces==1,"late close retired twice");
                check(read(gpu.stateBuffer(),288).getInt(184)==2,"namespace close did not retire every body");
            }
        }
        var consume=new java.util.concurrent.atomic.AtomicBoolean(false);
        try(var gpu=new PackageObserverGpu(2,PackageObserverGpuValidation::source,consume::get)) {
            var events=new ObserverLifecycle();
            try(var controller=new PackageObserverGpuController(DIM,Set.of(REGION),gpu,EPOCH,0,0,0,events)) {
                controller.enqueue(packet(0,3,100,List.of(member(0,100,true)),List.of()),0,0);controller.prepare(0,8);
                for(int i=1;i<=3;i++){controller.enqueue(packet(i,2,100,List.of(),List.of(new PackageDeltaCodec.Entry(serverIndex(0),1,state(i,true))),100),i*10_000_000L,0);check(controller.prepare(i*10_000_000L,8)==1,"controller upload slot");}
                controller.enqueue(packet(4,2,100,List.of(),List.of(new PackageDeltaCodec.Entry(serverIndex(0),16,state(0,false))),100),40_000_000,0);
                for(int frame=0;frame<8;frame++)check(controller.prepare(40_000_000+frame*5_000_000L,8)==0&&controller.queued()==1&&events.retired==0,"controller dropped pending release");
                GL11.glFinish();consume.set(true);check(controller.prepare(80_000_000,8)==1&&events.retired==1&&controller.healthy(),"controller release retry failed");stats(gpu,0,0);
            }
        }
        try(var gpu=new PackageObserverGpu(2,PackageObserverGpuValidation::source)) {
            var events=new ObserverLifecycle();
            try(var controller=new PackageObserverGpuController(DIM,Set.of(REGION),gpu,EPOCH,0,0,0,events)) {
                controller.enqueue(packet(0,3,100,List.of(member(0,100,false)),List.of()),0,0);controller.prepare(0,8);
                var before=read(gpu.stateBuffer(),144);
                controller.enqueue(packet(2,2,101,List.of(),List.of(new PackageDeltaCodec.Entry(serverIndex(0),1,state(1,false))),101),50_000_000,0);
                check(controller.prepare(50_000_000,8)==0&&!controller.healthy()&&events.failed==1,"controller sequence gap not rejected");
                check(before.equals(read(gpu.stateBuffer(),144)),"gap mutated previous GPU state");controller.failedFrame();check(events.failed==1,"fallback callback repeated");
            }
        }
    }
    static double percentile(double[] values,double p){var copy=values.clone();Arrays.sort(copy);return copy[(int)Math.ceil(copy.length*p)-1];}
    static void prepareMotion(ByteBuffer data,int n,long sequence,float receipt) {
        int displacement=(int)(sequence%64)*102;
        for(int i=0;i<n;i++){int p=i*128;data.putInt(p+40,1).putInt(p+48,(i%60)*4096+410+displacement)
                .putFloat(p+92,receipt).putLong(p+112,sequence);}
    }
    static void prepareCompactMotion(ByteBuffer data,int n,long sequence,float receipt) {
        int displacement=(int)(sequence%64)*102;
        for(int i=0;i<n;i++){int p=i*PackageObserverGpu.COMPACT_BYTES;data.putInt(p+24,1).putInt(p+32,(i%60)*4096+410+displacement)
                .putFloat(p+28,receipt).putLong(p+64,sequence);}
    }
    static void benchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,scenario,repeat,samples,prepare_p50_ms,prepare_p95_ms,submit_p50_ms,submit_p95_ms,gpu_p50_ms,gpu_p95_ms,upload_bytes_per_sample,readback_bytes_per_sample,active,stale");
        samples.add("count,scenario,repeat,sample,prepare_ms,submit_ms,gpu_ms");
        for(int n:new int[]{10000,65536,131072})for(String scenario:List.of("sample_publish_motion","delta_sample_publish_motion","compact_delta_sample_publish_motion"))for(int repeat=1;repeat<=3;repeat++)
            try(var mixed=new PackageMixedPhysicsGpu(0,0,n,2,PackageObserverGpuValidation::source)) {
                var gpu=mixed.observers();var data=baselines(n,true);gpu.apply(data,n,n,0);mixed.sampleObservers(0);mixed.publish();
                boolean compact=scenario.startsWith("compact_");
                if(compact) {
                    data=BufferUtils.createByteBuffer(n*PackageObserverGpu.COMPACT_BYTES);
                    for(int i=0;i<n;i++){data.position(i*PackageObserverGpu.COMPACT_BYTES);PackageObserverPatch.compactDelta(data,i,EPOCH,STREAM,1,new PackageDeltaCodec.Entry(serverIndex(i),1,state(i,true)),.05f);}
                    data.position(0);
                }
                int query=GL15.glGenQueries();long sequence=0;
                try {
                    long until=System.nanoTime()+500_000_000L;int warm=0;
                    do{float receipt=++sequence*.05f;
                        if(compact){prepareCompactMotion(data,n,sequence,receipt);gpu.applyCompact(data,n,n,receipt);}
                        else{prepareMotion(data,n,sequence,receipt);gpu.apply(data,n,n,receipt);}
                        mixed.sampleObservers(receipt+.025f);mixed.publish();GL11.glFinish();warm++;}
                    while(warm<30 || System.nanoTime()<until);
                    double[] prepare=new double[120],submit=new double[120],timing=new double[120];
                    for(int i=0;i<120;i++) {
                        float receipt=++sequence*.05f;long began=System.nanoTime();
                        if(compact)prepareCompactMotion(data,n,sequence,receipt);else prepareMotion(data,n,sequence,receipt);
                        prepare[i]=(System.nanoTime()-began)/1e6;
                        if(scenario.equals("sample_publish_motion")){gpu.apply(data,n,n,receipt);GL11.glFinish();}
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);began=System.nanoTime();
                        if(compact)gpu.applyCompact(data,n,n,receipt);else if(scenario.equals("delta_sample_publish_motion"))gpu.apply(data,n,n,receipt);
                        mixed.sampleObservers(receipt+.025f);mixed.publish();submit[i]=(System.nanoTime()-began)/1e6;
                        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);timing[i]=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;
                        samples.add(n+","+scenario+","+repeat+","+i+","+prepare[i]+","+submit[i]+","+timing[i]);
                    }
                    stats(gpu,n,0);var output=read(mixed.bodyBuffer(),n*64);
                    check(Float.isFinite(output.getFloat(0))&&Float.isFinite(output.getFloat((n-1)*64)),"benchmark nonfinite output");
                    String row=n+","+scenario+","+repeat+",120,"+percentile(prepare,.5)+","+percentile(prepare,.95)+","+percentile(submit,.5)+","+percentile(submit,.95)
                            +","+percentile(timing,.5)+","+percentile(timing,.95)+","+(compact?(long)n*PackageObserverGpu.COMPACT_BYTES:scenario.equals("delta_sample_publish_motion")?(long)n*128:0)+",0,"+n+",0";
                    rows.add(row);System.out.println(row);
                }finally{GL15.glDeleteQueries(query);}
            }
        Files.write(Path.of("build/package-observer-gpu.csv"),rows);Files.write(Path.of("build/package-observer-gpu-samples.csv"),samples);
    }
    public static void main(String[] args) throws Exception {
        var callback=GLFWErrorCallback.createPrint(System.err);callback.set();check(GLFW.glfwInit(),"GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Package observer GPU validation",0,0);check(window!=0,"context");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            boundaries();mergeAndCorrection();atomicFailures();sequenceAndRebuild();namespaceRetirement();referenceMerges();delayedUploads();mixedImport();asynchronousFeedback();compactParity();controller();recycledObservers();
            if(Arrays.asList(args).contains("--benchmark"))benchmark();
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"observer GL error");System.out.println("Package observer GPU: "+checks+" assertions passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
