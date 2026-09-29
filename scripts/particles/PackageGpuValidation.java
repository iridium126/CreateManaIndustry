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
        }finally{GL15.glDeleteBuffers(source);}
    }
    static int buffer(ByteBuffer bytes) {
        int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return id;
    }
    static ByteBuffer readBuffer(int id,int bytes) {
        ByteBuffer result=BufferUtils.createByteBuffer(bytes);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);return result;
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
    public static void main(String[] args)throws Exception {
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err);callback.set();
        check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Package GPU validation",0,0);check(window!=0,"GL context");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            sourceContract();boundaries();contact();sweep();chain();chainReference();readbacks();pool();render();previewLoad();poseParity();if(Arrays.asList(args).contains("--benchmark"))benchmark();
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error");System.out.println("Package GPU: "+checks+" assertions passed");
        }finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
