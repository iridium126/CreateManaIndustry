import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePhysicsGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageReadbackRing;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePoolGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaChannel;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaJournal;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageDeltaCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;

/** Driver validation of package kernels; does not enable gameplay takeover. */
public class PackageGpuValidation {
    static int checks;
    static String source(String name) {
        return ParticleShaderSource.loadParticle(name,path->{
            // Match ResourceManager's namespace-relative lookup in the processed runtime resources.
            try(var stream=PackagePoolGpu.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)) {
                if(stream==null)throw new IllegalArgumentException("Missing runtime resource: "+path);
                return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        });
    }
    static void sourceContract() {
        boolean rejected=false;
        try {source("assets/createmanaindustry/shaders/particles/packages/pool_select.comp");}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"filesystem shader paths must not reach ResourceManager");
        check(!source("packages/draw_count.comp").contains("#pragma cmi_include"),"runtime includes were not resolved");
        Map<String,String> cycle=Map.of("shaders/particles/cycle.glsl","#pragma cmi_include cycle.glsl");
        rejected=false;
        try {ParticleShaderSource.loadParticle("cycle.glsl",cycle::get);}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"recursive shader includes must be bounded");
    }
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static ByteBuffer bodies(int n){return BufferUtils.createByteBuffer(n*64);}
    static void body(ByteBuffer b,int i,float x,float y,float z,float mass){
        int p=i*64;b.putFloat(p,x).putFloat(p+4,y).putFloat(p+8,z).putFloat(p+12,mass);
        b.putFloat(p+32,.5f).putFloat(p+36,.5f).putFloat(p+40,.5f);
    }
    static ByteBuffer read(PackagePhysicsGpu gpu){
        ByteBuffer b=bodies(gpu.count());GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.stateBuffer());
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,b);return b;
    }
    static void boundaries(){
        for(int count:new int[]{0,1,63,64,65,1023,1024,1025}) {
            try(var gpu=new PackagePhysicsGpu(Math.max(1,count),2,PackageGpuValidation::source)) {
                ByteBuffer b=bodies(count);for(int i=0;i<count;i++)body(b,i,i*3,10,0,1);
                gpu.upload(b,count);gpu.step(.05f);
                if(count==0)continue;
                ByteBuffer result=read(gpu);
                for(int i=0;i<count;i++) {
                    check(Math.abs(result.getFloat(i*64+4)-9.9216f)<1e-5,"gravity/tail "+i);
                    check(result.getFloat(i*64)==i*3,"unrelated bodies must not interact");
                }
            }
        }
    }
    static void contact(){
        try(var gpu=new PackagePhysicsGpu(3,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(3);body(b,0,0,.0f,0,0);body(b,1,0,.9f,0,1);body(b,2,20,2,0,1);
            gpu.upload(b,3);
            for(int i=0;i<200;i++)gpu.step(.05f);
            ByteBuffer r=read(gpu);
            check(r.getFloat(4)==0,"static collider moved");
            check(Math.abs(r.getFloat(68)-1)<.02,"body failed to settle on support");
            check(r.getFloat(64+28)==1,"grounded flag missing");
            for(int i=0;i<3*16;i++)check(Float.isFinite(r.getFloat(i*4)),"non-finite contact result");
            // Coincident boxes have a deterministic antisymmetric tie breaker.
            body(b,0,0,10,0,1);body(b,1,0,10,0,1);body(b,2,20,10,0,0);
            gpu.upload(b,3);gpu.step(.05f);r=read(gpu);
            check(Math.abs(r.getFloat(4)-r.getFloat(68))>.99f,"coincident pair not separated");
        }
    }
    static void chain(){
        try(var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(65), c=bodies(65);
            for(int i=0;i<65;i++) {
                body(b,i,0,-9f/16f,.875f,1);
                int p=i*64;c.putFloat(p+12,.875f).putFloat(p+36,90).putFloat(p+40,1);
            }
            gpu.upload(b,65);gpu.uploadChains(c);
            for(int t=0;t<80;t++)gpu.stepChains(.05f);
            ByteBuffer r=read(gpu);
            for(int i=0;i<65;i++) {
                check(Math.abs(r.getFloat(i*64))<1.5,"chain pendulum escaped tether");
                check(Float.isFinite(r.getFloat(i*64+44)),"chain yaw not finite");
            }
        }
    }
    static void chainReference() {
        int n=16;Random random=new Random(124);
        float[][] pos=new float[n][3],vel=new float[n][3],start=new float[n][3];
        float[] angle=new float[n],speed=new float[n],yaw=new float[n];
        try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(n),c=bodies(n);
            for(int i=0;i<n;i++) {
                angle[i]=random.nextFloat()*360;speed[i]=i%2==0?90:-45;yaw[i]=angle[i]-120;
                for(int j=0;j<3;j++){pos[i][j]=random.nextFloat()*8-4;vel[i][j]=random.nextFloat()*.2f;start[i][j]=random.nextFloat()*4;}
                int p=i*64;body(b,i,pos[i][0],pos[i][1],pos[i][2],1);b.putFloat(p+44,yaw[i]);
                for(int j=0;j<3;j++){b.putFloat(p+16+j*4,vel[i][j]);c.putFloat(p+j*4,start[i][j]);}
                c.putFloat(p+12,.875f).putFloat(p+32,angle[i]).putFloat(p+36,speed[i]).putFloat(p+40,1).putFloat(p+44,i%3==0?1:0);
            }
            gpu.upload(b,n);gpu.uploadChains(c);
            for(int step=0;step<80;step++) {
                for(int i=0;i<n;i++) {
                    angle[i]=(angle[i]+speed[i]*.05f)%360;if(angle[i]<0)angle[i]+=360;
                    float[] target={start[i][0]+(float)Math.sin(Math.toRadians(angle[i]))*.875f,start[i][1],start[i][2]+(float)Math.cos(Math.toRadians(angle[i]))*.875f};
                    float dx=pos[i][0]-target[0],dy=pos[i][1]-target[1],dz=pos[i][2]-target[2],length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
                    if(length>1.5f)for(int j=0;j<3;j++)pos[i][j]=target[j]+(pos[i][j]-target[j])*(1.5f/length);
                    for(int j=0;j<3;j++){vel[i][j]=(vel[i][j]+(j==1?-.25f:0))*.75f+(target[j]-pos[i][j])*.25f;pos[i][j]+=vel[i][j];}
                    float dyaw=angle[i]+(i%3==0?180:0)-yaw[i];dyaw=(dyaw+180)%360;if(dyaw<0)dyaw+=360;dyaw-=180;
                    yaw[i]+=dyaw*.25f;
                }
                gpu.stepChains(.05f);ByteBuffer result=read(gpu);
                for(int i=0;i<n;i++) {
                    for(int j=0;j<3;j++)check(Math.abs(result.getFloat(i*64+j*4)-pos[i][j])<1e-4,"Create 20Hz chain recurrence position");
                    check(Math.abs(result.getFloat(i*64+44)-yaw[i])<1e-4,"Create 20Hz chain recurrence yaw");
                }
            }
        }
    }
    static void sweep(){
        try(var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(2);body(b,0,0,0,0,0);body(b,1,0,4,0,1);
            b.putFloat(64+20,-100);gpu.upload(b,2);gpu.step(.05f);
            ByteBuffer r=read(gpu);check(r.getFloat(68)>=.9999f,"high-speed body tunneled through static block");
            check(r.getFloat(64+20)==0,"swept collision did not remove inward velocity");
            body(b,1,0,4,0,1);b.putFloat(64+20,-100000);gpu.upload(b,2);gpu.step(.05f);
            r=read(gpu);check(r.getFloat(64+60)<0,"oversized sweep did not request fallback");
            check(r.getFloat(68)==4,"oversized sweep advanced without collision coverage");
        }
    }
    static void readbacks(){
        int source=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);
        GL15.glBufferData(GL31.GL_COPY_READ_BUFFER,16,GL15.GL_DYNAMIC_DRAW);
        ByteBuffer data=BufferUtils.createByteBuffer(16);
        try(var ring=new PackageReadbackRing(16)) {
            for(int i=0;i<4;i++) {
                data.putInt(0,i);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);
                GL15.glBufferSubData(GL31.GL_COPY_READ_BUFFER,0,data);
                check(ring.submit(source,1,i),"snapshot rejected free slot");
            }
            for(int i=0;i<8;i++)check(!ring.submit(source,1,4),"snapshot overwrote full ring");
            GL11.glFinish();List<Long> seen=new ArrayList<>();
            check(ring.poll(1,s->{seen.add(s.sequence());check(s.bytes().getInt(0)==s.sequence(),"snapshot source overwritten");})==4,"ring did not drain");
            check(seen.equals(List.of(0L,1L,2L,3L)),"snapshot order");
            check(ring.submit(source,1,4),"ring failed reuse");ring.invalidate();
            check(ring.submit(source,2,0),"new epoch sequence rejected");GL11.glFinish();
            check(ring.poll(2,s->check(s.epoch()==2,"stale epoch leaked"))==1,"reset snapshot missing");
            data.putInt(4,10).putInt(8,11);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);
            GL15.glBufferSubData(GL31.GL_COPY_READ_BUFFER,0,data);
            check(ring.submit(source,4,8,2,1),"fragment snapshot rejected");GL11.glFinish();
            for(int i=0;i<8;i++) {
                check(ring.pollAvailable(2,s->{check(s.bytes().remaining()==8,"fragment copied unused bytes");return false;})==0,"full journal silently consumed snapshot");
                check(ring.pending()==1,"completed refused snapshot was overwritten");
            }
            check(ring.poll(2,s->{check(s.bytes().getInt(0)==10 && s.bytes().getInt(4)==11,"fragment offset contents");})==1,"deferred snapshot did not resume");
            data.putInt(4,12).putInt(8,13);putBuffer(source,data);
            check(ring.submit(source,4,8,2,2),"cached scratch blocked new snapshot");GL11.glFinish();
            check(ring.poll(2,s->check(s.bytes().getInt(0)==12 && s.bytes().getInt(4)==13,"new snapshot reused old scratch"))==1,"cached slot failed to retire");
        }finally{GL15.glDeleteBuffers(source);}
    }
    static int buffer(ByteBuffer bytes) {
        int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return id;
    }
    static void deltaMeta(ByteBuffer m,int i,long generation) {
        int p=i*32;m.putLong(p,0x1234567800000001L+i).putLong(p+8,generation);
        m.putInt(p+16,i).putInt(p+20,i*2+7).putInt(p+24,1);
    }
    static PackageDeltaCodec.Quantized quantizedBody(ByteBuffer b,int i) {
        int p=i*64;return PackageDeltaCodec.quantize(new PackageLease.Pose(b.getFloat(p),
                (float)(b.getFloat(p+4)-b.getFloat(p+36)),b.getFloat(p+8),b.getFloat(p+16),b.getFloat(p+20),b.getFloat(p+24),b.getFloat(p+44)),
                0,0,0,b.getFloat(p+28)>.5?1:0);
    }
    static ByteBuffer captureRecords(PackageDeltaGpu.Capture capture) {
        ByteBuffer header=readBuffer(capture.headerBuffer(),16);int accepted=header.getInt(4);
        check(accepted>=0 && accepted<=capture.capacity(),"unclamped delta capture count");
        return readBuffer(capture.recordBuffer(),accepted*64);
    }
    static void acknowledge(PackageDeltaGpu gpu,PackageDeltaGpu.Capture capture,ByteBuffer records) {
        for(int p=0;p<records.limit();p+=2048*64) {
            var chunk=records.duplicate().order(ByteOrder.nativeOrder());chunk.position(p).limit(Math.min(records.limit(),p+2048*64));
            gpu.acknowledge(capture.stamp(),chunk);
        }
    }
    static void deltas() {
        var empty=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0);
        for(int n:new int[]{0,1,63,64,65,131072}) {
            ByteBuffer b=bodies(Math.max(1,n)),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
            for(int i=0;i<n;i++) {
                body(b,i,(i%128)*.25f+.125f,(i/128%128)*.25f+.625f,(i/16384)*3+.125f,1);
                int p=i*64;b.putFloat(p+16,i%2==0?.125f:-.5f).putFloat(p+20,(i%3-1)*.25f)
                        .putFloat(p+44,(i%721)-360+(i%4)*.0625f).putFloat(p+28,i%2);
                deltaMeta(meta,i,0x2345678900000001L);
            }
            int state=buffer(b);
            try(var gpu=new PackageDeltaGpu(Math.max(1,n),PackageGpuValidation::source)) {
                gpu.upload(meta,baseline,n);var first=gpu.capture(state,n,0,0,0,Math.max(1,n));
                ByteBuffer out=captureRecords(first);check(out.remaining()==n*64,"full-capacity dirty set missing");
                BitSet seen=new BitSet(n);
                for(int p=0;p<out.limit();p+=64) {
                    int candidate=out.getInt(p+16);check(candidate>=0 && candidate<n && !seen.get(candidate),"duplicate/invalid dirty identity");seen.set(candidate);
                    check(out.getLong(p)==0x1234567800000001L+candidate && out.getLong(p+8)==0x2345678900000001L,"truncated delta identity");
                    check(out.getInt(p+20)==candidate*2+7,"pool index used as wire identity");
                    var expected=quantizedBody(b,candidate);
                    check(out.getInt(p+24)==PackageDeltaCodec.changes(empty,expected) && out.getInt(p+28)==0,"independent field mask/release status");
                    check(out.getInt(p+32)==expected.x() && out.getInt(p+36)==expected.y() && out.getInt(p+40)==expected.z()
                            && out.getInt(p+44)==expected.flags(),"position/flags quantization parity");
                    check(out.getInt(p+48)==expected.vx() && out.getInt(p+52)==expected.vy() && out.getInt(p+56)==expected.vz()
                            && out.getInt(p+60)==expected.yaw(),"velocity/negative yaw quantization parity");
                }
                var blocked=new PackageDeltaGpu.Capture[3];
                for(int i=0;i<3;i++){blocked[i]=gpu.capture(state,n,0,0,0,Math.max(1,n));check(captureRecords(blocked[i]).remaining()==0,"readback implicitly acknowledged delta");}
                check(gpu.capture(state,n,0,0,0,Math.max(1,n))==null,"immutable capture bank overwritten");
                for(var capture:blocked)gpu.finish(capture);
                if(n>0) {
                    gpu.acknowledge(first.stamp()+100,out.duplicate().limit(Math.min(out.limit(),2048*64)));
                    // A wrong-stamp ACK must leave the pending records suppressed, rather than ACKing them.
                    var ignored=gpu.capture(state,n,0,0,0,n);check(captureRecords(ignored).remaining()==0,"wrong ACK stamp released flights");gpu.finish(ignored);
                }
                gpu.cancel(first);var retry=gpu.capture(state,n,0,0,0,Math.max(1,n));
                ByteBuffer again=captureRecords(retry);check(again.remaining()==n*64,"cancel lost dirty state");
                acknowledge(gpu,retry,again);gpu.finish(retry);
                var clean=gpu.capture(state,n,0,0,0,Math.max(1,n));check(captureRecords(clean).remaining()==0,"stationary acknowledged state was resent");gpu.finish(clean);
            }finally{GL15.glDeleteBuffers(state);}
        }
    }
    static void deltaOverflowAndIdentity() {
        int n=65;ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
        for(int i=0;i<n;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);}
        int state=buffer(b);
        try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source)) {
            gpu.upload(meta,baseline,n);var limited=gpu.capture(state,n,0,0,0,31);
            ByteBuffer header=readBuffer(limited.headerBuffer(),16);
            check(header.getInt(0)==65 && header.getInt(4)==31 && header.getInt(8)==34,"overflow counts not independently clamped");
            ByteBuffer first=captureRecords(limited);var second=gpu.capture(state,n,0,0,0,n);ByteBuffer rest=captureRecords(second);
            check(rest.remaining()==34*64,"overflowed candidates silently dropped");
            BitSet seen=new BitSet();for(var records:List.of(first,rest))for(int p=0;p<records.limit();p+=64) {
                int i=records.getInt(p+16);check(!seen.get(i),"overflow retry duplicates in-flight identity");seen.set(i);
            }
            check(seen.cardinality()==65,"overflow retry lost identities");
            acknowledge(gpu,limited,first);acknowledge(gpu,second,rest);gpu.finish(limited);gpu.finish(second);
            // Only velocity changes, even if the feet position is identical.
            b.putFloat(16,.25f);putBuffer(state,b);var changed=gpu.capture(state,n,0,0,0,n);ByteBuffer old=captureRecords(changed);
            check(old.remaining()==64 && old.getInt(24)==2,"velocity-only change was omitted");gpu.finish(changed);
            deltaMeta(meta,0,2);gpu.upload(meta,baseline,n);var replacement=gpu.capture(state,n,0,0,0,n);
            ByteBuffer current=captureRecords(replacement);gpu.acknowledge(replacement.stamp(),old);
            var pending=gpu.capture(state,n,0,0,0,n);check(captureRecords(pending).remaining()==0,"old generation modified replacement flight");gpu.finish(pending);
            gpu.acknowledge(changed.stamp(),old);acknowledge(gpu,replacement,current);gpu.finish(replacement);
            var clean=gpu.capture(state,n,0,0,0,n);check(captureRecords(clean).remaining()==0,"valid generation ACK did not commit");gpu.finish(clean);
            // Refused shader rebuild preserves the entire previous program set.
            boolean failed=false;try{gpu.rebuild(name->name.endsWith("delta_finalize.comp")?"invalid shader":source(name));}catch(RuntimeException expected){failed=true;}
            check(failed,"invalid delta rebuild accepted");
            b.putFloat(60,-1);putBuffer(state,b);var release=gpu.capture(state,n,0,0,0,n);var event=captureRecords(release);
            check(event.remaining()==64 && event.getInt(28)==1,"solver fallback did not emit ownership release");
            gpu.cancel(release);release=gpu.capture(state,n,0,0,0,n);event=captureRecords(release);
            check(event.remaining()==64 && event.getInt(28)==1,"unacknowledged release event was lost");
            acknowledge(gpu,release,event);gpu.finish(release);
            var retired=gpu.capture(state,n,0,0,0,n);check(captureRecords(retired).remaining()==0,"acknowledged release candidate remained active");gpu.finish(retired);
        }finally{GL15.glDeleteBuffers(state);}
    }
    static void deltaYawTies() {
        int n=129;ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
        for(int i=0;i<n;i++) {
            body(b,i,1,2,3,1);deltaMeta(meta,i,1);
            b.putFloat(i*64+44,(float)((i-64+.5)*(360.0/65536)));
        }
        int state=buffer(b);
        try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source)) {
            gpu.upload(meta,baseline,n);var capture=gpu.capture(state,n,0,0,0,n);var out=captureRecords(capture);
            check(out.remaining()==n*64,"yaw tie candidate missing");
            for(int p=0;p<out.limit();p+=64) {
                int i=out.getInt(p+16),expected=quantizedBody(b,i).yaw();
                check(out.getInt(p+60)==expected,"Java yaw tie "+i+": GPU "+out.getInt(p+60)+", expected "+expected);
            }
            gpu.cancel(capture);
        }finally{GL15.glDeleteBuffers(state);}
    }
    static void deltaQuantizationLimits() {
        int n=8;ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
        for(int i=0;i<n;i++){body(b,i,1,2,3,1);deltaMeta(meta,i,1);}
        b.putFloat(0,Math.nextDown(64f)); // rounds to the adjacent region, must release
        b.putFloat(64+16,25).putFloat(64+20,25); // each component fits short, speed does not
        b.putFloat(128+16,32); // short overflow, never saturated
        b.putFloat(192+44,-999999.9375f);
        b.putFloat(256+44,999999.9375f);
        b.putFloat(320+16,-.5f/1024).putFloat(320+20,.5f/1024); // Java's signed round ties
        b.putFloat(384,1+.5f/4096).putFloat(384+8,1+1.5f/4096); // ties-to-even positions
        meta.putInt(7*32+16,n); // invalid body index must not be dereferenced
        int state=buffer(b);
        try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source)) {
            gpu.upload(meta,baseline,n);var capture=gpu.capture(state,n,0,0,0,n);var out=captureRecords(capture);
            check(out.remaining()==n*64,"quantization boundary record missing");
            for(int p=0;p<out.limit();p+=64) {
                int i=out.getInt(p+16);boolean release=i<3 || i==7;
                check(out.getInt(p+28)==(release?1:0),"out-of-range candidate did not hand back individually");
                if(!release) {
                    var q=quantizedBody(b,i);
                    check(out.getInt(p+32)==q.x() && out.getInt(p+40)==q.z(),"position ties-to-even mismatch");
                    check(out.getInt(p+48)==q.vx() && out.getInt(p+52)==q.vy() && out.getInt(p+60)==q.yaw(),"signed velocity/large yaw rounding mismatch");
                }
            }
            gpu.cancel(capture);
        }finally{GL15.glDeleteBuffers(state);}
    }
    static ByteBuffer readBuffer(int id,int bytes) {
        ByteBuffer result=BufferUtils.createByteBuffer(bytes);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);return result;
    }
    static final class ChannelTransport implements PackageDeltaChannel.Transport {
        PackageDeltaChannel channel;
        final BitSet seen=new BitSet();
        final List<Long> waiting=new ArrayList<>();
        final PackageAuthorityRegion server;
        int records,failures,releases,notices;
        boolean accepted=true,automaticAck=true;
        long last=-1;
        ChannelTransport(PackageAuthorityRegion server){this.server=server;}
        public boolean send(long epoch,long revision,long sequence,ByteBuffer bytes) {
            if(!accepted)return false;
            check(epoch==77 && revision==3,"channel lost namespace");check(sequence>last,"wire packet order regressed");last=sequence;
            check(bytes.remaining()<=24576,"wire packet exceeded negotiated limit");
            var changes=PackageDeltaCodec.decode(bytes);check(!bytes.hasRemaining(),"wire body trailing bytes");
            if(server!=null)check(server.delta(new UUID(7,9),epoch,revision,sequence,0,changes,4)==PackageAuthorityRegion.Result.ACCEPTED,"actual server rejected channel delta");
            for(var change:changes){seen.set(change.id());records++;if(change.mask()==16)releases++;}
            if(automaticAck)check(channel.acknowledge(epoch,revision,sequence),"loopback ACK rejected");else waiting.add(sequence);
            return true;
        }
        public void failed(String reason){failures++;}
        public void released(int localId,long id,long generation){notices++;}
    }
    static final class ChannelTarget implements PackageAuthorityRegion.Target {
        final PackageLease.Identity identity;
        PackageAuthorityRegion.Snapshot state;
        int releases;
        ChannelTarget(int i){identity=new PackageLease.Identity(0x1234567800000001L+i,1);state=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(1,2.5,4,0,0,0,0),1);}
        public PackageLease.Identity identity(){return identity;}
        public PackageAuthorityRegion.Snapshot snapshot(){return state;}
        public boolean eligible(){return true;}
        public void apply(PackageAuthorityRegion.Snapshot next){state=next;}
        public void released(PackageAuthorityRegion.Baseline baseline){releases++;}
    }
    static void channelRoundTrip() {
        for(int n:new int[]{65,131072}) {
            var tasks=new ArrayDeque<Runnable>();var clock=new java.util.concurrent.atomic.AtomicLong();
            ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
            PackageAuthorityRegion server=n==65?new PackageAuthorityRegion(new PackageRegion(0,0,0),new UUID(7,9),77,3,0):null;
            ChannelTarget[] targets=n==65?new ChannelTarget[n]:null;
            for(int i=0;i<n;i++) {
                body(b,i,2,3,4,1);deltaMeta(meta,i,1);meta.putInt(i*32+20,i);
                if(server!=null) {
                    var target=new ChannelTarget(i);targets[i]=target;var offered=server.offer(target,0);
                    var prepared=server.prepared(new UUID(7,9),77,offered.index(),target.identity,offered.leaseEpoch(),offered.revision(),0);
                    check(server.finalReady(new UUID(7,9),77,prepared.index(),target.identity,prepared.leaseEpoch(),prepared.revision(),0),"channel server baseline ready");
                }
            }
            var transport=new ChannelTransport(server);int state=buffer(b);
            var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source);
            try(var channel=new PackageDeltaChannel(gpu,n,77,3,new PackageDeltaJournal.Encoder(tasks::add,4),transport,clock::get,true)) {
                transport.channel=channel;channel.append(meta,baseline,n);check(channel.capture(state,n,0,0,0),"channel initial capture");
                for(int frame=0;frame<12;frame++) {
                    GL11.glFinish();channel.pump(256);
                    check(!channel.closed(),"deferred worker blocked/closed within processing budget");
                    check(tasks.size()<=4,"encoder work exceeded global budget");
                }
                check(transport.records==0,"unfinished encoder published records");
                // Work can finish out of order; transport still must publish increasing sequences.
                for(int frame=0;frame<20 && channel.stats().ackedPackets()<(n+511)/512;frame++) {
                    while(!tasks.isEmpty())tasks.removeLast().run();GL11.glFinish();channel.pump(256);
                }
                check(!channel.closed() && transport.records==n && transport.seen.cardinality()==n,"fragmented full-capacity journal lost records");
                check(channel.stats().payloadBytes()==(long)n*64,"readback copied unused capacity");
                check(channel.stats().ackedPackets()==(n+511)/512,"ACK journal did not retire full capture");
                if(n==131072)check(channel.stats().ackDispatches()<=16,"ACK coalescing regressed to per-packet GL dispatch");
                var clean=gpu.capture(state,n,0,0,0,n);check(captureRecords(clean).remaining()==0,"server ACK did not advance GPU baseline");gpu.finish(clean);
                check(!channel.acknowledge(76,3,0) && !channel.acknowledge(77,4,0) && !channel.acknowledge(77,3,99999),"wrong namespace/unknown ACK accepted");
                if(server!=null) {
                    for(var target:targets)check(target.state.pose().x()==2,"end-to-end server pose mismatch");
                    transport.automaticAck=false;transport.seen.clear();
                    b.putFloat(60,-1);b.putFloat(64+16,.25f);putBuffer(state,b);
                    check(channel.capture(state,n,0,0,0),"mixed release capture");
                    for(int frame=0;frame<8 && transport.waiting.isEmpty();frame++){GL11.glFinish();channel.pump(8);while(!tasks.isEmpty())tasks.remove().run();}
                    check(transport.releases==1 && targets[0].releases==1,"GPU release did not commit exactly once");
                    check(targets[1].state.pose().vx()==.25,"velocity-only state lost beside release");
                    check(channel.released(77,new PackageAuthorityRegion.Baseline(0,targets[0].identity,1,2,targets[0].state)),"server ownership notice rejected");
                    long sequence=transport.waiting.getFirst();check(channel.acknowledge(77,3,sequence),"delayed valid ACK rejected");channel.pump(8);
                    var noDuplicate=gpu.capture(state,n,0,0,0,n);check(captureRecords(noDuplicate).remaining()==0,"release/pose ACK repeated dirty state");gpu.finish(noDuplicate);
                    check(transport.notices==1,"authority release callback lost");
                    channel.released(77,new PackageAuthorityRegion.Baseline(0,targets[0].identity,1,2,targets[0].state));channel.pump(8);
                    check(transport.notices==1,"duplicate terminal notification repeated lifecycle callback");
                }
            }finally{GL15.glDeleteBuffers(state);}
            check(transport.failures==0,"healthy channel called fallback");
        }
    }
    static void channelLifecycle() {
        ByteBuffer b=bodies(2),meta=BufferUtils.createByteBuffer(64),baseline=BufferUtils.createByteBuffer(64);
        for(int i=0;i<2;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);}
        int state=buffer(b);var tasks=new ArrayDeque<Runnable>();var clock=new java.util.concurrent.atomic.AtomicLong();
        var transport=new ChannelTransport(null);var gpu=new PackageDeltaGpu(2,PackageGpuValidation::source);
        try(var channel=new PackageDeltaChannel(gpu,2,77,3,new PackageDeltaJournal.Encoder(tasks::add,4),transport,clock::get)) {
            transport.channel=channel;
            var first=meta.duplicate().position(0).limit(32);var firstBase=baseline.duplicate().position(0).limit(32);
            channel.append(first,firstBase,1);check(channel.capture(state,2,0,0,0),"first append capture");GL11.glFinish();channel.pump(4);
            var next=meta.duplicate().position(32).limit(64);var nextBase=baseline.duplicate().position(32).limit(64);
            channel.append(next,nextBase,1);check(channel.capture(state,2,0,0,0),"append reset existing flights");
            for(int frame=0;frame<8 && channel.stats().ackedPackets()<2;frame++){GL11.glFinish();channel.pump(4);while(!tasks.isEmpty())tasks.remove().run();}
            check(transport.records==2 && channel.stats().ackedPackets()==2,"append during immutable readback lost or duplicated candidate");
            b.putFloat(0,3);putBuffer(state,b);channel.capture(state,2,0,0,0);
            clock.set(PackageDeltaChannel.PREPARATION_TIMEOUT_NANOS+1);GL11.glFinish();channel.pump(4);
            check(channel.closed() && transport.failures==1,"processing timeout did not restore Create");
            check(!channel.acknowledge(77,3,0),"old epoch ACK survived closed channel");
            while(!tasks.isEmpty())tasks.remove().run();channel.pump(4);check(transport.failures==1,"fallback callback repeated");
        }finally{GL15.glDeleteBuffers(state);}
    }
    static void channelImmutableAndPartialTransport() {
        for(boolean batched:new boolean[]{false,true}) {
            int n=1025;ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
            for(int i=0;i<n;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);meta.putInt(i*32+20,i);}
            int state=buffer(b);var tasks=new ArrayDeque<Runnable>();var time=new java.util.concurrent.atomic.AtomicLong();
            var transport=new ChannelTransport(null);transport.accepted=false;
            var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source);
            try(var channel=new PackageDeltaChannel(gpu,n,77,3,new PackageDeltaJournal.Encoder(tasks::add,4),transport,time::get,batched)) {
                transport.channel=channel;channel.append(meta,baseline,n);channel.capture(state,n,0,0,0);
                for(int frame=0;frame<5;frame++){GL11.glFinish();channel.pump(8);while(!tasks.isEmpty())tasks.remove().run();}
                check(transport.records==0 && !channel.acknowledge(77,3,0),"unpublished transport packet accepted ACK");
                // The actual bodies move while their old immutable records wait for transport.
                for(int i=0;i<n;i++)b.putFloat(i*64,3);putBuffer(state,b);
                check(channel.capture(state,n,0,0,0),"in-flight detector prevented simulation");
                for(int frame=0;frame<5;frame++){GL11.glFinish();channel.pump(8);}
                check(transport.records==0 && !channel.closed(),"backpressure lost or published queued records");
                transport.accepted=true;
                for(int frame=0;frame<5 && channel.stats().ackedPackets()<3;frame++){GL11.glFinish();channel.pump(8);}
                check(channel.stats().ackedPackets()==3 && transport.records==n,"transport retry lost/duplicated capture");
                check(channel.stats().ackDispatches()==(batched?1:3),"ACK batching command count");
                var newer=gpu.capture(state,n,0,0,0,n);var records=captureRecords(newer);
                check(records.remaining()==n*64,"old capture ACK acknowledged newer unsubmitted bodies");
                for(int p=0;p<records.limit();p+=64)check(records.getInt(p+32)==12288,"newer dirty state was overwritten by readback");
                gpu.cancel(newer);
                var stale=new PackageAuthorityRegion.Baseline(0,new PackageLease.Identity(0x1234567800000001L,2),1,2,
                        new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(2,2.5,4,0,0,0,0),0));
                channel.released(77,stale);channel.pump(8);
                check(transport.notices==0,"stale lifecycle generation released live package");
                var afterNotice=gpu.capture(state,n,0,0,0,n);check(captureRecords(afterNotice).remaining()==n*64,"stale terminal notice disabled live identity");gpu.cancel(afterNotice);
                check(channel.capture(state,n,0,0,0),"wrapped journal capture");
                for(int frame=0;frame<8 && channel.stats().ackedPackets()<6;frame++){GL11.glFinish();channel.pump(8);while(!tasks.isEmpty())tasks.remove().run();}
                check(channel.stats().ackedPackets()==6 && transport.records==2*n,"wrapped journal lost/duplicated records");
                var wrapped=gpu.capture(state,n,0,0,0,n);check(captureRecords(wrapped).remaining()==0,"GPU journal wrapped ACK range corrupted baseline");gpu.finish(wrapped);
                channel.capture(state,n,0,0,0);channel.close(); // No fence wait; invalidate unread source banks.
                check(!channel.acknowledge(77,3,0) && !channel.released(77,stale),"closed epoch accepted terminal mailbox data");
            }finally{GL15.glDeleteBuffers(state);}
            check(transport.failures==0,"partial transport triggered unwarranted fallback");
        }
    }
    static void putBuffer(int id,ByteBuffer bytes) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes);
    }
    static void pool() {
        int n=65;
        ByteBuffer poolBytes=BufferUtils.createByteBuffer(n*64+128);
        for(int j=n*64;j<poolBytes.capacity();j++)poolBytes.put(j,(byte)0x5a);
        int pool=buffer(poolBytes),counter=buffer(BufferUtils.createByteBuffer(16));
        try(var physics=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);
            var bridge=new PackagePoolGpu(n,4,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*PackagePoolGpu.META_BYTES);
            for(int i=0;i<n;i++) {
                body(b,i,i*3,10,0,1);
                int p=i*PackagePoolGpu.META_BYTES;
                meta.putLong(p,(1L<<40)+i).putLong(p+8,(1L<<35)+1)
                        .putInt(p+16,i).putInt(p+20,0).putInt(p+24,1).putInt(p+28,i%2==0?1:0)
                        .putFloat(p+56,23/16f).putInt(p+60,0xf000f0);
            }
            physics.upload(b,n);physics.uploadChains(bodies(n));
            ByteBuffer vertex=BufferUtils.createByteBuffer(6*PackagePoolGpu.VERTEX_BYTES);
            ByteBuffer ranges=BufferUtils.createByteBuffer(32);
            ranges.putInt(0,0).putInt(4,3).putFloat(8,1);
            ranges.putInt(16,3).putInt(20,3).putFloat(24,1);
            bridge.uploadMeshes(vertex,ranges,2);bridge.uploadMetadata(meta,n);
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),n,123.5f,0,0);
            for(int ordinary:new int[]{0,63,65,93}) {
                ByteBuffer c=BufferUtils.createByteBuffer(16);c.putInt(0,ordinary);putBuffer(counter,c);
                bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
                int accepted=Math.min(n,Math.max(0,n-ordinary));
                check(readBuffer(counter,16).getInt(0)==Math.min(ordinary,n)+accepted,"common pool count/capacity");
                ByteBuffer result=readBuffer(pool,poolBytes.capacity()),admission=readBuffer(bridge.admissionBuffer(),n*32);
                Set<Integer> slots=new HashSet<>();int rigs=0;
                for(int i=0;i<n;i++) {
                    int p=i*32;
                    check(admission.getLong(p)==(1L<<40)+i,"identity truncated/reordered");
                    check(admission.getLong(p+8)==(1L<<35)+1,"generation truncated");
                    int slot=admission.getInt(p+16)-1;if(slot<0)continue;
                    check(slot>=Math.min(ordinary,n) && slot<n,"common slot out of range");
                    check(slots.add(slot),"duplicate common slot");
                    check(result.getFloat(slot*64)==i*3+123.5f,"GPU physics pose not imported");
                    check(result.getInt(slot*64+60)==7,"emitter ABI offset");
                    if(i%2==0)rigs++;
                }
                check(slots.size()==accepted,"missing admission");
                for(int j=n*64;j<result.capacity();j++)check(result.get(j)==(byte)0x5a,"pool sentinel overwritten");
                ByteBuffer commands=readBuffer(bridge.commandBuffer(),32);
                check(commands.getInt(4)==accepted && commands.getInt(20)==rigs,"model partition counts");
                check(commands.getInt(12)==0 && commands.getInt(28)==accepted,"model prefix/baseInstance");
                ByteBuffer instances=readBuffer(bridge.instanceBuffer(),(accepted+rigs)*8);
                Set<Integer> boxes=new HashSet<>(),hooks=new HashSet<>();
                for(int j=0;j<accepted+rigs;j++) {
                    int slot=instances.getInt(j*8),part=instances.getInt(j*8+4)&1;
                    check(slots.contains(slot),"draw instance references rejected package");
                    check((part==0?boxes:hooks).add(slot),"duplicate mesh instance");
                }
                check(boxes.size()==accepted && hooks.size()==rigs,"box/rig partition uniqueness");
            }
            // Static colliders and fallback bodies consume sidecar storage, never a particle slot.
            b.putFloat(12,0).putFloat(64+60,-1);physics.upload(b,n);
            ByteBuffer c=BufferUtils.createByteBuffer(16);putBuffer(counter,c);
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),n,0,0,0);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            check(readBuffer(counter,16).getInt(0)==n-2,"rejected bodies left holes/count inflation");
            ByteBuffer good=readBuffer(bridge.commandBuffer(),32),goodAdmission=readBuffer(bridge.admissionBuffer(),n*32);
            putBuffer(counter,c);float[] hidden=new float[24];hidden[0]=1;hidden[3]=-10000;
            bridge.stage(pool,counter,7,hidden,0,0,0);bridge.abort();
            check(good.equals(readBuffer(bridge.commandBuffer(),32)),"failed frame published partial draw commands");
            check(goodAdmission.equals(readBuffer(bridge.admissionBuffer(),n*32)),"failed frame published partial identities");
            putBuffer(counter,c);bridge.stage(pool,counter,-1,new float[24],0,0,0);bridge.commit();
            check(readBuffer(counter,16).getInt(0)==0 && bridge.admissionCount()==0,"missing emitter imported invalid records");
            bridge.uploadMetadata(BufferUtils.createByteBuffer(0),0);c.putInt(0,7);putBuffer(counter,c);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            check(readBuffer(counter,16).getInt(0)==7,"empty import changed ordinary live count");
            check(readBuffer(bridge.commandBuffer(),32).getInt(4)==0,"empty generation retained draw instances");
        }finally{GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);}
    }
    static int texture(int unit,int size) {
        int id=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
        ByteBuffer pixels=BufferUtils.createByteBuffer(size*size*4);while(pixels.hasRemaining())pixels.put((byte)255);pixels.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,size,size,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return id;
    }
    static void render() {
        int pool=buffer(bodies(2)),counter=buffer(BufferUtils.createByteBuffer(16)),atlas=texture(1,1),light=texture(2,16);
        try(var physics=new PackagePhysicsGpu(2,2,PackageGpuValidation::source);
            var bridge=new PackagePoolGpu(2,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(2),meta=BufferUtils.createByteBuffer(2*PackagePoolGpu.META_BYTES);
            for(int i=0;i<2;i++) {
                body(b,i,i-.5f,0,0,1);b.putFloat(i*64+44,-90);
                int m=i*PackagePoolGpu.META_BYTES;
                meta.putLong(m,i+1).putLong(m+8,1).putInt(m+16,i).putInt(m+20,i).putInt(m+24,-1).putInt(m+60,0xf000f0);
            }
            physics.upload(b,2);
            ByteBuffer vertices=BufferUtils.createByteBuffer(12*PackagePoolGpu.VERTEX_BYTES),ranges=BufferUtils.createByteBuffer(32);
            int[] corners={0,1,2,2,3,0};float[][] xy={{0,0},{1,0},{1,1},{0,1}};
            for(int mesh=0;mesh<2;mesh++)for(int corner:corners) {
                vertices.putFloat(xy[corner][0]).putFloat(xy[corner][1]).putFloat(.5f).putFloat(0).putFloat(0);
                vertices.putFloat(0).putFloat(0).putFloat(0);
                vertices.putFloat(mesh==0?1:0).putFloat(0).putFloat(mesh==1?1:0).putFloat(1);
            }
            vertices.flip();ranges.putInt(0,0).putInt(4,6).putFloat(8,1).putInt(16,6).putInt(20,6).putFloat(24,1);
            bridge.uploadMeshes(vertices,ranges,2);bridge.uploadMetadata(meta,2);
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),2,0,0,0);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            for(int reload=0;reload<3;reload++) {
                if(reload==1) {
                    boolean failed=false;
                    try {bridge.rebuild(name->name.equals("packages/package.fsh")?"broken shader":source(name));}
                    catch(IllegalStateException expected){failed=true;}
                    check(failed,"invalid graphics reload accepted");
                } else if(reload==2)bridge.rebuild(PackageGpuValidation::source);
                GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
                GL11.glClearColor(0,1,0,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                bridge.draw(pool,new Matrix4f(),new Matrix4f(),0,0,0,1);
                ByteBuffer pixels=BufferUtils.createByteBuffer(64*64*4);
                GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                int left=(32*64+16)*4,right=(32*64+48)*4;
                check((pixels.get(left)&255)==255 && (pixels.get(left+2)&255)==0,"first mesh indirect render/reload");
                check((pixels.get(right)&255)==0 && (pixels.get(right+2)&255)==255,"baseInstance selects wrong package/mesh");
            }
        }finally {GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);GL11.glDeleteTextures(atlas);GL11.glDeleteTextures(light);}
    }
    static void previewLoad() {
        // Exercise the reported 65,536-package workload through processed resources and pool import.
        // This validates submission, not whole-frame performance or actual Create model rendering.
        int n=65536;
        int pool=buffer(bodies(n)),counter=buffer(BufferUtils.createByteBuffer(16));
        try(var physics=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);
            var bridge=new PackagePoolGpu(n,1,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(n),chains=bodies(n),meta=BufferUtils.createByteBuffer(n*PackagePoolGpu.META_BYTES);
            for(int i=0;i<n;i++) {
                int p=i*64,m=i*PackagePoolGpu.META_BYTES;
                body(b,i,(i%256)*3,-9f/16f,(i/256)*3,1);
                chains.putFloat(p,(i%256)*3).putFloat(p+8,(i/256)*3).putFloat(p+12,.875f)
                        .putFloat(p+36,90).putFloat(p+40,1);
                meta.putLong(m,i+1L).putLong(m+8,1).putInt(m+16,i).putInt(m+20,0).putInt(m+24,0)
                        .putInt(m+28,PackagePoolGpu.CHAIN).putInt(m+60,0xf000f0);
            }
            physics.upload(b,n);physics.uploadChains(chains);physics.stepChains(.05f);
            ByteBuffer vertices=BufferUtils.createByteBuffer(3*PackagePoolGpu.VERTEX_BYTES),ranges=BufferUtils.createByteBuffer(16);
            ranges.putInt(0,0).putInt(4,3).putFloat(8,1);
            bridge.uploadMeshes(vertices,ranges,1);bridge.uploadMetadata(meta,n);
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),n,0,64,0);
            bridge.stage(pool,counter,7,new float[24],0,64,0);bridge.commit();
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            ByteBuffer c=BufferUtils.createByteBuffer(16);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counter);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,c);
            check(c.getInt(0)==n,"65536 preview candidates missing from live count");
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bridge.commandBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,c);
            check(c.getInt(0)==3 && c.getInt(4)==2*n,"65536 preview box/rig draw commands missing");
            ByteBuffer a=BufferUtils.createByteBuffer(n*32);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bridge.admissionBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,a);
            BitSet slots=new BitSet(n);boolean valid=true;
            for(int i=0;i<n;i++) {
                int slot=a.getInt(i*32+16)-1;
                if(a.getLong(i*32)!=i+1L || slot<0 || slot>=n || slots.get(slot)){valid=false;break;}
                slots.set(slot);
            }
            check(valid && slots.cardinality()==n,"65536 preview admission identities/slots are not unique");
        } finally {GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);}
    }
    static void poseParity() {
        int shader=GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(shader,"#version 450 core\n"+source("packages/package.vsh"));GL20.glCompileShader(shader);
        check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,"pose vertex compilation");
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);
        GL30.glTransformFeedbackVaryings(program,new CharSequence[]{"gl_Position","vColor"},GL30.GL_INTERLEAVED_ATTRIBS);GL20.glLinkProgram(program);
        check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,"pose feedback link");GL20.glDeleteShader(shader);
        int pool=buffer(bodies(1)),attachment=buffer(BufferUtils.createByteBuffer(32)),output=buffer(BufferUtils.createByteBuffer(32)),light=texture(2,16);
        int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);GL20.glUseProgram(program);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,attachment);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
        float[] identity=new float[16];new Matrix4f().get(identity);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ModelViewMat"),false,identity);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ProjMat"),false,identity);
        GL20.glUniform3f(GL20.glGetUniformLocation(program,"uCamPos"),0,0,0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program,"uLightmap"),2);GL20.glVertexAttrib4f(3,1,1,1,1);
        Vector3f l0=new Vector3f(.16169f,.80845f,-.56594f).normalize(),l1=new Vector3f(-.16169f,.80845f,.56594f).normalize();
        GL20.glUniform3f(GL20.glGetUniformLocation(program,"uLight0"),l0.x,l0.y,l0.z);
        GL20.glUniform3f(GL20.glGetUniformLocation(program,"uLight1"),l1.x,l1.y,l1.z);
        Random random=new Random(79);
        try {
            for(int flags:new int[]{0,1,3})for(int rig=0;rig<2;rig++)for(float pt:new float[]{0,.25f,.5f,1})for(int run=0;run<32;run++) {
                Vector3f previous=new Vector3f(random.nextFloat()*2,random.nextFloat()*2,random.nextFloat()*2);
                Vector3f current=new Vector3f(previous).add(.2f,-.1f,.3f),oldTarget=new Vector3f(1,2,3),target=new Vector3f(1.2f,2.1f,3.2f);
                float yaw0=random.nextFloat()*720-360,yaw1=yaw0+210,hook=23/16f;
                ByteBuffer state=bodies(1),extra=BufferUtils.createByteBuffer(32);
                state.putFloat(0,current.x).putFloat(4,current.y).putFloat(8,current.z)
                        .putFloat(16,previous.x).putFloat(20,previous.y).putFloat(24,previous.z)
                        .putFloat(32,target.x).putFloat(36,target.y).putFloat(40,target.z).putInt(44,flags<<24)
                        .putFloat(48,yaw1).putFloat(52,yaw0).putFloat(56,hook);
                extra.putFloat(0,oldTarget.x).putFloat(4,oldTarget.y).putFloat(8,oldTarget.z)
                        .putFloat(16,.001f).putFloat(20,-.002f).putFloat(24,.003f);
                putBuffer(pool,state);putBuffer(attachment,extra);
                Vector3f vertex=new Vector3f(random.nextFloat(),random.nextFloat(),random.nextFloat());
                int lighting=run%2,ambient=(run/2)%2;boolean unshaded=run%5==0;
                Vector3f normal=unshaded?new Vector3f():new Vector3f(run%3==0?1:0,run%3==1?1:0,run%3==2?1:0);
                GL20.glVertexAttrib3f(2,normal.x,normal.y,normal.z);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"uLightingMode"),lighting);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"uConstantAmbient"),ambient);
                GL20.glVertexAttrib3f(0,vertex.x,vertex.y,vertex.z);GL30.glVertexAttribI2ui(4,0,rig);
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"uPartialTick"),pt);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_POINTS);
                GL11.glDrawArrays(GL11.GL_POINTS,0,1);GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                float yaw=yaw0+((yaw1-yaw0+180)%360-180)*pt;
                Matrix4f transform=new Matrix4f();Vector3f pos=new Vector3f(previous).lerp(current,pt);
                if((flags&1)!=0) {
                    Vector3f anchor=new Vector3f(oldTarget).lerp(target,pt),d=new Vector3f(anchor).add(0,.5f,0).sub(pos);
                    new Matrix4f().rotateY((float)Math.toRadians(-yaw)).transformDirection(d);
                    float zr=(float)Math.toDegrees(Math.atan2(-d.x,d.y)),xr=(float)Math.toDegrees(Math.atan2(d.z,d.y));
                    zr=((zr+180)%360-180)*.5f;xr=((xr+180)%360-180)*.5f;
                    transform.translate(anchor).translate(0,10/16f,0).rotateY((float)Math.toRadians(yaw))
                            .rotateZ((float)Math.toRadians(Math.clamp(zr,-25,25))).rotateX((float)Math.toRadians(Math.clamp(xr,-25,25)));
                    if(rig==1 && (flags&2)!=0)transform.rotateY((float)Math.PI);
                    transform.translate(-.5f,-.5f,-.5f).translate(0,-hook+7/16f,0);
                } else transform.translate(pos).translate(.001f,-.002f,.003f).translate(0,.5f,0)
                        .rotateY((float)Math.toRadians(-yaw-90)).translate(-.5f,-.5f,-.5f);
                Vector3f expected=transform.transformPosition(vertex);ByteBuffer result=readBuffer(output,32);
                check(Math.abs(result.getFloat(0)-expected.x)<2e-5,"Create pose x parity");
                check(Math.abs(result.getFloat(4)-expected.y)<2e-5,"Create pose y parity");
                check(Math.abs(result.getFloat(8)-expected.z)<2e-5,"Create pose z parity");
                check(result.getFloat(12)==1,"Create pose homogeneous coordinate");
                float diffuse;
                if(lighting==1 && (flags&1)!=0) {
                    transform.transformDirection(normal);
                    float yf=ambient!=0?.9f:(3+normal.y)*.25f;
                    diffuse=unshaded?(ambient!=0?.9f:1):Math.min(1,normal.x*normal.x*.6f+normal.y*normal.y*yf+normal.z*normal.z*.8f);
                } else {
                    if(unshaded)normal.set(0,1,0);else transform.transformDirection(normal);
                    diffuse=Math.min(1,(Math.max(0,l0.dot(normal))+Math.max(0,l1.dot(normal)))*.6f+.4f);
                }
                for(int channel=0;channel<3;channel++)check(Math.abs(result.getFloat(16+channel*4)-diffuse)<2e-5,"Create/Flywheel diffuse parity");
                check(result.getFloat(28)==1,"package color alpha");
            }
        }finally {
            GL20.glUseProgram(0);GL20.glDeleteProgram(program);GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(attachment);GL15.glDeleteBuffers(output);
            GL11.glDeleteTextures(light);
        }
    }
    static void benchmark() throws Exception {
        List<String> rows=new ArrayList<>();rows.add("count,scenario,run,gpu_ms");
        for(int n:new int[]{10000,65536,131072})try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(n),c=bodies(n);
            for(int i=0;i<n;i++) {
                int x=i%512,z=i/512;body(b,i,x*3,10,z*3,1);
                int p=i*64;c.putFloat(p,x*3).putFloat(p+4,10).putFloat(p+8,z*3).putFloat(p+12,.875f)
                        .putFloat(p+36,90).putFloat(p+40,1);
            }
            for(String scenario:List.of("separated_free","chain")) {
                gpu.upload(b,n);gpu.uploadChains(c);
                Runnable step=scenario.equals("chain")?()->gpu.stepChains(.05f):()->gpu.step(.05f);
                for(int warm=0;warm<20;warm++)step.run();GL11.glFinish();
                for(int run=1;run<=3;run++) {
                    int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);
                    for(int j=0;j<30;j++)step.run();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                    double ms=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/30e6;GL15.glDeleteQueries(query);
                    rows.add(n+","+scenario+","+run+","+ms);
                }
            }
        }
        Files.write(Path.of("build/package-gpu-kernels.csv"),rows);
        for(String row:rows)System.out.println(row);
    }
    static double percentile(double[] values,double fraction) {
        var sorted=values.clone();Arrays.sort(sorted);return sorted[Math.max(0,(int)Math.ceil(fraction*sorted.length)-1)];
    }
    static void deltaBenchmark() throws Exception {
        var rows=new ArrayList<String>();rows.add("count,scenario,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms");
        for(int n:new int[]{10000,65536,131072}) {
            ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
            for(int i=0;i<n;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);}int state=buffer(b);
            try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source)) {
                for(String scenario:List.of("dirty_capture_cancel","unchanged_capture")) {
                    gpu.upload(meta,baseline,n);
                    if(scenario.equals("unchanged_capture")) {
                        var initial=gpu.capture(state,n,0,0,0,n);acknowledge(gpu,initial,captureRecords(initial));gpu.finish(initial);
                    }
                    Runnable frame=()->{var capture=gpu.capture(state,n,0,0,0,n);if(scenario.equals("dirty_capture_cancel"))gpu.cancel(capture);else gpu.finish(capture);};
                    for(int warm=0;warm<20;warm++)frame.run();GL11.glFinish();
                    for(int run=1;run<=3;run++) {
                        int[] queries=new int[60];double[] cpu=new double[60],times=new double[60];
                        for(int i=0;i<queries.length;i++) {
                            queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);
                            long begin=System.nanoTime();frame.run();cpu[i]=(System.nanoTime()-begin)/1e6;
                            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        }
                        for(int i=0;i<queries.length;i++){times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[i]);}
                        rows.add(n+","+scenario+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95));
                    }
                }
            }finally{GL15.glDeleteBuffers(state);}
        }
        Files.write(Path.of("build/package-gpu-deltas.csv"),rows);for(String row:rows)System.out.println(row);
    }
    static volatile com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket benchmarkPacket;
    static final class PipelineTransport implements PackageDeltaChannel.Transport {
        static final PackageRegion REGION=new PackageRegion(0,0,0);
        PackageDeltaChannel channel;
        String failure;
        final boolean workerPackets;
        PipelineTransport(boolean workerPackets){this.workerPackets=workerPackets;}
        static Object packet(long epoch,long revision,long sequence,ByteBuffer bytes) {
            // Match production's immutable packet copy. No Netty, network, server entity update or render.
            byte[] body=new byte[bytes.remaining()];bytes.get(body);
            return new com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket(5,0,
                    REGION,epoch,0,null,0,revision,sequence,body);
        }
        public Object prepare(long epoch,long revision,long sequence,ByteBuffer bytes){return workerPackets?packet(epoch,revision,sequence,bytes):null;}
        public boolean send(long epoch,long revision,long sequence,ByteBuffer bytes){return sendPrepared(epoch,revision,sequence,packet(epoch,revision,sequence,bytes),bytes);}
        public boolean sendPrepared(long epoch,long revision,long sequence,Object prepared,ByteBuffer bytes) {
            if(prepared==null)prepared=packet(epoch,revision,sequence,bytes);
            benchmarkPacket=(com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket)prepared;
            if(!channel.acknowledge(epoch,revision,sequence))throw new AssertionError("Benchmark loopback ACK rejected");return true;
        }
        public void failed(String reason){failure=reason;}
    }
    static void pipelineBenchmark() throws Exception {
        long setup=System.nanoTime();
        benchmarkPacket=com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket.capabilities(0);
        System.out.println("Pipeline packet class setup (excluded from warmed samples): "+(System.nanoTime()-setup)/1e6+" ms");
        var summaries=new ArrayList<String>();var samples=new ArrayList<String>();
        summaries.add("count,scenario,run,samples,cpu_total_p50_ms,cpu_total_p95_ms,cpu_peak_call_p50_ms,cpu_peak_call_p95_ms,latency_p50_ms,latency_p95_ms,encoder_work_mean_ms,render_alloc_mean_kib,readback_mean_bytes,wire_body_mean_bytes,packets_mean,ack_dispatch_mean");
        samples.add("count,scenario,run,sample,cpu_total_ms,cpu_peak_call_ms,latency_ms,encoder_work_ms,render_alloc_bytes,readback_bytes,wire_body_bytes,packets,ack_dispatches");
        var threadBean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        boolean allocations=threadBean.isThreadAllocatedMemorySupported();if(allocations)threadBean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var workerNanos=new java.util.concurrent.atomic.AtomicLong();
            var encoder=new PackageDeltaJournal.Encoder(task->executor.execute(()->{
                long start=System.nanoTime();try{task.run();}finally{workerNanos.addAndGet(System.nanoTime()-start);}
            }),4);
            for(int n:new int[]{10000,65536,131072}) {
                ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
                for(int i=0;i<n;i++) {
                    body(b,i,.5f+(i%128)*.25f,1+(i/128%64)*.5f,.5f+(i/8192)*2,1);
                    b.putFloat(i*64+16,.25f);deltaMeta(meta,i,1);meta.putInt(i*32+20,i);
                }
                int state=buffer(b);
                try {
                    // Alternate order by repetition to reduce fixed warmup/temperature order bias.
                    for(int run=1;run<=3;run++)for(int mode:run%2==0?new int[]{2,1,0}:new int[]{0,1,2}) {
                        boolean batched=mode==2;
                        String scenario=mode==0?"direct_render_packet":mode==1?"direct_worker_packet":"gpu_journal_worker_packet";
                        var transport=new PipelineTransport(mode!=0);
                        var detector=new PackageDeltaGpu(n,PackageGpuValidation::source);
                        try(var channel=new PackageDeltaChannel(detector,n,77,3,encoder,transport,System::nanoTime,batched)) {
                            transport.channel=channel;channel.append(meta,baseline,n);int packets=(n+511)/512;
                            double[] cpu=new double[30],peak=new double[30],latency=new double[30];
                            double workerTotal=0,allocatedTotal=0,readbackTotal=0,wireTotal=0,packetTotal=0,dispatchTotal=0;
                            for(int sample=-15;sample<30;sample++) {
                                for(int i=0;i<n;i++)b.putFloat(i*64,.5f+(i%128)*.25f+((sample&1)==0?.03125f:0));
                                putBuffer(state,b); // Synthetic motion upload excluded; production solver writes on GPU.
                                var before=channel.stats();long workerStart=workerNanos.get();
                                long allocated=allocations?threadBean.getThreadAllocatedBytes(thread):0;
                                long start=System.nanoTime(),call=start;
                                check(channel.capture(state,n,0,0,0),"benchmark capture skipped");
                                long elapsed=System.nanoTime()-call,total=elapsed,maximum=elapsed;
                                int pumps=0;
                                while(channel.stats().ackedPackets()<before.ackedPackets()+packets && !channel.closed()) {
                                    call=System.nanoTime();channel.pump(256);elapsed=System.nanoTime()-call;total+=elapsed;maximum=Math.max(maximum,elapsed);
                                    if(++pumps>2000)throw new AssertionError("Benchmark did not drain");
                                    if(channel.stats().ackedPackets()<before.ackedPackets()+packets)Thread.sleep(1); // Harness polling cadence, outside CPU totals.
                                }
                                long end=System.nanoTime();var after=channel.stats();
                                check(!channel.closed(),"pipeline benchmark fallback count="+n+", run="+run+", sample="+sample+", elapsed_ms="+(end-start)/1e6+": "+transport.failure);
                                check(after.payloadBytes()-before.payloadBytes()==(long)n*64,"pipeline benchmark lost dirty bodies");
                                long allocationBytes=allocations?threadBean.getThreadAllocatedBytes(thread)-allocated:-1;
                                long worker=workerNanos.get()-workerStart,readback=after.payloadBytes()-before.payloadBytes(),wire=after.wireBytes()-before.wireBytes();
                                long sent=after.sentPackets()-before.sentPackets(),dispatches=after.ackDispatches()-before.ackDispatches();
                                if(sample>=0) {
                                    cpu[sample]=total/1e6;peak[sample]=maximum/1e6;latency[sample]=(end-start)/1e6;
                                    workerTotal+=worker/1e6;allocatedTotal+=allocationBytes/1024.0;readbackTotal+=readback;wireTotal+=wire;packetTotal+=sent;dispatchTotal+=dispatches;
                                    samples.add(n+","+scenario+","+run+","+sample+","+cpu[sample]+","+peak[sample]+","+latency[sample]+","+worker/1e6+","+allocationBytes+","+readback+","+wire+","+sent+","+dispatches);
                                }
                            }
                            var clean=detector.capture(state,n,0,0,0,n);check(captureRecords(clean).remaining()==0,"pipeline benchmark baseline mismatch");detector.finish(clean);
                            String summary=n+","+scenario+","+run+",30,"+percentile(cpu,.5)+","+percentile(cpu,.95)+","+percentile(peak,.5)+","+percentile(peak,.95)+","+percentile(latency,.5)+","+percentile(latency,.95)+","+workerTotal/30+","+allocatedTotal/30+","+readbackTotal/30+","+wireTotal/30+","+packetTotal/30+","+dispatchTotal/30;
                            summaries.add(summary);System.out.println(summary);
                        }
                    }
                }finally{GL15.glDeleteBuffers(state);}
            }
        }
        Files.write(Path.of("build/package-delta-pipeline.csv"),summaries);Files.write(Path.of("build/package-delta-pipeline-samples.csv"),samples);
    }
    public static void main(String[] args)throws Exception {
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err);callback.set();
        check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Package GPU validation",0,0);check(window!=0,"GL context");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            sourceContract();boundaries();contact();sweep();chain();chainReference();readbacks();pool();render();previewLoad();poseParity();deltas();deltaOverflowAndIdentity();deltaQuantizationLimits();deltaYawTies();channelRoundTrip();channelLifecycle();channelImmutableAndPartialTransport();if(Arrays.asList(args).contains("--benchmark")){benchmark();deltaBenchmark();}
            if(Arrays.asList(args).contains("--delta-pipeline-benchmark"))pipelineBenchmark();
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error");System.out.println("Package GPU: "+checks+" assertions passed");
        }finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
