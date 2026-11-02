import com.iridium126.createmanaindustry.client.particles.shaderpack.*;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;

/** Constant-context candidate for the production shadow vertex input; native pack fragment is retained. */
public class ShadowDrawGpuValidation {
    static final List<String> rows=new ArrayList<>(List.of("pack,count,workload,scene,round,pair,aa_ms,a_ms,b_ms"));
    static final List<String> contracts=new ArrayList<>(List.of("pack,variant,geometry,active_images,coverage,checks"));
    static String candidate(String own){
        String result=own.replaceAll("\\buniform\\s+bool\\s+cmi_ShadowPass\\s*;","const bool cmi_ShadowPass=true;")
            .replaceAll("\\buniform\\s+int\\s+cmi_LodPass\\s*;","const int cmi_LodPass=0;")
            .replaceAll("\\buniform\\s+int\\s+cmi_FeatherPass\\s*,\\s*cmi_FeatherWordBase\\s*;","const int cmi_FeatherPass=0; uniform int cmi_FeatherWordBase;");
        if(result.equals(own)||!result.contains("const bool cmi_ShadowPass=true"))throw new IllegalStateException("CMI shadow declaration drift");return result;
    }
    static void draw(ModelMergeProductionFixture f,ModelMergeGpuValidation.Target target,int p,int count,int scene){
        target.clear(scene);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);GL11.glEnable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
        f.use(p,false);ModelMergeGpuValidation.integer(p,"cmi_ShadowPass",1);ModelMergeGpuValidation.integer(p,"cmi_SegmentMode",0);GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,f.body,GL11.GL_UNSIGNED_INT,0L,count);
        f.use(p,true);ModelMergeGpuValidation.integer(p,"cmi_SegmentMode",2);GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,f.opaque-f.body,GL11.GL_UNSIGNED_INT,f.body*4L,f.carrierCount);
        f.use(p,false);ModelMergeGpuValidation.integer(p,"cmi_SegmentMode",0);GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,f.indices-f.opaque,GL11.GL_UNSIGNED_INT,f.opaque*4L,count);
    }
    static double time(Runnable work){int q=GL15.glGenQueries();try{GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,q);work.run();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);return GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT)/1e6;}finally{GL15.glDeleteQueries(q);}}
    static boolean images(int p){for(int i=0;i<GL20.glGetProgrami(p,GL20.GL_ACTIVE_UNIFORMS);i++)try(var stack=org.lwjgl.system.MemoryStack.stackPush()){var size=stack.mallocInt(1);var type=stack.mallocInt(1);GL20.glGetActiveUniform(p,i,size,type);if(type.get(0)>=0x904c&&type.get(0)<=0x906d)return true;}return false;}
    static int[][] read(ModelMergeGpuValidation.Target t){
        if(t.w==64)return t.read();
        // Atlas shadow programs gate gl_FragCoord against their native resolution. Keep that viewport,
        // read a bounded tile around the opaque atlas centre instead of allocating 8 full-size CPU images.
        int size=t.w/4,y=t.h*5/8;int[][] result=new int[9][];
        // The native shader routes translucent entity art into the left tile. Test both tiles.
        for(int i=0;i<8;i++){result[i]=new int[size*size*8];GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0+i);
            for(int tile=0;tile<2;tile++){var bytes=org.lwjgl.BufferUtils.createByteBuffer(size*size*16);GL11.glReadPixels(t.w*(tile==0?1:5)/8,y,size,size,t.types[i]==GL11.GL_FLOAT?GL11.GL_RGBA:GL30.GL_RGBA_INTEGER,t.types[i],bytes);bytes.asIntBuffer().get(result[i],tile*size*size*4,size*size*4);}}
        result[8]=new int[size*size*2];for(int tile=0;tile<2;tile++){var depth=org.lwjgl.BufferUtils.createFloatBuffer(size*size);GL11.glReadPixels(t.w*(tile==0?1:5)/8,y,size,size,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depth);for(int i=0;i<size*size;i++)result[8][tile*size*size+i]=Float.floatToRawIntBits(depth.get(i));}return result;
    }
    public static void main(String[] args)throws Exception{
        var out=Path.of(System.getProperty("cmi.benchmark.output","build/shadow-quality/draw"));Files.createDirectories(out);ModelMergeGpuValidation.output=out;
        org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err).set();if(!GLFW.glfwInit())throw new IllegalStateException("GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Shadow vertex differential",0,0);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();System.out.println(GL11.glGetString(GL11.GL_RENDERER));
            for(var phase:net.irisshaders.iris.pipeline.WorldRenderingPhase.values())ModelMaterialGpuValidation.DEFINES.add(new net.irisshaders.iris.helpers.StringPair("MC_RENDER_STAGE_"+phase.name(),Integer.toString(phase.ordinal())));
            var compiler=new ShaderPackProgramCompiler(name->ParticleShaderSource.loadParticle(name,p->{try{return Files.readString(Path.of("src/main/resources/assets/createmanaindustry",p));}catch(Exception e){throw new IllegalStateException(e);}}));
            var method=ShaderPackProgramCompiler.class.getDeclaredMethod("buildMergedSource",boolean.class);method.setAccessible(true);String original=(String)method.invoke(compiler,false),special=candidate(original);Files.writeString(out.resolve("shadow-input-candidate.glsl"),special);
            int packs=0;try(var samples=Files.list(Path.of("run/shaderpacks"));var inputs=new ModelMergeGpuValidation.Inputs()){
                for(Path zip:samples.filter(p->p.toString().endsWith(".zip")).sorted().toList()){
                    String pack=zip.getFileName().toString().replace(',','_');var inventory=ShaderPackInventory.readPack(zip);Path root=out.resolve(inventory.digest().substring(0,12));Files.createDirectories(root);
                    for(var entry:inventory.sources().entrySet()){Path p=root.resolve(entry.getKey());Files.createDirectories(p.getParent());Files.writeString(p,entry.getValue());}
                    String base=null;for(String name:List.of("world0/shadow_entities","shadow_entities","world0/shadow","shadow"))if(Files.exists(root.resolve(name+".vsh"))&&Files.exists(root.resolve(name+".fsh"))){base=name;break;}
                    if(base==null)throw new IllegalStateException("No shadow source "+pack);
                    String v=ModelMaterialGpuValidation.preprocess(root,base+".vsh"),fragment=ModelMaterialGpuValidation.preprocess(root,base+".fsh"),geometry=Files.exists(root.resolve(base+".gsh"))?ModelMaterialGpuValidation.preprocess(root,base+".gsh"):null;
                    var context=ShadowVertexContext.prepare(original,v,geometry,null,null,fragment);
                    var injector=new ParticleVertexInjector();String av=JcppProcessor.glslPreprocessSource(injector.patch(v,original,"shadow-reference"),ModelMaterialGpuValidation.DEFINES),bv=JcppProcessor.glslPreprocessSource(injector.patch(v,context.source(),"shadow-specialized"),ModelMaterialGpuValidation.DEFINES);
                    int a=ModelMaterialGpuValidation.compile(av,geometry,fragment,"shadow-reference",root),b=ModelMaterialGpuValidation.compile(bv,geometry,fragment,"shadow-specialized",root);
                    int nativeSize=64;if(fragment.contains("centerTexelOffset")){var resolution=Pattern.compile("const int shadowMapResolution\\s*=\\s*(\\d+)").matcher(fragment);if(!resolution.find())throw new IllegalStateException("Unknown shadow atlas extent");nativeSize=Integer.parseInt(resolution.group(1));}
                    try(var fixture=new ModelMergeProductionFixture(inputs,0);var target=new ModelMergeGpuValidation.Target(a,nativeSize,nativeSize)){
                        boolean unsupported=geometry!=null||images(a)||images(b);boolean covered=false;
                        if(!unsupported){for(int workload:new int[]{0,1,5})for(int pose=0;pose<3;pose++)for(int sword:new int[]{1,3,6}){
                            fixture.workload=workload;fixture.prepare(64,0,pose,sword);
                            if(nativeSize>64){for(int i=0;i<64;i++){fixture.pool[i*16]=(i%8-3.5f)*.006f;fixture.pool[i*16+1]=(i/8-3.5f)*.006f;}fixture.upload(1,fixture.pool);}
                            fixture.initialize(new int[]{a},new int[]{b});draw(fixture,target,a,64,0);var reference=read(target);for(int depth:reference[8])covered|=depth!=Float.floatToRawIntBits(1);draw(fixture,target,b,64,0);ModelMergeGpuValidation.compare(reference,read(target),target,pack+" shadow context");
                        }ModelMergeGpuValidation.check(covered,"shadow test has no covered pixels "+pack);}
                        contracts.add(pack+","+base+","+(geometry!=null)+","+unsupported+","+covered+","+ModelMergeGpuValidation.checks);
                        if(!unsupported&&Arrays.stream(System.getProperty("cmi.shadow.draw.packs","complementary,solas").split(",")).anyMatch(s->pack.toLowerCase(Locale.ROOT).contains(s))){
                            try(var performance=new ModelMergeGpuValidation.Target(a,nativeSize>64?nativeSize:1024,nativeSize>64?nativeSize:1024)){
                                for(int n:new int[]{65536,131072})for(int workload:new int[]{1,5})for(int scene=0;scene<3;scene++){
                                    fixture.workload=workload;fixture.prepare(n,scene,0,5);fixture.initialize(new int[]{a},new int[]{b});int layout=scene;Runnable ra=()->draw(fixture,performance,a,n,layout),rb=()->draw(fixture,performance,b,n,layout);
                                    long end=System.nanoTime()+1_000_000_000L;do{ra.run();rb.run();GL11.glFinish();}while(System.nanoTime()<end);
                                    int round=Integer.getInteger("cmi.shadow.round",0);for(int pair=0;pair<64;pair++){double aa=time(ra),at,bt;if((pair&1)==0){at=time(ra);bt=time(rb);}else{bt=time(rb);at=time(ra);}rows.add(pack+","+n+","+workload+","+scene+","+round+","+pair+","+aa+","+at+","+bt);}
                                }
                            }
                        }
                    }finally{GL20.glDeleteProgram(a);GL20.glDeleteProgram(b);}System.out.println(pack+" shadow candidate tested");packs++;
                }
            }ModelMergeGpuValidation.check(packs>=8,"eight shadow packs");ModelMergeGpuValidation.check(GL11.glGetError()==0,"shadow candidate GL error");System.out.println("Shadow draw: "+ModelMergeGpuValidation.checks+" checks passed");
        }finally{Files.write(out.resolve("shadow-draw-samples.csv"),rows);Files.write(out.resolve("shadow-draw-contracts.csv"),contracts);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
