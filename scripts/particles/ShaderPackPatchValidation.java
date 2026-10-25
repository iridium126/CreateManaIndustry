import com.google.common.collect.ImmutableList;
import com.iridium126.createmanaindustry.client.render.shaderpack.*;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.parsing.*;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import java.nio.file.*;
import java.util.*;

/** Real Iris include/preprocessor + runtime GLSL parser. Does not certify a client pipeline. */
public final class ShaderPackPatchValidation {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static String preprocess(Path root,String program){
        var path=AbsolutePackPath.fromAbsolutePath("/"+program);
        var graph=new IncludeGraph(root,ImmutableList.of(path),false);
        check(graph.getFailures().isEmpty(),"include graph "+graph.getFailures());
        var lines=new IncludeProcessor(graph).getIncludedFile(path);
        return JcppProcessor.glslPreprocessSource(String.join("\n",lines),List.of(new StringPair("MC_VERSION","12101"),new StringPair("MC_GL_VERSION","450"),new StringPair("MC_GLSL_VERSION","450"),new StringPair("MC_RENDER_QUALITY","1.0"),new StringPair("MC_SHADOW_QUALITY","1.0"),new StringPair("IS_IRIS","1")));
    }
    static void parse(String source){var parser=new ASTParser();parser.setThrowParseErrors(true);parser.parseTranslationUnit(RootSupplier.DEFAULT,source);checks++;}
    public static void main(String[] args)throws Exception{
        List<Path> packs;try(var files=Files.list(Path.of("run/shaderpacks"))){packs=files.filter(p->p.toString().endsWith(".zip")).sorted().toList();}
        check(packs.size()>=8,"eight local packs required");
        int patched=0,nativeScenes=0;
        for(Path pack:packs){
            var inventory=ShaderPackInventory.readPack(pack);var plan=ShaderPatchPlan.prepare(inventory);checks++;
            var caps=PackCapabilities.detect(inventory,plan);check(caps.worldPath()!=PackCapabilities.WorldPath.BASELINE,"native candidate required: "+pack.getFileName());
            if(!plan.rawLayers()&&caps.nativeScene()==null)continue;
            Path folder=Path.of("build/shaderpack-patch-validation",inventory.digest().substring(0,12));Files.createDirectories(folder);
            for(var entry:inventory.sources().entrySet()){Path file=folder.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());}
            if(caps.nativeScene()!=null){
                var scene=caps.nativeScene();String name="composite"+(scene.compositeIndex()==0?"":scene.compositeIndex())+".fsh";
                String entry=inventory.source("world0/"+name).isEmpty()?name:"world0/"+name;
                String active=preprocess(folder,entry);parse(active);
                Files.writeString(folder.resolve("cmi-active-native.glsl"),active);
                check(scene.acceptsActiveSource(active),"default effective source contract "+pack.getFileName());
                if(scene.alphaBlendEncoding()){
                    Path settings=folder.resolve("lib/settings.glsl");String original=Files.readString(settings);
                    String changed=java.util.regex.Pattern.compile("(?m)(#define\\s+ALPHA_BLEND\\s+)0\\b").matcher(original).replaceAll(m->m.group(1)+"1");
                    check(!original.equals(changed),"alpha blend option fixture changed");Files.writeString(settings,changed);
                    try{String alternate=preprocess(folder,entry);parse(alternate);check(scene.acceptsActiveSource(alternate),"alternate effective ALPHA_BLEND contract");check(scene.gammaForAlphaBlend(1)==1,"alternate scene is linear");}finally{Files.writeString(settings,original);}
                }
                nativeScenes++;System.out.println(pack.getFileName()+": native "+caps.worldPath()+" colortex"+scene.buffer()+" effective source confirmed");
            }
            if(!plan.rawLayers())continue;
            parse(preprocess(folder,"composite.fsh"));
            for(var edit:plan.edits().entrySet())Files.writeString(folder.resolve(edit.getKey()),edit.getValue());
            String processed=preprocess(folder,"composite.fsh");parse(processed);
            check(processed.contains("cmiMist"),"compiled branch contains the CMI layer merge");
            var constructor=PackRenderTargetDirectives.class.getDeclaredConstructor(Set.class);constructor.setAccessible(true);
            var targets=constructor.newInstance(Set.of(plan.mistBuffer(),plan.glowBuffer()));
            var directives=new DispatchingDirectiveHolder();targets.acceptDirectives(directives);
            ConstDirectiveParser.findDirectives(processed).forEach(directives::processDirective);
            for(int buffer:new int[]{plan.mistBuffer(),plan.glowBuffer()}){
                var settings=targets.getRenderTargetSettings().get(buffer);
                check(settings.getInternalFormat()==InternalTextureFormat.RGBA16F,"raw layers must retain fractional/HDR radiance");
                check(settings.shouldClear(),"raw layer must clear each frame");
                check(settings.getClearColor().isPresent()&&settings.getClearColor().get().equals(new org.joml.Vector4f(0,0,0,0)),"raw layer clear must be transparent black");
            }
            patched++;System.out.println(pack.getFileName()+": original + patched composite parsed with Iris runtime libraries");
        }
        check(patched>0,"curve layer sample must be exercised");
        check(nativeScenes==6,"all six migrated native scene contracts exercised");
        System.out.println("Shaderpack patch parser: "+checks+" assertions passed");
    }
}
