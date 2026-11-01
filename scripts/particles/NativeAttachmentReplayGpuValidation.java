import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Synthetic format/replay contract test; never represents a client performance capture. */
public class NativeAttachmentReplayGpuValidation {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(32,32,"Attachment replay contract",0,0);check(window!=0,"context");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            Path root=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(root);
            Path snapshots=root.resolve("synthetic-snapshots");Files.createDirectories(snapshots);ModelMergeGpuValidation.output=root;
            var data=new TreeMap<String,Object>();data.put("synthetic_test_snapshot",true);data.put("pack","fixture.zip");data.put("source_sha256","0123456789abcdef");
            data.put("world","minecraft:overworld");data.put("options_available",true);data.put("options",Map.of("CMI_REPLAY_TEST","true"));data.put("options_hash","fixture-hash");
            data.put("model_environment_macros",List.copyOf(ModelMaterialGpuValidation.DEFINES));data.put("model_draw_buffers",new int[]{0,1});
            data.put("color_attachment_formats_gl",Map.of("0",GL11.GL_RGBA8,"1",GL30.GL_RGBA16F));data.put("depth_format_gl",GL30.GL_DEPTH24_STENCIL8);
            Files.writeString(snapshots.resolve("fixture.json"),new com.google.gson.Gson().toJson(data));
            System.setProperty("cmi.draw.snapshots",snapshots.toString());
            ModelMergeGpuValidation.benchmarkEnvironment("fixture.zip","0123456789abcdef");
            ModelMergeGpuValidation.benchmarkFormats("fixture.zip","0123456789abcdef","fixture-hash");
            Files.writeString(root.resolve("fixture.vsh"),"#version 330 compatibility\n//#define CMI_REPLAY_TEST\n#ifdef CMI_REPLAY_TEST\nconst int result=7;\n#else\nconst int result=3;\n#endif\n");
            String processed=ModelMaterialGpuValidation.preprocess(root,"fixture.vsh");check(processed.contains("result=7")&&!processed.contains("result=3"),"Effective option values were not replayed");
            boolean rejected=false;try{ModelMergeGpuValidation.benchmarkFormats("fixture.zip","0123456789abcdef","different-options");}catch(IllegalStateException expected){rejected=true;}
            check(rejected,"Options mismatch must fail closed");ModelMergeGpuValidation.benchmarkFormats("fixture.zip","0123456789abcdef","fixture-hash");
            int shader=GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER),program=GL20.glCreateProgram();
            try{
                GL20.glShaderSource(shader,"#version 450 core\nlayout(location=0)out vec4 color;void main(){color=vec4(.5);}");GL20.glCompileShader(shader);GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
                try(var target=new ModelMergeGpuValidation.Target(program,32,32,true)){
                    for(int i=0;i<2;i++){GL11.glBindTexture(GL11.GL_TEXTURE_2D,target.colors[i]);check(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT)==(i==0?GL11.GL_RGBA8:GL30.GL_RGBA16F),"Native color format lost");}
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D,target.depth);check(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT)==GL30.GL_DEPTH24_STENCIL8,"Native depth/stencil format lost");
                }
            }finally{GL20.glDeleteShader(shader);GL20.glDeleteProgram(program);}
            check(GL11.glGetError()==0,"GL state");System.out.println("Native attachment replay GPU: option/macro replay, mismatch rejection, RGBA8/RGBA16F and D24S8 passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
