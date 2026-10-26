import com.google.common.collect.ImmutableList;
import com.iridium126.createmanaindustry.client.render.shaderpack.*;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Real Iris preprocessing/parser and driver compilation of actual eight-pack programs, not stand-in shaders. */
public final class NativeRiftPackValidation {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static String preprocess(Path root,String name){
        var path=AbsolutePackPath.fromAbsolutePath("/"+name);var graph=new IncludeGraph(root,ImmutableList.of(path),false);
        check(graph.getFailures().isEmpty(),"include "+name+": "+graph.getFailures());
        var lines=new IncludeProcessor(graph).getIncludedFile(path);
        return JcppProcessor.glslPreprocessSource(String.join("\n",lines),List.of(new StringPair("MC_VERSION","12101"),new StringPair("MC_GL_VERSION","450"),new StringPair("MC_GLSL_VERSION","450"),new StringPair("MC_HAND_DEPTH","0.125"),new StringPair("MC_RENDER_QUALITY","1.0"),new StringPair("MC_SHADOW_QUALITY","1.0"),new StringPair("IS_IRIS","1")));
    }
    static int compile(String source,int type,String label,Path output)throws Exception{
        Files.writeString(output,source);
        var parser=new ASTParser();parser.setThrowParseErrors(true);parser.parseTranslationUnit(RootSupplier.DEFAULT,source);checks++;
        int shader=GL20.glCreateShader(type);
            GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
            check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==GL11.GL_TRUE,label+"\n"+GL20.glGetShaderInfoLog(shader)+"\n"+output);
        return shader;
    }
    static void run()throws Exception{
        List<Path> samples;try(var files=Files.list(Path.of("run/shaderpacks"))){samples=files.filter(p->p.toString().endsWith(".zip")).sorted().toList();}
        check(samples.size()>=8,"eight shader samples required");
        for(Path sample:samples) {
            var inventory=ShaderPackInventory.readPack(sample);var colored=ShaderPatchPlan.prepare(inventory);var combined=new TreeMap<>(inventory.sources());combined.putAll(colored.edits());
            var rift=NativeRiftPatchPlan.prepare(ShaderPackInventory.of(combined));check(rift.enabled(),sample.getFileName()+": "+rift.reason());combined.putAll(rift.edits());
            Path root=Path.of("build/rift-pack-validation",inventory.digest().substring(0,12));Files.createDirectories(root);
            for(var entry:combined.entrySet()){Path file=root.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());}
            for(var program:rift.programs()){
                String baseName=program.array().toLowerCase(Locale.ROOT)+(program.index()==0?"":program.index());
                String entry=Files.isRegularFile(root.resolve("world0/"+baseName+".fsh"))?"world0/"+baseName:baseName;
                String vertex=preprocess(root,entry+".vsh"),fragment=preprocess(root,entry+".fsh");String geometry=Files.isRegularFile(root.resolve(entry+".gsh"))?preprocess(root,entry+".gsh"):null;
                check(fragment.contains(program.marker()),"effective native rift marker: "+entry);
                var transformed=net.irisshaders.iris.pipeline.transform.TransformPatcher.patchComposite("cmi-rift-"+baseName,vertex,geometry,fragment,net.irisshaders.iris.shaderpack.texture.TextureStage.COMPOSITE_AND_FINAL,new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
                var shaders=new ArrayList<Integer>();int linked=GL20.glCreateProgram();try {
                for(String suffix:geometry==null?List.of(".fsh",".vsh"):List.of(".fsh",".vsh",".gsh")) {
                String name=baseName+suffix;
                check(Files.isRegularFile(root.resolve(entry+suffix)),"program "+entry+suffix+" for "+sample.getFileName());
                String source=transformed.get(suffix.equals(".fsh")?net.irisshaders.iris.pipeline.transform.PatchShaderType.FRAGMENT:suffix.equals(".gsh")?net.irisshaders.iris.pipeline.transform.PatchShaderType.GEOMETRY:net.irisshaders.iris.pipeline.transform.PatchShaderType.VERTEX);
                int shader=compile(source,suffix.equals(".fsh")?GL20.GL_FRAGMENT_SHADER:suffix.equals(".gsh")?GL32.GL_GEOMETRY_SHADER:GL20.GL_VERTEX_SHADER,sample.getFileName()+" "+entry,root.resolve("cmi-"+name+".glsl"));shaders.add(shader);GL20.glAttachShader(linked,shader);
                }
                GL20.glLinkProgram(linked);check(GL20.glGetProgrami(linked,GL20.GL_LINK_STATUS)==GL11.GL_TRUE,sample.getFileName()+" "+entry+" link: "+GL20.glGetProgramInfoLog(linked));
                if(program.required()){check(GL20.glGetUniformLocation(linked,"CmiRiftEnabled")>=0,"Required main sky has no active rift enable uniform: "+sample.getFileName()+" "+entry);check(GL20.glGetUniformLocation(linked,"CmiSkyContext")>=0,"Required main sky has no active shared sky context: "+sample.getFileName()+" "+entry);}
                } finally {for(int shader:shaders)GL20.glDeleteShader(shader);GL20.glDeleteProgram(linked);}
            }
            System.out.println(sample.getFileName()+": native rift main + cloud programs compiled");
        }
        System.out.println("Native rift pack validation: "+checks+" assertions passed");
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        long window=GLFW.glfwCreateWindow(32,32,"Native rift pack validation",0,0);check(window!=0,"OpenGL context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));run();}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
