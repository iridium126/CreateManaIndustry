import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePhysicsGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageMovingGeometry;
import com.iridium126.createmanaindustry.client.particles.packages.PackageMovingCollisionCache;
import com.iridium126.createmanaindustry.client.particles.packages.PackageMovingCollisionGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageReadbackRing;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePoolGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaChannel;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaJournal;
import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionCache;
import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionGpu;
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
    static final PackageCollisionCache.Cell WORLD_AIR=new PackageCollisionCache.Cell(List.of(),.6f,0);
    static PackageCollisionCache.Cell shape(float height,float friction) {
        return new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,height,1)),friction,0);
    }
    static PackageCollisionCache.Snapshot snapshot(PackageCollisionCache.Source source) {
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);
        var section=new PackageCollisionCache.Section(0,0,0);cache.request(section);
        for(int i=0;i<3 && cache.snapshot(section)==null;i++)cache.tick(source,1);
        check(cache.snapshot(section)!=null,"immutable world fixture did not finish");return cache.snapshot(section);
    }
    static void uploadWorld(PackageCollisionGpu atlas) {
        for(int i=0;i<1000 && atlas.stats().pending()!=0;i++)atlas.pump(262144,Long.MAX_VALUE);
        check(atlas.stats().pending()==0,"world upload did not complete");
    }
    static void cubeWorld(PackageCollisionGpu atlas,PackageCollisionCache.Snapshot air,PackageCollisionCache.Snapshot floor,int ox,int oy,int oz) {
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)
            check(atlas.offer(new PackageCollisionCache.Section(ox+x,oy+y,oz+z),y==0?floor:air),"world section admission");
        uploadWorld(atlas);
    }
    static void worldUploadVersions() {
        var air=snapshot((s,i)->WORLD_AIR);var section=new PackageCollisionCache.Section(0,0,0);
        try(var atlas=new PackageCollisionGpu(1,1,()->0L)) {
            check(atlas.offer(section,air),"world offer rejected");atlas.pump(1024,1);
            check(!atlas.covered(section,air.revision()),"partial GPU upload published coverage");
            check(atlas.stats().uploadedBytes()==1024,"upload exceeded byte budget");
            uploadWorld(atlas);check(atlas.covered(section,air.revision()),"completed coverage missing");
            check(!atlas.offer(new PackageCollisionCache.Section(1,0,0),air),"capacity silently exceeded");
            atlas.invalidate(section,air.revision()+1);
            check(!atlas.covered(section,air.revision()),"invalidation kept stale coverage");
            check(!atlas.offer(section,air),"obsolete worker reintroduced coverage");
            atlas.forget(section);check(atlas.offer(section,air),"retired identity prevented reuse");uploadWorld(atlas);
            atlas.clear();check(!atlas.covered(section,air.revision()),"clear kept coverage");
            boolean invalid=false;try(var view=atlas.view(Integer.MAX_VALUE,0,0)){}catch(IllegalArgumentException expected){invalid=true;}
            check(invalid,"invalid world origin accepted");
        }
        var many=snapshot((s,i)->i==0?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1),
                new PackageCollisionCache.Box(0,0,0,1,.5f,1)),.6f,0):WORLD_AIR);
        var overhang=snapshot((s,i)->i==0?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(-1.01f,0,0,1,1,1)),.6f,0):WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(1,1)) {
            check(!atlas.offer(section,many),"shape capacity silently truncated");
            check(!atlas.offer(section,overhang),"unbounded overhang admitted");
            check(!atlas.covered(section,many.revision()),"rejected world falsely covered");
        }
        var ticks=new java.util.concurrent.atomic.AtomicLong();
        try(var atlas=new PackageCollisionGpu(1,1,()->ticks.getAndAdd(100))) {
            atlas.offer(section,air);atlas.pump(65536,50);
            check(atlas.stats().uploadedBytes()==0 && !atlas.covered(section,air.revision()),"time budget ignored");
        }
    }
    static void worldShapes() {
        var air=snapshot((s,i)->WORLD_AIR);
        for(float height:new float[]{.5f,1f,1.5f}) {
            var floor=snapshot((s,i)->i>>>8==0?shape(height,.6f):WORLD_AIR);
            for(int[] origin:new int[][]{{0,0,0},{-100,-2000000,100},{100,2000000,-100}}) {
                try(var atlas=new PackageCollisionGpu(27,2);var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source)) {
                    cubeWorld(atlas,air,floor,origin[0],origin[1],origin[2]);
                    ByteBuffer b=bodies(65);
                    for(int i=0;i<65;i++)body(b,i,2+i%7*4,7,2+i/7*3,1);
                    gpu.upload(b,65);
                    try(var view=atlas.view(origin[0],origin[1],origin[2])) {
                        for(int step=0;step<80;step++)gpu.stepWorld(.05f,view);
                    }
                    var result=read(gpu);
                    for(int i=0;i<65;i++) {
                        check(Math.abs(result.getFloat(i*64+4)-(height+.5f))<1e-4,"world support / origin / tail "+i);
                        check(result.getFloat(i*64+28)==1 && result.getFloat(i*64+60)>=0,"world grounded or fallback "+i);
                        check(result.getFloat(i*64)==b.getFloat(i*64) && result.getFloat(i*64+8)==b.getFloat(i*64+8),"unrelated world bodies moved");
                    }
                }
            }
        }
        // A two-box stair and an offset slab cannot be represented as a full block.
        var step=snapshot((s,i)->i==0?new PackageCollisionCache.Cell(List.of(
                new PackageCollisionCache.Box(0,0,0,1,.5f,1),new PackageCollisionCache.Box(.5f,.5f,0,1,1,1)),.6f,0):WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(27,4);var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,step,0,0,0);ByteBuffer b=bodies(2);
            body(b,0,.25f,4,.5f,1);body(b,1,.75f,4,.5f,1);
            for(int i=0;i<2;i++)for(int axis=0;axis<3;axis++)b.putFloat(i*64+32+axis*4,.1f);
            gpu.upload(b,2);try(var view=atlas.view(0,0,0)){for(int i=0;i<80;i++)gpu.stepWorld(.05f,view);}
            var r=read(gpu);check(Math.abs(r.getFloat(4)-.6f)<1e-4,"stair lower tread");check(Math.abs(r.getFloat(68)-1.1f)<1e-4,"stair upper tread");
        }
    }
    static void worldSweepsAndMaterials() {
        var air=snapshot((s,i)->WORLD_AIR);
        for(float material:new float[]{.6f,.98f}) {
            var floor=snapshot((s,i)->i>>>8==0?shape(1,material):WORLD_AIR);
            try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
                cubeWorld(atlas,air,floor,0,0,0);ByteBuffer b=bodies(1);body(b,0,5,6,5,1);b.putFloat(20,-100);
                gpu.upload(b,1);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
                var r=read(gpu);check(Math.abs(r.getFloat(4)-1.5f)<1e-4,"world high-speed sweep tunneled");check(r.getFloat(20)==0,"world inward velocity remained");
                body(b,0,5,1.5f,5,1);b.putFloat(16,10).putFloat(20,0);gpu.upload(b,1);
                try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
                r=read(gpu);check(Math.abs(r.getFloat(16)-9.8f*material)<1e-4,"support material friction mismatch");
                body(b,0,5,6,5,1);b.putFloat(16,0).putFloat(20,-100000);gpu.upload(b,1);
                try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);gpu.stepWorld(.05f,view);}
                r=read(gpu);check(r.getFloat(4)==6 && r.getFloat(60)<0,"oversized world sweep advanced or resumed");
            }
        }
        // Negative local blocks and a guard cell with a shape extending beyond its owner.
        var overhang=snapshot((s,i)->i==15?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,2,1,1)),.6f,0):WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,air,0,0,0);atlas.forget(new PackageCollisionCache.Section(-1,0,0));
            atlas.offer(new PackageCollisionCache.Section(-1,0,0),overhang);uploadWorld(atlas);
            ByteBuffer b=bodies(1);body(b,0,.25f,4,.5f,1);for(int axis=0;axis<3;axis++)b.putFloat(32+axis*4,.1f);
            gpu.upload(b,1);try(var view=atlas.view(0,0,0)){for(int i=0;i<80;i++)gpu.stepWorld(.05f,view);}
            var r=read(gpu);check(Math.abs(r.getFloat(4)-1.1f)<1e-4 && r.getFloat(60)>=0,"negative-section overhang missed");
        }
    }
    static void worldMissingAndInvalidated() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int flags:new int[]{1,2,4,8,16}) {
            var hazard=snapshot((s,i)->i==(5|5<<4|4<<8)?new PackageCollisionCache.Cell(List.of(),.6f,flags):WORLD_AIR);
            try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
                cubeWorld(atlas,air,hazard,0,0,0);ByteBuffer b=bodies(1);body(b,0,5.5f,4.5f,5.5f,1);gpu.upload(b,1);
                try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);gpu.stepWorld(.05f,view);}
                var r=read(gpu);check(r.getFloat(4)==4.5f && r.getFloat(60)<0,"hazard/unsupported did not hand back");
            }
        }
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,floor,0,0,0);ByteBuffer b=bodies(1);body(b,0,4,6,4,1);gpu.upload(b,1);
            try(var view=atlas.view(0,0,0)) {
                gpu.stepWorld(.05f,view);
                atlas.invalidate(new PackageCollisionCache.Section(0,0,0),floor.revision()+1);
                check(!atlas.covered(new PackageCollisionCache.Section(0,0,0),floor.revision()),"active view kept CPU coverage");
                gpu.stepWorld(.05f,view);
            }
            var r=read(gpu);check(Math.abs(r.getFloat(4)-5.9216f)<1e-4 && r.getFloat(60)<0,"stale immutable view was reused after revocation");
            atlas.forget(new PackageCollisionCache.Section(0,0,0));atlas.offer(new PackageCollisionCache.Section(0,0,0),floor);uploadWorld(atlas);
            gpu.upload(b,1);atlas.forget(new PackageCollisionCache.Section(0,0,0));
            try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
            r=read(gpu);check(r.getFloat(4)==6 && r.getFloat(60)<0,"missing section treated as air");
            gpu.upload(bodies(0),0);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}check(gpu.count()==0,"zero world workload changed count");
        }
    }
    static void worldFullCapacity() {
        int n=131072;var air=snapshot((s,i)->WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(196,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
            for(int x=0;x<7;x++)for(int y=0;y<4;y++)for(int z=0;z<7;z++)atlas.offer(new PackageCollisionCache.Section(x,y,z),air);
            uploadWorld(atlas);ByteBuffer b=bodies(n);
            for(int i=0;i<n;i++){body(b,i,2+i%64*1.5f,4+i/4096*1.5f,2+i/64%64*1.5f,1);b.putFloat(i*64+16,.25f);}
            gpu.upload(b,n);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
            var r=read(gpu);
            gpu.upload(b,n);try(var view=atlas.view(0,0,0,false)){gpu.stepWorld(.05f,view);}
            var reference=read(gpu);
            for(int i=0;i<n;i++) {
                check(Math.abs(r.getFloat(i*64+4)-(b.getFloat(i*64+4)-.0784f))<1e-5,"full world gravity "+i);
                check(Math.abs(r.getFloat(i*64)-(b.getFloat(i*64)+.01225f))<1e-5,"full world motion "+i);
                check(r.getFloat(i*64+60)>=0,"full world coverage "+i);
                for(int word=0;word<16;word++)check(r.getInt(i*64+word*4)==reference.getInt(i*64+word*4),"coarse/cell full-world parity "+i+"/"+word);
            }
        }
    }
    static void worldRigidSupportAndReplacement() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(4,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,floor,0,0,0);ByteBuffer b=bodies(4);
            for(int i=0;i<4;i++)body(b,i,4,1.5f+i,4,1);
            gpu.upload(b,4);try(var view=atlas.view(0,0,0)){for(int i=0;i<100;i++)gpu.stepWorld(.05f,view);}
            var r=read(gpu);for(int i=0;i<4;i++)check(r.getFloat(i*64+4)>=1.5f,"dynamic contacts diluted rigid world support");
        }
        // Queue world versions and GPU copies without finishing between them. Retired
        // storage cannot be overwritten merely because a new CPU version exists.
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);var section=new PackageCollisionCache.Section(0,0,0);
        cache.request(section);int saved=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,saved);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,12*64,GL15.GL_DYNAMIC_READ);
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,air,0,0,0);ByteBuffer b=bodies(1);
            for(int version=0;version<12;version++) {
                float height=(version&1)==0?.5f:1f;cache.invalidate(section);
                for(int tick=0;tick<3 && cache.snapshot(section)==null;tick++)cache.tick((s,i)->i>>>8==0?shape(height,.6f):WORLD_AIR,1);
                var snapshot=cache.snapshot(section);atlas.invalidate(section,snapshot.revision());atlas.offer(section,snapshot);uploadWorld(atlas);
                body(b,0,4,4,4,1);b.putFloat(20,-100);gpu.upload(b,1);
                try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,gpu.stateBuffer());GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,saved);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,version*64,64);
            }
            var records=readBuffer(saved,12*64);
            for(int version=0;version<12;version++) {
                check(Math.abs(records.getFloat(version*64+4)-((version&1)==0?1f:1.5f))<1e-4,"world version snapshot overwritten");
                check(records.getFloat(version*64+60)>=0,"world version unexpectedly unavailable");
            }
        }finally{GL15.glDeleteBuffers(saved);}
    }
    static void supportProjection() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int n:new int[]{1,32,63,64,65,129}) {
            int top=1+n/16;
            try(var atlas=new PackageCollisionGpu(9*(top+2),1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=top;y++)
                    atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air);
                uploadWorld(atlas);var b=bodies(n);
                for(int i=0;i<n;i++){body(b,i,4,1.5f+i*1.03125f,4,1);b.putFloat(i*64+20,-1);}
                gpu.upload(b,n);
                try(var view=atlas.view(0,0,0)){for(int step=0;step<100;step++)gpu.stepWorld(.05f,view,true,4);}
                var r=read(gpu);var counters=readBuffer(gpu.supportStatsBuffer(),32);
                System.out.println("Support stack "+n+" edges="+counters.getInt(0)+" corrected="+counters.getInt(4)+" rejected="+counters.getInt(8)+" top="+r.getFloat((n-1)*64+4));
                check(counters.getInt(8)==0,"support stack rejected valid bodies");
                for(int i=0;i<n;i++) {
                    check(r.getFloat(i*64+60)>=0,"support projection marked valid stack unsupported");
                    check(Math.abs(r.getFloat(i*64+4)-(1.5f+i))<.002f,"support stack compressed layer "+i+" / "+n);
                    check(r.getFloat(i*64)==4 && r.getFloat(i*64+8)==4,"vertical support moved lateral coordinates");
                }
            }
        }
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,air,0,0,0);var b=bodies(65);for(int i=0;i<65;i++)body(b,i,2+i%7*4,7,2+i/7*3,1);
            gpu.upload(b,65);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
            var reference=read(gpu);gpu.upload(b,65);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
            var r=read(gpu);var counters=readBuffer(gpu.supportStatsBuffer(),32);
            check(counters.getInt(0)==0 && counters.getInt(16)==0 && counters.getInt(20)==1 && counters.getInt(24)==1,"empty support dispatch did not generate zero groups");
            for(int p=0;p<r.limit();p+=4)check(r.getInt(p)==reference.getInt(p),"support-free frame changed body fields");
        }
    }
    // Validation only: rebuild the final-state grid and inspect every candidate pair, rather
    // than only neighbours in the initial fixture. No fixed per-cell neighbour cutoff.
    static final class ContactProbe implements AutoCloseable {
        final int capacity,tableSize,heads,links,stats,grid,probe,drive;
        final int gridCount,probeCount,driveCount,phaseLocation,floorLocation;
        ContactProbe(int capacity) {
            this.capacity=capacity;tableSize=Integer.highestOneBit(Math.max(64,capacity-1))<<1;
            heads=buffer(BufferUtils.createByteBuffer(tableSize*4));links=buffer(BufferUtils.createByteBuffer(capacity*4));
            stats=buffer(BufferUtils.createByteBuffer(32));grid=compute(source("packages/grid.comp"));
            probe=compute(source("packages/state.glsl")+"""
                layout(local_size_x=64) in;
                layout(std430,binding=6) buffer Statistics { uint stats[8]; };
                uniform float uFloor;
                shared uint sums[512];
                void main() {
                    uint i=gl_GlobalInvocationID.x,lane=gl_LocalInvocationID.x;
                    uint row[8]=uint[8](0u,0u,0u,0u,0u,0u,0u,0u);
                    if(i<uCount) {
                        Body b=src[i];bool finite=!(any(isnan(b.positionMass))||any(isinf(b.positionMass))
                            ||any(isnan(b.velocityGround))||any(isinf(b.velocityGround))
                            ||any(isnan(b.extentYaw))||any(isinf(b.extentYaw))
                            ||any(isnan(b.previousSleep))||any(isinf(b.previousSleep)));
                        row[3]=b.previousSleep.w<0?1u:0u;row[4]=finite?0u:1u;
                        row[5]=length(b.velocityGround.xyz)>1e-4?1u:0u;row[7]=1u;
                        float maximum=0.0;
                        if(finite && b.previousSleep.w>=0) {
                            ivec3 cell=cellOf(b.positionMass.xyz);
                            for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                                ivec3 neighbor=cell+ivec3(x,y,z);uint j=heads[hashCell(neighbor)];
                                while(j!=END) {
                                    Body other=src[j];
                                    if(j!=i && other.previousSleep.w>=0 && (b.positionMass.w>0 || other.positionMass.w>0)
                                            && all(equal(cellOf(other.positionMass.xyz),neighbor))) {
                                        vec3 overlap=b.extentYaw.xyz+other.extentYaw.xyz-abs(b.positionMass.xyz-other.positionMass.xyz);
                                        if(all(greaterThan(overlap,vec3(0)))) {
                                            float depth=min(overlap.x,min(overlap.y,overlap.z));maximum=max(maximum,depth);
                                            if(j>i && depth>1e-4)row[0]++;
                                        }
                                    }
                                    j=links[j];
                                }
                            }
                        }
                        row[1]=floatBitsToUint(maximum);row[2]=maximum>1e-4?1u:0u;
                        row[6]=finite?floatBitsToUint(max(0.0,uFloor-b.positionMass.y)):0u;
                    }
                    for(uint k=0u;k<8u;k++)sums[k*64u+lane]=row[k];barrier();
                    for(uint stride=32u;stride>0u;stride>>=1u) {
                        if(lane<stride)for(uint k=0u;k<8u;k++) {
                            uint p=k*64u+lane;
                            sums[p]=(k==1u || k==6u)?max(sums[p],sums[p+stride]):sums[p]+sums[p+stride];
                        }
                        barrier();
                    }
                    if(lane==0u)for(uint k=0u;k<8u;k++) {
                        if(k==1u || k==6u)atomicMax(stats[k],sums[k*64u]);else atomicAdd(stats[k],sums[k*64u]);
                    }
                }
                """);
            drive=compute("#define CMI_BODY_INPLACE 1\n"+source("packages/state.glsl")+"""
                layout(local_size_x=64) in;
                uniform float uPhase;
                void main() {
                    uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;Body b=src[i];
                    if(b.positionMass.w>0 && b.previousSleep.w>=0) {
                        float phase=uPhase+float(i/4096u)*.12;
                        b.velocityGround.x+=.08*cos(phase);b.velocityGround.z+=.06*sin(phase);src[i]=b;
                    }
                }
                """);
            for(int program:new int[]{grid,probe,drive}) {
                GL41.glProgramUniform1ui(program,GL20.glGetUniformLocation(program,"uTableMask"),tableSize-1);
                GL41.glProgramUniform1f(program,GL20.glGetUniformLocation(program,"uCellSize"),2);
            }
            gridCount=GL20.glGetUniformLocation(grid,"uCount");probeCount=GL20.glGetUniformLocation(probe,"uCount");
            driveCount=GL20.glGetUniformLocation(drive,"uCount");phaseLocation=GL20.glGetUniformLocation(drive,"uPhase");
            floorLocation=GL20.glGetUniformLocation(probe,"uFloor");
        }
        void bind(int program,PackagePhysicsGpu gpu) {
            if(gpu.count()>capacity)throw new IllegalArgumentException("Probe capacity");
            GL20.glUseProgram(program);GL30.glUniform1ui(program==grid?gridCount:program==probe?probeCount:driveCount,gpu.count());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,gpu.stateBuffer());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,heads);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,links);
        }
        ByteBuffer inspect(PackagePhysicsGpu gpu){return inspect(gpu,-1e30f);}
        ByteBuffer inspect(PackagePhysicsGpu gpu,float floor) {
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,heads);
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(-1));
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,stats);
                GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
            }
            if(gpu.count()>0) {
                bind(grid,gpu);GL43.glDispatchCompute((gpu.count()+63)/64,1,1);
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                bind(probe,gpu);GL20.glUniform1f(floorLocation,floor);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stats);GL43.glDispatchCompute((gpu.count()+63)/64,1,1);
            }
            return readBuffer(stats,32);
        }
        void drive(PackagePhysicsGpu gpu,int step) {
            if(gpu.count()==0)return;bind(drive,gpu);GL20.glUniform1f(phaseLocation,step*.15f);
            GL43.glDispatchCompute((gpu.count()+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        }
        public void close(){for(int p:new int[]{grid,probe,drive})GL20.glDeleteProgram(p);for(int b:new int[]{heads,links,stats})GL15.glDeleteBuffers(b);}
    }
    static int compute(String text) {
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+text);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            return program;
        } catch(RuntimeException failure){if(program!=0)GL20.glDeleteProgram(program);throw failure;}
        finally{GL20.glDeleteShader(shader);}
    }
    static void contactProbeReference() {
        try(var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source);var probe=new ContactProbe(65)) {
            for(int n:new int[]{0,1,63,64,65}) {
                var b=bodies(n);var random=new Random(567);
                for(int i=0;i<n;i++)body(b,i,random.nextFloat()*5-2.5f,random.nextFloat()*5-2.5f,random.nextFloat()*5-2.5f,i%5==0?0:1);
                gpu.upload(b,n);var actual=probe.inspect(gpu);int pairs=0,penetrating=0;float maximum=0;
                for(int i=0;i<n;i++) {
                    float own=0;
                    for(int j=0;j<n;j++)if(j!=i && (b.getFloat(i*64+12)>0 || b.getFloat(j*64+12)>0)) {
                        float dx=1-Math.abs(b.getFloat(i*64)-b.getFloat(j*64)),dy=1-Math.abs(b.getFloat(i*64+4)-b.getFloat(j*64+4)),
                                dz=1-Math.abs(b.getFloat(i*64+8)-b.getFloat(j*64+8));
                        if(dx>0 && dy>0 && dz>0){float depth=Math.min(dx,Math.min(dy,dz));own=Math.max(own,depth);if(j>i && depth>1e-4)pairs++;}
                    }
                    maximum=Math.max(maximum,own);if(own>1e-4)penetrating++;
                }
                check(actual.getInt(0)==pairs,"GPU contact probe missed or duplicated a pair");
                check(Math.abs(actual.getFloat(4)-maximum)<1e-6,"GPU contact probe penetration maximum differs");
                check(actual.getInt(8)==penetrating,"GPU contact probe body count differs");
                check(actual.getInt(12)==0 && actual.getInt(16)==0 && actual.getInt(28)==n,"GPU contact probe tail/empty count");
                var terrain=probe.inspect(gpu,-1.25f);float depth=0;
                for(int i=0;i<n;i++)depth=Math.max(depth,-1.25f-b.getFloat(i*64+4));
                check(terrain.getFloat(24)==depth,"GPU contact probe plane depth differs");
            }
        }
    }
    static ByteBuffer stackBodies(int n,int width,int depth,boolean staggered) {
        var b=bodies(n);
        for(int i=0;i<n;i++) {
            int layer=i/(width*depth);float shift=staggered && (layer&1)!=0?.4f:0;
            body(b,i,2+i%width*1.03125f+shift,1.5f+layer*1.03125f,2+i/width%depth*1.03125f+shift,1);
            b.putFloat(i*64+20,-1);
        }
        return b;
    }
    static void supportContactCases() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int fixture=0;fixture<3;fixture++) {
            boolean staggered=fixture>0;
            int n=1024;
            try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var probe=new ContactProbe(n)) {
                cubeWorld(atlas,air,floor,0,0,0);var initial=stackBodies(n,8,8,staggered);
                if(fixture==2)for(int i=0;i<n;i++)initial.putFloat(i*64+12,i%3==0?.25f:4f);
                gpu.upload(initial,n);
                try(var view=atlas.view(0,0,0)){for(int step=0;step<100;step++)gpu.stepWorld(.05f,view,true,4);}
                var settled=read(gpu);var stats=probe.inspect(gpu);
                System.out.println("Support "+(fixture==2?"mixed mass":staggered?"staggered":"aligned")+" 1024 pairs="+stats.getInt(0)+" max="+stats.getFloat(4)+" fallbacks="+stats.getInt(12));
                check(stats.getInt(12)==0 && stats.getInt(16)==0,"support fixture hid failure with fallback/nonfinite");
                check(stats.getFloat(4)<.002,"support fixture retains visible inter-body penetration");
                for(int i=0;i<n;i++)check(Math.abs(settled.getFloat(i*64+4)-(1.5f+i/64))<.003,"support fixture height mismatch");
                // Remove the entire plane between generations while retaining GPU bodies.
                atlas.clear();cubeWorld(atlas,air,air,0,0,0);
                try(var view=atlas.view(0,0,0)){for(int step=0;step<10;step++)gpu.stepWorld(.05f,view,true,4);}
                var falling=read(gpu);stats=probe.inspect(gpu);
                System.out.println("Removed support moving="+stats.getInt(20)+" fallback="+stats.getInt(12)+" finite="+stats.getInt(16)+" root="+falling.getFloat(4)+" vy="+falling.getFloat(20)+" top="+falling.getFloat((n-1)*64+4)+" topVy="+falling.getFloat((n-1)*64+20));
                check(stats.getInt(12)==0 && stats.getInt(16)==0 && stats.getInt(20)==n,"removed support left frozen bodies");
                check(stats.getFloat(4)<.002,"removed support compressed stack");
                for(int i=0;i<n;i++)check(falling.getFloat(i*64+4)<settled.getFloat(i*64+4)-2,"removed support left a suspended body");
            }
        }
        // A body waiting for Create handback must not be revived by a support edge.
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source);var probe=new ContactProbe(2)) {
            cubeWorld(atlas,air,air,0,0,0);var b=bodies(2);body(b,0,4,4,4,1);body(b,1,4,4.9f,4,1);b.putFloat(60,-1);
            gpu.upload(b,2);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
            var r=read(gpu);check(r.getFloat(4)==4 && r.getFloat(60)==-1,"support projection revived a handback body");
            check(probe.inspect(gpu).getInt(12)==1,"contact probe omitted handback body");
        }
    }
    static void worldEntryFace() {
        var air=snapshot((s,i)->WORLD_AIR);
        var geometry=snapshot((s,i)->i>>>8==0 || i>>>8==2?shape(1,.6f):WORLD_AIR);
        int program=compute(source("packages/state.glsl")+source("packages/world_collision.glsl")+"""
            layout(local_size_x=64) in;
            void main() {
                uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;Body b=src[i];
                vec3 correction,velocity=b.velocityGround.xyz;bool grounded=false;float friction;
                bool valid=solveWorld(b,correction,velocity,grounded,friction);
                b.positionMass.xyz+=correction;b.velocityGround=vec4(velocity,grounded?1:0);
                b.previousSleep.w=valid?0:-1;dst[i]=b;
            }
            """);
        int[] locations=new int[5];String[] names={"uWorldReady","uWorldOriginSection","uWorldTableMask","uWorldSlotWords","uWorldShapeCapacity"};
        for(int i=0;i<names.length;i++)locations[i]=GL20.glGetUniformLocation(program,names[i]);
        try(var atlas=new PackageCollisionGpu(27,1)) {
            cubeWorld(atlas,air,geometry,0,0,0);
            for(boolean ceiling:new boolean[]{false,true}) {
                int n=65;var b=bodies(n);
                for(int i=0;i<n;i++) {
                    float x=2+i%8*1.03125f,z=2+i/8*1.03125f;
                    body(b,i,x,ceiling?2.9f:.6f,z,1);b.putFloat(i*64+20,ceiling?20:-20);
                    b.putFloat(i*64+48,x).putFloat(i*64+52,1.5f).putFloat(i*64+56,z);
                }
                int input=buffer(b),output=buffer(bodies(n));
                try {
                    GL20.glUseProgram(program);GL30.glUniform1ui(GL20.glGetUniformLocation(program,"uCount"),n);
                    GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,output);
                    try(var view=atlas.view(0,0,0)){view.bind(locations,0,true);GL43.glDispatchCompute(2,1,1);}
                    var r=readBuffer(output,n*64);
                    for(int i=0;i<n;i++) {
                        check(r.getFloat(i*64+60)==0,"deep entry incorrectly treated voxel seams as opposing walls");
                        check(Math.abs(r.getFloat(i*64+4)-1.5f)<1e-4,"deep correction resolved through the wrong world face");
                        check(r.getFloat(i*64)==b.getFloat(i*64) && r.getFloat(i*64+8)==b.getFloat(i*64+8),"world entry changed lateral coordinates");
                        check(r.getFloat(i*64+20)==0,"world entry failed to clip incoming velocity");
                    }
                } finally{GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);}
            }
        } finally{GL20.glDeleteProgram(program);}
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
    static void worldBenchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,scenario,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,atlas_bytes,upload_p50_ms,upload_p95_ms,upload_overruns,upload_calls,uploaded_bytes,fallbacks,moving,terrain_penetration_max,adjacent_overlap_max");
        samples.add("count,scenario,run,sample,gpu_ms,cpu_submit_ms");
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int n:new int[]{10000,65536,131072}) {
            // 343 sections cover the complete 20 warm + 30 measured steps without dropping
            // any candidate. This is a kernel fixture, not the production residency setting.
            try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
                for(String scenario:List.of("legacy_air","world_air_cells","world_air","world_dense_cells","world_dense_contacts")) {
                    boolean world=!scenario.equals("legacy_air"),contacts=scenario.startsWith("world_dense"),coarse=!scenario.endsWith("_cells");
                    try(var atlas=world?new PackageCollisionGpu(343,1):null) {
                        int uploadCalls=0;
                        if(world) {
                            for(int x=0;x<7;x++)for(int y=-3;y<4;y++)for(int z=0;z<7;z++)
                                atlas.offer(new PackageCollisionCache.Section(x,y,z),contacts && y==0?floor:air);
                            while(atlas.stats().pending()!=0) {
                                atlas.pump(PackageCollisionGpu.DEFAULT_UPLOAD_BYTES,PackageCollisionGpu.DEFAULT_UPLOAD_NANOS);
                                if(++uploadCalls>20000)throw new AssertionError("World benchmark upload starved");
                            }
                        }
                        ByteBuffer b=bodies(n);float spacing=contacts?1.03125f:1.5f;
                        for(int i=0;i<n;i++) {
                            body(b,i,2+i%64*spacing,(contacts?1.5f:4)+i/4096*spacing,2+i/64%64*spacing,1);
                            b.putFloat(i*64+16,.25f);if(contacts)b.putFloat(i*64+20,-1);
                        }
                        gpu.upload(b,n);
                        try(var view=world?atlas.view(0,0,0,coarse):null) {
                            for(int warm=0;warm<20;warm++){if(world)gpu.stepWorld(.05f,view);else gpu.step(.05f);}
                        }
                        GL11.glFinish();
                        for(int run=1;run<=3;run++) {
                            gpu.upload(b,n);int[] queries=new int[30];double[] cpu=new double[30],times=new double[30];
                            try(var view=world?atlas.view(0,0,0,coarse):null) {
                                for(int i=0;i<queries.length;i++) {
                                    queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);
                                    long start=System.nanoTime();if(world)gpu.stepWorld(.05f,view);else gpu.step(.05f);
                                    cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                                }
                            }
                            for(int i=0;i<queries.length;i++) {
                                times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[i]);
                                samples.add(n+","+scenario+","+run+","+i+","+times[i]+","+cpu[i]);
                            }
                            var result=read(gpu);int fallback=0,moving=0;double penetration=0,overlap=0;
                            for(int i=0;i<n;i++) {
                                int p=i*64;
                                check(Float.isFinite(result.getFloat(p)) && Float.isFinite(result.getFloat(p+4)) && Float.isFinite(result.getFloat(p+8)),"world benchmark nonfinite");
                                if(result.getFloat(p+60)<0)fallback++;
                                if(Math.abs(result.getFloat(p+16))+Math.abs(result.getFloat(p+20))+Math.abs(result.getFloat(p+24))>.0001f)moving++;
                                if(contacts) {
                                    penetration=Math.max(penetration,1.5-result.getFloat(p+4));
                                    for(int offset:new int[]{1,64,4096})if(i+offset<n) {
                                        int q=(i+offset)*64;double dx=1-Math.abs(result.getFloat(p)-result.getFloat(q)),
                                                dy=1-Math.abs(result.getFloat(p+4)-result.getFloat(q+4)),dz=1-Math.abs(result.getFloat(p+8)-result.getFloat(q+8));
                                        if(dx>0 && dy>0 && dz>0)overlap=Math.max(overlap,Math.min(dx,Math.min(dy,dz)));
                                    }
                                }
                            }
                            check(fallback==0,"world benchmark hid missing coverage with fallback");
                            var stats=world?atlas.stats():null;
                            long bytes=world?343L*2*(PackageCollisionGpu.CELL_BYTES+32)+4L*1024*PackageCollisionGpu.HEAD_BYTES:0;
                            String row=n+","+scenario+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)
                                    +","+bytes+","+(world?stats.p50Nanos()/1e6:0)+","+(world?stats.p95Nanos()/1e6:0)+","+(world?stats.overruns():0)
                                    +","+uploadCalls+","+(world?stats.uploadedBytes():0)+","+fallback+","+moving+","+penetration+","+overlap;
                            rows.add(row);System.out.println(row);
                        }
                    }
                }
            }
        }
        Files.write(Path.of("build/package-world-kernels.csv"),rows);Files.write(Path.of("build/package-world-kernel-samples.csv"),samples);
    }
    static void stackBenchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,scenario,solver,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,support_extra_required_bytes,penetrating_pairs,penetrating_bodies,all_pair_overlap_max,terrain_penetration_max,fallbacks,moving,quality_pass");
        samples.add("count,scenario,solver,run,sample,gpu_ms,cpu_submit_ms");
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int n:new int[]{10000,65536,131072}) {
            // The complete trajectory includes negative X/Z section guards under the
            // alternating drive. Missing coverage is a correct handback, not a solver pass.
            try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
                for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)
                    check(atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air),"stack benchmark world admission");
                uploadWorld(atlas);
                for(String scenario:List.of("aligned_still","staggered_still","staggered_driven")) {
                    var b=stackBodies(n,64,64,!scenario.equals("aligned_still"));boolean driven=scenario.endsWith("driven");
                    for(String policy:List.of("jacobi4","jacobi16","support4"))for(int run=1;run<=3;run++) {
                        boolean support=policy.equals("support4");int iterations=policy.equals("jacobi16")?16:4;
                        gpu.upload(b,n);
                        try(var view=atlas.view(0,0,0)) {
                            for(int warm=0;warm<50;warm++){if(driven)probe.drive(gpu,warm);gpu.stepWorld(.05f,view,support,iterations);}
                        }
                        GL11.glFinish();int[] queries=new int[40];double[] times=new double[40],cpu=new double[40];
                        try(var view=atlas.view(0,0,0)) {
                            for(int i=0;i<queries.length;i++) {
                                queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);long start=System.nanoTime();
                                if(driven)probe.drive(gpu,50+i);gpu.stepWorld(.05f,view,support,iterations);
                                cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                            }
                        }
                        for(int i=0;i<queries.length;i++) {
                            times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[i]);
                            samples.add(n+","+scenario+","+policy+","+run+","+i+","+times[i]+","+cpu[i]);
                        }
                        var stats=probe.inspect(gpu,1.5f);float terrain=stats.getFloat(24);
                        check(stats.getInt(16)==0,"stack benchmark nonfinite results");
                        check(stats.getInt(12)==0,"stack benchmark did not cover complete trajectory: "+n+" "+scenario+" "+policy);
                        boolean quality=stats.getFloat(4)<.002 && terrain<1e-4 && stats.getInt(12)==0;
                        var row=n+","+scenario+","+policy+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)
                                +","+(support?32L*n+32:0)+","+stats.getInt(0)+","+stats.getInt(8)+","+stats.getFloat(4)+","+terrain+","+stats.getInt(12)+","+stats.getInt(20)+","+(quality?1:0);
                        rows.add(row);System.out.println(row);
                        Files.write(Path.of("build/package-stack-kernels.csv"),rows);Files.write(Path.of("build/package-stack-kernel-samples.csv"),samples);
                    }
                }
            }
        }
    }
    static void stackStress() {
        int n=65536;var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
            for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air);
            uploadWorld(atlas);gpu.upload(stackBodies(n,64,64,true),n);
            for(int step=0;step<120;step++) {
                probe.drive(gpu,step);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
                if(step%10==9) {
                    var stats=probe.inspect(gpu);var r=read(gpu);float top=-1e30f;int printed=0;
                    for(int i=0;i<n;i++) {
                        top=Math.max(top,r.getFloat(i*64+4));
                        if(r.getFloat(i*64+60)<0 && printed++<2)System.out.println("Rejected "+i+" xyz="+r.getFloat(i*64)+","+r.getFloat(i*64+4)+","+r.getFloat(i*64+8));
                    }
                    System.out.println("Stress step="+step+" top="+top+" max="+stats.getFloat(4)+" rejected="+stats.getInt(12));
                }
            }
        }
    }
    static void rangeIndexContract() {
        // A larger allocation makes the unused scatter tail a real sentinel test.
        try(var gpu=new PackagePhysicsGpu(131072,2,PackageGpuValidation::source)) {
            for(int n:new int[]{0,1,63,64,65,127,128,129,4097,131072}) {
                var b=stackBodies(n,64,64,true);
                for(int i=0;i<n;i++){b.putFloat(i*64,b.getFloat(i*64)-48);b.putFloat(i*64+8,b.getFloat(i*64+8)-48);}
                gpu.upload(b,n);gpu.rebuildRangeIndex();
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.rangeBodyBuffer());
                try(var s=org.lwjgl.system.MemoryStack.stackPush()){GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,s.ints(-1));}
                gpu.rebuildRangeIndex();
                var table=BufferUtils.createByteBuffer(gpu.rangeTableSize()*16);var indices=BufferUtils.createByteBuffer(131072*4);
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.rangeTableBuffer());GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,table);
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.rangeBodyBuffer());GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,indices);
                var seen=new BitSet(n);int total=0;
                for(int slot=0;slot<gpu.rangeTableSize();slot++) {
                    int p=slot*16,representative=table.getInt(p)-1,count=table.getInt(p+4),start=table.getInt(p+8);
                    check(start==total && count>=0 && count<=n-total,"range scan bounds/prefix");
                    check(table.getInt(p+12)==count,"range scatter count");
                    if(count>0)check(representative>=0 && representative<n,"range representative identity");
                    for(int k=0;k<count;k++) {
                        int index=indices.getInt((start+k)*4);
                        check(index>=0 && index<n && !seen.get(index),"range scatter lost/duplicated an identity");seen.set(index);
                        for(int axis=0;axis<3;axis++)check((int)Math.floor(b.getFloat(index*64+axis*4)/2)==(int)Math.floor(b.getFloat(representative*64+axis*4)/2),"range mixed exact cells");
                    }
                    total+=count;
                }
                check(total==n && seen.cardinality()==n,"range index omitted a qualified body");
                for(int i=n;i<131072;i++)check(indices.getInt(i*4)==-1,"range scatter wrote past active count");
                var state=read(gpu);for(int i=0;i<n*16;i++)check(state.getInt(i*4)==b.getInt(i*4),"range index reordered/changed body state");
            }
        }
        // Deliberate exact-cell hash collisions exhaust the probe budget, never masquerade as empty.
        try(var gpu=new PackagePhysicsGpu(35,2,PackageGpuValidation::source)) {
            var b=bodies(35);for(int i=0;i<35;i++)body(b,i,1+i*512,3,1,1);
            gpu.upload(b,35);gpu.rebuildRangeIndex();var table=BufferUtils.createByteBuffer(gpu.rangeTableSize()*16);
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.rangeTableBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,table);int total=0;
            for(int slot=0;slot<gpu.rangeTableSize();slot++)total+=table.getInt(slot*16+4);
            check(total==32,"collision fixture did not exercise bounded probing");
            var r=read(gpu);int rejected=0;for(int i=0;i<35;i++)if(r.getFloat(i*64+60)<0)rejected++;
            check(rejected>=3,"probe overflow did not request handback");
        }
        // This would require ~17 billion pair visits without the preflight budget.
        int n=131072;
        try(var gpu=new PackagePhysicsGpu(n+1,2,PackageGpuValidation::source)) {
            var b=bodies(n+1);for(int i=0;i<n;i++)body(b,i,4,4,4,1);body(b,n,12,4,4,1);
            for(var mode:List.of(PackagePhysicsGpu.IndexMode.EXACT_RANGES,PackagePhysicsGpu.IndexMode.BOUNDED_LINKED)) {
                gpu.upload(b,n+1);gpu.rebuildIndex(mode);var r=read(gpu);
                for(int i=0;i<n;i++)check(r.getFloat(i*64+60)==-1 && r.getFloat(i*64)==4,"dense cell did not freeze for local handback");
                check(r.getFloat(n*64+60)==0,"dense cell rejected a separate region");
            }
        }
        // Static occupants still count towards queries; they must not be removed to fit a budget.
        try(var gpu=new PackagePhysicsGpu(514,2,PackageGpuValidation::source)) {
            var b=bodies(514);for(int i=0;i<513;i++)body(b,i,4,4,4,0);body(b,513,4,4,4,1);
            for(var mode:List.of(PackagePhysicsGpu.IndexMode.EXACT_RANGES,PackagePhysicsGpu.IndexMode.BOUNDED_LINKED)) {
                gpu.upload(b,514);gpu.rebuildIndex(mode);var r=read(gpu);
                check(r.getFloat(513*64+60)==-1,"dense static occupancy was ignored");
                for(int i=0;i<513;i++)check(r.getFloat(i*64+60)==0,"static collider requested gameplay handback");
            }
        }
        for(var mode:List.of(PackagePhysicsGpu.IndexMode.EXACT_RANGES,PackagePhysicsGpu.IndexMode.BOUNDED_LINKED)) {
            try(var gpu=new PackagePhysicsGpu(513,2,PackageGpuValidation::source)) {
                for(int active:new int[]{511,512,513}) {
                    var b=bodies(active);for(int i=0;i<active;i++)body(b,i,4,4,4,1);
                    gpu.upload(b,active);gpu.rebuildIndex(mode);var r=read(gpu);
                    for(int i=0;i<active;i++)check(r.getFloat(i*64+60)==(active>512?-1:0),"candidate budget boundary");
                }
            }
            try(var gpu=new PackagePhysicsGpu(601,2,PackageGpuValidation::source)) {
                var b=bodies(601);for(int i=0;i<600;i++)body(b,i,i<300?4:6,4,4,1);body(b,600,14,4,4,1);
                gpu.upload(b,601);gpu.rebuildIndex(mode);var r=read(gpu);
                for(int i=0;i<600;i++)check(r.getFloat(i*64+60)==-1,"neighbor-cell aggregate exceeded budget without handback");
                check(r.getFloat(600*64+60)==0,"neighbor-cell overload became global fallback");
            }
            // A sweep can query more than the local 27-cell admission neighborhood.
            var air=snapshot((s,i)->WORLD_AIR);
            try(var gpu=new PackagePhysicsGpu(601,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(27,1)) {
                cubeWorld(atlas,air,air,0,0,0);var b=bodies(601);body(b,0,4,4,4,1);b.putFloat(16,400);
                for(int i=1;i<=600;i++)body(b,i,10+(i-1)/100*2,4,4,0);
                gpu.upload(b,601);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4,mode);}
                var r=read(gpu);check(r.getFloat(60)==-1 && r.getFloat(0)==4,"extended sweep budget silently dropped static contacts");
            }
        }
    }
    static void rangeContactParity() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(var mode:List.of(PackagePhysicsGpu.IndexMode.EXACT_RANGES,PackagePhysicsGpu.IndexMode.BOUNDED_LINKED))
          for(int n:new int[]{1,63,64,65,1024})try(var atlas=new PackageCollisionGpu(27,1);
                var old=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var ranged=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var probe=new ContactProbe(n)) {
            cubeWorld(atlas,air,floor,0,0,0);var b=stackBodies(n,8,8,true);
            for(int i=0;i<n;i++)b.putFloat(i*64+12,i%3==0?.25f:4f);
            old.upload(b,n);ranged.upload(b,n);
            try(var view=atlas.view(0,0,0)){for(int step=0;step<100;step++){old.stepWorld(.05f,view,true,4);ranged.stepWorld(.05f,view,true,4,mode);}}
            var a=read(old);var r=read(ranged);
            for(int i=0;i<n*16;i++)check(Math.abs(a.getFloat(i*4)-r.getFloat(i*4))<.003,"range changed qualified contact/pose results");
            var stats=probe.inspect(ranged,1.5f);
            check(stats.getInt(12)==0 && stats.getInt(16)==0 && stats.getFloat(4)<.002 && stats.getFloat(24)<1e-4,"range contact quality");
            atlas.clear();cubeWorld(atlas,air,air,0,0,0);
            try(var view=atlas.view(0,0,0)){for(int step=0;step<10;step++)ranged.stepWorld(.05f,view,true,4,mode);}
            stats=probe.inspect(ranged);check(stats.getInt(12)==0 && stats.getInt(20)==n && stats.getFloat(4)<.002,"range removed support stayed suspended");
        }
    }
    static void rangeBenchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,scenario,index,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,index_extra_required_bytes,penetrating_pairs,all_pair_overlap_max,terrain_penetration_max,fallbacks,moving,quality_pass");
        samples.add("count,scenario,index,run,sample,gpu_ms,cpu_submit_ms");
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int n:new int[]{10000,65536,131072})try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
            for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air);
            uploadWorld(atlas);
            for(String scenario:List.of("aligned_still","staggered_still","staggered_driven")) {
                var b=stackBodies(n,64,64,!scenario.equals("aligned_still"));boolean driven=scenario.endsWith("driven");
                // Rotate order across repetitions to reduce thermal/order bias.
                var modes=new PackagePhysicsGpu.IndexMode[]{PackagePhysicsGpu.IndexMode.LINKED,PackagePhysicsGpu.IndexMode.BOUNDED_LINKED,PackagePhysicsGpu.IndexMode.EXACT_RANGES};
                for(int run=1;run<=3;run++)for(int order=0;order<3;order++) {
                    var mode=modes[(order+run-1)%3];String index=mode.name().toLowerCase(Locale.ROOT);gpu.upload(b,n);
                    try(var view=atlas.view(0,0,0)){for(int warm=0;warm<50;warm++){if(driven)probe.drive(gpu,warm);gpu.stepWorld(.05f,view,true,4,mode);}}
                    GL11.glFinish();int[] queries=new int[40];double[] times=new double[40],cpu=new double[40];
                    try(var view=atlas.view(0,0,0)) {
                        for(int i=0;i<queries.length;i++) {
                            queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);long start=System.nanoTime();
                            if(driven)probe.drive(gpu,50+i);gpu.stepWorld(.05f,view,true,4,mode);
                            cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        }
                    }
                    for(int i=0;i<queries.length;i++){times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[i]);samples.add(n+","+scenario+","+index+","+run+","+i+","+times[i]+","+cpu[i]);}
                    var stats=probe.inspect(gpu,1.5f);
                    boolean quality=stats.getInt(12)==0 && stats.getInt(16)==0 && stats.getFloat(4)<.002 && stats.getFloat(24)<1e-4;
                    check(quality,"range benchmark quality/fallback: "+n+" "+scenario+" "+index);
                    var row=n+","+scenario+","+index+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+gpu.indexWorkspaceBytes(mode)+","+stats.getInt(0)+","+stats.getFloat(4)+","+stats.getFloat(24)+","+stats.getInt(12)+","+stats.getInt(20)+",1";
                    rows.add(row);System.out.println(row);
                    Files.write(Path.of("build/package-range-kernels.csv"),rows);Files.write(Path.of("build/package-range-kernel-samples.csv"),samples);
                }
            }
        }
    }
    static void supportSustainedMotion() {
        int n=131072;var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
            for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)
                check(atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air),"sustained motion world admission");
            uploadWorld(atlas);gpu.upload(stackBodies(n,64,64,true),n);
            for(var mode:PackagePhysicsGpu.IndexMode.values()) {
              gpu.upload(stackBodies(n,64,64,true),n);
              for(int step=0;step<120;step++) {
                probe.drive(gpu,step);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4,mode);}
                if(step%20==19) {
                    var stats=probe.inspect(gpu,1.5f);
                    check(stats.getInt(28)==n && stats.getInt(12)==0 && stats.getInt(16)==0,"sustained motion omitted or rejected bodies");
                    check(stats.getFloat(4)<.002 && stats.getFloat(24)<1e-4,"sustained motion contact quality failed");
                    check(stats.getInt(20)>n*.99,"sustained motion replaced activity with frozen bodies");
                }
              }
            }
        }
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

    static PackageMovingGeometry.Pose movingPose(double x,double y,double z,double angle,double sx,double sy,double sz) {
        double c=Math.cos(angle),s=Math.sin(angle);
        return new PackageMovingGeometry.Pose(c*sx,0,-s*sx,0,sy,0,s*sz,0,c*sz,x,y,z);
    }
    static final class MovingSource implements PackageMovingCollisionCache.Source {
        final PackageMovingGeometry.Key key=
            new PackageMovingGeometry.Key(0,UUID.randomUUID());
        long revision=1;boolean alive=true;
        List<PackageMovingGeometry.Box> boxes;
        PackageMovingGeometry.Pose previous=movingPose(0,0,0,0,1,1,1),current=previous;
        MovingSource(float x0,float y0,float z0,float x1,float y1,float z1){boxes=List.of(new PackageMovingGeometry.Box(x0,y0,z0,x1,y1,z1,.6f,0));}
        public PackageMovingGeometry.Key key(){return key;}
        public long revision(){return revision;}public boolean alive(){return alive;}
        public PackageMovingGeometry.Bounds bounds(){
            var b=boxes.getFirst();return new PackageMovingGeometry.Bounds(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1());
        }
        public PackageMovingGeometry.Pose pose(boolean old){return old?previous:current;}
        public PackageMovingCollisionCache.Cursor open(){
            check(Thread.currentThread()==captureOwner,"moving world fixture off owner");var rows=boxes.iterator();
            return new PackageMovingCollisionCache.Cursor(){public boolean hasNext(){return rows.hasNext();}public List<PackageMovingGeometry.Box> next(){return List.of(rows.next());}};
        }
        final Thread captureOwner=Thread.currentThread();
    }
    static PackageMovingCollisionCache movingCache(MovingSource...sources){
        var cache=new PackageMovingCollisionCache(Runnable::run,64,()->0L);
        for(var source:sources)check(cache.offer(source),"moving source admission");
        for(int i=0;i<3;i++)cache.tick(1);
        for(var entry:cache.entries())check(entry.snapshot()!=null,"moving geometry ready");return cache;
    }
    static void movingStep(PackagePhysicsGpu gpu,PackageCollisionGpu world,
                           PackageMovingCollisionGpu atlas,
                           PackageMovingCollisionCache cache,
                           PackagePhysicsGpu.IndexMode mode) {
        cache.tick(1);atlas.sync(cache.entries());atlas.pump(262144,Long.MAX_VALUE);
        var moving=atlas.views(cache.entries(),cache.posesReady(),0,0,0);
        try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,mode,moving);}
        finally{atlas.endViews(moving);}
        // Fixture-only synchronization: the production atlas must never wait.
        GL11.glFinish();
    }
    static void movingGeometryContract() {
        var slabs=new ArrayList<PackageMovingGeometry.Box>();
        for(int z=0;z<256;z++)for(int x=0;x<256;x++)slabs.add(new PackageMovingGeometry.Box(x,0,z,x+1,1,z+1,.6f,0));
        var merged=PackageMovingGeometry.bake(1,slabs);
        check(merged.count()==1,"large exact platform not merged");
        var data=merged.nodes();check(data.isReadOnly()&&data.getInt(12)==1&&data.getInt(28)==1,"moving node layout");
        slabs.clear();slabs.add(new PackageMovingGeometry.Box(0,0,0,1,1,1,.6f,0));
        slabs.add(new PackageMovingGeometry.Box(2,0,0,3,1,1,.6f,0));
        check(PackageMovingGeometry.bake(2,slabs).count()==3,"exact merge filled a hole");
        boolean invalid=false;try{new PackageMovingGeometry.Pose(1,0,0,.1,1,0,0,0,1,0,0,0);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"shear must request handback");
        var pose=movingPose(30000000.25,-30000000.5,30000000.75,.7,2,3,4);var bytes=BufferUtils.createByteBuffer(64);
        pose.put(bytes,30000000,-30000000,30000000);
        check(bytes.getFloat(48)==.25f&&bytes.getFloat(52)==-.5f&&bytes.getFloat(56)==.75f,"double local origin precision");
        var jobs=new ArrayList<Runnable>();var source=new MovingSource(2,1,2,14,2,14);
        var cache=new PackageMovingCollisionCache(jobs::add,1,()->0L);
        cache.offer(source);cache.tick(1);check(jobs.size()==1,"moving immutable bake scheduling");
        source.revision++;cache.invalidate(source.key);jobs.removeFirst().run();cache.tick(1);
        check(cache.entries().iterator().next().snapshot()==null,"obsolete moving bake published");check(jobs.size()==1,"moving replacement bake not scheduled");
        jobs.removeFirst().run();cache.tick(1);check(cache.entries().iterator().next().snapshot()!=null,"moving replacement missing");
        cache.tick(0);check(!cache.posesReady(),"zero capture budget retained old pose coverage");
        cache.clear();check(cache.offer(source),"moving clear");cache.tick(1);
        check(cache.entries().iterator().next().identity==2,"moving identities reused");
    }
    static void movingContacts() {
        movingGeometryContract();var air=snapshot((s,i)->WORLD_AIR);
        for(var mode:PackagePhysicsGpu.IndexMode.values())for(int n:new int[]{1,63,64,65}) {
            var source=new MovingSource(-2,1,-2,32,2,32);var cache=movingCache(source);
            try(var atlas=new PackageMovingCollisionGpu();
                var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
                cubeWorld(world,air,air,0,0,0);var bodies=bodies(n);
                for(int i=0;i<n;i++)body(bodies,i,2+i%9*3,2.5f,2+i/9*3,1);gpu.upload(bodies,n);
                movingStep(gpu,world,atlas,cache,mode);
                for(int step=0;step<10;step++){
                    source.previous=source.current;source.current=movingPose((step+1)*.1,(step+1)*.02,0,0,1,1,1);
                    movingStep(gpu,world,atlas,cache,mode);
                }
                var result=read(gpu);for(int i=0;i<n;i++){
                    check(Math.abs(result.getFloat(i*64)-(bodies.getFloat(i*64)+1))<.003,"moving carry x / tail / index "+i+" "+mode+" x="+result.getFloat(i*64)+" y="+result.getFloat(i*64+4)+" vx="+result.getFloat(i*64+16)+" flag="+result.getFloat(i*64+60));
                    check(Math.abs(result.getFloat(i*64+4)-2.7)<.003,"moving carry y "+i);
                    check(result.getFloat(i*64+60)>=0&&result.getFloat(i*64+28)==1,"moving platform contact fallback "+i);
                    check(Math.abs(result.getFloat(i*64+16)-2)<.003,"moving surface velocity "+i);
                }
                // A dirty source must revoke a view that was already opened.
                var views=atlas.views(cache.entries(),true,0,0,0);cache.invalidate(source.key);
                try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,mode,views);}finally{atlas.endViews(views);}
                check(read(gpu).getFloat(60)<0,"old geometry view survived CPU invalidation");
            }
        }
        // Translating wall crosses a body even though both endpoint boxes miss it.
        for(float speed:new float[]{4,8}) {
            var source=new MovingSource(-.25f,-2,-2,.25f,2,2);
            source.previous=movingPose(2,5,5,0,1,1,1);source.current=movingPose(2+speed,5,5,0,1,1,1);var cache=movingCache(source);
            try(var atlas=new PackageMovingCollisionGpu();
                var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
                cubeWorld(world,air,air,0,0,0);var bodies=bodies(1);body(bodies,0,4,5,5,1);gpu.upload(bodies,1);
                movingStep(gpu,world,atlas,cache,PackagePhysicsGpu.IndexMode.LINKED);var result=read(gpu);
                check(result.getFloat(60)>=0,"moving CCD unexpected handback");
                check(result.getFloat(0)>=2+speed+.749f,"moving wall tunneled "+result.getFloat(0));
            }
        }
        // OBB SAT: a rotated long box's AABB corner must stay empty.
        var source=new MovingSource(-3,-1,-.1f,3,1,.1f);source.previous=source.current=movingPose(6,5,6,Math.PI/4,1,1,1);var cache=movingCache(source);
        try(var atlas=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);var bodies=bodies(1);body(bodies,0,7.5f,5,7.5f,1);gpu.upload(bodies,1);
            movingStep(gpu,world,atlas,cache,PackagePhysicsGpu.IndexMode.LINKED);var result=read(gpu);
            check(result.getFloat(60)>=0&&Math.abs(result.getFloat(0)-7.5)<1e-5&&Math.abs(result.getFloat(8)-7.5)<1e-5,"OBB empty corner collision");
            // An unknown scene always hands back rather than simulating against air.
            gpu.upload(bodies,1);var views=atlas.unavailableViews();
            try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,PackagePhysicsGpu.IndexMode.LINKED,views);}finally{atlas.endViews(views);}
            check(read(gpu).getFloat(60)<0,"unknown moving scene treated as air");
        }
        System.out.println("Moving collision fixtures passed");
    }


    static PackageMovingGeometry.Pose arbitraryPose(double ax,double ay,double az,double sx,double sy,double sz) {
        var r=new org.joml.Matrix3d().rotateXYZ(ax,ay,az).scale(sx,sy,sz);
        return new PackageMovingGeometry.Pose(r.m00,r.m01,r.m02,r.m10,r.m11,r.m12,r.m20,r.m21,r.m22,6,6,6);
    }
    static double referenceMovingGap(float x,float y,float z,float ex,float ey,float ez,MovingSource source) {
        var p=source.current;var b=source.boxes.getFirst();var centre=p.transform((b.x0()+b.x1())*.5,(b.y0()+b.y1())*.5,(b.z0()+b.z1())*.5);
        var delta=new org.joml.Vector3d(x,y,z).sub(centre);var columns=new org.joml.Vector3d[]{new org.joml.Vector3d(p.xx(),p.xy(),p.xz()),new org.joml.Vector3d(p.yx(),p.yy(),p.yz()),new org.joml.Vector3d(p.zx(),p.zy(),p.zz())};
        var world=new org.joml.Vector3d[]{new org.joml.Vector3d(0,1,0),new org.joml.Vector3d(1,0,0),new org.joml.Vector3d(0,0,1)};
        var axes=new ArrayList<org.joml.Vector3d>();Collections.addAll(axes,world);Collections.addAll(axes,columns);
        for(var a:world)for(var c:columns)axes.add(new org.joml.Vector3d(a).cross(c));
        double gap=-Double.MAX_VALUE;double[] half={(b.x1()-b.x0())*.5,(b.y1()-b.y0())*.5,(b.z1()-b.z0())*.5};
        for(var axis:axes){if(axis.lengthSquared()<1e-10)continue;var a=new org.joml.Vector3d(axis).normalize();double radius=Math.abs(a.x)*ex+Math.abs(a.y)*ey+Math.abs(a.z)*ez;
            for(int k=0;k<3;k++)radius+=Math.abs(a.dot(columns[k]))*half[k];gap=Math.max(gap,Math.abs(a.dot(delta))-radius);}
        return gap;
    }
    static void movingReference() {
        var air=snapshot((s,i)->WORLD_AIR);var random=new Random(628142);
        var source=new MovingSource(-2,-1.1f,-.25f,2,1.1f,.25f);var cache=movingCache(source);
        try(var atlas=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);
            for(int test=0;test<150;test++){
                source.previous=source.current=arbitraryPose(random.nextDouble()*6.28,random.nextDouble()*6.28,random.nextDouble()*6.28,.5+random.nextDouble()*2,.5+random.nextDouble()*2,.5+random.nextDouble()*2);
                var bodies=bodies(1);float x=6+(random.nextFloat()-.5f)*7,y=6+(random.nextFloat()-.5f)*7,z=6+(random.nextFloat()-.5f)*7;
                body(bodies,0,x,y,z,1);bodies.putFloat(32,.25f).putFloat(36,.3f).putFloat(40,.4f);gpu.upload(bodies,1);
                double before=referenceMovingGap(x,y-.0784f,z,.25f,.3f,.4f,source);
                movingStep(gpu,world,atlas,cache,PackagePhysicsGpu.IndexMode.LINKED);var result=read(gpu);
                check(result.getFloat(60)>=0,"random OBB unexpected fallback "+test);
                check(referenceMovingGap(result.getFloat(0),result.getFloat(4),result.getFloat(8),.25f,.3f,.4f,source)>-3e-4,"random OBB remained penetrated "+test);
                if(before>.001)check(Math.abs(result.getFloat(0)-x)<3e-5&&Math.abs(result.getFloat(8)-z)<3e-5,"random OBB false positive "+test);
            }
            source.previous=movingPose(6,6,6,0,1,1,1);source.current=movingPose(6,6,6,Math.PI/2,1,1,1);
            var bodies=bodies(1);body(bodies,0,7.3f,6,4.7f,1);bodies.putFloat(32,.15f).putFloat(36,.2f).putFloat(40,.15f);gpu.upload(bodies,1);
            movingStep(gpu,world,atlas,cache,PackagePhysicsGpu.IndexMode.LINKED);var result=read(gpu);
            check(result.getFloat(60)>=0,"rotating CCD handback");
            check(Math.abs(result.getFloat(0)-7.3)>1e-3||Math.abs(result.getFloat(8)-4.7)>1e-3,"rotating wall missed intermediate pose");
            source.boxes=List.of(new PackageMovingGeometry.Box(-4,0,-4,4,1,4,.6f,0));source.revision++;cache.invalidate(source.key);cache.tick(1);cache.tick(1);
            source.previous=source.current=movingPose(6,4,6,0,1,1,1);body(bodies,0,8,5.5f,6,1);bodies.putFloat(32,.5f).putFloat(36,.5f).putFloat(40,.5f);gpu.upload(bodies,1);
            movingStep(gpu,world,atlas,cache,PackagePhysicsGpu.IndexMode.LINKED);
            for(int step=1;step<=12;step++){source.previous=source.current;source.current=movingPose(6,4,6,step*.025,1+step*.01,1,1);movingStep(gpu,world,atlas,cache,PackagePhysicsGpu.IndexMode.LINKED);}
            result=read(gpu);var expected=source.current.transform(2,1,0);
            check(result.getFloat(60)>=0,"rotating carried body fallback");
            check(Math.abs(result.getFloat(0)-expected.x)<.015&&Math.abs(result.getFloat(8)-expected.z)<.015,"rotating/scaled support drift");
        }
    }


    static void movingWorld(PackageCollisionGpu world,boolean staticFloor) {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)
            check(world.offer(new PackageCollisionCache.Section(x,y,z),staticFloor&&y==0?floor:air),"moving full world admission");
        uploadWorld(world);
    }
    static MovingSource[] movingPlatforms(int count) {
        var sources=new MovingSource[count];
        for(int i=0;i<count;i++){int width=count==16?4:1;float span=72f/width;
            sources[i]=new MovingSource(i%width*span-1,0,i/width*span-1,(i%width+1)*span-1,1,(i/width+1)*span-1);}
        return sources;
    }
    static void movingCapacity() {
        int n=131072;var source=movingPlatforms(1);var cache=movingCache(source);
        try(var moving=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(448,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var probe=new ContactProbe(n)){
            movingWorld(world,false);gpu.upload(stackBodies(n,64,64,true),n);
            for(int frame=0;frame<60;frame++){
                source[0].previous=source[0].current;source[0].current=movingPose((frame+1)*.003,0,0,0,1,1,1);
                probe.drive(gpu,frame);movingStep(gpu,world,moving,cache,PackagePhysicsGpu.IndexMode.LINKED);
                if(frame%10==9){var stats=probe.inspect(gpu,1.5f);
                    check(stats.getInt(28)==n&&stats.getInt(12)==0&&stats.getInt(16)==0,"moving capacity omitted/nonfinite/fallback");
                    check(stats.getFloat(4)<.002&&stats.getFloat(24)<1e-4,"moving capacity overlap/plane frame="+frame+" overlap="+stats.getFloat(4)+" plane="+stats.getFloat(24));
                    check(stats.getInt(20)>n*.99,"moving capacity simulated sleeping stand-ins");
                }
            }
            check(moving.uploadedBytes()==48,"rigid motion rebuilt local geometry");
        }
    }
    static void movingBenchmark() throws Exception {
        var rows=new ArrayList<String>();rows.add("count,structures,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,pose_capture_p50_ms,pose_capture_p95_ms,geometry_uploaded_bytes,fallbacks,moving,all_pair_overlap_max,plane_penetration_max");
        var samples=new ArrayList<String>();samples.add("count,structures,run,sample,gpu_ms,cpu_submit_ms,pose_capture_ms");
        for(int n:new int[]{10000,65536,131072})for(int structures:new int[]{0,1,16}) {
            var sources=movingPlatforms(structures);var cache=movingCache(sources);
            try(var moving=new PackageMovingCollisionGpu();
                var world=new PackageCollisionGpu(448,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var probe=new ContactProbe(n)){
                movingWorld(world,structures==0);moving.sync(cache.entries());moving.pump(262144,Long.MAX_VALUE);
                for(int run=0;run<=3;run++){
                    gpu.upload(stackBodies(n,64,64,true),n);int count=run==0?20:30;
                    double[] cpu=new double[count],times=new double[count],capture=new double[count];int[] queries=new int[count];
                    for(int frame=0;frame<count;frame++){
                        for(var source:sources){source.previous=frame==0?movingPose(0,0,0,0,1,1,1):source.current;source.current=movingPose((frame+1)*.003,0,0,0,1,1,1);}
                        long captureStart=System.nanoTime();cache.tick(1);capture[frame]=(System.nanoTime()-captureStart)/1e6;
                        probe.drive(gpu,frame);queries[frame]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[frame]);long submit=System.nanoTime();
                        var views=moving.views(cache.entries(),cache.posesReady(),0,0,0);
                        try(var view=world.view(0,0,0)){
                            if(structures==0)gpu.stepWorld(.05f,view,true,4);
                            else gpu.stepWorldMoving(view,4,PackagePhysicsGpu.IndexMode.LINKED,views);
                        }finally{moving.endViews(views);}
                        cpu[frame]=(System.nanoTime()-submit)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        // Timing reads occur after the submitted interval. They keep
                        // synthetic pose banks available, never enter runtime code.
                        times[frame]=GL33.glGetQueryObjectui64(queries[frame],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[frame]);
                        if(run>0)samples.add(n+","+structures+","+run+","+frame+","+times[frame]+","+cpu[frame]+","+capture[frame]);
                    }
                    if(run==0)continue;var stats=probe.inspect(gpu,1.5f);
                    check(stats.getInt(28)==n&&stats.getInt(12)==0&&stats.getInt(16)==0,"moving benchmark omitted/fallback/nonfinite n="+n+" structures="+structures+" rejected="+stats.getInt(12));
                    check(stats.getFloat(4)<.002&&stats.getFloat(24)<1e-4,"moving benchmark quality n="+n+" structures="+structures+" overlap="+stats.getFloat(4)+" plane="+stats.getFloat(24));
                    check(stats.getInt(20)>n*.99,"moving benchmark frozen stand-ins");
                    var row=n+","+structures+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+percentile(capture,.5)+","+percentile(capture,.95)+","+moving.uploadedBytes()+","+stats.getInt(12)+","+stats.getInt(20)+","+stats.getFloat(4)+","+stats.getFloat(24);
                    rows.add(row);System.out.println(row);
                }
            }
        }
        Files.write(Path.of("build/package-moving-kernels.csv"),rows);Files.write(Path.of("build/package-moving-kernel-samples.csv"),samples);
    }


    static void movingLifecycle() {
        var air=snapshot((s,i)->WORLD_AIR);var source=new MovingSource(2,1,2,14,2,14);var cache=movingCache(source);
        var complete=new java.util.concurrent.atomic.AtomicBoolean();
        try(var moving=new PackageMovingCollisionGpu(fence->complete.get()?GL32.GL_ALREADY_SIGNALED:GL32.GL_TIMEOUT_EXPIRED);
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);var body=bodies(1);body(body,0,4,2.5f,4,1);
            for(int frame=0;frame<5;frame++){
                gpu.upload(body,1);movingStep(gpu,world,moving,cache,PackagePhysicsGpu.IndexMode.LINKED);
                check((read(gpu).getFloat(60)<0)==(frame==4),"four moving banks overwritten or waited");
            }
            check(moving.skippedViews()==1,"moving full-ring accounting");complete.set(true);gpu.upload(body,1);
            movingStep(gpu,world,moving,cache,PackagePhysicsGpu.IndexMode.LINKED);check(read(gpu).getFloat(60)>=0,"completed moving bank did not recover");
            var views=moving.views(cache.entries(),true,0,0,0);boolean rejected=false;
            try(var view=world.view(0,0,0)){try{gpu.stepWorldMoving(view,4,PackagePhysicsGpu.IndexMode.LINKED,views);}catch(IllegalStateException duplicate){rejected=true;}}finally{moving.endViews(views);}
            check(rejected,"same tick applied twice through a new view");
            gpu.upload(body,1);views=moving.views(cache.entries(),true,0,0,0);cache.clear();
            try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,PackagePhysicsGpu.IndexMode.LINKED,views);}finally{moving.endViews(views);}
            check(read(gpu).getFloat(60)<0,"world clear retained an old moving identity");
        }
        source=new MovingSource(2,1,2,14,2,14);cache=movingCache(source);
        try(var moving=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);var body=bodies(1);body(body,0,20,5,20,1);gpu.upload(body,1);moving.sync(cache.entries());
            // No pump: source geometry exists on CPU but was never made visible.
            var views=moving.views(cache.entries(),true,0,0,0);
            try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,PackagePhysicsGpu.IndexMode.LINKED,views);}finally{moving.endViews(views);}
            check(read(gpu).getFloat(60)<0,"unuploaded shape treated as bounded air");
            source.boxes=List.of(new PackageMovingGeometry.Box(2,1,2,14,2,14,.6f,PackageCollisionCache.UNSUPPORTED));
            source.revision++;cache.invalidate(source.key);cache.tick(1);cache.tick(1);body(body,0,4,2.5f,4,1);gpu.upload(body,1);
            movingStep(gpu,world,moving,cache,PackagePhysicsGpu.IndexMode.LINKED);check(read(gpu).getFloat(60)<0,"callback/hazard box accepted");
        }
        var ceiling=snapshot((s,i)->i>>>8==4?shape(1,.6f):WORLD_AIR);
        source=new MovingSource(2,0,2,14,1,14);cache=movingCache(source);
        try(var moving=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,ceiling,0,0,0);var body=bodies(1);body(body,0,4,1.5f,4,1);gpu.upload(body,1);
            movingStep(gpu,world,moving,cache,PackagePhysicsGpu.IndexMode.LINKED);
            source.previous=source.current;source.current=movingPose(0,4,0,0,1,1,1);
            movingStep(gpu,world,moving,cache,PackagePhysicsGpu.IndexMode.LINKED);check(read(gpu).getFloat(60)<0,"moving/static terrain crush must hand back");
        }
    }
    static void movingFriction() {
        var air=snapshot((s,i)->WORLD_AIR);
        for(float friction:new float[]{0,.6f,.98f,1.2f})for(boolean seam:new boolean[]{false,true}){
            var first=new MovingSource(-1,0,-1,seam?5:15,1,15);first.boxes=List.of(new PackageMovingGeometry.Box(-1,0,-1,seam?5:15,1,15,friction,0));
            var second=new MovingSource(5,0,-1,15,1,15);second.boxes=List.of(new PackageMovingGeometry.Box(5,0,-1,15,1,15,friction,0));
            var cache=seam?movingCache(first,second):movingCache(first);
            try(var moving=new PackageMovingCollisionGpu();var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
                cubeWorld(world,air,air,0,0,0);var body=bodies(1);body(body,0,4.7f,1.5f,4,1);body.putFloat(16,1);gpu.upload(body,1);
                movingStep(gpu,world,moving,cache,PackagePhysicsGpu.IndexMode.LINKED);var result=read(gpu);
                check(result.getFloat(60)>=0,"moving friction handback");check(Math.abs(result.getFloat(16)-.98f*friction)<1e-5,"moving friction applied more than once or clamped: "+friction+" seam="+seam+" vx="+result.getFloat(16));
            }
        }
    }

    public static void main(String[] args)throws Exception {
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err);callback.set();
        check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Package GPU validation",0,0);check(window!=0,"GL context");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            movingContacts();movingReference();movingLifecycle();movingFriction();movingCapacity();if(Arrays.asList(args).contains("--moving-benchmark"))movingBenchmark();if(Arrays.asList(args).contains("--moving-only")){check(GL11.glGetError()==GL11.GL_NO_ERROR,"moving GL error");System.out.println("Package moving GPU: "+checks+" assertions passed");return;}
            sourceContract();boundaries();contact();sweep();worldUploadVersions();worldShapes();worldSweepsAndMaterials();worldMissingAndInvalidated();worldFullCapacity();worldRigidSupportAndReplacement();supportProjection();contactProbeReference();supportContactCases();worldEntryFace();supportSustainedMotion();chain();chainReference();readbacks();pool();render();previewLoad();poseParity();deltas();deltaOverflowAndIdentity();deltaQuantizationLimits();deltaYawTies();channelRoundTrip();channelLifecycle();channelImmutableAndPartialTransport();if(Arrays.asList(args).contains("--benchmark")){benchmark();deltaBenchmark();}
            if(Arrays.asList(args).contains("--delta-pipeline-benchmark"))pipelineBenchmark();
            if(Arrays.asList(args).contains("--world-benchmark"))worldBenchmark();
            rangeIndexContract();rangeContactParity();
            if(Arrays.asList(args).contains("--range-benchmark"))rangeBenchmark();
            if(Arrays.asList(args).contains("--stack-benchmark"))stackBenchmark();
            if(Arrays.asList(args).contains("--stack-stress"))stackStress();
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error");System.out.println("Package GPU: "+checks+" assertions passed");
        }finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
