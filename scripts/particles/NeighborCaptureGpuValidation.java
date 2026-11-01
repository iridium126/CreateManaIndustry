import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.NeighborInputCapture;

/** Real-driver immutable SSBO/TBO/image/3D/uniform freeze and GL-state preservation. */
public final class NeighborCaptureGpuValidation {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static int buffer(int value){int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,new int[]{value,value+1,value+2,value+3},GL15.GL_DYNAMIC_COPY);return id;}
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);long window=GLFW.glfwCreateWindow(32,32,"input freeze regression",0,0);check(window!=0,"context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        Path root=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/neighbor-r5"),"capture-regression-"+UUID.randomUUID()).toAbsolutePath();Files.createDirectories(root);
        int input=buffer(105),output=buffer(0),image=buffer(701),sample=buffer(901),previousRead=buffer(23),previousWrite=buffer(24),previousPack=buffer(25);
        int alignment=GL11.glGetInteger(GL43.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,sample);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,alignment+16,GL15.GL_DYNAMIC_COPY);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,alignment,new int[]{901,902,903,904});
        int imageTexture=GL11.glGenTextures(),sampleTexture=GL11.glGenTextures(),atlas=GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,imageTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32UI,image);GL42.glBindImageTexture(3,imageTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,sampleTexture);GL43.glTexBufferRange(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32UI,sample,alignment,16);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL12.GL_TEXTURE_3D,atlas);GL12.glTexImage3D(GL12.GL_TEXTURE_3D,0,GL30.GL_R32UI,2,2,2,0,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,new int[]{1,2,3,4,5,6,7,8});
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,"#version 450 core\nlayout(local_size_x=1)in;layout(std430,binding=0)readonly buffer Input{uvec4 a;};layout(std430,binding=1)buffer Output{uvec4 b;};layout(rgba32ui,binding=3)uniform uimageBuffer img;layout(binding=2)uniform usamplerBuffer tb;layout(binding=1)uniform usampler3D atlas;uniform uint bits;void main(){b=a+imageLoad(img,0)+texelFetch(tb,0)+texelFetch(atlas,ivec3(0),0)+uvec4(bits);}");GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));GL20.glDeleteShader(shader);GL20.glUseProgram(program);GL30.glUniform1ui(GL20.glGetUniformLocation(program,"bits"),-1);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,output);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,previousRead);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,previousWrite);GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,previousPack);GL13.glActiveTexture(GL13.GL_TEXTURE5);GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,13);
        var capture=NeighborInputCapture.INSTANCE;check(capture.request("fixture"),"request accepted");capture.freezeForValidation(program,root);
        check(GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER)==previousRead,"copy read restored");check(GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER)==previousWrite,"copy write restored");check(GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)==previousPack,"PBO restored");check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE5,"active texture restored");check(GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH)==13,"pack row length restored");
        // Mutate source after the ordered copy. Frozen data must retain the earlier generation.
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,new int[]{999,999,999,999});GL11.glFlush();
        Path manifest=null;long deadline=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<deadline){capture.poll();try(var paths=Files.walk(root)){manifest=paths.filter(p->p.getFileName().toString().equals("manifest.json")).findFirst().orElse(null);}if(manifest!=null)break;Thread.sleep(2);}
        check(manifest!=null,"asynchronous export completed");JsonObject data=JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();check(data.get("complete").getAsBoolean(),"committed manifest");
        String source=data.getAsJsonObject("ssbo").getAsJsonObject("0").get("file").getAsString();check(ByteBuffer.wrap(Files.readAllBytes(manifest.getParent().resolve(source))).order(ByteOrder.LITTLE_ENDIAN).getInt()==105,"immutable input generation");
        check(data.getAsJsonObject("images").has("img"),"image storage captured");check(data.getAsJsonObject("textures").has("tb")&&data.getAsJsonObject("textures").has("atlas"),"samplers captured");
        check(data.get("schema").getAsInt()==2,"explicit coverage schema");check(!data.getAsJsonObject("coverage").get("contacts").getAsBoolean(),"do not invent full-chain coverage");
        check(data.getAsJsonObject("textures").getAsJsonObject("tb").get("offset").getAsInt()==alignment&&data.getAsJsonObject("textures").getAsJsonObject("tb").get("bytes").getAsInt()==16,"TBO range captured");
        check(data.getAsJsonObject("uniforms").getAsJsonObject("bits").getAsJsonArray("value_bits").get(0).getAsInt()==-1,"unsigned uniform raw bits");
        for(var entry:data.getAsJsonObject("sha256").entrySet()){byte[] bytes=Files.readAllBytes(manifest.getParent().resolve(entry.getKey()));check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(entry.getValue().getAsString()),"snapshot checksum");}
        check(GL11.glGetError()==0,"GL errors");capture.close();for(int b:new int[]{input,output,image,sample,previousRead,previousWrite,previousPack})GL15.glDeleteBuffers(b);for(int texture:new int[]{imageTexture,sampleTexture,atlas})GL11.glDeleteTextures(texture);GL20.glDeleteProgram(program);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();System.out.println("Input freeze GPU regression passed: "+manifest);
    }
}
