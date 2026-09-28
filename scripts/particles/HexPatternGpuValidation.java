import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import at.petrak.hexcasting.api.addldata.ADPigment;
import at.petrak.hexcasting.api.pigment.ColorProvider;
import net.minecraft.world.phys.Vec3;

/** Real-driver checks. Run with the project's runtime classpath and LWJGL natives. */
public class HexPatternGpuValidation {
    static final Path ROOT = Path.of("src/main/resources/assets/createmanaindustry/shaders/particles");
    static String prelude;
    static int checks;
    static ByteBuffer bytes(int size) { return BufferUtils.createByteBuffer(size); }
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static String source(String name) throws Exception {
        String text = Files.readString(ROOT.resolve(name));
        text = text.replace("#pragma cmi_types spawn", com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks("spawn"))
                .replace("#pragma cmi_types update", com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks("update"));
        Matcher m = Pattern.compile("(?m)^\\s*#pragma cmi_include (\\S+)\\s*$").matcher(text);
        StringBuffer output = new StringBuffer();
        while (m.find()) m.appendReplacement(output, Matcher.quoteReplacement(source(m.group(1))));
        m.appendTail(output);
        return output.toString();
    }
    static int stage(String name, int type) throws Exception { return stageText(name, type, source(name)); }
    static int stageText(String name, int type, String body) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, "#version 450 core\n" + prelude + body);
        GL20.glCompileShader(shader);
        check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0, name + ": " + GL20.glGetShaderInfoLog(shader));
        return shader;
    }
    static int link(int... shaders) {
        int program = GL20.glCreateProgram();
        for (int shader : shaders) GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(program));
        for (int shader : shaders) GL20.glDeleteShader(shader);
        return program;
    }
    static int compute(String name) throws Exception { return link(stage(name, GL43.GL_COMPUTE_SHADER)); }
    static int buffer(int binding, ByteBuffer data) {
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, buffer);
        return buffer;
    }
    static void upload(int buffer, ByteBuffer data) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, data);
    }
    static void dispatch(int program, int x) {
        GL20.glUseProgram(program);
        GL43.glDispatchCompute(x,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
        check(GL11.glGetError() == GL11.GL_NO_ERROR, "GL dispatch error");
    }
    static void colors() throws Exception {
        String kernel = source("chunks/hex_pattern.glsl") + "\nlayout(local_size_x=64) in;\nlayout(std430,binding=29) writeonly buffer Result{vec4 c[];};\nvoid main(){uint i=gl_GlobalInvocationID.x;if(i<64u)c[i]=hexPatternColor(i);}";
        int program = link(stageText("pigment parity", GL43.GL_COMPUTE_SHADER, kernel));
        ByteBuffer inputs = bytes((20481+1)*16), resources = bytes(64*16), out = bytes(64*16);
        int inputBuffer = buffer(24,inputs), resourceBuffer = buffer(26,resources), result = buffer(29,out);
        buffer(25,bytes(4096*4));
        int[][] palettes = {{0xffab65eb},{0xff000000},{0xff101010},{0xff54398a,0xffcfa0f3,0xfffecbe6,0xffcfa0f3,0xffe77c56},
            {0xffeb92ea,0xffffffff,0xff6ac2e4},{0xff123456,0xffabcdef},{0xff302f30,0xff000000}};
        float[] times = {0,0.25f,399.99f,400,600,10000,1000000,10000000};
        Random random = new Random(28);
        Vec3[] positions = new Vec3[64];
        for (int i=0;i<64;i++) positions[i]=new Vec3((float)(random.nextDouble()*6-3),(float)(random.nextDouble()*6-3),(float)(random.nextDouble()*6-3));
        for (int[] palette : palettes) for (float divisor : new float[]{0,400,600}) for (float time : times) {
            for (int i=0;i<palette.length;i++) {
                int c=palette[i]; resources.putFloat(i*16,(c>>16)&255).putFloat(i*16+4,(c>>8)&255).putFloat(i*16+8,c&255);
            }
            inputs.putFloat(8,time);
            ColorProvider provider = new ColorProvider() {
                protected int getRawColor(float t, Vec3 pos) {
                    return divisor == 0 ? palette[0] : ADPigment.morphBetweenColors(palette,new Vec3(0.1,0.1,0.1),t/divisor,pos);
                }
            };
            for(int i=0;i<64;i++) {
                int b=(1+i*5)*16; Vec3 pos=positions[i];
                inputs.putFloat(b+16,i+1);
                inputs.putFloat(b+32,(float)pos.x).putFloat(b+36,(float)pos.y).putFloat(b+40,(float)pos.z);
                inputs.putInt(b+48,0).putInt(b+52,palette.length).putFloat(b+56,divisor);
            }
            upload(inputBuffer,inputs);upload(resourceBuffer,resources);dispatch(program,1);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,result);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,out);
            for(int i=0;i<64;i++) {
                int expected=provider.getColor(time,positions[i]);
                for(int channel=0;channel<3;channel++) {
                    int actual=Math.round(out.getFloat(i*16+channel*4)*255);
                    int wanted=(expected >> (16-channel*8))&255;
                    check(Math.abs(actual-wanted)<=1,"pigment mismatch time="+time+" divisor="+divisor+" got="+actual+" expected="+wanted);
                }
                float alpha=i+1<=60 ? (float)Math.floor((i+1)/60f*255)/255 : 1;
                check(Math.abs(alpha-out.getFloat(i*16+12))<0.00001,"fade alpha mismatch");
            }
        }
        // Already corrected third-party sample: black must stay black, without another minimum-luminance pass.
        for(int i=0;i<64;i++) inputs.putFloat((1+i*5)*16+56,-1).putInt((1+i*5)*16+44,0xff000000);
        upload(inputBuffer,inputs);dispatch(program,1);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,result);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,out);
        check(out.getFloat(0)==0 && out.getFloat(4)==0 && out.getFloat(8)==0,"fallback corrected twice");
        System.out.println("GPU pigment parity: built-in palette formulas, dark colors, long times, fades and fallback passed");
    }
    static void poolAndSort() throws Exception {
        ByteBuffer inputs=bytes((20481+1)*16), resources=bytes(16), live=bytes(4096*4);
        inputs.putFloat(0,3);
        for(int i=0;i<3;i++) {
            int b=(1+i*5)*16;
            inputs.putInt(b,4096+i).putFloat(b+16,100).putFloat(b+28,3.5f);
            inputs.putFloat(b+56,-1).putInt(b+44,0xffabcdef);
        }
        inputs.putFloat(20481*16,10).putFloat(20481*16+4,20).putFloat(20481*16+8,30);
        int inputBuffer=buffer(24,inputs), liveBuffer=buffer(25,live);buffer(26,resources);
        ByteBuffer particleData=bytes(4*64), counter=bytes(16);
        int output=buffer(1,particleData), counterBuffer=buffer(3,counter);
        int reconcile=compute("hex_reconcile.comp");
        GL20.glUseProgram(reconcile);GL30.glUniform1ui(GL20.glGetUniformLocation(reconcile,"uCapacity"),4);GL30.glUniform1ui(GL20.glGetUniformLocation(reconcile,"uEmitter"),0);
        dispatch(reconcile,1);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counterBuffer);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,counter);
        check(counter.getInt(0)==3,"one particle per holder");
        dispatch(reconcile,1);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counterBuffer);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,counter);
        check(counter.getInt(0)==3,"reconcile duplicated existing holder");
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,output);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,particleData);
        for(int i=0;i<3;i++) check(particleData.getFloat(i*64)==10 && particleData.getFloat(i*64+4)==20,"anchor at emission");
        // Reuse metadata slot 1 with another generation; old particle must disappear.
        inputs.putInt((1+5)*16,8193).putFloat(20481*16,40);
        upload(inputBuffer,inputs);
        int inputPool=buffer(0,particleData);
        counter.putInt(0,0);upload(counterBuffer,counter);upload(liveBuffer,bytes(4096*4));
        ByteBuffer emitter=bytes(20*16);emitter.putFloat(7*16,4);buffer(5,emitter);
        ByteBuffer previous=bytes(16);previous.putInt(0,3);buffer(14,previous);
        for(int binding:new int[]{11,13,16,18,19,21}) buffer(binding,bytes(65536));
        int update=compute("update.comp");GL20.glUseProgram(update);
        GL30.glUniform1ui(GL20.glGetUniformLocation(update,"uCapacity"),4);
        GL30.glUniform1ui(GL20.glGetUniformLocation(update,"uKillEmit"),-1);
        GL20.glUniform1f(GL20.glGetUniformLocation(update,"uDt"),0.05f);
        dispatch(update,1);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counterBuffer);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,counter);
        check(counter.getInt(0)==2,"stale generation survived compaction");
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,output);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,particleData);
        Set<Integer> handles=new HashSet<>();
        for(int i=0;i<2;i++) {handles.add(particleData.getInt(i*64+56));check(particleData.getFloat(i*64)==40,"anchor update lagged");}
        check(handles.equals(Set.of(4096,4098)),"handle changed during pool compaction");
        dispatch(reconcile,1);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counterBuffer);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,counter);
        check(counter.getInt(0)==3,"new generation was not emitted");
        ByteBuffer indirect=bytes(7*20);indirect.putInt(21*4,2).putInt(26*4,2).putInt(31*4,2);buffer(2,indirect);
        ByteBuffer unsorted=bytes(6*8);int[] keys={530,50,280,512,10,260};
        for(int i=0;i<6;i++)unsorted.putInt(i*8,keys[i]).putInt(i*8+4,i);
        buffer(6,unsorted);int sortResult=buffer(7,bytes(48));buffer(9,bytes(1024*4));buffer(10,bytes(1024*4+4));
        dispatch(compute("radix_hist.comp"),1);dispatch(compute("radix_scan.comp"),1);dispatch(compute("radix_scatter.comp"),1);
        ByteBuffer sorted=bytes(48);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,sortResult);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,sorted);
        Arrays.sort(keys);for(int i=0;i<6;i++)check(keys[i]==sorted.getInt(i*8),"three-way depth partition corrupt");
        System.out.println("Pool reconciliation, generation rejection, compaction, anchors and three-way sort passed");
    }

    static void read(int id, ByteBuffer data) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
        data.clear(); GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
    }
    static void uint(int program,String name,int value) {
        GL20.glUseProgram(program);GL30.glUniform1ui(GL20.glGetUniformLocation(program,name),value);
    }
    static void dispatchBounds() throws Exception {
        int program=compute("prepare_dispatch.comp");
        ByteBuffer previous=bytes(16), current=bytes(16), commands=bytes(140), args=bytes(36);
        int prev=buffer(14,previous), cur=buffer(3,current), cmd=buffer(2,commands), out=buffer(29,args);
        uint(program,"uCapacity",129);
        for(int n:new int[]{0,1,63,64,65,128,129,130,1000000}) {
            previous.putInt(0,n); current.putInt(0,n).putInt(12,n); commands.putInt(16*4,20000);
            upload(prev,previous); upload(cur,current);upload(cmd,commands);
            for(int phase=0;phase<3;phase++) {
                uint(program,"uPhase",phase);dispatch(program,1);read(out,args);
                int expected=(Math.min(n,129)+63)/64;
                check(args.getInt(phase*12)==expected,"GPU dispatch bound "+phase+" / "+n);
                check(args.getInt(phase*12+4)==1 && args.getInt(phase*12+8)==1,"dispatch dimensions");
            }
            read(cur,current);check(current.getInt(0)==Math.min(n,129),"published live count");
            read(cmd,commands);check(commands.getInt(16*4)==16384,"carrier count clamp");
        }
        for(int id:new int[]{prev,cur,cmd,out})GL15.glDeleteBuffers(id);
        GL20.glDeleteProgram(program);
        System.out.println("GPU dispatch bounds: empty, tails, saturation and carrier capacity passed");
    }
    static void capacity() throws Exception {
        int cap=65;
        ByteBuffer pool=bytes((cap+1)*64), counts=bytes(16), command=bytes(48), header=bytes(320);
        for(int i=cap*64;i<pool.capacity();i+=4)pool.putInt(i,0x5a5a5a5a);
        header.putFloat(4*3,1).putFloat(5*16,10).putFloat(5*16+4,10);
        header.putFloat(5*16+8,1).putFloat(5*16+12,1).putFloat(6*16+8,1);
        header.putFloat(8*16,1).putFloat(8*16+4,1).putFloat(8*16+8,1).putFloat(8*16+12,1);
        command.putFloat(12,129).putFloat(20,1);
        int out=buffer(1,pool), counter=buffer(3,counts), emitCommand=buffer(4,command), emitter=buffer(5,header);
        int emit=compute("emit.comp");uint(emit,"uCapacity",cap);uint(emit,"uTotalSpawn",129);uint(emit,"uEmitCount",1);
        dispatch(emit,3);read(out,pool);read(counter,counts);
        check(counts.getInt(0)==129,"allocation attempts remain monotonic on overflow");
        for(int i=cap*64;i<pool.capacity();i+=4)check(pool.getInt(i)==0x5a5a5a5a,"emit wrote beyond capacity");
        for(int i=0;i<cap;i++) check(pool.getFloat(i*64+52)>0,"dense initialized output");
        int input=buffer(0,pool);ByteBuffer previous=bytes(16);previous.putInt(0,1000000);int prev=buffer(14,previous);
        counts.putInt(0,0);upload(counter,counts);
        int update=compute("update.comp");uint(update,"uCapacity",cap);uint(update,"uKillEmit",-1);
        GL20.glUniform1f(GL20.glGetUniformLocation(update,"uDt"),0.05f);
        dispatch(update,3);read(counter,counts);check(counts.getInt(0)==cap,"update bounded overflowing previous census");
        read(out,pool);for(int i=cap*64;i<pool.capacity();i+=4)check(pool.getInt(i)==0x5a5a5a5a,"update wrote beyond capacity");
        // Same material, custom behavior: no engine or sorting changes required.
        counts.putInt(0,0);upload(counter,counts);header.putFloat(0,1000);upload(emitter,header);
        uint(emit,"uTotalSpawn",1);dispatch(emit,1);read(out,pool);
        check(pool.getFloat(20)==2.0f,"registered spawn module not applied");
        upload(input,pool);previous.putInt(0,1);upload(prev,previous);counts.putInt(0,0);upload(counter,counts);
        dispatch(update,1);read(out,pool);
        check(Math.abs(pool.getFloat(20)-2.05f)<0.00001f,"registered update module not applied");
        for(int id:new int[]{out,counter,emitCommand,emitter,input,prev})GL15.glDeleteBuffers(id);
        GL20.glDeleteProgram(emit);GL20.glDeleteProgram(update);
        System.out.println("Real emit/update capacity and sentinel tests passed");
    }


    static void deathChainAndRebirth() throws Exception {
        int cap=65;
        ByteBuffer pool=bytes((cap+1)*64), headers=bytes(640), counts=bytes(16), previous=bytes(16);
        headers.putFloat(7*16,2).putFloat(5*16+12,1).putFloat(16*16,1);
        headers.putFloat(320+7*16,1);
        for(int i=0;i<8;i++)pool.putFloat(i*64+12,1).putFloat(i*64+28,17).putFloat(i*64+52,-1.1f).putFloat(i*64+56,i+1);
        for(int i=cap*64;i<pool.capacity();i+=4)pool.putInt(i,0x5a5a5a5a);
        previous.putInt(0,8);
        int input=buffer(0,pool), output=buffer(1,pool), emitter=buffer(5,headers), counter=buffer(3,counts), prev=buffer(14,previous);
        buffer(16,bytes(16+64*32));buffer(31,bytes(4+cap*8));
        int update=compute("update.comp"), prepare=compute("prepare_dispatch.comp"), keygen=compute("keygen.comp");
        uint(update,"uCapacity",cap);uint(update,"uKillEmit",-1);dispatch(update,1);read(counter,counts);
        check(counts.getInt(0)>=cap,"death chain failed to fill pool");read(output,pool);
        for(int i=cap*64;i<pool.capacity();i+=4)check(pool.getInt(i)==0x5a5a5a5a,"death chain exceeded capacity");
        ByteBuffer commands=bytes(140), args=bytes(36);int indirect=buffer(2,commands), dispatchBuffer=buffer(29,args);
        uint(prepare,"uCapacity",cap);uint(prepare,"uPhase",1);dispatch(prepare,1);read(counter,counts);read(dispatchBuffer,args);
        check(counts.getInt(0)==cap && args.getInt(12)==2,"GPU death chain omitted from next dispatch");
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,output);
        int sorted=buffer(7,bytes((cap+16385)*8));buffer(23,bytes((cap+16385)*8));buffer(8,bytes(cap*4));buffer(15,bytes(cap*4));
        uint(keygen,"uUpper",cap);uint(keygen,"uCarrierBase",cap);GL20.glUniform1f(GL20.glGetUniformLocation(keygen,"uMaxDepth"),128);
        GL20.glUniform1f(GL20.glGetUniformLocation(keygen,"uDepthLogRange"),7);
        GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,dispatchBuffer);
        GL20.glUseProgram(keygen);GL43.glDispatchComputeIndirect(12);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        read(indirect,commands);check(commands.getInt(26*4)==cap,"death-chain last particle was not culled/sorted");
        // Expire every poof, then create a new generation; no stale counters survive.
        for(int i=0;i<cap;i++)pool.putFloat(i*64+48,100);
        upload(input,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);
        previous.putInt(0,cap);upload(prev,previous);counts.putInt(0,0);upload(counter,counts);
        dispatch(update,2);read(counter,counts);check(counts.getInt(0)==0,"all-dead frame not empty");
        pool.putFloat(48,0);pool.putFloat(52,10);upload(input,pool);previous.putInt(0,1);upload(prev,previous);
        dispatch(update,1);read(counter,counts);check(counts.getInt(0)==1,"rebirth after empty generation failed");
        for(int id:new int[]{input,output,emitter,counter,prev,indirect,dispatchBuffer,sorted})GL15.glDeleteBuffers(id);
        for(int program:new int[]{update,prepare,keygen})GL20.glDeleteProgram(program);
        System.out.println("Death-chain saturation, indirect coverage, all-dead and rebirth passed");
    }
    static void stableDamage() throws Exception {
        ByteBuffer data=bytes(3*64), header=bytes(320), counts=bytes(16), previous=bytes(16), ids=bytes(4+6*4), damage=bytes(16+32);
        header.putFloat(7*16,2); previous.putInt(0,2);
        for(int i=0;i<2;i++)data.putFloat(i*64+12,1).putFloat(i*64+52,20).putFloat(i*64+56,i+1);
        ids.putInt(4,111).putInt(8,222);
        damage.putInt(0,1).putFloat(16,0).putFloat(20,5).putInt(40,222);
        int input=buffer(0,data), output=buffer(1,bytes(3*64)), emitter=buffer(5,header), counter=buffer(3,counts), prev=buffer(14,previous), identities=buffer(31,ids), damageBuffer=buffer(16,damage);
        int program=compute("update.comp");uint(program,"uCapacity",3);uint(program,"uKillEmit",-1);uint(program,"uIdentityReadBase",0);uint(program,"uIdentityWriteBase",3);
        dispatch(program,1);read(output,data);read(identities,ids);
        boolean first=false,second=false;
        for(int i=0;i<2;i++) {
            int token=ids.getInt(4+(3+i)*4);
            if(token==111) { check(data.getFloat(i*64+52)==20,"stale pool index damaged wrong particle");first=true; }
            if(token==222) { check(data.getFloat(i*64+52)==15,"stable identity damage lost");second=true; }
        }
        check(first&&second,"identity did not survive compaction");
        for(int id:new int[]{input,output,emitter,counter,prev,identities,damageBuffer})GL15.glDeleteBuffers(id);
        GL20.glDeleteProgram(program);
        System.out.println("Delayed local MODEL damage follows stable identity, not stale pool index");
    }
    static Object invoke(Object target,String name,Class<?>[] types,Object... args) throws Exception {
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);
    }
    static void setField(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    static void ringAndRollback() throws Exception {
        var gpu=new com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers();
        check(gpu.init(1000,8),"buffer init");
        long epoch=gpu.identityEpoch();gpu.clearMemberMap();check(gpu.identityEpoch()==epoch,"per-frame map clear invalidated readbacks");
        gpu.invalidateIdentities();check(gpu.identityEpoch()>epoch,"reset did not invalidate identities");
        int committed=gpu.sortBuffer(0);
        gpu.beginComputeFrame();int scratch=gpu.sortBuffer(0);check(scratch!=committed,"compute overwrote committed sort storage");
        gpu.restoreCommittedFrame();check(gpu.sortBuffer(0)==committed,"abort published scratch render buffers");
        gpu.beginComputeFrame();gpu.swap();gpu.restoreCommittedFrame();check(gpu.sortBuffer(0)==scratch,"commit did not promote render storage");
        Class<?> ringClass=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.ParticleReadbacks");
        Constructor<?> ctor=ringClass.getDeclaredConstructor();ctor.setAccessible(true);Object ring=ctor.newInstance();
        Object[] slots=new Object[4];
        for(int i=0;i<4;i++) {
            slots[i]=invoke(ring,"freeSlot",new Class<?>[]{});
            setField(slots[i],"generation",(long)i);
            gpu.bindCounter(3,0);int buffer=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,3);
            ByteBuffer count=bytes(16);count.putInt(0,i+100);upload(buffer,count);
            invoke(gpu,"copySnapshot",new Class<?>[]{int.class,slots[i].getClass()},0,slots[i]);
            setField(slots[i],"fence",GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0));
        }
        for(int frame=0;frame<8;frame++)check(invoke(ring,"freeSlot",new Class<?>[]{})==null,"overwrote an unconsumed readback slot");
        // Finishing is test-only: production always polls with a zero timeout.
        GL11.glFinish();
        for(int i=0;i<4;i++) {
            Object slot=invoke(ring,"completed",new Class<?>[]{});
            check(slot==slots[i],"readback completion order");
            check(((ByteBuffer)field(slot,"data")).getInt(0)==i+100,"snapshot changed after source reuse");
            invoke(ring,"release",new Class<?>[]{slot.getClass()},slot);
        }
        check(invoke(ring,"freeSlot",new Class<?>[]{})!=null,"released slots not reused");
        invoke(ring,"clear",new Class<?>[]{});check(invoke(ring,"completed",new Class<?>[]{})==null,"reset kept stale snapshot");
        invoke(ring,"close",new Class<?>[]{});
        float[] h=new float[80];h[3]=1;gpu.setEmitterHeader(2,h);gpu.setEmitterHeader(3,h);gpu.uploadDirtyEmitters();
        gpu.setEmitterHeader(2,h);check(((BitSet)field(field(gpu,"emitterUploads"),"dirty")).isEmpty(),"unchanged header re-uploaded");
        gpu.unbindShaders();gpu.free();gpu.free();
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"buffer lifecycle GL error");
        System.out.println("Four-slot backpressure, immutable snapshots, reset and render-generation rollback passed");
    }

    static void waveRetention() throws Exception {
        int cap=65;
        ByteBuffer particles=bytes(cap*64), header=bytes(320), counts=bytes(16), events=bytes(4+16384*20), stamps=bytes(cap*16);
        header.putFloat(18*16,2);counts.putInt(0,1);
        for(int i=0;i<cap;i++)particles.putFloat(i*64+12,(1<<18)+i+1).putFloat(i*64+52,20);
        int pool=buffer(1,particles), emitter=buffer(5,header), counter=buffer(3,counts), queue=buffer(22,events), stamp=buffer(30,stamps);
        int program=compute("wavecontact.comp");GL20.glUseProgram(program);
        GL20.glUniform4fv(GL20.glGetUniformLocation(program,"uWave"),new float[]{1,1,0,100,0,0,0,0,0,0,0,0,0,0,0,0});
        GL20.glUniform4fv(GL20.glGetUniformLocation(program,"uWaveTarget"),new float[]{0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0});
        for(int frame=0;frame<8;frame++) { counts.putInt(0,frame+1);upload(counter,counts);dispatch(program,1); }
        read(queue,events);check(events.getInt(0)==8,"contacts lost while snapshots unavailable or latch already set");
        dispatch(program,1);read(queue,events);check(events.getInt(0)==8,"duplicate wave contacts");
        // Snapshot copied/acknowledged: clear only the queue, preserve per-wave deduplication.
        upload(queue,bytes(events.capacity()));dispatch(program,1);read(queue,events);
        check(events.getInt(0)==0,"acknowledged wave reported twice");
        counts.putInt(0,9);upload(counter,counts);dispatch(program,1);read(queue,events);
        check(events.getInt(0)==1,"new contact blocked by older acknowledgement");
        // An acknowledged earlier slot must not hide an overlapping later wave.
        GL20.glUniform4fv(GL20.glGetUniformLocation(program,"uWave"),new float[]{1,1,0,100,1,1,0,100,0,0,0,0,0,0,0,0});
        GL20.glUniform4fv(GL20.glGetUniformLocation(program,"uWaveTarget"),new float[]{0,0,0,1,0,0,0,2,0,0,0,0,0,0,0,0});
        dispatch(program,1);read(queue,events);
        check(events.getInt(0)==10,"earlier wave stamp hid overlapping wave");
        dispatch(program,1);read(queue,events);check(events.getInt(0)==10,"overlapping wave duplicated");
        for(int id:new int[]{pool,emitter,counter,queue,stamp})GL15.glDeleteBuffers(id);
        GL20.glDeleteProgram(program);System.out.println("Wave contacts persist across eight delayed frames and are reported once");
    }

    static void atomicReload() throws Exception {
        var programs=new com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms();
        java.util.function.ToIntFunction<String> good=name -> link(stageText(name,GL43.GL_COMPUTE_SHADER,"layout(local_size_x=1) in; void main(){}"));
        java.util.function.BiFunction<String,String,Integer> graphics=(a,b)->good.applyAsInt(a);
        Class<?>[] signature={java.util.function.ToIntFunction.class,java.util.function.BiFunction.class};
        invoke(programs,"rebuildWithCompiler",signature,good,graphics);
        int old=programs.update();check(programs.ready()&&GL20.glIsProgram(old),"initial shader set");
        List<Integer> candidate=new ArrayList<>();
        java.util.function.ToIntFunction<String> failed=name -> {
            if(name.endsWith("emit.comp"))return 0;
            int id=good.applyAsInt(name);candidate.add(id);return id;
        };
        invoke(programs,"rebuildWithCompiler",signature,failed,graphics);
        check(programs.update()==old&&GL20.glIsProgram(old),"failed reload destroyed working set");
        for(int id:candidate)check(!GL20.glIsProgram(id),"failed candidate leaked program");
        invoke(programs,"rebuildWithCompiler",signature,good,graphics);
        check(programs.ready()&&programs.update()!=old&&!GL20.glIsProgram(old),"successful reload not atomic");
        programs.delete();programs.delete();
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"reload lifecycle GL error");
        System.out.println("Atomic reload keeps old programs on failure and frees rejected candidates");
    }
    static void sortRun(int[] programs,int groups,int histogram) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,histogram);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
        for(int pass=0;pass<3;pass++) {
            GL20.glUseProgram(programs[pass]);GL43.glDispatchCompute(pass==1?1:groups,1,1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        }
    }

    static void submissionBenchmark() throws Exception {
        var gpu=new com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers();
        check(gpu.init(1000,128),"submission benchmark init");
        float[] header=new float[80], mirror=new float[128*80];
        int legacy=buffer(5,bytes(mirror.length*4));
        StringBuilder report=new StringBuilder("operation,variant,trial,cpu_ms,bytes_per_frame,calls_per_frame\n");
        for(int variant=0;variant<2;variant++) {
            for(int trial=-1;trial<3;trial++) {
                GL11.glFinish();long start=System.nanoTime();
                for(int frame=0;frame<5000;frame++) {
                    header[3]=frame;
                    if(variant==0) {
                        System.arraycopy(header,0,mirror,0,80);
                        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                            FloatBuffer staging=stack.mallocFloat(mirror.length);staging.put(mirror).flip();
                            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,legacy);
                            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,staging);
                        }
                    } else { gpu.setEmitterHeader(0,header);gpu.uploadDirtyEmitters(); }
                }
                double time=(System.nanoTime()-start)/5000e6;
                if(trial>=0) report.append("header,").append(variant).append(',').append(trial).append(',').append(time)
                        .append(',').append(variant==0?40960:320).append(",2\n");
            }
        }
        for(int variant=0;variant<2;variant++) for(int trial=-1;trial<3;trial++) {
            GL11.glFinish();long start=System.nanoTime();
            for(int frame=0;frame<5000;frame++) {
                if(variant==0) for(int binding=0;binding<29;binding++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,binding,0);
                else GL44.nglBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,32,0L);
            }
            double time=(System.nanoTime()-start)/5000e6;
            if(trial>=0)report.append("unbind,").append(variant).append(',').append(trial).append(',').append(time)
                    .append(",0,").append(variant==0?29:1).append('\n');
        }
        Files.writeString(Path.of("build/particle-submit-benchmark.csv"),report);
        System.out.print(report);GL15.glDeleteBuffers(legacy);gpu.free();
    }
    static void sorting(boolean benchmark) throws Exception {
        int[][] programs=new int[2][3];
        String[] names={"radix_hist.comp","radix_scan.comp","radix_scatter.comp"};
        for(int i=0;i<3;i++) {
            programs[0][i]=link(stageText(names[i],GL43.GL_COMPUTE_SHADER,Files.readString(Path.of("scripts/particles/reference/"+names[i]))));
            programs[1][i]=compute(names[i]);
        }
        StringBuilder report=new StringBuilder("count,distribution,variant,trial,gpu_ms,cpu_submit_ms\n");
        int[] sizes=benchmark?new int[]{0,10000,100000,1000000,2000000}:new int[]{0,1,63,64,65,1023,1024,1025,4097};
        for(int n:sizes) for(int distribution=0;distribution<2;distribution++) {
            ByteBuffer commands=bytes(140);commands.putInt(21*4,n);
            ByteBuffer input=bytes(Math.max(1,n)*8), result=bytes((n+1)*8);
            Random random=new Random(28);
            for(int i=0;i<n;i++) input.putInt(i*8,distribution==0?random.nextInt(768):42).putInt(i*8+4,i);
            result.putInt(n*8,0x5a5a5a5a);
            int cmd=buffer(2,commands), src=buffer(6,input), dst=buffer(7,result), hist=buffer(9,bytes(4096)), offs=buffer(10,bytes(4100));
            for(int variant=0;variant<2;variant++) {
                int block=variant==0 || n<65536?64:1024;
                int groups=(n+block-1)/block;
                sortRun(programs[variant],groups,hist);
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);read(dst,result);
                BitSet seen=new BitSet(n);int last=-1;
                for(int i=0;i<n;i++) {
                    int key=result.getInt(i*8), id=result.getInt(i*8+4);
                    check(key>=last,"sort key order");last=key;
                    check(id>=0 && id<n && !seen.get(id),"sort duplicate or invalid payload");seen.set(id);
                    check(key==input.getInt(id*8),"key payload mismatch");
                }
                check(seen.cardinality()==n && result.getInt(n*8)==0x5a5a5a5a,"sort cardinality and sentinel");
                if(benchmark) {
                    for(int warm=0;warm<20;warm++)sortRun(programs[variant],groups,hist);
                    GL11.glFinish();
                    int query=GL15.glGenQueries();
                    for(int trial=0;trial<3;trial++) {
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long start=System.nanoTime();
                        for(int repeat=0;repeat<30;repeat++)sortRun(programs[variant],groups,hist);
                        double cpu=(System.nanoTime()-start)/30e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        double gpu=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/30e6;
                        String line=n+","+distribution+","+variant+","+trial+","+gpu+","+cpu;
                        report.append(line).append('\n');System.out.println(line);
                    }
                    GL15.glDeleteQueries(query);
                }
            }
            for(int id:new int[]{cmd,src,dst,hist,offs})GL15.glDeleteBuffers(id);
        }
        for(int[] set:programs)for(int id:set)GL20.glDeleteProgram(id);
        if(benchmark)Files.writeString(Path.of("build/particle-sort-benchmark.csv"),report);
        System.out.println("Baseline and grouped sort order, payload, tail and capacity tests passed");
    }
    @SuppressWarnings("unchecked")
    static void geometry() throws Exception {
        Class<?> shapeClass=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.HexPatternRuntime$Shape");
        Constructor<?> constructor=shapeClass.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Field pointsField=shapeClass.getDeclaredField("points");pointsField.setAccessible(true);
        Field countField=shapeClass.getDeclaredField("vertexCount");countField.setAccessible(true);
        int program=compute("hex_prepare.comp");
        for(String signature:List.of("", "qwe", "wwwww", "qqqqqqqq", "qweadqwead")) {
            var pattern=at.petrak.hexcasting.api.casting.math.HexPattern.fromAngleString(signature,at.petrak.hexcasting.api.casting.math.HexDir.values()[0],false);
            Object shape=constructor.newInstance(pattern);
            List<float[]> descriptors=(List<float[]>)pointsField.get(shape);
            int vertices=countField.getInt(shape);
            ByteBuffer resources=bytes((256+descriptors.size()*2)*16);
            Random random=new Random(9001);random.nextDouble();random.nextDouble();random.nextDouble();
            int[] perm=new int[256];for(int i=0;i<256;i++)perm[i]=i;
            for(int i=0;i<256;i++){int j=i+random.nextInt(256-i),v=perm[i];perm[i]=perm[j];perm[j]=v;}
            for(int i:perm)resources.putFloat(i).putFloat(0).putFloat(0).putFloat(0);
            for(float[] d:descriptors)for(float f:d)resources.putFloat(f);resources.flip();buffer(26,resources);
            ByteBuffer inputs=bytes((20481+1)*16);inputs.putFloat(0,1).putInt(16,4096).putInt(20,256).putInt(24,descriptors.size());
            inputs.putFloat(32,100).putInt(84,vertices);int inputBuffer=buffer(24,inputs);
            ByteBuffer pool=bytes(64);pool.putInt(56,4096);buffer(1,pool);
            ByteBuffer sorted=bytes(8);buffer(6,sorted);
            ByteBuffer indirect=bytes(140);indirect.putInt(31*4,1);buffer(2,indirect);
            int commands=buffer(27,bytes(16)), result=buffer(28,bytes(descriptors.size()*16));buffer(25,bytes(4096*9*4));
            var center=pattern.getCenter(1f);float dx=0,dy=0;
            for(var point:pattern.toLines(1f,net.minecraft.world.phys.Vec2.ZERO)){dx=Math.max(dx,Math.abs(point.x-center.x));dy=Math.max(dy,Math.abs(point.y-center.y));}
            float scale=Math.min(3.8f,Math.min(6.4f/dx,6.4f/dy));
            var bare=new ArrayList<>(pattern.toLines(scale,pattern.getCenter(scale).negated()));
            for(int i=0;i<bare.size();i++)bare.set(i,new net.minecraft.world.phys.Vec2(bare.get(i).x,-bare.get(i).y));
            for(int seed:new int[]{1,12345,1234567890,-1234567890})for(int ticks:new int[]{0,100,10000,10000000}) {
                inputs.putInt(40,seed).putFloat(12,ticks);upload(inputBuffer,inputs);
                at.petrak.hexcasting.client.ClientTickCounter.ticksInGame=ticks;
                at.petrak.hexcasting.client.ClientTickCounter.partialTicks=0;
                var expected=at.petrak.hexcasting.client.render.RenderLib.makeZappy(bare,at.petrak.hexcasting.client.render.RenderLib.findDupIndices(pattern.positions()),5,0.65f,0.1f,0.2f,0f,1f,seed);
                check(expected.size()==descriptors.size(),"duplicate-path topology mismatch");
                dispatch(program,1);
                ByteBuffer actual=bytes(descriptors.size()*16);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,result);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,actual);
                for(int i=0;i<expected.size();i++) {
                    double error=Math.hypot(expected.get(i).x-actual.getFloat(i*16),expected.get(i).y-actual.getFloat(i*16+4));
                    check(error<0.002,"zappy mismatch "+signature+" seed="+seed+" tick="+ticks+" point="+i+" error="+error);
                }
                ByteBuffer command=bytes(16);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,commands);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,command);
                check(command.getInt(0)==vertices && command.getInt(4)==1,"variable geometry indirect command");
            }
        }
        System.out.println("GPU zappy points match Hex RenderLib across seeds, times and overlapping paths");
    }
    public static void main(String[] args) throws Exception {
        var materialTypes = com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Material.values();
        for (var material : materialTypes) {
            var standard = com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.standard(material);
            var spec = com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec.builder().type(standard).build();
            check(spec.type == standard, "standard type mapping for " + material);
            check(spec.packed().length == 80, "emitter header vec4 count for " + material);
            check(spec.packed()[0] == standard.id(), "emitter type id header for " + material);
            check(spec.packed()[7 * 4] == material.index(), "emitter material header for " + material);
        }
        var example=com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.register(
                new com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Type(1000,"rising_spark",
                        com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Material.ADDITIVE,
                        "chunks/examples/rising_spark_spawn.glsl","chunks/examples/rising_spark_update.glsl",Set.of()));
        var exampleSpec=com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec.builder().type(example).build();
        check(exampleSpec.packed().length==80 && exampleSpec.packed()[0]==1000,"type registration/ABI");
        GLFWErrorCallback.createPrint(System.err).set();
        check(GLFW.glfwInit(),"GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(64,64,"Hex GPU validation",0,0);
        check(window!=0,"OpenGL 4.5 context unavailable");
        GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        System.out.println("Driver: "+GL11.glGetString(GL11.GL_RENDERER));
        System.out.println("OpenGL: "+GL11.glGetString(GL11.GL_VERSION));
        Class<?> programs=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms");
        Field field=programs.getDeclaredField("PRELUDE");field.setAccessible(true);prelude=(String)field.get(null);
        for(String shader:List.of("prepare_dispatch.comp","gridbuild.comp","hit.comp","stormpos.comp","wavecontact.comp","block_emit.comp","capture.comp","update.comp","emit.comp","keygen.comp","reset.comp","radix_hist.comp","radix_scan.comp","radix_scatter.comp","hex_reconcile.comp","hex_prepare.comp")) {
            GL20.glDeleteProgram(compute(shader));System.out.println("Compiled "+shader);
        }
        for(String shader:List.of("hex_pattern","additive","model","textured"))
            GL20.glDeleteProgram(link(stage(shader+".vsh",GL20.GL_VERTEX_SHADER),stage(shader+".fsh",GL20.GL_FRAGMENT_SHADER)));
        buffer(31,bytes(4+2*4096*4));
        colors();
        poolAndSort();
        geometry();
        dispatchBounds();
        capacity();
        deathChainAndRebirth();
        stableDamage();
        ringAndRollback();
        waveRetention();
        atomicReload();
        sorting(Arrays.asList(args).contains("--benchmark"));
        if(Arrays.asList(args).contains("--benchmark"))submissionBenchmark();
        System.out.println("PASS: "+checks+" assertions");
        GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
    }
}
