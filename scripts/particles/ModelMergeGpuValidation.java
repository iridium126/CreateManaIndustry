import com.iridium126.createmanaindustry.client.particles.shaderpack.*;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.transform.*;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.gl.blending.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Paired actual-pack fragment validation and reproducible fragment-pressure benchmark.
 * Raster inputs are synthetic instanced surfaces, not a live Minecraft pipeline.
 * Production CMI+pack stages are separately linked before fragment isolation. */
public final class ModelMergeGpuValidation {
    static String sha(String value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    static final int W=1920,H=1080;
    static final List<String> firstDrawRows=new ArrayList<>(List.of("pack,width,height,count,workload,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms"));
    static final boolean PRODUCTION_DRAW=Boolean.getBoolean("cmi.draw.production");
    static final boolean FEATHER=Boolean.getBoolean("cmi.feather.merge");
    static final boolean ROUND2=Boolean.getBoolean("cmi.shader.round2");
    static final boolean READONLY_EXPERIMENT=Boolean.getBoolean("cmi.readonly.draw");
    static long checks;
    static final List<String> coverageRows=new ArrayList<>(List.of("pack,width,height,workload,scene,count,baseline_covered,candidate_covered"));
    static Path output;
    static String replayOptionHash;static int[] nativeBenchmarkFormats;static int nativeBenchmarkDepth;
    static void benchmarkEnvironment(String pack,String source)throws Exception{
        ModelMaterialGpuValidation.OPTION_OVERRIDES=Map.of();replayOptionHash=null;ModelMaterialGpuValidation.STRICT_CAPTURE_OPTIONS=false;
        String directory=System.getProperty("cmi.draw.snapshots");if(directory==null)return;
        try(var files=Files.list(Path.of(directory))){
            for(Path file:files.filter(p->p.toString().endsWith(".json")).sorted().toList()){
                var json=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if(!json.has("source_sha256")||!json.get("source_sha256").getAsString().equals(source)||!json.get("pack").getAsString().equals(pack)||!json.get("world").getAsString().equals("minecraft:overworld"))continue;
                var macros=new ArrayList<StringPair>();
                for(var entry:json.getAsJsonArray("model_environment_macros")){var macro=entry.getAsJsonObject();macros.add(new StringPair(macro.get("key").getAsString(),macro.get("value").getAsString()));}
                var options=new TreeMap<String,String>();json.getAsJsonObject("options").entrySet().forEach(entry->options.put(entry.getKey(),entry.getValue().getAsString()));
                String actual=sha(options.toString()).substring(0,12);if(!json.get("options_available").getAsBoolean()||!actual.equals(json.get("options_hash").getAsString()))throw new IllegalStateException("Captured option map/hash unavailable or inconsistent: "+file);replayOptionHash=actual;ModelMaterialGpuValidation.OPTION_OVERRIDES=Map.copyOf(options);ModelMaterialGpuValidation.STRICT_CAPTURE_OPTIONS=true;
                DEFINES.clear();DEFINES.addAll(macros);return;
            }
        }
        throw new IllegalStateException("No matching source/pack/overworld environment snapshot: "+pack);
    }
    static void benchmarkFormats(String pack,String source,String options)throws Exception{
        nativeBenchmarkFormats=null;nativeBenchmarkDepth=GL30.GL_DEPTH_COMPONENT32F;
        String directory=System.getProperty("cmi.draw.snapshots");
        if(directory==null)return;
        try(var files=Files.list(Path.of(directory))){
            for(Path file:files.filter(p->p.toString().endsWith(".json")).sorted().toList()){
                var json=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if(!json.has("pack")||!json.get("pack").getAsString().equals(pack)||!json.has("source_sha256")||!json.get("source_sha256").getAsString().equals(source))continue;
                if(!json.get("options_available").getAsBoolean()||!json.get("options_hash").getAsString().equals(options)||!json.get("world").getAsString().equals("minecraft:overworld"))continue;
                if(!json.get("model_environment_macros").equals(new com.google.gson.Gson().toJsonTree(DEFINES)))continue;
                int[] formats=new int[8];Arrays.fill(formats,GL11.GL_RGBA8);
                var buffers=json.getAsJsonArray("model_draw_buffers");var colors=json.getAsJsonObject("color_attachment_formats_gl");
                check(buffers.size()<=8,"Snapshot MRT count");
                for(int i=0;i<buffers.size();i++){String index=buffers.get(i).getAsString();check(colors.has(index),"Snapshot lacks a live MODEL attachment "+index);formats[i]=colors.get(index).getAsInt();}
                nativeBenchmarkDepth=json.get("depth_format_gl").getAsInt();nativeBenchmarkFormats=formats;
                Files.writeString(output.resolve("native-formats-"+source.substring(0,12)+".json"),json.toString()+"\n");return;
            }
        }
        throw new IllegalStateException("No active snapshot matches pack/source/effective options/overworld/macros: "+pack);
    }
    static final List<StringPair> DEFINES=ModelMaterialGpuValidation.DEFINES;
    static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static void integer(int p,String name,int value){GL20.glUniform1i(GL20.glGetUniformLocation(p,name),value);}
    static void number(int p,String name,float value){GL20.glUniform1f(GL20.glGetUniformLocation(p,name),value);}
    static String clientOptionHash(String pack)throws java.io.IOException{
        Path log=Path.of("run/logs/debug.log");if(!Files.exists(log))return "unrecorded";
        var pattern=Pattern.compile("Capture frames=\\d+\\.\\.\\d+; \\d+x\\d+; pack="+Pattern.quote(pack)+"; source=[^;]+; options=\\d+ options / ([0-9a-f]+);");
        var matches=pattern.matcher(Files.readString(log));String latest="unrecorded";
        while(matches.find())latest=matches.group(1);
        return latest;
    }
    static Map<PatchShaderType,String> transform(ModelShaderOptimization.Stages s,AlphaTest alpha,String name){
        return TransformPatcher.patchVanilla(name,s.vertex(),s.geometry(),s.tessControl(),s.tessEval(),s.fragment(),alpha,false,true,
                new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
    }
    // Experiment-only exclusions; never read by the production optimizer.
    static ModelShaderOptimization.Plan candidate(ModelShaderOptimization.Stages input,int id){
        if(ROUND2&&System.getProperty("cmi.model.draw.variant","full").equals("aa"))return new ModelShaderOptimization.Plan(oldPlan(input,id),List.of("A/A frozen optimizer"),List.of());
        Set<String> excluded=new HashSet<>(Arrays.asList(System.getProperty("cmi.model.merge.exclude","").split(",")));
        java.util.function.UnaryOperator<String> protect=s->{if(s==null)return null;for(String name:excluded)if(!name.isEmpty())s=s.replaceAll("\\b"+Pattern.quote(name)+"\\b","cmi_reference_"+name);return s;};
        java.util.function.UnaryOperator<String> restore=s->{if(s==null)return null;for(String name:excluded)if(!name.isEmpty())s=s.replace("cmi_reference_"+name,name);return s;};
        var plan=ModelShaderOptimization.prepare(map(input,protect),id,64,64);
        return new ModelShaderOptimization.Plan(map(plan.stages(),restore),plan.applied(),plan.skipped());
    }
    static ModelShaderOptimization.Stages oldPlan(ModelShaderOptimization.Stages s,int id){
        if(READONLY_EXPERIMENT)return ModelShaderOptimization.prepare(s,id,64,64).stages();
        var old=EfficiencyBaselineOptimization.prepare(new EfficiencyBaselineOptimization.Stages(s.vertex(),s.geometry(),s.tessControl(),s.tessEval(),s.fragment()),id,64,64).stages();
        return new ModelShaderOptimization.Stages(old.vertex(),old.geometry(),old.tessControl(),old.tessEval(),old.fragment());
    }
    static ModelShaderOptimization.Stages map(ModelShaderOptimization.Stages s,java.util.function.UnaryOperator<String> f){return new ModelShaderOptimization.Stages(f.apply(s.vertex()),f.apply(s.geometry()),f.apply(s.tessControl()),f.apply(s.tessEval()),f.apply(s.fragment()));}
    static String rasterVertex(String f,int id){
        String v=samplingVertex(f);
        // Keep the fragment interface, replacing only the fixture's screen geometry.
        v=v.replace("#version 330 compatibility","#version 400 compatibility\nuniform vec2 fixtureViewport;uniform int fixtureCount;uniform int fixtureScene;uniform float fixtureOverlay;uniform float fixtureAlpha;uniform int fixtureGhost;uniform int fixtureLod;");
        v=v.replace("vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);", """
            vec2 corner=vec2((gl_VertexID==1||gl_VertexID==2||gl_VertexID==4)?1.:0.,(gl_VertexID==2||gl_VertexID==4||gl_VertexID==5)?1.:0.);
            vec2 cell=vec2(gl_InstanceID%160,gl_InstanceID/160);
            vec2 centre=fixtureScene==1?fixtureViewport*.3+mod(cell,max(floor(fixtureViewport/48.),vec2(1)))*10.:vec2(8)+mod(cell,max(floor(fixtureViewport/12.),vec2(1)))*12.;
            vec2 pixel=centre+corner*(fixtureLod!=0?1.:8.);
            float z=fixtureGhost!=0?.8-float(gl_InstanceID)*1e-6:-.8+float(gl_InstanceID)*1e-6;
            gl_Position=vec4(pixel/fixtureViewport*2.-1.,z,1.);
            """);
        v=v.replace("texCoord=vec2(.5)","texCoord=mix(vec2(.001),vec2(.499),corner)");
        // Pack interfaces differ. Only existing, recognized fields are initialized.
        v=v.replace("entityColor=vec4(0)","entityColor=vec4(1,0,0,fixtureOverlay)");
        v=v.replace("glColor=vec4(1)","glColor=vec4(1,1,1,fixtureAlpha)")
                .replace("color=vec4(1)","color=vec4(1,1,1,fixtureAlpha)");
        v=v.replace("mat=int(0)","mat=int("+id+")");
        if(f.contains("in vec4 tangent;"))v=v.replace("tangent=vec3(1,0,0)","tangent=vec4(1,0,0,1)");
        if(f.contains("in vec4 normal;"))v=v.replace("normal=vec3(0,0,1)","normal=vec4(0,0,1,1)");
        v=v.replace("normalMat=vec4(0)","normalMat=vec4(0,0,1,1)")
                .replace("FlatNormals=vec3(0)","FlatNormals=vec3(0,0,1)")
                .replace("lmtexcoord=vec4(0)","lmtexcoord=vec4(mix(vec2(.001),vec2(.499),corner),1,1)")
                .replace("texcoord=vec4(0)","texcoord=vec4(mix(vec2(.001),vec2(.499),corner),1,1)")
                .replace("viewVector=vec3(0)","viewVector=vec3(0,0,-1)")
                .replace("PCSS_data=vec4(0)","PCSS_data=vec4(.01,.01,1,.01)")
                .replace("vertexShadowPosDiffuse=vec4(0)","vertexShadowPosDiffuse=vec4(.5,.5,.5,1)");
        return v;
    }
    static String samplingVertex(String fragment){
        var declarations=new StringBuilder("#version 330 compatibility\n");var assignments=new StringBuilder();
        var inputs=Pattern.compile("(?m)^\\s*(flat\\s+)?in\\s+(\\w+)\\s+(\\w+(?:\\s*,\\s*\\w+)*)\\s*;").matcher(fragment);
        while(inputs.find()){
            String type=inputs.group(2);declarations.append(inputs.group(1)==null?"":inputs.group(1)).append("out ").append(type).append(' ').append(inputs.group(3)).append(";\n");
            for(String name:inputs.group(3).split(",")){
                name=name.trim();String value=type+"(0)";
                if(type.startsWith("mat"))value=type+"(1)";
                if(name.equals("texCoord"))value="vec2(.5)";
                if(name.equals("color")||name.equals("glColor"))value="vec4(1)";
                if(name.equals("lmCoord")||name.equals("blockLight"))value="vec2(1)";
                if(name.equals("normal")||name.equals("northVec"))value=type.equals("vec4")?"vec4(0,0,1,1)":"vec3(0,0,1)";
                if(name.equals("upVec")||name.equals("sunVec"))value="vec3(0,1,0)";
                if(name.equals("eastVec")||name.equals("tangent"))value=type.equals("vec4")?"vec4(1,0,0,1)":"vec3(1,0,0)";
                if(name.equals("binormal"))value="vec3(0,1,0)";
                if(name.equals("viewPos"))value="vec3(0,0,-10)";
                if(name.equals("materialIDs"))value="128.0";
                assignments.append(name).append('=').append(value).append(';');
            }
        }
        return declarations+"void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);"+assignments+"}";
    }
    static int fixture(String f,int id,String label,Path root)throws Exception{
        return ModelMaterialGpuValidation.link(Map.of(PatchShaderType.VERTEX,rasterVertex(f,id),PatchShaderType.FRAGMENT,f),label,root);
    }
    static int texture(int format,int type,int w,int h,ByteBuffer data){
        int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,w,h,0,GL11.GL_RGBA,type,data);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);return t;
    }
    static final class Target implements AutoCloseable {
        final int fbo,depth;final int[] colors,types;final int w,h;
        Target(int p,int w,int h){
            this(p,w,h,false);
        }
        Target(int p,int w,int h,boolean performance){
            this.w=w;this.h=h;types=new int[8];Arrays.fill(types,GL11.GL_FLOAT);
            int resources=GL43.glGetProgramInterfacei(p,GL43.GL_PROGRAM_OUTPUT,GL43.GL_ACTIVE_RESOURCES);
            for(int i=0;i<resources;i++){
                int[] result=new int[2];GL43.glGetProgramResourceiv(p,GL43.GL_PROGRAM_OUTPUT,i,new int[]{GL43.GL_LOCATION,GL43.GL_TYPE},null,result);
                if(result[0]<0||result[0]>=8)continue;
                types[result[0]]=switch(result[1]){case GL30.GL_UNSIGNED_INT_VEC2,GL30.GL_UNSIGNED_INT_VEC3,GL30.GL_UNSIGNED_INT_VEC4,GL11.GL_UNSIGNED_INT->GL11.GL_UNSIGNED_INT;
                    case GL20.GL_INT_VEC2,GL20.GL_INT_VEC3,GL20.GL_INT_VEC4,GL11.GL_INT->GL11.GL_INT;default->GL11.GL_FLOAT;};
            }
            fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);colors=new int[8];
            for(int i=0;i<8;i++){
                colors[i]=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,colors[i]);
                int internal=types[i]==GL11.GL_UNSIGNED_INT?GL30.GL_RGBA32UI:types[i]==GL11.GL_INT?GL30.GL_RGBA32I:GL30.GL_RGBA32F;
                if(performance&&nativeBenchmarkFormats!=null)internal=nativeBenchmarkFormats[i];
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,internal,w,h,0,types[i]==GL11.GL_FLOAT?GL11.GL_RGBA:GL30.GL_RGBA_INTEGER,types[i],(ByteBuffer)null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0+i,GL11.GL_TEXTURE_2D,colors[i],0);
            }
            depth=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);
            int depthFormat=performance&&nativeBenchmarkFormats!=null?nativeBenchmarkDepth:GL30.GL_DEPTH_COMPONENT32F;
            boolean stencil=depthFormat==GL30.GL_DEPTH24_STENCIL8||depthFormat==GL30.GL_DEPTH32F_STENCIL8;
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,depthFormat,w,h,0,stencil?GL30.GL_DEPTH_STENCIL:GL11.GL_DEPTH_COMPONENT,
                    stencil?(depthFormat==GL30.GL_DEPTH24_STENCIL8?GL30.GL_UNSIGNED_INT_24_8:GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV):GL11.GL_FLOAT,(ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2,GL30.GL_COLOR_ATTACHMENT3,GL30.GL_COLOR_ATTACHMENT4,GL30.GL_COLOR_ATTACHMENT5,GL30.GL_COLOR_ATTACHMENT6,GL30.GL_COLOR_ATTACHMENT7});
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"MRT framebuffer");
        }
        void clear(int scene){
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glViewport(0,0,w,h);GL11.glDepthMask(true);
            for(int i=0;i<8;i++)if(types[i]==GL11.GL_FLOAT)GL30.glClearBufferfv(GL11.GL_COLOR,i,new float[]{.25f,.25f,.25f,.25f});
                else if(types[i]==GL11.GL_UNSIGNED_INT)GL30.glClearBufferuiv(GL11.GL_COLOR,i,new int[]{0x45,0x45,0x45,0x45});
                else GL30.glClearBufferiv(GL11.GL_COLOR,i,new int[]{0x45,0x45,0x45,0x45});
            GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{scene==2?0:1});
        }
        int[][] read(){
            int[][] data=new int[9][];
            for(int i=0;i<8;i++){
                var b=org.lwjgl.BufferUtils.createByteBuffer(w*h*16);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0+i);
                GL11.glReadPixels(0,0,w,h,types[i]==GL11.GL_FLOAT?GL11.GL_RGBA:GL30.GL_RGBA_INTEGER,types[i],b);
                data[i]=new int[w*h*4];b.asIntBuffer().get(data[i]);
            }
            var b=org.lwjgl.BufferUtils.createFloatBuffer(w*h);GL11.glReadPixels(0,0,w,h,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,b);
            data[8]=new int[w*h];for(int i=0;i<w*h;i++)data[8][i]=Float.floatToRawIntBits(b.get(i));return data;
        }
        public void close(){GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(colors);GL11.glDeleteTextures(depth);}
    }
    static final class Inputs implements AutoCloseable {
        final PackFixtureResources realistic=new PackFixtureResources();
        final int atlas,flat,normal,depth,shadow,volume,cube;
        Inputs()throws Exception{
            var b=org.lwjgl.BufferUtils.createByteBuffer(64*64*4);
            for(int y=0;y<64;y++)for(int x=0;x<64;x++)b.put((byte)(40+x*3)).put((byte)(70+y*2)).put((byte)232).put((byte)((x+y)%4==0?0:((x+y)%4==1?25:((x+y)%4==2?160:255))));b.flip();
            if(FEATHER)for(int at=0;at<b.capacity();at++)b.put(at,(byte)0);
            // Native body and sword art; unused pixels retain an alpha-edge fixture.
            var canvas=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for(int frame=0;frame<7;frame++){
                String name=new String[]{"allay_0","sword_wooden","sword_stone","sword_golden","sword_iron","sword_diamond","sword_netherite"}[frame];
                var image=javax.imageio.ImageIO.read(Path.of("src/main/resources/assets/createmanaindustry/textures/particle/"+name+".png").toFile());
                var rect=com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlasLayout.model(frame);
                for(int y=0;y<rect.height();y++)for(int x=0;x<rect.width();x++){int c=image.getRGB(x,y),at=((rect.y()+y)*64+rect.x()+x)*4;b.put(at,(byte)(c>>16));b.put(at+1,(byte)(c>>8));b.put(at+2,(byte)c);b.put(at+3,(byte)(c>>24));}
            }
            if(FEATHER)for(int frame=0;frame<12;frame++){
                var art=javax.imageio.ImageIO.read(Path.of("src/main/resources/assets/createmanaindustry/textures/particle/cherry_"+frame+".png").toFile());
                for(int palette=0;palette<3;palette++){var rect=com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlasLayout.feather(frame+palette*12);
                    for(int y=-1;y<=3;y++)for(int x=-1;x<=3;x++){
                        int argb=art.getRGB(Math.clamp(x,0,2),Math.clamp(y,0,2));int rgba=(argb&0xff00ff00)|(argb>>>16&255)|(argb&255)<<16;
                        int c=com.iridium126.createmanaindustry.client.particles.engine.AllayFeatherAppearance.recolor(rgba,palette),at=((rect.y()+y)*64+rect.x()+x)*4;
                        b.put(at,(byte)c);b.put(at+1,(byte)(c>>>8));b.put(at+2,(byte)(c>>>16));b.put(at+3,(byte)(c>>>24));
                    }
                }
            }
            atlas=texture(GL11.GL_RGBA8,GL11.GL_UNSIGNED_BYTE,64,64,b);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,4);GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL30.GL_NEAREST_MIPMAP_LINEAR);
            flat=ModelMaterialGpuValidation.texture(.3f,.6f,.2f,.4f);normal=ModelMaterialGpuValidation.texture(.5f,.5f,1,1);
            depth=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH_COMPONENT32F,1,1,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,new float[]{1});
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            shadow=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,shadow);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH_COMPONENT32F,1,1,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,new float[]{1});
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL14.GL_TEXTURE_COMPARE_MODE,GL30.GL_COMPARE_REF_TO_TEXTURE);
            volume=GL11.glGenTextures();GL11.glBindTexture(GL12.GL_TEXTURE_3D,volume);GL12.glTexImage3D(GL12.GL_TEXTURE_3D,0,GL30.GL_RGBA32F,1,1,1,0,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{.5f,.5f,.5f,1});GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            cube=GL11.glGenTextures();GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP,cube);for(int i=0;i<6;i++)GL11.glTexImage2D(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X+i,0,GL30.GL_RGBA32F,1,1,0,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{.5f,.5f,.5f,1});GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        }
        void bind(int p,int entityId){
            GL20.glUseProgram(p);
            int active=GL20.glGetProgrami(p,GL20.GL_ACTIVE_UNIFORMS);
            for(int i=0;i<active;i++)try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
                var size=stack.mallocInt(1);var type=stack.mallocInt(1);String name=GL20.glGetActiveUniform(p,i,size,type);int location=GL20.glGetUniformLocation(p,name),kind=type.get(0);
                if(kind==GL20.GL_FLOAT_MAT4)GL20.glUniformMatrix4fv(location,false,new org.joml.Matrix4f().get(new float[16]));
                if(kind==GL20.GL_FLOAT_MAT3)GL20.glUniformMatrix3fv(location,false,new org.joml.Matrix3f().get(new float[9]));
                int unit=-1,target=GL11.GL_TEXTURE_2D,texture=atlas;
                if(kind==GL20.GL_SAMPLER_2D){unit=0;if(name.equals("specular")){unit=1;texture=flat;}if(name.equals("normals")){unit=2;texture=normal;}if(name.startsWith("shadowtex")){unit=3;texture=depth;}}
                if(kind==GL20.GL_SAMPLER_2D_SHADOW){unit=4;texture=shadow;}
                if(kind==GL20.GL_SAMPLER_3D){unit=5;texture=volume;target=GL12.GL_TEXTURE_3D;}
                if(kind==GL20.GL_SAMPLER_CUBE){unit=6;texture=cube;target=GL13.GL_TEXTURE_CUBE_MAP;}
                if(unit>=0){GL20.glUniform1i(location,unit);GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(target,texture);}
            }
            integer(p,"entityId",entityId);integer(p,"blockEntityId",0);integer(p,"currentRenderedItemId",0);
            GL20.glUniform2i(GL20.glGetUniformLocation(p,"atlasSize"),64,64);GL20.glUniform2i(GL20.glGetUniformLocation(p,"gtextureSize"),64,64);
            number(p,"viewWidth",W);number(p,"viewHeight",H);number(p,"near",.1f);number(p,"far",4096);number(p,"sunAngle",.25f);number(p,"timeAngle",.25f);number(p,"frameTimeCounter",12);
            number(p,"rainStrength",.2f);number(p,"shadowDistance",128);number(p,"viewDistance",4096);
            GL20.glUniform2f(GL20.glGetUniformLocation(p,"texelSize"),1f/W,1f/H);
            GL20.glUniform3f(GL20.glGetUniformLocation(p,"sunPosition"),0,1,0);
            GL20.glUniform4f(GL20.glGetUniformLocation(p,"entityColor"),0,0,0,0);
            if(ROUND2)realistic.bind(p,atlas);
        }
        public void close(){realistic.close();GL11.glDeleteTextures(new int[]{atlas,flat,normal,depth,shadow,volume,cube});}
    }
    static void render(int p,Target t,Inputs inputs,int entityId,int count,int scene,boolean ghost,float overlay,float alpha,float threshold,boolean lod){
        prepareDraw(p,t,inputs,entityId,count,scene,ghost,overlay,alpha,threshold,lod);
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,count);
    }
    static void prepareDraw(int p,Target t,Inputs inputs,int entityId,int count,int scene,boolean ghost,float overlay,float alpha,float threshold,boolean lod){
        t.clear(scene);inputs.bind(p,entityId);integer(p,"fixtureCount",count);integer(p,"fixtureScene",scene);integer(p,"fixtureGhost",ghost?1:0);integer(p,"fixtureLod",lod?1:0);number(p,"fixtureOverlay",overlay);number(p,"fixtureAlpha",alpha);number(p,"iris_currentAlphaTest",threshold);number(p,"alphaTestRef",threshold);
        GL20.glUniform2f(GL20.glGetUniformLocation(p,"fixtureViewport"),t.w,t.h);
        GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
        if(ghost&&t.types[0]==GL11.GL_FLOAT){GL30.glEnablei(GL11.GL_BLEND,0);GL40.glBlendFuncSeparatei(0,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);}
    }
    static void compare(int[][] a,int[][] b,Target target,String label){
        for(int attachment=0;attachment<a.length;attachment++)for(int i=0;i<a[attachment].length;i++){
            int x=a[attachment][i],y=b[attachment][i];
            // Deliberately stricter than a color epsilon: floating-point MRT
            // channels may encode packed material bits. Compare every raw word.
            checks++;if(x!=y)throw new AssertionError(label+" attachment="+attachment+" word="+i+" "+Integer.toHexString(x)+" != "+Integer.toHexString(y));
        }
    }
    static void differential(int baseline,int candidate,Inputs inputs,int id,Path root)throws Exception{
        try(var target=new Target(baseline,64,64)){
            if(ROUND2){
                render(baseline,target,inputs,id,64,0,false,0,1,.1f,false);var repeat=target.read();
                render(baseline,target,inputs,id,64,0,false,0,1,.1f,false);compare(repeat,target.read(),target,"Identical-program resource repeatability "+root);
            }
            // Keep the same 1080p fixture geometry, inspecting its top-left region.
            for(boolean ghost:new boolean[]{false,true})for(int scene:new int[]{0,1,2})for(float overlay:new float[]{0,77f/255})for(float threshold:new float[]{0,.1f,.5f}){
                render(baseline,target,inputs,id,64,scene,ghost,overlay,1,threshold,false);var a=target.read();
                render(candidate,target,inputs,id,64,scene,ghost,overlay,1,threshold,false);compare(a,target.read(),target,root+" ghost="+ghost+" scene="+scene+" threshold="+threshold);
            }
            check(GL11.glGetError()==0,"Differential GL state "+root);
        }
    }
    static double measure(int p,Target target,Inputs inputs,int id,int count,int scene,boolean ghost,boolean lod){
        prepareDraw(p,target,inputs,id,count,scene,ghost,0,1,.1f,lod);
        int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,count);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        double ms=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(query);return ms;
    }
    static void benchmark(String pack,int baseline,int candidate,Inputs inputs,int id,List<String> csv)throws Exception{
        try(var target=new Target(baseline,W,H)){
            for(boolean ghost:new boolean[]{false,true})for(int scene=0;scene<3;scene++)for(int count:new int[]{0,1000,10000,50000}){
                for(int i=0;i<20;i++)render(i%2==0?baseline:candidate,target,inputs,id,count,scene,ghost,0,1,.1f,false);GL11.glFinish();
                for(int round=0;round<3;round++)for(int sample=0;sample<24;sample++){
                    double a,b;if((round+sample)%2==0){a=measure(baseline,target,inputs,id,count,scene,ghost,false);b=measure(candidate,target,inputs,id,count,scene,ghost,false);}
                    else{b=measure(candidate,target,inputs,id,count,scene,ghost,false);a=measure(baseline,target,inputs,id,count,scene,ghost,false);}
                    csv.add(String.format(Locale.ROOT,"%s,%s,%d,%d,%d,%d,%.6f,%.6f",pack,ghost?"ghost":"body",scene,count,round,sample,a,b));
                }
            }
            for(int round=0;round<3;round++)for(int sample=0;sample<24;sample++){
                double a=measure(baseline,target,inputs,id,131072,0,false,true),b=measure(candidate,target,inputs,id,131072,0,false,true);
                csv.add(String.format(Locale.ROOT,"%s,lod2,0,131072,%d,%d,%.6f,%.6f",pack,round,sample,a,b));
            }
        }
    }
    static void optionVariants(Path root,String base,int id,Object compiler,java.lang.reflect.Method build,Inputs inputs,AlphaTest alpha)throws Exception{
        for(String[] variant:new String[][]{{"lib/common.glsl","#define RP_MODE 1","#define RP_MODE 2","custom-pbr"},
                {"lib/settings.glsl","//#define ADVANCED_MATERIALS","#define ADVANCED_MATERIALS","advanced-materials"}}){
            Path file=root.resolve(variant[0]);if(!Files.exists(file))continue;String saved=Files.readString(file);if(!saved.contains(variant[1]))continue;
            Files.writeString(file,saved.replace(variant[1],variant[2]).replace("#define ALPHA_BLEND 0","#define ALPHA_BLEND 1"));
            try{
                String v=ModelMaterialGpuValidation.preprocess(root,base+".vsh"),f=ModelMaterialGpuValidation.preprocess(root,base+".fsh");
                var overlay=Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;");boolean exact=!overlay.matcher(v).replaceAll("").contains("entityColor");
                v=new ParticleVertexInjector().patch(exact?overlay.matcher(v).replaceAll(""):v,(String)build.invoke(compiler,exact),"option-"+variant[3]);
                v=net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor.glslPreprocessSource(v,DEFINES);
                var raw=new ModelShaderOptimization.Stages(v,null,null,null,exact?overlay.matcher(f).replaceAll("in vec4 entityColor;"):f);
                var original=transform(ROUND2?oldPlan(raw,id):raw,alpha,"option-original");
                var plan=candidate(new ModelShaderOptimization.Stages(v,null,null,null,exact?overlay.matcher(f).replaceAll("in vec4 entityColor;"):f),id);
                var optimized=transform(plan.stages(),alpha,"option-optimized");
                int fullA=ModelMaterialGpuValidation.link(original,"option-"+variant[3]+"-baseline",root),fullB=ModelMaterialGpuValidation.link(optimized,"option-"+variant[3]+"-candidate",root);
                int a=fixture(original.get(PatchShaderType.FRAGMENT),id,"option-raster-"+variant[3]+"-baseline",root),b=fixture(optimized.get(PatchShaderType.FRAGMENT),id,"option-raster-"+variant[3]+"-candidate",root);
                try{differential(a,b,inputs,id,root.resolve(variant[3]));if(ROUND2)try(var production=new ModelMergeProductionFixture(inputs,id)){production.validateFullFrame(new int[]{fullA,fullA,fullA},new int[]{fullB,fullB,fullB},variant[3]);}}
                finally{for(int p:new int[]{fullA,fullB,a,b})GL20.glDeleteProgram(p);}
                System.out.println(root.getFileName()+": "+variant[3]+" option exact MRT comparison passed");
            }finally{Files.writeString(file,saved);}
        }
    }
    static ModelShaderOptimization.Stages frozenFeatherPlan(ModelShaderOptimization.Stages raw,int id){
        var frozen=FeatherBaselineOptimization.prepare(new FeatherBaselineOptimization.Stages(raw.vertex(),raw.geometry(),raw.tessControl(),raw.tessEval(),raw.fragment()),id,64,64).stages();
        return new ModelShaderOptimization.Stages(frozen.vertex(),frozen.geometry(),frozen.tessControl(),frozen.tessEval(),frozen.fragment());
    }
    static ModelShaderOptimization.Plan experimentFeather(ModelShaderOptimization.Stages raw,boolean exact){
        var plan=FeatherExperimentOptimization.prepareFeather(new FeatherExperimentOptimization.Stages(raw.vertex(),raw.geometry(),raw.tessControl(),raw.tessEval(),raw.fragment()),exact);
        var stages=plan.stages();return new ModelShaderOptimization.Plan(new ModelShaderOptimization.Stages(stages.vertex(),stages.geometry(),stages.tessControl(),stages.tessEval(),stages.fragment()),plan.applied(),plan.skipped());
    }
    static boolean featherBranch(){return System.getProperty("cmi.feather.variant","branch").equals("branch");}
    static ModelShaderOptimization.Stages thinFeather(java.util.function.Function<String,String> loader,String v,String g,String f,boolean exact,int id)throws Exception{
        String adapted=new ParticleVertexInjector().patch(exact?Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;").matcher(v).replaceAll(""):v,FeatherExperimentalSources.buildFeatherSource(loader,exact),"feather-input");
        adapted=net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor.glslPreprocessSource(adapted,DEFINES);
        var base=ModelShaderOptimization.prepare(new ModelShaderOptimization.Stages(adapted,g,null,null,null),id,64,64).stages();
        return experimentFeather(new ModelShaderOptimization.Stages(base.vertex(),g,null,null,f),exact).stages();
    }
    static void featherLoadProbe(java.util.function.Function<String,String> loader,String v,String g,String f,boolean exact,int id,AlphaTest alpha,Path root,ModelShaderOptimization.Stages expectedBody,ModelShaderOptimization.Stages expectedCarrier,ModelShaderOptimization.Stages expectedFeather)throws Exception{
        var rows=new ArrayList<String>();rows.add("round,baseline_ms,candidate_ms,baseline_programs,candidate_programs");
        for(int round=-1;round<Integer.getInteger("cmi.feather.load.rounds",3);round++){
            double[] cost=new double[2];int[] counts=new int[2];
            for(int order=0;order<2;order++){
                boolean extra=((round+order)&1)!=0&&!Boolean.getBoolean("cmi.feather.aa");int side=(round+order)&1;
                var compiler=new ShaderPackProgramCompiler(loader);long start=System.nanoTime();
                var builder=ShaderPackProgramCompiler.class.getDeclaredMethod("buildMergedSource",boolean.class);builder.setAccessible(true);
                String merged=new ParticleVertexInjector().patch(exact?Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;").matcher(v).replaceAll(""):v,(String)builder.invoke(compiler,exact),"load-body");
                merged=net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor.glslPreprocessSource(merged,DEFINES);
                var raw=new ModelShaderOptimization.Stages(merged,g,null,null,f);
                try(var cache=extra?ModelShaderOptimization.batch():null){
                var body=extra?ModelShaderOptimization.prepare(raw,id,64,64).stages():frozenFeatherPlan(raw,id);var carrier=extra?ModelShaderOptimization.prepare(raw,0,64,64).stages():frozenFeatherPlan(raw,0);
                var ghostBody=body;
                if(extra&&featherBranch()){boolean shared=body.equals(carrier);body=ModelShaderOptimization.prepareFeatherBranches(body,exact).stages();if(shared)carrier=body;}
                if(extra){check(body.equals(expectedBody),"AST cache changed body source");check(carrier.equals(expectedCarrier),"AST cache changed carrier source");}
                var programs=new ArrayList<Integer>();var bodyMap=transform(body,alpha,"load-body");
                try{programs.add(ModelMaterialGpuValidation.link(bodyMap,"load-body",root));
                    if(!body.equals(carrier))programs.add(ModelMaterialGpuValidation.link(transform(carrier,alpha,"load-carrier"),"load-carrier",root));
                    programs.add(ModelMaterialGpuValidation.link(extra&&featherBranch()?transform(ghostBody,alpha,"load-native-ghost"):bodyMap,"load-ghost",root));
                    if(extra&&!featherBranch()){var feather=thinFeather(loader,v,g,body.fragment(),exact,id);check(feather.equals(expectedFeather),"AST cache changed feather source");if(!feather.fragment().equals(body.fragment()))programs.add(ModelMaterialGpuValidation.link(transform(feather,alpha,"load-feather"),"load-feather",root));}
                    cost[side]=(System.nanoTime()-start)/1e6;counts[side]=programs.size();
                }finally{for(int p:programs)GL20.glDeleteProgram(p);}
                }
            }
            if(round>=0)rows.add(round+","+cost[0]+","+cost[1]+","+counts[0]+","+counts[1]);
        }
        Files.write(root.resolve("feather-load.csv"),rows);
    }
    static void run(boolean benchmark)throws Exception{
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        DEFINES.add(new StringPair("MC_NORMAL_MAP","1"));DEFINES.add(new StringPair("MC_SPECULAR_MAP","1"));
        for(var phase:net.irisshaders.iris.pipeline.WorldRenderingPhase.values())DEFINES.add(new StringPair("MC_RENDER_STAGE_"+phase.name(),Integer.toString(phase.ordinal())));
        output=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/model-merge"));Files.createDirectories(output);
        var csv=new ArrayList<String>();csv.add("pack,segment,scene,count,round,sample,baseline_ms,candidate_ms");
        var compilation=new ArrayList<String>();compilation.add("pack,context,baseline_compile_ms,candidate_compile_ms,applied,skipped");
        var productionCsv=new ArrayList<String>();productionCsv.add("pack,scene,count,round,sample,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms,width,height,workload,baseline_body_ms,candidate_body_ms,baseline_carrier_ms,candidate_carrier_ms,baseline_ghost_ms,candidate_ghost_ms");
        var startupCsv=new ArrayList<String>();startupCsv.add("pack,baseline_programs,candidate_programs,baseline_stages_ms,candidate_stages_ms,optimization_ms");
        java.util.function.Function<String,String> sourceLoader=name->com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource.loadParticle(name,path->{try{return Files.readString(Path.of("src/main/resources/assets/createmanaindustry",path));}catch(Exception e){throw new RuntimeException(e);}});
        boolean drawOptimization=Boolean.getBoolean("cmi.model.draw");
        java.util.function.Function<String,String> frozenLoader=name->com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource.loadParticle(name,path->{try{return Files.readString(READONLY_EXPERIMENT?Path.of(System.getProperty("cmi.readonly.baseline"),"assets/createmanaindustry",path):Path.of(ROUND2?".local/particle-baselines/reference/shader-efficiency-20261030-r2/src/main/resources/assets/createmanaindustry":".local/particle-baselines/reference/model-draw-20261030/src/main/resources/assets/createmanaindustry",path+".txt"));}catch(Exception e){throw new RuntimeException(e);}});
        java.util.function.Function<String,String> experimentLoader=name->{
            Path source=Path.of("scripts/particles/experiments/model-draw",Path.of(name).getFileName()+".txt");
            return com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource.loadParticle(name,path->{try{
                Path experimentFile=Path.of("scripts/particles/experiments/model-draw",Path.of(path).getFileName()+".txt");
                return Files.readString(Files.exists(experimentFile)?experimentFile:Path.of(ROUND2?".local/particle-baselines/reference/shader-efficiency-20261030-r2/src/main/resources/assets/createmanaindustry":".local/particle-baselines/reference/model-draw-20261030/src/main/resources/assets/createmanaindustry",path+".txt"));
            }catch(Exception e){throw new RuntimeException(e);}});
        };
        var compiler=new FrozenModelProgramCompiler(frozenLoader);
        Object delivered=ROUND2?new EfficiencyBaselineCompiler(frozenLoader):new DeliveredModelProgramCompiler(frozenLoader);
        if(READONLY_EXPERIMENT)delivered=Class.forName("com.iridium126.createmanaindustry.client.particles.shaderpack.ReadonlyBaselineCompiler").getConstructor(java.util.function.Function.class).newInstance(frozenLoader);
        if(PRODUCTION_DRAW)delivered=new ShaderPackProgramCompiler(sourceLoader);
        var deliveredBuild=delivered.getClass().getDeclaredMethod("buildMergedSource",boolean.class);deliveredBuild.setAccessible(true);
        var build=FrozenModelProgramCompiler.class.getDeclaredMethod("buildMergedSource",boolean.class);build.setAccessible(true);
        String variant=System.getProperty("cmi.model.draw.variant","full");
        Object current=new ShaderPackProgramCompiler(sourceLoader);
        if(READONLY_EXPERIMENT){
            java.util.function.Function<String,String> readonlyLoader=name->com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource.loadParticle(name,path->{try{return Files.readString(Path.of(System.getProperty("cmi.readonly.candidate"),"assets/createmanaindustry",path));}catch(Exception e){throw new RuntimeException(e);}});
            current=Class.forName("com.iridium126.createmanaindustry.client.particles.shaderpack.ReadonlyCandidateCompiler").getConstructor(java.util.function.Function.class).newInstance(readonlyLoader);
        }
        if(!variant.equals("full")&&!variant.equals("aa")){
            Class<?> type=Class.forName("com.iridium126.createmanaindustry.client.particles.shaderpack."+variant+"ModelProgramCompiler");
            current=type.getConstructor(java.util.function.Function.class).newInstance(variant.equals("Context")?experimentLoader:frozenLoader);
        }
        var currentBuild=current.getClass().getDeclaredMethod("buildMergedSource",boolean.class);currentBuild.setAccessible(true);

        var uniform=Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;");int packs=0,vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
        try(var inputs=new Inputs();var samples=Files.list(Path.of("run/shaderpacks"))){for(var sample:samples.filter(p->p.toString().endsWith(".zip")).sorted().toList()){
            String pack=sample.getFileName().toString().replace(',','_');
            if(Arrays.stream(System.getProperty("cmi.model.draw.pack","").toLowerCase(Locale.ROOT).split(",")).noneMatch(token->pack.toLowerCase(Locale.ROOT).contains(token)))continue;
            var inventory=ShaderPackInventory.readPack(sample);Path root=output.resolve(inventory.digest().substring(0,12));Files.createDirectories(root);
            if(READONLY_EXPERIMENT||System.getProperty("cmi.draw.snapshots")!=null)benchmarkEnvironment(pack,inventory.digest());
            for(var entry:inventory.sources().entrySet()){Path p=root.resolve(entry.getKey());Files.createDirectories(p.getParent());Files.writeString(p,entry.getValue());}
            String base=null;for(String c:List.of("gbuffers_entities","gbuffers_textured_lit","gbuffers_textured","gbuffers_basic")){String path=Files.exists(root.resolve("world0/"+c+".vsh"))?"world0/"+c:c;if(Files.exists(root.resolve(path+".vsh"))&&Files.exists(root.resolve(path+".fsh"))){base=path;break;}}check(base!=null,"Entities fallback");
            String v=ModelMaterialGpuValidation.preprocess(root,base+".vsh"),f=ModelMaterialGpuValidation.preprocess(root,base+".fsh"),g=Files.exists(root.resolve(base+".gsh"))?ModelMaterialGpuValidation.preprocess(root,base+".gsh"):null;
            var graph=new IncludeGraph(root,ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/"+base+".vsh"),AbsolutePackPath.fromAbsolutePath("/"+base+".fsh")),false);
            var options=new ShaderPackOptions(graph,ModelMaterialGpuValidation.OPTION_OVERRIDES);var constructor=net.irisshaders.iris.shaderpack.IdMap.class.getDeclaredConstructor(Path.class,ShaderPackOptions.class,Iterable.class);constructor.setAccessible(true);
            int allay=ModelEntityContext.resolve((net.irisshaders.iris.shaderpack.IdMap)constructor.newInstance(root,options,DEFINES)).allayId();
            var properties=new ShaderProperties(inventory.source("shaders.properties"),options,DEFINES);
            if(ROUND2){
                String shadowPath=Files.exists(root.resolve("world0/shadow.fsh"))?"world0/shadow.fsh":"shadow.fsh";
                inputs.realistic.configure(properties,v+"\n"+f+(Files.exists(root.resolve(shadowPath))?ModelMaterialGpuValidation.preprocess(root,shadowPath):""));
                var allRoots=ImmutableList.copyOf(inventory.sources().keySet().stream().filter(name->List.of(".vsh",".fsh",".gsh",".tcs",".tes",".csh").stream().anyMatch(name::endsWith)).map(name->AbsolutePackPath.fromAbsolutePath("/"+name)).toList());
                var allOptions=new ShaderPackOptions(new IncludeGraph(root,allRoots,false),ModelMaterialGpuValidation.OPTION_OVERRIDES);var values=new TreeMap<String,String>();
                allOptions.getOptionSet().getBooleanOptions().keySet().forEach(key->values.put(key,Boolean.toString(allOptions.getOptionValues().getBooleanValueOrDefault(key))));
                allOptions.getOptionSet().getStringOptions().keySet().forEach(key->values.put(key,allOptions.getOptionValues().getStringValueOrDefault(key)));
                String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(values.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,12);
                if(READONLY_EXPERIMENT||System.getProperty("cmi.draw.snapshots")!=null)benchmarkFormats(pack,inventory.digest(),replayOptionHash);
                String expected=replayOptionHash!=null?replayOptionHash:FEATHER?clientOptionHash(pack):pack.toLowerCase(Locale.ROOT).contains("photon")?"8891f40a9e8c":pack.toLowerCase(Locale.ROOT).contains("complementary")?"2f845d3b8761":"unrecorded";
                var optionAudit=new TreeMap<String,Object>();optionAudit.put("pack",pack);optionAudit.put("hash",replayOptionHash==null?digest:replayOptionHash);optionAudit.put("option_count",replayOptionHash==null?values.size():ModelMaterialGpuValidation.OPTION_OVERRIDES.size());optionAudit.put("expected",expected);optionAudit.put("matched",(replayOptionHash==null?digest:replayOptionHash).equals(expected));optionAudit.put("reparsed_all_roots_hash",digest);optionAudit.put("reparsed_all_roots_count",values.size());optionAudit.put("reparsed_all_roots_values",values);optionAudit.put("context_options",ModelMaterialGpuValidation.OPTION_OVERRIDES);optionAudit.put("context_scope","captured complete option map is authoritative; actual compiled root options checked strictly; all-root discovery can include inactive Colorwheel and omit injected/Voxy guards");optionAudit.put("shadow_resolution",inputs.realistic.shadows.getResolution());Files.writeString(output.resolve("options-"+inventory.digest().substring(0,12)+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(optionAudit)+"\n");
            }
            AlphaTest alpha=properties.getAlphaTestOverrides().getOrDefault("gbuffers_entities",AlphaTests.ONE_TENTH_ALPHA);
            boolean exact=g==null&&!uniform.matcher(v).replaceAll("").contains("entityColor");
            String merged=new ParticleVertexInjector().patch(exact?uniform.matcher(v).replaceAll(""):v,(String)(drawOptimization?deliveredBuild.invoke(delivered,exact):build.invoke(compiler,exact)),"model-merge-validation");
            merged=net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor.glslPreprocessSource(merged,DEFINES);
            var stages=new ModelShaderOptimization.Stages(merged,g,null,null,exact?uniform.matcher(f).replaceAll("in vec4 entityColor;"):f);
            String candidateMerged=new ParticleVertexInjector().patch(exact?uniform.matcher(v).replaceAll(""):v,(String)(variant.equals("aa")?deliveredBuild.invoke(delivered,exact):currentBuild.invoke(current,exact)),"model-draw-validation");
            candidateMerged=net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor.glslPreprocessSource(candidateMerged,DEFINES);
            var candidateStages=new ModelShaderOptimization.Stages(candidateMerged,g,null,null,stages.fragment());
            int[] productionBaseline=new int[2],productionCandidate=new int[2];
            double[] baselineTimes=new double[2],candidateTimes=new double[2];double optimizationMs=0;
            Map<PatchShaderType,String> baselineBody=null,candidateBody=null;
            ModelShaderOptimization.Stages[] baselineStages=new ModelShaderOptimization.Stages[2];ModelShaderOptimization.Stages bodyStages=null,carrierStages=null,unguardedBodyStages=null;
            for(int context=0;context<2;context++){
                int id=context==0?allay:0;long optimizeStart=System.nanoTime();var plan=candidate(candidateStages,id);optimizationMs+=(System.nanoTime()-optimizeStart)/1e6;
                if(FEATHER){var frozen=FeatherBaselineOptimization.prepare(new FeatherBaselineOptimization.Stages(candidateStages.vertex(),candidateStages.geometry(),candidateStages.tessControl(),candidateStages.tessEval(),candidateStages.fragment()),id,64,64).stages();
                    check(plan.stages().equals(new ModelShaderOptimization.Stages(frozen.vertex(),frozen.geometry(),frozen.tessControl(),frozen.tessEval(),frozen.fragment())),"Feather baseline body/carrier source drift");}
                if(context==0)unguardedBodyStages=plan.stages();
                if((PRODUCTION_DRAW||FEATHER&&featherBranch())&&(context==0||plan.stages().equals(unguardedBodyStages))){
                    var feather=ModelShaderOptimization.prepareFeatherBranches(plan.stages(),exact);
                    plan=new ModelShaderOptimization.Plan(feather.stages(),java.util.stream.Stream.concat(plan.applied().stream(),feather.applied().stream()).toList(),java.util.stream.Stream.concat(plan.skipped().stream(),feather.skipped().stream()).toList());
                }
                baselineStages[context]=PRODUCTION_DRAW?plan.stages():FEATHER?frozenFeatherPlan(stages,id):drawOptimization?(ROUND2?oldPlan(stages,id):candidate(stages,id).stages()):stages;long start=System.nanoTime();var original=transform(baselineStages[context],alpha,"merge-baseline");if(context==0)baselineBody=original;productionBaseline[context]=ModelMaterialGpuValidation.link(original,"baseline-"+context,root);double baselineCompile=(System.nanoTime()-start)/1e6;
                start=System.nanoTime();var optimized=transform(plan.stages(),alpha,"merge-candidate");if(ROUND2&&variant.equals("aa"))check(baselineStages[context].equals(plan.stages()),"A/A stage sources differ");productionCandidate[context]=ModelMaterialGpuValidation.link(optimized,"candidate-"+context,root);double optimizedCompile=(System.nanoTime()-start)/1e6;
                baselineTimes[context]=baselineCompile;candidateTimes[context]=optimizedCompile;
                if(context==0){candidateBody=optimized;bodyStages=plan.stages();}else carrierStages=plan.stages();
                if(context==0&&g==null){
                    ModelMaterialGpuValidation.mergedFeatherVertices(plan.stages().vertex(),plan.stages().fragment(),pack+" optimized feather");
                    GL30.glBindVertexArray(vao);check(GL11.glGetError()==0,"Feather fixture cleanup");
                }
                compilation.add(String.format(Locale.ROOT,"%s,%d,%.3f,%.3f,\"%s\",\"%s\"",pack,context,baselineCompile,optimizedCompile,String.join(";",plan.applied()),String.join(";",plan.skipped())));
                int a=fixture(original.get(PatchShaderType.FRAGMENT),id,"raster-baseline-"+context,root),b=fixture(optimized.get(PatchShaderType.FRAGMENT),id,"raster-candidate-"+context,root);
                try{differential(a,b,inputs,id,root);if(benchmark&&context==0&&!drawOptimization)benchmark(pack,a,b,inputs,id,csv);}finally{GL20.glDeleteProgram(a);GL20.glDeleteProgram(b);}
            }
            Files.write(output.resolve("compile.csv"),compilation);
            long baselineGhostStart=System.nanoTime();int baselineGhost=drawOptimization?ModelMaterialGpuValidation.link(PRODUCTION_DRAW?transform(unguardedBodyStages,alpha,"production-native-ghost"):baselineBody,"baseline-ghost",root):0;
            double baselineGhostMs=drawOptimization?(System.nanoTime()-baselineGhostStart)/1e6:0;
            long ghostStart=System.nanoTime();int candidateGhost=ModelMaterialGpuValidation.link((PRODUCTION_DRAW||FEATHER&&featherBranch())?transform(unguardedBodyStages,alpha,"native-ghost"):candidateBody,"candidate-ghost",root);double ghostMs=(System.nanoTime()-ghostStart)/1e6;
            boolean shared=bodyStages.equals(carrierStages);
            startupCsv.add(String.format(Locale.ROOT,"%s,%d,%d,%.3f,%.3f,%.3f",pack,drawOptimization?(baselineStages[0].equals(baselineStages[1])?2:3):2,shared?2:3,
                    baselineTimes[0]+(drawOptimization&&baselineStages[0].equals(baselineStages[1])?0:baselineTimes[1])+baselineGhostMs,candidateTimes[0]+(shared?0:candidateTimes[1])+ghostMs,optimizationMs));
            if(ROUND2&&!FEATHER&&!PRODUCTION_DRAW){
                var loadRows=new ArrayList<String>();loadRows.add("round,order,baseline_ms,candidate_ms");
                for(int loadRound=-1;loadRound<Integer.getInteger("cmi.model.load.rounds",3);loadRound++){
                    double[] costs=new double[2];
                    for(int order=0;order<2;order++){
                        boolean old=((loadRound+order)&1)==0;long started=System.nanoTime();
                        var body=old?oldPlan(stages,allay):candidate(candidateStages,allay).stages();
                        var carrier=old?oldPlan(stages,0):candidate(candidateStages,0).stages();
                        var bodyMap=transform(body,alpha,"load-body");var ids=new ArrayList<Integer>();
                        ids.add(ModelMaterialGpuValidation.link(bodyMap,"load-body-"+old,root));
                        if(!body.equals(carrier))ids.add(ModelMaterialGpuValidation.link(transform(carrier,alpha,"load-carrier"),"load-carrier-"+old,root));
                        ids.add(ModelMaterialGpuValidation.link(bodyMap,"load-ghost-"+old,root));
                        costs[old?0:1]=(System.nanoTime()-started)/1e6;for(int pid:ids)GL20.glDeleteProgram(pid);
                    }
                    if(loadRound>=0)loadRows.add(loadRound+","+(loadRound%2==0?"baseline-first":"candidate-first")+","+costs[0]+","+costs[1]);
                }
                Files.write(output.resolve("load-"+inventory.digest().substring(0,12)+".csv"),loadRows);
            }
            int[] originalDraw={productionBaseline[0],drawOptimization?(baselineStages[0].equals(baselineStages[1])?productionBaseline[0]:productionBaseline[1]):productionBaseline[0],drawOptimization?baselineGhost:productionBaseline[1]};
            int[] candidateDraw={productionCandidate[0],bodyStages.equals(carrierStages)?productionCandidate[0]:productionCandidate[1],candidateGhost};
            try{if(FEATHER){try(var feathers=new FeatherMergeProductionFixture(inputs,allay)){int dedicated=0;try{
                    if(Set.of("thin","branch").contains(System.getProperty("cmi.feather.variant","branch")))featherLoadProbe(sourceLoader,v,g,candidateStages.fragment(),exact,allay,alpha,root,bodyStages,carrierStages,featherBranch()?null:thinFeather(sourceLoader,v,g,bodyStages.fragment(),exact,allay));
                    if(Set.of("specialized","thin").contains(System.getProperty("cmi.feather.variant","indexed"))){
                        long started=System.nanoTime();var featherBase=bodyStages;
                        if(System.getProperty("cmi.feather.variant","indexed").equals("thin")){
                            featherBase=thinFeather(sourceLoader,v,g,bodyStages.fragment(),exact,allay);
                        }
                        var featherPlan=experimentFeather(featherBase,exact);
                        if(!featherPlan.stages().fragment().equals(bodyStages.fragment()))dedicated=ModelMaterialGpuValidation.link(transform(featherPlan.stages(),alpha,"feather-specialized"),"feather-specialized",root);
                        Files.writeString(root.resolve("feather-compile-ms.txt"),Double.toString((System.nanoTime()-started)/1e6));
                    }
                    boolean changed=!baselineStages[0].equals(bodyStages);
                    feathers.run(featherBranch()?productionBaseline[0]:productionCandidate[0],featherBranch()&&changed?productionCandidate[0]:dedicated,pack,benchmark,root);
                    if(featherBranch())try(var models=new ModelMergeProductionFixture(inputs,allay)){models.validate(originalDraw,candidateDraw,pack);models.validateLod(originalDraw,candidateDraw,pack);
                        if(Boolean.getBoolean("cmi.feather.model.bench")){String saved=System.getProperty("cmi.model.draw.quick");System.setProperty("cmi.model.draw.quick","true");try{models.benchmark(originalDraw,Boolean.getBoolean("cmi.feather.aa")?originalDraw:candidateDraw,pack,productionCsv);}finally{if(saved==null)System.clearProperty("cmi.model.draw.quick");else System.setProperty("cmi.model.draw.quick",saved);}}
                    }
                }finally{if(dedicated!=0)GL20.glDeleteProgram(dedicated);}}}
            else try(var production=new ModelMergeProductionFixture(inputs,allay)){
                production.validate(originalDraw,candidateDraw,pack);production.validateLod(originalDraw,candidateDraw,pack);if(ROUND2)production.validateFullFrame(originalDraw,candidateDraw,pack);
                if(benchmark)production.benchmark(originalDraw,candidateDraw,pack,productionCsv);
            }
            }finally{if(baselineGhost!=0)GL20.glDeleteProgram(baselineGhost);for(int p:productionBaseline)GL20.glDeleteProgram(p);for(int p:productionCandidate)GL20.glDeleteProgram(p);GL20.glDeleteProgram(candidateGhost);GL30.glBindVertexArray(vao);}
            if(!PRODUCTION_DRAW&&!FEATHER&&g==null&&(!drawOptimization||ROUND2&&!benchmark))optionVariants(root,base,allay,ROUND2?delivered:compiler,ROUND2?deliveredBuild:build,inputs,alpha);
            packs++;System.out.println(pack+": original/candidate body+carrier stages and MRT differences passed");
            Files.write(output.resolve("compile.csv"),compilation);Files.write(output.resolve("draw.csv"),csv);
            Files.write(output.resolve("production.csv"),productionCsv);
            Files.write(output.resolve("startup.csv"),startupCsv);Files.write(output.resolve("coverage.csv"),coverageRows);Files.write(output.resolve("first-draw.csv"),firstDrawRows);
        }}finally{GL30.glDeleteVertexArrays(vao);}
        check(packs>= (System.getProperty("cmi.model.draw.pack","").isEmpty()?8:1),"Pack matrix");check(GL11.glGetError()==0,"Final GL state");
        System.out.println("MODEL merge GPU: "+checks+" checks passed");
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,System.getProperty("cmi.draw.snapshots")==null?5:6);
        long window=GLFW.glfwCreateWindow(64,64,"Model merge differential",0,0);check(window!=0,"OpenGL context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));run(Arrays.asList(args).contains("--benchmark"));}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
