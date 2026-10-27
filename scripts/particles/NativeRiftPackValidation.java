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
    static Map<String,String> options(String family,boolean off) {
        return switch(family) {
            case "complementary" -> Map.of("CLOUD_STYLE_DEFINE",off?"0":"3");
            case "bsl" -> Map.of("CLOUDS",off?"0":"1");
            case "bliss" -> off?Map.of("VOLUMETRIC_CLOUDS","-"):Map.of("CLOUDS_QUALITY","1.0");
            case "iteration","solas" -> off?Map.of("VOLUMETRIC_CLOUDS","-","PLANAR_CLOUDS","-"):Map.of("VOLUMETRIC_CLOUDS","-");
            case "sildur" -> Map.of("Clouds",off?"0":"4");
            case "sundial-lite" -> off?Map.of("CLOUD_TYPE","0","PLANE_CLOUD","-"):Map.of("CLOUD_TYPE","1");
            case "photon" -> Map.of("CLOUDS_CUMULUS","-","CLOUDS_CUMULUS_CONGESTUS","-","CLOUDS_ALTOCUMULUS","-","CLOUDS_CIRRUS","-","BLOCKY_CLOUDS",off?"-":"");
            default -> throw new IllegalArgumentException(family);
        };
    }
    static Map<String,String> configure(Map<String,String> sources,Map<String,String> options) {
        var result=new TreeMap<>(sources);
        for(var option:options.entrySet()) {
            var pattern=java.util.regex.Pattern.compile("(?m)^[ \t]*(?://[ \t]*)?#define[ \t]+"+option.getKey()+"\\b[^\\r\\n]*");int changes=0;
            for(var entry:result.entrySet()) {
                var matcher=pattern.matcher(entry.getValue());if(!matcher.find())continue;changes++;
                entry.setValue(matcher.replaceAll(java.util.regex.Matcher.quoteReplacement((option.getValue().equals("-")?"//#define ":"#define ")+option.getKey()+" "+(option.getValue().equals("-")?"":option.getValue()))));
            }
            check(changes>0,"actual option anchor "+option.getKey());
        }
        return result;
    }
    static void run()throws Exception{
        List<Path> samples;try(var files=Files.list(Path.of("run/shaderpacks"))){samples=files.filter(p->p.toString().endsWith(".zip")).sorted().toList();}
        check(samples.size()>=8,"eight shader samples required");
        for(Path sample:samples) {
            var inventory=ShaderPackInventory.readPack(sample);var colored=ShaderPatchPlan.prepare(inventory);var combined=new TreeMap<>(inventory.sources());combined.putAll(colored.edits());
            var rift=NativeRiftPatchPlan.prepare(ShaderPackInventory.of(combined));check(rift.enabled(),sample.getFileName()+": "+rift.reason());combined.putAll(rift.edits());
            var defaults=new TreeMap<>(combined);
            for(String variant:List.of("default","clouds-off","alternate-clouds")) {
            combined=new TreeMap<>(variant.equals("default")?defaults:configure(defaults,options(rift.family(),variant.equals("clouds-off"))));
            int cloudPrograms=0;
            Path root=Path.of("build/rift-pack-validation",inventory.digest().substring(0,12),variant);Files.createDirectories(root);
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
                if(GL20.glGetUniformLocation(linked,"CmiStormCloud")>=0&&GL20.glGetUniformLocation(linked,"CmiStormCenter")>=0)cloudPrograms++;
                if(program.required()){check(GL20.glGetUniformLocation(linked,"CmiRiftEnabled")>=0,"Required main sky has no active rift enable uniform: "+sample.getFileName()+" "+entry);check(GL20.glGetUniformLocation(linked,"CmiTargetSky")>=0,"Required main sky has no native target sampler: "+sample.getFileName()+" "+entry);check(GL20.glGetUniformLocation(linked,"CmiJuliaHit")>=0,"Required main sky has no Julia projection sampler: "+sample.getFileName()+" "+entry);}
                } finally {for(int shader:shaders)GL20.glDeleteShader(shader);GL20.glDeleteProgram(linked);}
            }
            for(var program:rift.programs()) {
                String name=program.array().toLowerCase(Locale.ROOT)+(program.index()==0?"":program.index());
                String entry=Files.isRegularFile(root.resolve("world0/"+name+".fsh"))?"world0/"+name:name;
                String vertex=NativeSkySources.readOnly(preprocess(root,entry+".vsh"));
                String fragment=NativeSkySources.isolate(preprocess(root,entry+".fsh"),rift.family());
                String geometry=Files.isRegularFile(root.resolve(entry+".gsh"))?NativeSkySources.readOnly(preprocess(root,entry+".gsh")):null;
                var transformed=net.irisshaders.iris.pipeline.transform.TransformPatcher.patchComposite("cmi_target_"+name,vertex,geometry,fragment,net.irisshaders.iris.shaderpack.texture.TextureStage.COMPOSITE_AND_FINAL,new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
                var ids=new ArrayList<Integer>();int link=GL20.glCreateProgram();
                try {
                    for(var stage:transformed.entrySet()) {
                        if(stage.getValue()==null)continue;
                        int type=stage.getKey()==net.irisshaders.iris.pipeline.transform.PatchShaderType.VERTEX?GL20.GL_VERTEX_SHADER:stage.getKey()==net.irisshaders.iris.pipeline.transform.PatchShaderType.GEOMETRY?GL32.GL_GEOMETRY_SHADER:GL20.GL_FRAGMENT_SHADER;
                        int id=compile(stage.getValue(),type,"target "+sample.getFileName()+" "+name,root.resolve("target-"+name+"-"+stage.getKey()+".glsl"));ids.add(id);GL20.glAttachShader(link,id);
                    }
                    GL20.glLinkProgram(link);check(GL20.glGetProgrami(link,GL20.GL_LINK_STATUS)!=0,"target link "+sample.getFileName()+" "+name+": "+GL20.glGetProgramInfoLog(link));
                } finally {ids.forEach(GL20::glDeleteShader);GL20.glDeleteProgram(link);}
            }
            if(variant.equals("default"))check(cloudPrograms>0,"effective default cloud field "+sample.getFileName());
            // Some packs retain cloud-shadow helpers even with visible clouds disabled; uniform liveness alone is not a visibility claim.
            System.out.println(sample.getFileName()+" ["+variant+"]: native source and isolated target sky/cloud programs compiled; cloud field programs="+cloudPrograms);
            }
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
