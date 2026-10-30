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
    static final int W=1920,H=1080;
    static int checks;
    static Path output;
    static final List<StringPair> DEFINES=ModelMaterialGpuValidation.DEFINES;
    static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static void integer(int p,String name,int value){GL20.glUniform1i(GL20.glGetUniformLocation(p,name),value);}
    static void number(int p,String name,float value){GL20.glUniform1f(GL20.glGetUniformLocation(p,name),value);}
    static Map<PatchShaderType,String> transform(ModelShaderOptimization.Stages s,AlphaTest alpha,String name){
        return TransformPatcher.patchVanilla(name,s.vertex(),s.geometry(),s.tessControl(),s.tessEval(),s.fragment(),alpha,false,true,
                new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
    }
    // Experiment-only exclusions; never read by the production optimizer.
    static ModelShaderOptimization.Plan candidate(ModelShaderOptimization.Stages input,int id){
        Set<String> excluded=new HashSet<>(Arrays.asList(System.getProperty("cmi.model.merge.exclude","").split(",")));
        java.util.function.UnaryOperator<String> protect=s->{if(s==null)return null;for(String name:excluded)if(!name.isEmpty())s=s.replaceAll("\\b"+Pattern.quote(name)+"\\b","cmi_reference_"+name);return s;};
        java.util.function.UnaryOperator<String> restore=s->{if(s==null)return null;for(String name:excluded)if(!name.isEmpty())s=s.replace("cmi_reference_"+name,name);return s;};
        var plan=ModelShaderOptimization.prepare(map(input,protect),id,64,64);
        return new ModelShaderOptimization.Plan(map(plan.stages(),restore),plan.applied(),plan.skipped());
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
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,internal,w,h,0,types[i]==GL11.GL_FLOAT?GL11.GL_RGBA:GL30.GL_RGBA_INTEGER,types[i],(ByteBuffer)null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0+i,GL11.GL_TEXTURE_2D,colors[i],0);
            }
            depth=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH_COMPONENT32F,w,h,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,(ByteBuffer)null);
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
        final int atlas,flat,normal,depth,shadow,volume,cube;
        Inputs()throws Exception{
            var b=org.lwjgl.BufferUtils.createByteBuffer(64*64*4);
            for(int y=0;y<64;y++)for(int x=0;x<64;x++)b.put((byte)(40+x*3)).put((byte)(70+y*2)).put((byte)232).put((byte)((x+y)%4==0?0:((x+y)%4==1?25:((x+y)%4==2?160:255))));b.flip();
            // Native body and sword art; unused pixels retain an alpha-edge fixture.
            var canvas=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for(int frame=0;frame<7;frame++){
                String name=new String[]{"allay_0","sword_wooden","sword_stone","sword_golden","sword_iron","sword_diamond","sword_netherite"}[frame];
                var image=javax.imageio.ImageIO.read(Path.of("src/main/resources/assets/createmanaindustry/textures/particle/"+name+".png").toFile());
                var rect=com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlasLayout.model(frame);
                for(int y=0;y<rect.height();y++)for(int x=0;x<rect.width();x++){int c=image.getRGB(x,y),at=((rect.y()+y)*64+rect.x()+x)*4;b.put(at,(byte)(c>>16));b.put(at+1,(byte)(c>>8));b.put(at+2,(byte)c);b.put(at+3,(byte)(c>>24));}
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
        }
        public void close(){GL11.glDeleteTextures(new int[]{atlas,flat,normal,depth,shadow,volume,cube});}
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
            check(x==y,label+" attachment="+attachment+" word="+i+" "+Integer.toHexString(x)+" != "+Integer.toHexString(y));
        }
    }
    static void differential(int baseline,int candidate,Inputs inputs,int id,Path root)throws Exception{
        try(var target=new Target(baseline,64,64)){
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
    static void optionVariants(Path root,String base,int id,FrozenModelProgramCompiler compiler,java.lang.reflect.Method build,Inputs inputs,AlphaTest alpha)throws Exception{
        for(String[] variant:new String[][]{{"lib/common.glsl","#define RP_MODE 1","#define RP_MODE 2","custom-pbr"},
                {"lib/settings.glsl","//#define ADVANCED_MATERIALS","#define ADVANCED_MATERIALS","advanced-materials"}}){
            Path file=root.resolve(variant[0]);if(!Files.exists(file))continue;String saved=Files.readString(file);if(!saved.contains(variant[1]))continue;
            Files.writeString(file,saved.replace(variant[1],variant[2]).replace("#define ALPHA_BLEND 0","#define ALPHA_BLEND 1"));
            try{
                String v=ModelMaterialGpuValidation.preprocess(root,base+".vsh"),f=ModelMaterialGpuValidation.preprocess(root,base+".fsh");
                var overlay=Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;");boolean exact=!overlay.matcher(v).replaceAll("").contains("entityColor");
                v=new ParticleVertexInjector().patch(exact?overlay.matcher(v).replaceAll(""):v,(String)build.invoke(compiler,exact),"option-"+variant[3]);
                v=net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor.glslPreprocessSource(v,DEFINES);
                var original=transform(new ModelShaderOptimization.Stages(v,null,null,null,exact?overlay.matcher(f).replaceAll("in vec4 entityColor;"):f),alpha,"option-original");
                var plan=candidate(new ModelShaderOptimization.Stages(v,null,null,null,exact?overlay.matcher(f).replaceAll("in vec4 entityColor;"):f),id);
                var optimized=transform(plan.stages(),alpha,"option-optimized");
                int fullA=ModelMaterialGpuValidation.link(original,"option-"+variant[3]+"-baseline",root),fullB=ModelMaterialGpuValidation.link(optimized,"option-"+variant[3]+"-candidate",root);
                int a=fixture(original.get(PatchShaderType.FRAGMENT),id,"option-raster-"+variant[3]+"-baseline",root),b=fixture(optimized.get(PatchShaderType.FRAGMENT),id,"option-raster-"+variant[3]+"-candidate",root);
                try{differential(a,b,inputs,id,root.resolve(variant[3]));}
                finally{for(int p:new int[]{fullA,fullB,a,b})GL20.glDeleteProgram(p);}
                System.out.println(root.getFileName()+": "+variant[3]+" option exact MRT comparison passed");
            }finally{Files.writeString(file,saved);}
        }
    }
    static void run(boolean benchmark)throws Exception{
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        DEFINES.add(new StringPair("MC_NORMAL_MAP","1"));DEFINES.add(new StringPair("MC_SPECULAR_MAP","1"));
        for(var phase:net.irisshaders.iris.pipeline.WorldRenderingPhase.values())DEFINES.add(new StringPair("MC_RENDER_STAGE_"+phase.name(),Integer.toString(phase.ordinal())));
        output=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/model-merge"));Files.createDirectories(output);
        var csv=new ArrayList<String>();csv.add("pack,segment,scene,count,round,sample,baseline_ms,candidate_ms");
        var compilation=new ArrayList<String>();compilation.add("pack,context,baseline_compile_ms,candidate_compile_ms,applied,skipped");
        var productionCsv=new ArrayList<String>();productionCsv.add("pack,scene,count,round,sample,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms");
        var startupCsv=new ArrayList<String>();startupCsv.add("pack,baseline_programs,candidate_programs,baseline_stages_ms,candidate_stages_ms,optimization_ms");
        java.util.function.Function<String,String> sourceLoader=name->com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource.loadParticle(name,path->{try{return Files.readString(Path.of("src/main/resources/assets/createmanaindustry",path));}catch(Exception e){throw new RuntimeException(e);}});
        var compiler=new FrozenModelProgramCompiler(sourceLoader);
        var build=FrozenModelProgramCompiler.class.getDeclaredMethod("buildMergedSource",boolean.class);build.setAccessible(true);
        var current=new ShaderPackProgramCompiler(sourceLoader);var currentBuild=ShaderPackProgramCompiler.class.getDeclaredMethod("buildMergedSource",boolean.class);currentBuild.setAccessible(true);
        for(boolean exact:new boolean[]{false,true})check(build.invoke(compiler,exact).equals(currentBuild.invoke(current,exact)),"Frozen baseline vertex ABI differs from production");
        var uniform=Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;");int packs=0,vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
        try(var inputs=new Inputs();var samples=Files.list(Path.of("run/shaderpacks"))){for(var sample:samples.filter(p->p.toString().endsWith(".zip")).sorted().toList()){
            String pack=sample.getFileName().toString().replace(',','_');var inventory=ShaderPackInventory.readPack(sample);Path root=output.resolve(inventory.digest().substring(0,12));Files.createDirectories(root);
            for(var entry:inventory.sources().entrySet()){Path p=root.resolve(entry.getKey());Files.createDirectories(p.getParent());Files.writeString(p,entry.getValue());}
            String base=null;for(String c:List.of("gbuffers_entities","gbuffers_textured_lit","gbuffers_textured","gbuffers_basic")){String path=Files.exists(root.resolve("world0/"+c+".vsh"))?"world0/"+c:c;if(Files.exists(root.resolve(path+".vsh"))&&Files.exists(root.resolve(path+".fsh"))){base=path;break;}}check(base!=null,"Entities fallback");
            String v=ModelMaterialGpuValidation.preprocess(root,base+".vsh"),f=ModelMaterialGpuValidation.preprocess(root,base+".fsh"),g=Files.exists(root.resolve(base+".gsh"))?ModelMaterialGpuValidation.preprocess(root,base+".gsh"):null;
            var graph=new IncludeGraph(root,ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/"+base+".vsh"),AbsolutePackPath.fromAbsolutePath("/"+base+".fsh")),false);
            var options=new ShaderPackOptions(graph,Map.of());var constructor=net.irisshaders.iris.shaderpack.IdMap.class.getDeclaredConstructor(Path.class,ShaderPackOptions.class,Iterable.class);constructor.setAccessible(true);
            int allay=ModelEntityContext.resolve((net.irisshaders.iris.shaderpack.IdMap)constructor.newInstance(root,options,DEFINES)).allayId();
            var properties=new ShaderProperties(inventory.source("shaders.properties"),options,DEFINES);
            AlphaTest alpha=properties.getAlphaTestOverrides().getOrDefault("gbuffers_entities",AlphaTests.ONE_TENTH_ALPHA);
            boolean exact=g==null&&!uniform.matcher(v).replaceAll("").contains("entityColor");
            String merged=new ParticleVertexInjector().patch(exact?uniform.matcher(v).replaceAll(""):v,(String)build.invoke(compiler,exact),"model-merge-validation");
            merged=net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor.glslPreprocessSource(merged,DEFINES);
            var stages=new ModelShaderOptimization.Stages(merged,g,null,null,exact?uniform.matcher(f).replaceAll("in vec4 entityColor;"):f);
            int[] productionBaseline=new int[2],productionCandidate=new int[2];
            double[] baselineTimes=new double[2],candidateTimes=new double[2];double optimizationMs=0;
            Map<PatchShaderType,String> candidateBody=null;ModelShaderOptimization.Stages bodyStages=null,carrierStages=null;
            for(int context=0;context<2;context++){
                int id=context==0?allay:0;long optimizeStart=System.nanoTime();var plan=candidate(stages,id);optimizationMs+=(System.nanoTime()-optimizeStart)/1e6;
                long start=System.nanoTime();var original=transform(stages,alpha,"merge-baseline");productionBaseline[context]=ModelMaterialGpuValidation.link(original,"baseline-"+context,root);double baselineCompile=(System.nanoTime()-start)/1e6;
                start=System.nanoTime();var optimized=transform(plan.stages(),alpha,"merge-candidate");productionCandidate[context]=ModelMaterialGpuValidation.link(optimized,"candidate-"+context,root);double optimizedCompile=(System.nanoTime()-start)/1e6;
                baselineTimes[context]=baselineCompile;candidateTimes[context]=optimizedCompile;
                if(context==0){candidateBody=optimized;bodyStages=plan.stages();}else carrierStages=plan.stages();
                if(context==0&&g==null){
                    ModelMaterialGpuValidation.mergedFeatherVertices(plan.stages().vertex(),plan.stages().fragment(),pack+" optimized feather");
                    GL30.glBindVertexArray(vao);check(GL11.glGetError()==0,"Feather fixture cleanup");
                }
                compilation.add(String.format(Locale.ROOT,"%s,%d,%.3f,%.3f,\"%s\",\"%s\"",pack,context,baselineCompile,optimizedCompile,String.join(";",plan.applied()),String.join(";",plan.skipped())));
                int a=fixture(original.get(PatchShaderType.FRAGMENT),id,"raster-baseline-"+context,root),b=fixture(optimized.get(PatchShaderType.FRAGMENT),id,"raster-candidate-"+context,root);
                try{differential(a,b,inputs,id,root);if(benchmark&&context==0)benchmark(pack,a,b,inputs,id,csv);}finally{GL20.glDeleteProgram(a);GL20.glDeleteProgram(b);}
            }
            long ghostStart=System.nanoTime();int candidateGhost=ModelMaterialGpuValidation.link(candidateBody,"candidate-ghost",root);double ghostMs=(System.nanoTime()-ghostStart)/1e6;
            boolean shared=bodyStages.equals(carrierStages);
            startupCsv.add(String.format(Locale.ROOT,"%s,2,%d,%.3f,%.3f,%.3f",pack,shared?2:3,
                    baselineTimes[0]+baselineTimes[1],candidateTimes[0]+(shared?0:candidateTimes[1])+ghostMs,optimizationMs));
            int[] originalDraw={productionBaseline[0],productionBaseline[0],productionBaseline[1]};
            int[] candidateDraw={productionCandidate[0],bodyStages.equals(carrierStages)?productionCandidate[0]:productionCandidate[1],candidateGhost};
            try(var production=new ModelMergeProductionFixture(inputs,allay)){
                production.validate(originalDraw,candidateDraw,pack);production.validateLod(originalDraw,candidateDraw,pack);
                if(benchmark)production.benchmark(originalDraw,candidateDraw,pack,productionCsv);
            }
            finally{for(int p:productionBaseline)GL20.glDeleteProgram(p);for(int p:productionCandidate)GL20.glDeleteProgram(p);GL20.glDeleteProgram(candidateGhost);GL30.glBindVertexArray(vao);}
            if(g==null)optionVariants(root,base,allay,compiler,build,inputs,alpha);
            packs++;System.out.println(pack+": original/candidate body+carrier stages and MRT differences passed");
            Files.write(output.resolve("compile.csv"),compilation);Files.write(output.resolve("draw.csv"),csv);
            Files.write(output.resolve("production.csv"),productionCsv);
            Files.write(output.resolve("startup.csv"),startupCsv);
        }}finally{GL30.glDeleteVertexArrays(vao);}
        check(packs>=8,"Eight pack matrix");check(GL11.glGetError()==0,"Final GL state");
        System.out.println("MODEL merge GPU: "+checks+" checks passed");
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Model merge differential",0,0);check(window!=0,"OpenGL context");
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));run(Arrays.asList(args).contains("--benchmark"));}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
