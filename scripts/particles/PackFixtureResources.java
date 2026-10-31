import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import org.joml.Matrix4f;
import net.irisshaders.iris.shaderpack.properties.*;
import net.irisshaders.iris.shaderpack.parsing.*;

/** Deterministic nonconstant resources; a controlled fixture, never a world replay. */
final class PackFixtureResources implements AutoCloseable {
    final Map<String,Integer> images=new HashMap<>(),units=new HashMap<>();int nextUnit=1;
    PackShadowDirectives shadows;int width=1920,height=1080;
    void configure(ShaderProperties properties,String sources){
        close();units.clear();nextUnit=1;shadows=new PackShadowDirectives(properties);var holder=new DispatchingDirectiveHolder();shadows.acceptDirectives(holder);
        for(var directive:ConstDirectiveParser.findDirectives(sources))holder.processDirective(directive);
        System.out.println("Fixture shadow resolution="+shadows.getResolution()+"; depth0 nearest="+shadows.getDepthSamplingSettings().get(0).getNearest()+"; hardware="+shadows.getDepthSamplingSettings().get(0).getHardwareFiltering());
    }
    void viewport(int w,int h){if(w!=width||h!=height){close();width=w;height=h;}}
    static void matrix(int p,String name,Matrix4f value){GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,name),false,value.get(new float[16]));}
    void bind(int p,int atlas){
        int active=GL20.glGetProgrami(p,GL20.GL_ACTIVE_UNIFORMS),max=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        for(int i=0;i<active;i++)try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
            var size=stack.mallocInt(1);var type=stack.mallocInt(1);String first=GL20.glGetActiveUniform(p,i,size,type);int kind=type.get(0);
            if(first.startsWith("cmi_"))continue;
            int target=switch(kind){case GL20.GL_SAMPLER_2D,GL20.GL_SAMPLER_2D_SHADOW->GL11.GL_TEXTURE_2D;case GL20.GL_SAMPLER_3D->GL12.GL_TEXTURE_3D;case GL20.GL_SAMPLER_CUBE->GL13.GL_TEXTURE_CUBE_MAP;default->0;};
            if(target==0)continue;
            for(int element=0;element<size.get(0);element++){
                String name=first.endsWith("[0]")?first.substring(0,first.length()-3)+"["+element+"]":first;
                boolean diffuse=Set.of("tex","gtexture","iris_Sampler0","diffuse").contains(name);
                int unit=0;
                if(!diffuse){String key=name+":"+kind;
                    if(!units.containsKey(key)){while(nextUnit>=10&&nextUnit<=15)nextUnit++;units.put(key,nextUnit++);}
                    unit=units.get(key);
                }
                if(unit>=max)throw new AssertionError("Sampler capacity exceeded");
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
                int texture=diffuse?atlas:image(name,kind,target);
                GL20.glUniform1i(GL20.glGetUniformLocation(p,name),unit);GL11.glBindTexture(target,texture);
            }
        }
        float aspect=(float)width/height;
        Matrix4f projection=new Matrix4f().perspective((float)Math.toRadians(70),aspect,.1f,4096);
        for(String name:List.of("iris_ProjMat","gbufferProjection","projectionMatrix","projection"))matrix(p,name,projection);
        matrix(p,"gbufferProjectionInverse",new Matrix4f(projection).invert());
        Matrix4f shadowProjection=new Matrix4f().ortho(-shadows.getDistance(),shadows.getDistance(),-shadows.getDistance(),shadows.getDistance(),-256,256);
        matrix(p,"shadowProjection",shadowProjection);matrix(p,"shadowProjectionInverse",new Matrix4f(shadowProjection).invert());
        GL20.glUniform3f(GL20.glGetUniformLocation(p,"shadowLightPosition"),30,100,60);
        GL20.glUniform3f(GL20.glGetUniformLocation(p,"sunPosition"),30,100,60);
        GL20.glUniform3f(GL20.glGetUniformLocation(p,"moonPosition"),-30,-100,-60);
        GL20.glUniform3f(GL20.glGetUniformLocation(p,"skyColor"),.45f,.65f,.9f);
        GL20.glUniform3f(GL20.glGetUniformLocation(p,"fogColor"),.6f,.7f,.8f);
        GL20.glUniform2i(GL20.glGetUniformLocation(p,"eyeBrightness"),240,240);
        GL20.glUniform2i(GL20.glGetUniformLocation(p,"eyeBrightnessSmooth"),240,240);
        ModelMergeGpuValidation.integer(p,"frameCounter",120);ModelMergeGpuValidation.integer(p,"worldTime",6000);
        ModelMergeGpuValidation.number(p,"frameTime",1f/60);ModelMergeGpuValidation.number(p,"shadowDistance",shadows.getDistance());
        ModelMergeGpuValidation.number(p,"viewWidth",width);ModelMergeGpuValidation.number(p,"viewHeight",height);
        GL20.glUniform2f(GL20.glGetUniformLocation(p,"texelSize"),1f/width,1f/height);
    }
    int image(String name,int kind,int target){
        String key=name+":"+kind;Integer previous=images.get(key);if(previous!=null)return previous;
        int texture=GL11.glGenTextures();images.put(key,texture);GL11.glBindTexture(target,texture);
        boolean depth=kind==GL20.GL_SAMPLER_2D_SHADOW||name.startsWith("shadowtex")||name.startsWith("depthtex");
        int w=name.startsWith("shadow")?shadows.getResolution():name.startsWith("colortex")||name.startsWith("depthtex")?width:name.toLowerCase(Locale.ROOT).contains("noise")?256:64;
        int h=name.startsWith("colortex")||name.startsWith("depthtex")?height:w;
        w=Math.max(1,w);h=Math.max(1,h);
        if(target==GL12.GL_TEXTURE_3D){int side=32;var b=BufferUtils.createByteBuffer(side*side*side*4);for(int i=0;i<b.capacity();i++)b.put((byte)((i*37+name.hashCode())>>>3));b.flip();GL12.glTexImage3D(target,0,GL11.GL_RGBA8,side,side,side,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);}
        else if(target==GL13.GL_TEXTURE_CUBE_MAP){for(int face=0;face<6;face++){var b=colors(64,64,name+face);GL11.glTexImage2D(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X+face,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);}}
        else if(depth){var b=BufferUtils.createFloatBuffer(w*h);for(int y=0;y<h;y++)for(int x=0;x<w;x++)b.put(.15f+.7f*((x/13+y/17)%2));b.flip();GL11.glTexImage2D(target,0,GL30.GL_DEPTH_COMPONENT32F,w,h,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,b);}
        else GL11.glTexImage2D(target,0,GL11.GL_RGBA8,w,h,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,colors(w,h,name));
        boolean nearest=name.startsWith("shadowtex")?shadows.getDepthSamplingSettings().get(name.contains("1")?1:0).getNearest():false;
        boolean mip=name.startsWith("shadowtex")&&shadows.getDepthSamplingSettings().get(name.contains("1")?1:0).getMipmap();
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_MIN_FILTER,mip?(nearest?GL11.GL_NEAREST_MIPMAP_NEAREST:GL11.GL_LINEAR_MIPMAP_LINEAR):(nearest?GL11.GL_NEAREST:GL11.GL_LINEAR));
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_MAG_FILTER,nearest?GL11.GL_NEAREST:GL11.GL_LINEAR);
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(target,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        if(kind==GL20.GL_SAMPLER_2D_SHADOW){GL11.glTexParameteri(target,GL14.GL_TEXTURE_COMPARE_MODE,GL30.GL_COMPARE_REF_TO_TEXTURE);GL11.glTexParameteri(target,GL14.GL_TEXTURE_COMPARE_FUNC,GL11.GL_LEQUAL);}
        if(mip)GL30.glGenerateMipmap(target);return texture;
    }
    java.nio.ByteBuffer colors(int w,int h,String name){
        var b=BufferUtils.createByteBuffer(w*h*4);int seed=name.hashCode();for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int noise=(x*73856093)^(y*19349663)^seed;
            int r=48+(noise&127),g=48+((noise>>>8)&127),v=48+((noise>>>16)&127);
            if(name.equals("normals")){r=128+(x%7)-3;g=128+(y%7)-3;v=250;}
            if(name.equals("lightmap")){r=g=v=80+175*Math.max(x,y)/Math.max(w,h);}
            b.put((byte)r).put((byte)g).put((byte)v).put((byte)255);
        }return b.flip();
    }
    public void close(){for(int texture:images.values())GL11.glDeleteTextures(texture);images.clear();}
}
