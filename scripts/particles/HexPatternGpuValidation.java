import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;




/** Real-driver checks. Run with the project's runtime classpath and LWJGL natives. */
public class HexPatternGpuValidation {
    static String prelude;
    static int checks;
    static ByteBuffer bytes(int size) { return BufferUtils.createByteBuffer(size); }
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static String source(String name) throws Exception {
        return com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource.loadParticle(name,path->{
            try(var stream=com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.class.getClassLoader()
                    .getResourceAsStream("assets/createmanaindustry/"+path)) {
                if(stream==null)throw new IllegalArgumentException("Missing runtime resource: "+path);
                return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
                        .replace("#pragma cmi_types spawn", com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks("spawn"))
                        .replace("#pragma cmi_types update", com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks("update"));
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        });
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
    static void read(int id, ByteBuffer data) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
        data.clear(); GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
    }
    static void uint(int program,String name,int value) {
        GL20.glUseProgram(program);GL30.glUniform1ui(GL20.glGetUniformLocation(program,name),value);
    }
    static void residentResources() {
        var table=new com.iridium126.createmanaindustry.client.particles.engine.BlockEmitterTable();
        var identities=new com.iridium126.createmanaindustry.client.allaystorm.StormIdentityBuffers();
        try {
            table.add(1,2,3,100,0,8);table.uploadAndBind();
            int old=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,4);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,old);
            check(GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)<1024,"small source allocated full table");
            var phase=bytes(4).putFloat(0,.875f);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,20,phase);
            for(int i=1;i<100;i++)table.add(i,2,3,100,0,8);
            table.uploadAndBind();int grown=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,4);
            var rows=bytes(100*32);read(grown,rows);
            check(rows.getFloat(20)==.875f,"source growth reset GPU-owned fractional phase");
            check(rows.getFloat(99*32)==99,"source growth lost dirty tail");
            table.remove(50);table.uploadAndBind();read(grown,rows);
            check(rows.getFloat(50*32+12)==0,"removed source still active");
            check(rows.getFloat(20)==.875f,"neighbor dirty update reset phase");
            identities.initialize();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,identities.corrections());
            check(GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)==32,"idle Storm allocated full corrections");
            identities.ensureMembers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,identities.members());
            check(GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)==131072*4,"Storm storage follows particle capacity");
            var correction=BufferUtils.createFloatBuffer(8).put(new float[]{1,2,3,4,5,6,7,8}).flip();
            identities.write(131071,correction);
            var result=bytes(32);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,identities.corrections());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,131071L*32,result);
            check(result.getFloat(28)==8,"last Storm identity correction dropped");
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"resident buffer GL error");
        } finally {table.free();identities.close();}
        int foreign=GL15.glGenQueries();
        try(var timer=new com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing(4)) {
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,foreign);
            check(timer.begin(),"timestamp ring refused a foreign elapsed query");
            timer.end(false);
            check(GL15.glGetQueryi(GL33.GL_TIME_ELAPSED,GL15.GL_CURRENT_QUERY)==foreign,"timestamp scope ended foreign timer");
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);GL11.glFinish();timer.poll();
            check(!timer.sampled(),"aborted compute published a timing sample");
            check(timer.begin(),"timestamp slot did not retire");timer.end(true);GL11.glFinish();timer.poll();
            check(timer.sampled()&&timer.lastMs()>=0,"complete timestamp pair missing");
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"timestamp query GL error");
        } finally {GL15.glDeleteQueries(foreign);}
        System.out.println("Resident source growth preserves phases; lazy Storm identity storage passed");
    }

    static void ownedShaderCompiler() {
        String vertex="#version 450 core\nvoid main(){gl_Position=vec4(0,0,0,1);}";
        String fragment="#version 450 core\nout vec4 color;void main(){color=vec4(1);}";
        int compute=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("owned compute",
                "#version 450 core\nlayout(local_size_x=1)in;void main(){}");
        int graphics=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.graphics("owned graphics",vertex,fragment);
        try {
            check(GL20.glGetProgrami(compute,GL20.GL_ATTACHED_SHADERS)==0,"owned compute retained stage objects");
            check(GL20.glGetProgrami(graphics,GL20.GL_ATTACHED_SHADERS)==0,"owned graphics retained stage objects");
            boolean failed=false;
            try {com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.graphics("invalid fragment",vertex,"#version 450 core\ninvalid source");}
            catch(IllegalStateException expected){failed=true;}
            check(failed,"invalid owned fragment was accepted");
            failed=false;
            try {com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.graphics("invalid interface",
                    "#version 450 core\nout vec3 value;void main(){value=vec3(1);gl_Position=vec4(0,0,0,1);}",
                    "#version 450 core\nin vec4 value;out vec4 color;void main(){color=value;}");}
            catch(IllegalStateException expected){failed=true;}
            check(failed,"incompatible owned stages were linked");
            check(GL20.glIsProgram(compute)&&GL20.glIsProgram(graphics),"rejected compiler candidate damaged live programs");
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"owned compiler cleanup left GL errors");
        } finally {GL20.glDeleteProgram(compute);GL20.glDeleteProgram(graphics);}
        System.out.println("Owned shader compiler: detached stages, compile/link rejection and live-program isolation passed");
    }

    static Field nativeParticleField(Class<?> type,String name)throws Exception{
        while(type!=null){try{var field=type.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}
        throw new NoSuchFieldException(name);
    }
    static void nativeConjureMotion() throws Exception {
        var type=Class.forName("at.petrak.hexcasting.client.particles.ConjureParticle");
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var sprites=(net.minecraft.client.particle.SpriteSet)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{net.minecraft.client.particle.SpriteSet.class},(proxy,method,args)->null);
        var lifetime=nativeParticleField(type,"lifetime");var age=nativeParticleField(type,"age");
        var tick=type.getMethod("tick");
        Field[] positions={nativeParticleField(type,"x"),nativeParticleField(type,"y"),nativeParticleField(type,"z")};
        Field[] velocities={nativeParticleField(type,"xd"),nativeParticleField(type,"yd"),nativeParticleField(type,"zd")};
        int program=compute("update.comp");ByteBuffer data=bytes(64),header=bytes(320),result=bytes(64);
        header.putFloat(0,1).putFloat(17*16,5).putFloat(17*16+4,5);
        int input=buffer(0,data),output=buffer(1,result),headers=buffer(5,header),counter=buffer(3,bytes(16)),previous=buffer(14,bytes(16).putInt(0,1));
        try {
            uint(program,"uCapacity",1);uint(program,"uKillEmit",-1);
            for(double[] velocity:new double[][]{{.01,.02,.03},{.01,0,.03},{0,0,0}})for(float dt:new float[]{.05f,.01f}){
                Object nativeParticle=constructor.newInstance(null,4.,5.,6.,velocity[0],velocity[1],velocity[2],sprites,0xffab65eb);
                lifetime.setInt(nativeParticle,40);age.setInt(nativeParticle,0);
                data.clear();for(int i=0;i<64;i+=4)data.putInt(i,0);
                data.putFloat(0,4).putFloat(4,5).putFloat(8,6).putFloat(12,1).putFloat(16,(float)(velocity[0]*20)).putFloat(20,(float)(velocity[1]*20)).putFloat(24,(float)(velocity[2]*20));
                data.putFloat(44,velocity[0]!=0&&velocity[1]!=0&&velocity[2]!=0?-1:1).putFloat(52,2);
                upload(input,data);int logical=0;
                for(int frame=1;frame<=(int)Math.round(1.5/dt);frame++){
                    int target=(int)Math.floor(frame*(double)dt*20+.0001);
                    while(logical<target){tick.invoke(nativeParticle);logical++;}
                    upload(counter,bytes(16));GL20.glUseProgram(program);
                    GL20.glUniform1f(GL20.glGetUniformLocation(program,"uDt"),.05f);
                    GL20.glUniform1f(GL20.glGetUniformLocation(program,"uHexDt"),dt);
                    dispatch(program,1);read(output,result);
                    for(int axis=0;axis<3;axis++){
                        check(Math.abs(result.getFloat(axis*4)-positions[axis].getDouble(nativeParticle))<.0001,"native Conjure position differs at tick "+logical);
                        check(Math.abs(result.getFloat(16+axis*4)/20-velocities[axis].getDouble(nativeParticle))<.00001,"native Conjure velocity differs at tick "+logical);
                    }
                    upload(input,result);
                }
                ByteBuffer frozen=bytes(64);frozen.put(result.duplicate().clear()).clear();
                upload(counter,bytes(16));GL20.glUniform1f(GL20.glGetUniformLocation(program,"uHexDt"),0);dispatch(program,1);read(output,result);
                for(int i=0;i<64;i+=4)check(result.getInt(i)==frozen.getInt(i),"paused native Conjure advanced");
                // Production uses the exact ParticleEngine.tick counter, including wrap-around.
                result.putFloat(48,0).putInt(56,0xfffffffe);upload(input,result);
                uint(program,"uHexCurrentTick",1);GL20.glUniform1i(GL20.glGetUniformLocation(program,"uHexTickReady"),1);
                upload(counter,bytes(16));dispatch(program,1);read(output,result);
                check(Math.abs(result.getFloat(48)-.15)<1e-6,"native tick counter lost wrapped steps");
                check(result.getInt(56)==1,"native tick stamp was not published");
                upload(input,result);upload(counter,bytes(16));dispatch(program,1);
                ByteBuffer repeated=bytes(64);read(output,repeated);
                for(int i=0;i<64;i+=4)check(result.getInt(i)==repeated.getInt(i),"same native tick advanced twice");
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"uHexTickReady"),0);
            }
        } finally {for(int id:new int[]{input,output,headers,counter,previous})GL15.glDeleteBuffers(id);GL20.glDeleteProgram(program);}
        System.out.println("Native ConjureParticle tick motion, blocked-Y acceleration, sub-tick partitioning and pause passed");
    }

    static void nativePigmentParameters() throws Exception {
        String shader="""
            layout(local_size_x=64) in;
            layout(std430,binding=4) readonly buffer EmitCmdBuf {float entries[];} emitCmd;
            layout(std430,binding=0) readonly buffer Directions {vec4 values[];} directions;
            layout(std430,binding=1) writeonly buffer Colors {vec4 values[];} colors;
            #pragma cmi_include chunks/hex_pigment.glsl
            uniform uint uCount;
            void main(){uint i=gl_GlobalInvocationID.x;if(i<uCount)colors.values[i]=vec4(hexPigment(0u,directions.values[i].xyz),1.);}
            """;
        shader=shader.replace("#pragma cmi_include chunks/hex_pigment.glsl",source("chunks/hex_pigment.glsl"));
        int program=link(stageText("native pigment parameters",GL43.GL_COMPUTE_SHADER,shader));
        int count=257;var dirs=bytes(count*16);var result=bytes(count*16);
        Random random=new Random(421);for(int i=0;i<count;i++)for(int axis=0;axis<3;axis++)dirs.putFloat(i*16+axis*4,random.nextFloat()*4-2);
        int input=buffer(0,dirs),output=buffer(1,result),parameters=buffer(4,bytes(48*4));
        try {
            uint(program,"uCount",count);uint(program,"uHexParameterBase",0);
            for(int[] palette:new int[][]{{0xffab65eb},{0xff000000},{0xff200000,0xff000000,0xff002000},{0xff54398a,0xffcfa0f3,0xfffecbe6,0xffcfa0f3,0xffe77c56}}) {
                for(float time:new float[]{0,1,-1,399.5f,8000,1000000}) {
                    var data=com.iridium126.createmanaindustry.client.hex.particles.HexPigmentData.palette(palette,time,400);
                    var encoded=bytes(48*4);data.write(encoded.asFloatBuffer(),0,0);upload(parameters,encoded);
                    dispatch(program,(count+63)/64);read(output,result);
                    var provider=new at.petrak.hexcasting.api.pigment.ColorProvider(){
                        @Override protected int getRawColor(float t,net.minecraft.world.phys.Vec3 position){return palette.length==1?palette[0]:at.petrak.hexcasting.api.addldata.ADPigment.morphBetweenColors(palette,new net.minecraft.world.phys.Vec3(.1,.1,.1),t/400,position);}
                    };
                    for(int i=0;i<count;i++) {
                        int color=provider.getColor(time,new net.minecraft.world.phys.Vec3(dirs.getFloat(i*16),dirs.getFloat(i*16+4),dirs.getFloat(i*16+8)));
                        for(int channel=0;channel<3;channel++)check(Math.round(result.getFloat(i*16+channel*4)*255)==((color>>(16-channel*8))&255),"native pigment byte mismatch at time "+time+", direction "+i+", channel "+channel);
                    }
                }
            }
        } finally {for(int id:new int[]{input,output,parameters})GL15.glDeleteBuffers(id);GL20.glDeleteProgram(program);}
        System.out.println("Native pigment parameters match actual ColorProvider RGB, including dark colors and negative/long clocks");
    }

    static void directHexBatch() throws Exception {
        int program=compute("emit.comp");
        int cap=1024;
        var inputs=new com.iridium126.createmanaindustry.client.particles.emitter.DirectParticleBatch();
        ByteBuffer pool=bytes((cap+1)*64),counter=bytes(16),header=bytes(320);
        header.putFloat(17*16,5).putFloat(17*16+4,5);
        int output=buffer(1,pool),counts=buffer(3,counter),headers=buffer(5,header),records=0;
        try {
            uint(program,"uCapacity",cap);uint(program,"uDirectHexBatch",1);
            for(int n:new int[]{255,256,257,1024,1025}) {
                inputs.clear();for(int i=0;i<n;i++)check(inputs.offer(i,2,3,.01,.02,.03,0xff1256ab),"direct input admission");
                var floats=inputs.encode(0,1);var encoded=bytes(floats.remaining()*4);encoded.asFloatBuffer().put(floats);
                if(records!=0)GL15.glDeleteBuffers(records);records=buffer(4,encoded);
                upload(counts,bytes(16));for(int i=cap*64;i<pool.capacity();i+=4)pool.putInt(i,0x5a5a5a5a);upload(output,pool);
                uint(program,"uTotalSpawn",n);uint(program,"uEmitCount",n);dispatch(program,(n+63)/64);
                read(counts,counter);check(counter.getInt(0)==n,"direct batch lost inputs at shared command limit");
                read(output,pool);var seen=new BitSet();
                for(int i=0;i<Math.min(n,cap);i++){
                    int b=i*64;int x=(int)pool.getFloat(b);check(x>=0&&x<n&&!seen.get(x),"direct input duplicate/position");seen.set(x);
                    check(Math.abs(pool.getFloat(b+32)-0x12/255f)<1e-6,"direct RGB lost");
                    check(pool.getFloat(b+44)<0,"direct gravity flag lost");
                    check(Math.abs(pool.getFloat(b+52)*20-Math.round(pool.getFloat(b+52)*20))<1e-4,"direct lifetime is not integer ticks");
                }
                for(int i=cap*64;i<pool.capacity();i+=4)check(pool.getInt(i)==0x5a5a5a5a,"direct emission exceeded capacity");
            }
        } finally {for(int id:new int[]{output,counts,headers,records})GL15.glDeleteBuffers(id);GL20.glDeleteProgram(program);}
        System.out.println("Direct Hex batches: 255/256/257 and saturation passed");
    }

    static void mixedEmitWriter() throws Exception {
        int program=compute("emit.comp"),cap=1024;
        var writer=new com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter();
        var header=bytes(640);
        header.asFloatBuffer().put(com.iridium126.createmanaindustry.client.particles.engine.HexSpecs.packedSprayHeader());
        header.asFloatBuffer().position(80).put(com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec.builder().speed(0,0).gravity(0,0,0).life(10,10).build().packed());
        var pool=bytes((cap+1)*64);var counter=bytes(16);
        int output=buffer(1,pool),counts=buffer(3,counter),headers=buffer(5,header),commands=0;
        try {
            uint(program,"uCapacity",cap);uint(program,"uDirectHexBatch",0);
            for(boolean compact:new boolean[]{false,true}) {
                writer.clear();
                for(int i=0;i<256;i++) {
                    if(i%3==0)check(writer.basic(1,1,new net.minecraft.world.phys.Vec3(i,0,0),0),"mixed writer basic admission");
                    else {
                        int rgb=0xff000000|((i+1)<<16)|((256-i)<<8)|i;
                        var palette=com.iridium126.createmanaindustry.client.hex.particles.HexPigmentData.palette(new int[]{rgb},0,400);
                        check(writer.parameterized(0,3,i,0,0,0,0,0,0,target->palette.write(target,0,0)),"mixed writer parameter admission");
                    }
                }
                if(compact)writer.limitParticles(writer.particles()/2,false);
                var encoded=writer.encode(37);var raw=bytes(encoded.remaining()*4);raw.asFloatBuffer().put(encoded);
                if(commands!=0)GL15.glDeleteBuffers(commands);commands=buffer(4,raw);
                upload(counts,bytes(16));for(int b=cap*64;b<pool.capacity();b+=4)pool.putInt(b,0x5a5a5a5a);upload(output,pool);
                uint(program,"uTotalSpawn",writer.particles());uint(program,"uEmitCount",writer.size());
                uint(program,"uHexParameterBase",writer.size()*12);dispatch(program,(writer.particles()+63)/64);
                read(counts,counter);check(counter.getInt(0)==writer.particles(),"mixed writer spawn count/prefix");read(output,pool);
                int[] seen=new int[256];
                for(int i=0;i<writer.particles();i++) {
                    int b=i*64,x=Math.round(pool.getFloat(b));check(x>=0&&x<256,"mixed writer origin corrupted");seen[x]++;
                    if(x%3!=0) {
                        check(pool.getInt(b+60)==0,"mixed writer selected wrong behavior header");
                        // ColorProvider clamps dark RGB to minimum luminance; test the actual per-command palette.
                        int rawRgb=0xff000000|((x+1)<<16)|((256-x)<<8)|x;
                        var provider=new at.petrak.hexcasting.api.pigment.ColorProvider(){@Override protected int getRawColor(float t,net.minecraft.world.phys.Vec3 pos){return rawRgb;}};
                        int expected=provider.getColor(0,net.minecraft.world.phys.Vec3.ZERO);
                        for(int c=0;c<3;c++)check(Math.round(pool.getFloat(b+32+c*4)*255)==((expected>>(16-c*8))&255),"mixed writer palette detached from command");
                    }
                }
                for(int x=0;x<256;x++)check(seen[x]==(compact?(x%3==0?0:1):(x%3==0?1:3)),"mixed writer lost/duplicated row after compaction");
                for(int b=cap*64;b<pool.capacity();b+=4)check(pool.getInt(b)==0x5a5a5a5a,"mixed writer exceeded pool");
            }
        } finally {for(int id:new int[]{output,counts,headers,commands})GL15.glDeleteBuffers(id);GL20.glDeleteProgram(program);}
        System.out.println("Bounded writer: mixed ordinary/native commands, 170 palettes, compaction and slot reuse passed");
    }

    static void dispatchBounds() throws Exception {
        int program=compute("prepare_dispatch.comp");
        ByteBuffer previous=bytes(16), current=bytes(16), commands=bytes(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.INDIRECT_UINTS*4), args=bytes(36);
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
        ByteBuffer commands=bytes(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.INDIRECT_UINTS*4), args=bytes(36);int indirect=buffer(2,commands), dispatchBuffer=buffer(29,args);
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
        gpu.commitPopulation(10);int role=gpu.populationBufferId(10);check(role>0,"role publication missing");
        gpu.beginComputeFrame();gpu.restoreCommittedFrame();check(gpu.populationBufferId(10)==role,"abort discarded committed role index");
        gpu.beginComputeFrame();gpu.swap();check(gpu.populationBufferId(10)==0,"pool swap exposed old-generation roles");gpu.commitPopulation(11);check(gpu.populationBufferId(11)>0,"next role generation did not publish");
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

    static void particleVisibility() throws Exception {
        var gpu=new com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers();
        check(gpu.init(1000,8),"visibility buffer init");
        Class<?> geometry=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.AllayModelGeometry");
        Field vertices=geometry.getDeclaredField("VERTICES"),indices=geometry.getDeclaredField("INDICES"),body=geometry.getDeclaredField("BODY_OPAQUE_INDEX_COUNT"),opaque=geometry.getDeclaredField("OPAQUE_INDEX_COUNT");
        for(Field f:new Field[]{vertices,indices,body,opaque})f.setAccessible(true);
        gpu.uploadModelGeometry((float[])vertices.get(null),(int[])indices.get(null),body.getInt(null),opaque.getInt(null));
        int additive=link(stage("additive.vsh",GL20.GL_VERTEX_SHADER),stage("additive.fsh",GL20.GL_FRAGMENT_SHADER));
        int textured=link(stage("textured.vsh",GL20.GL_VERTEX_SHADER),stage("textured.fsh",GL20.GL_FRAGMENT_SHADER));
        int model=link(stage("model.vsh",GL20.GL_VERTEX_SHADER),stage("model.fsh",GL20.GL_FRAGMENT_SHADER));
        int keygen=compute("keygen.comp"),hist=compute("radix_hist.comp"),scan=compute("radix_scan.comp"),scatter=compute("radix_scatter.comp");
        int framebuffer=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),white=GL11.glGenTextures();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
        check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"visibility framebuffer");
        GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,white);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes(4).putInt(0,-1));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
        var foreign=buffer(0,bytes(65536));var boundary=new com.iridium126.createmanaindustry.client.particles.engine.ParticleComputeState();
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        try {
            // All tests draw after the pool has swapped and foreign state was restored.
            // Include each billboard alone, mixed with MODEL, and an all-culled frame.
            for(int scene=0;scene<7;scene++)for(boolean early:new boolean[]{false,true}) {
                boolean models=scene==3;boolean culled=scene==5||scene==6;int count=scene==6?0:scene<3?1:4;
                gpu.beginBindings();
                for(int binding=0;binding<16;binding++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,binding,foreign);
                if(early)boundary.capture();
                gpu.beginComputeFrame();
                var pool=bytes(count*64);
                for(int i=0;i<count;i++){
                    int material=scene<3?new int[]{0,1,3}[scene]:new int[]{0,1,3,models?2:0}[i];
                    pool.putFloat(i*64,culled?5:0).putFloat(i*64+12,1).putFloat(i*64+32,1).putFloat(i*64+36,1).putFloat(i*64+40,1).putFloat(i*64+44,1);
                    pool.putFloat(i*64+48,1).putFloat(i*64+52,10).putFloat(i*64+56,.123f).putInt(i*64+60,material);
                }
                gpu.bindParticleWrite(1);upload(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,1),pool);
                for(int material=0;material<4;material++) {
                    var h=new float[80];h[5*4+2]=h[5*4+3]=.4f;h[6*4]=1;h[6*4+2]=1;h[6*4+3]=1;h[7*4]=material;
                    h[8*4]=h[8*4+1]=h[8*4+2]=h[8*4+3]=1;h[16*4+1]=1;gpu.setEmitterHeader(material,h);
                }
                gpu.uploadDirtyEmitters();gpu.bindCounter(3,0);int counter=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,3);upload(counter,bytes(16).putInt(0,count));
                gpu.bindIndirect(2);int commands=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,2);
                var cmd=bytes(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.INDIRECT_UINTS*4);for(int command:new int[]{0,1,5})cmd.putInt(command*20,6);upload(commands,cmd);
                gpu.bindParticleWrite(0);gpu.bindEmitters(5);gpu.bindSort(7,gpu.sortBuffer(0));gpu.bindSort(13,gpu.sortBuffer(1));gpu.bindOrderAdd();gpu.bindOrderOpaque();
                uint(keygen,"uUpper",1000);uint(keygen,"uCarrierBase",1000);uint(keygen,"uPackageStaged",0);
                float[] identity=new org.joml.Matrix4f().get(new float[16]);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(keygen,"uView"),false,identity);
                GL20.glUniform1f(GL20.glGetUniformLocation(keygen,"uDepthLogRange"),8);GL20.glUniform1f(GL20.glGetUniformLocation(keygen,"uMaxDepth"),256);
                GL20.glUniform4fv(GL20.glGetUniformLocation(keygen,"uFrustum"),new float[]{1,0,0,1,-1,0,0,1,0,1,0,1,0,-1,0,1,0,0,1,1,0,0,-1,1});dispatch(keygen,1);
                read(commands,cmd);for(int mode=0;mode<3;mode++)check(cmd.getInt(new int[]{4,104,24}[mode])==(culled?0:scene<3?(scene==mode?1:0):1+(mode==0&&!models?1:0)),"visible billboard command / scene "+scene+" mode "+mode);
                check(cmd.getInt(44)==(models&&!culled?1:0),"MODEL main cull count");
                int permutation=gpu.sortBuffer(1);gpu.bindSort(6,gpu.sortBuffer(0));gpu.bindSort(7,permutation);buffer(9,bytes(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.RADIX_BINS*4));buffer(10,bytes(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.RADIX_BINS*4+4));
                dispatch(hist,1);dispatch(scan,1);dispatch(scatter,1);
                gpu.swap();gpu.restoreCommittedFrame();if(early)boundary.restore();
                // Simulate the ordinary package/light pass overwriting all draw slots,
                // including sort slot 7; this must not affect the next material.
                for(int mode=0;mode<3;mode++) {
                    for(int binding=0;binding<16;binding++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,binding,foreign);
                    gpu.beginBindings();gpu.bindBillboardDrawInputs(mode,permutation);
                    int program=mode==0?additive:textured;GL20.glUseProgram(program);
                    GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ModelViewMat"),false,identity);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ProjMat"),false,identity);
                    GL20.glUniform3f(GL20.glGetUniformLocation(program,"uCamRight"),1,0,0);GL20.glUniform3f(GL20.glGetUniformLocation(program,"uCamUp"),0,1,0);
                    GL20.glUniform1f(GL20.glGetUniformLocation(program,"uFadeDist"),100);GL20.glUniform1f(GL20.glGetUniformLocation(program,"uGlow"),1);
                    GL20.glUniform1f(GL20.glGetUniformLocation(program,"uAtlasCols"),1);GL20.glUniform1f(GL20.glGetUniformLocation(program,"uAtlasRows"),1);
                    GL20.glUniform1i(GL20.glGetUniformLocation(program,"uMode"),mode==1?1:0);GL20.glUniform1i(GL20.glGetUniformLocation(program,"uSprite"),1);
                    GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,white);
                    GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);gpu.drawIndirect(new int[]{0,1,5}[mode]);
                    var pixel=bytes(4);GL11.glReadPixels(32,32,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
                    check((pixel.get(0)&255)>30==(cmd.getInt(new int[]{4,24,104}[mode])>0),"billboard pixel missing / scene "+scene+" early="+early+" mode="+mode);
                    check(GL11.glGetError()==GL11.GL_NO_ERROR,"billboard draw GL error");
                }
                for(int binding=0;binding<16;binding++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,binding,foreign);
                gpu.beginBindings();gpu.bindModelDrawInputs(permutation);
                check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,7)==permutation,"MODEL inherited package or shadow sort list");
                check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,1)==gpu.particleReadBufferId(),"MODEL used stale pool");
                GL20.glUseProgram(model);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(model,"ModelViewMat"),false,identity);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(model,"ProjMat"),false,identity);
                GL20.glUniform1f(GL20.glGetUniformLocation(model,"uFadeDist"),100);GL20.glUniform1i(GL20.glGetUniformLocation(model,"uSprite"),1);uint(model,"uCarrierBase",1000);
                // Preserve the geometry counts installed by uploadModelGeometry in the real model command.
                cmd.putInt(40,body.getInt(null));cmd.putInt(60,opaque.getInt(null)-body.getInt(null)).putInt(68,body.getInt(null));cmd.putInt(80,((int[])indices.get(null)).length-opaque.getInt(null)).putInt(88,opaque.getInt(null));upload(commands,cmd);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);gpu.drawModelSegments();var image=bytes(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,image);
                boolean lit=false;for(int i=0;i<image.capacity();i+=4)lit|=(image.get(i)&255)>20;check(lit==(models&&!culled),"MODEL fallback pixels missing");
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"MODEL draw GL error");
            }
            int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
            for(int binding:new int[]{16,24,27,31})GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,binding,foreign,alignment,64);
            boundary.capture();gpu.unbindShaders();boundary.restore();
            for(int binding:new int[]{16,24,27,31})check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,binding)==foreign&&GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,binding)==alignment&&GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,binding)==64,"early compute lost high SSBO range "+binding);
        } finally {
            GL20.glUseProgram(0);gpu.unbindShaders();gpu.free();GL15.glDeleteBuffers(foreign);
            for(int p:new int[]{additive,textured,model,keygen,hist,scan,scatter})GL20.glDeleteProgram(p);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(framebuffer);GL11.glDeleteTextures(color);GL11.glDeleteTextures(white);
        }
        System.out.println("Actual additive/ALPHA/OPAQUE/MODEL pixels: late draws, early state restoration, mixed/empty/offscreen scenes and foreign package/high-SSBO bindings passed");
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
        Files.writeString(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/particle-submit-benchmark.csv"),report);
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
            ByteBuffer commands=bytes(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.INDIRECT_UINTS*4);commands.putInt(21*4,n);
            ByteBuffer input=bytes(Math.max(1,n)*8), result=bytes((n+1)*8);
            Random random=new Random(28);
            for(int i=0;i<n;i++) input.putInt(i*8,distribution==0?random.nextInt(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.RADIX_BINS):42).putInt(i*8+4,i);
            result.putInt(n*8,0x5a5a5a5a);
            int cmd=buffer(2,commands), src=buffer(6,input), dst=buffer(7,result), hist=buffer(9,bytes(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.RADIX_BINS*4)), offs=buffer(10,bytes(com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.RADIX_BINS*4+4));
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
        if(benchmark)Files.writeString(Path.of(System.getProperty("cmi.benchmark.output", "build/benchmarks/current")+"/particle-sort-benchmark.csv"),report);
        System.out.println("Baseline and grouped sort order, payload, tail and capacity tests passed");
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
        for(String shader:List.of("prepare_dispatch.comp","gridbuild.comp","hit.comp","stormpos.comp","wavecontact.comp","block_emit.comp","capture.comp","update.comp","emit.comp","keygen.comp","reset.comp","radix_hist.comp","radix_scan.comp","radix_scatter.comp")) {
            GL20.glDeleteProgram(compute(shader));System.out.println("Compiled "+shader);
        }
        for(String shader:List.of("additive","model","textured"))
            GL20.glDeleteProgram(link(stage(shader+".vsh",GL20.GL_VERTEX_SHADER),stage(shader+".fsh",GL20.GL_FRAGMENT_SHADER)));
        buffer(31,bytes(4+2*4096*4));
        residentResources();
        nativeConjureMotion();
        ownedShaderCompiler();
        nativePigmentParameters();
        directHexBatch();
        mixedEmitWriter();
        dispatchBounds();
        capacity();
        deathChainAndRebirth();
        stableDamage();
        ringAndRollback();
        waveRetention();
        particleVisibility();
        atomicReload();
        sorting(Arrays.asList(args).contains("--benchmark"));
        if(Arrays.asList(args).contains("--benchmark"))submissionBenchmark();
        System.out.println("PASS: "+checks+" assertions");
        GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
    }
}
