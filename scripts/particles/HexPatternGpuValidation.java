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
        buffer(6,unsorted);int sortResult=buffer(7,bytes(48));buffer(9,bytes(1024*4));buffer(10,bytes(1024*4));
        dispatch(compute("radix_hist.comp"),1);dispatch(compute("radix_scan.comp"),1);dispatch(compute("radix_scatter.comp"),1);
        ByteBuffer sorted=bytes(48);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,sortResult);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,sorted);
        Arrays.sort(keys);for(int i=0;i<6;i++)check(keys[i]==sorted.getInt(i*8),"three-way depth partition corrupt");
        System.out.println("Pool reconciliation, generation rejection, compaction, anchors and three-way sort passed");
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
        Class<?> programs=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms");
        Field field=programs.getDeclaredField("PRELUDE");field.setAccessible(true);prelude=(String)field.get(null);
        for(String shader:List.of("update.comp","emit.comp","keygen.comp","reset.comp","radix_hist.comp","radix_scan.comp","radix_scatter.comp","hex_reconcile.comp","hex_prepare.comp")) {
            GL20.glDeleteProgram(compute(shader));System.out.println("Compiled "+shader);
        }
        for(String shader:List.of("hex_pattern","additive","model","textured"))
            GL20.glDeleteProgram(link(stage(shader+".vsh",GL20.GL_VERTEX_SHADER),stage(shader+".fsh",GL20.GL_FRAGMENT_SHADER)));
        colors();
        poolAndSort();
        geometry();
        System.out.println("PASS: "+checks+" assertions");
        GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
    }
}
