package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.infrastructure.config.*;
import com.iridium126.createmanaindustry.client.allaystorm.visual.StormCenterView;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.*;
import java.util.Map;

/** Main-frame visibility. Never reuses a historical occlusion result. */
public final class RiftVisibility {
    private static boolean visible=true,clipping;private static RiftCulling mode=RiftCulling.NONE;
    private static int flag,fbo,query,program,vao;private static boolean conditional;
    private static float[] rectangle;private static String reason="not evaluated";
    private static RiftRenderFrame owner;
    private static String identity="none";
    private RiftVisibility(){}
    public static boolean clipping(){return clipping;}
    public static int texture(){return flag;}
    public static boolean guarded(){return flag!=0&&owner==RiftRenderFrame.current()&&!RiftRenderFrame.auxiliary();}
    public static boolean visible(){return visible;}
    public static void scissor(int width,int height){if(!clipping||rectangle==null||!visible)return;GL11.glEnable(GL11.GL_SCISSOR_TEST);if(rectangle[0]>rectangle[2]){GL11.glScissor(0,0,0,0);return;}int x=Math.max(0,(int)Math.floor((rectangle[0]+1)*.5*width)-2),y=Math.max(0,(int)Math.floor((rectangle[1]+1)*.5*height)-2);int right=Math.min(width,(int)Math.ceil((rectangle[2]+1)*.5*width)+2),top=Math.min(height,(int)Math.ceil((rectangle[3]+1)*.5*height)+2);GL11.glScissor(x,y,right-x,top-y);}
    public static Map<String,Object> metadata(){
        var data=new java.util.LinkedHashMap<String,Object>();
        data.put("clipping_requested",ClientConfig.riftSkyClipping);data.put("culling_requested",ClientConfig.riftCulling.name());
        data.put("clipping_applied",clipping);data.put("culling_applied",mode.name());
        data.put("frustum_visible",visible);data.put("depth_path",reason);data.put("gpu_occlusion_result","unknown on CPU; same-frame GPU only");
        data.put("rift_identity",identity);data.put("frame",owner==null?-1:owner.number());data.put("pipeline_generation",owner==null?-1:owner.generation());
        data.put("width",owner==null?-1:owner.width());data.put("height",owner==null?-1:owner.height());return data;
    }
    public static void begin(RiftRenderFrame frame){
        try(var timing=com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages.begin("rift.visibility.prepare")){evaluate(frame);}
    }
    private static void evaluate(RiftRenderFrame frame){
        owner=frame;identity="none";clipping=ClientConfig.riftSkyClipping;mode=ClientConfig.riftCulling;rectangle=null;visible=true;reason=mode==RiftCulling.NONE?"disabled":"frustum only";
        var mc=Minecraft.getInstance();var snapshot=AllaySkyProvider.rift();var shape=JuliaProjection.shape();
        if(snapshot==null||shape==null||mc.level==null){owner=null;reason="no active Julia bounds";return;}
        identity=snapshot.identity().toString();
        var r=AllaySkyProvider.rift(frame.camera(),frame.partial());
        var centre=StormCenterView.timeline().sample(mc.level.getGameTime(),frame.partial());if(r.w<0||centre==null){owner=null;reason="no active rift centre";return;}
        double scale=shape.scale(centre.radius(),centre.y(),snapshot.planeY(),r.w);
        var style=JuliaShape.projection(new Vector3d(r.x,r.y,r.z).length());
        rectangle=juliaBounds(new Matrix4f(frame.projection()).mul(frame.view()),shape,r.x,r.y,r.z,(float)scale,style);
        if(mode!=RiftCulling.NONE&&rectangle!=null&&rectangle[0]>rectangle[2])visible=false;
        resetFlag(visible?1:0);
    }
    /** Affine image of the original axial box, using the exact vertex shader deformation. */
    public static float[] juliaBounds(Matrix4fc viewProjection,JuliaShape shape,float x,float y,float z,float scale,JuliaShape.ProjectionStyle style){
        double length=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
        float rx=(float)(x/Math.max(length,.0001)),ry=(float)(y/Math.max(length,.0001)),rz=(float)(z/Math.max(length,.0001));
        float a=style.weak(),g=scale*style.gain();
        var affine=new Matrix4f().m00(g*(1-a*rx*rx)).m01(-g*a*rx*ry).m02(-g*a*rx*rz)
                .m10(-g*a*ry*rx).m11(g*(1-a*ry*ry)).m12(-g*a*ry*rz)
                .m20(-g*a*rz*rx).m21(-g*a*rz*ry).m22(g*(1-a*rz*rz)).m30(x).m31(y).m32(z);
        affine.scale((float)(shape.xHalfExtent*1.001+.001),(float)(shape.radialHalfExtent*1.001+.001),(float)(shape.radialHalfExtent*1.001+.001));
        return bounds(new Matrix4f(viewProjection).mul(affine),0,0,0,1);
    }
    /** Bounds match Julia's far-plane clamp. Null means near crossing/uncertain: keep visible. */
    public static float[] bounds(Matrix4fc matrix,float x,float y,float z,float extent){
        if(!Float.isFinite(extent)||extent<=0)return null;
        float left=Float.POSITIVE_INFINITY,bottom=left,right=-left,top=-left,depth=1;
        for(int i=0;i<8;i++){
            var p=new Vector4f(x+((i&1)==0?-extent:extent),y+((i&2)==0?-extent:extent),z+((i&4)==0?-extent:extent),1).mul(matrix);
            if(!Float.isFinite(p.x)||!Float.isFinite(p.y)||!Float.isFinite(p.z)||!Float.isFinite(p.w)||p.w<=0||p.z<=-p.w)return null;
            left=Math.min(left,p.x/p.w);right=Math.max(right,p.x/p.w);bottom=Math.min(bottom,p.y/p.w);top=Math.max(top,p.y/p.w);
            depth=Math.min(depth,Math.min(p.z/p.w,.99999f)*.5f+.5f);
        }
        if(right< -1||left>1||top< -1||bottom>1)return new float[]{1,1,-1,-1,0};
        return new float[]{Math.max(-1,left),Math.max(-1,bottom),Math.min(1,right),Math.min(1,top),Math.max(0,Math.nextDown(depth)-.00001f)};
    }
    private static void resetFlag(float value){
        int oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        var colors=org.lwjgl.BufferUtils.createByteBuffer(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,colors);
        try{
            GL11.glDisable(GL11.GL_SCISSOR_TEST);GL30.glColorMaski(0,true,true,true,true);
            if(flag==0)try(var textures=new com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState(0)){
            com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState.activate(0);
                flag=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,flag);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_R32F,1,1,0,GL11.GL_RED,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);fbo=GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,flag,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{value,0,0,0});
        }finally{GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glColorMaski(0,colors.get(0)!=0,colors.get(1)!=0,colors.get(2)!=0,colors.get(3)!=0);enable(GL11.GL_SCISSOR_TEST,scissor);}
    }
    /** The caller's framebuffer must contain current opaque depth in the published projection. */
    public static AutoCloseable depth(boolean qualified){
        return depth(qualified,0);
    }
    public static AutoCloseable depth(boolean qualified,int expectedDepth){
        try(var timing=com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages.begin("rift.visibility.depth")){return testDepth(qualified,expectedDepth);}
    }
    private static AutoCloseable testDepth(boolean qualified,int expectedDepth){
        if(!visible||mode!=RiftCulling.FRUSTUM_DEPTH)return ()->{};
        if(!qualified||rectangle==null){reason="unqualified depth/projection; frustum fallback";return ()->{};}
        double[] range=new double[2];GL11.glGetDoublev(GL11.GL_DEPTH_RANGE,range);
        if(range[0]!=0||range[1]!=1||(GL.getCapabilities().OpenGL45&&GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE)!=GL45.GL_NEGATIVE_ONE_TO_ONE)){reason="nonstandard depth mapping; frustum fallback";return ()->{};}
        if(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==0||GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)==GL11.GL_NONE){reason="opaque framebuffer has no certified depth; frustum fallback";return ()->{};}
        if(expectedDepth!=0&&(GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL11.GL_TEXTURE||GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)!=expectedDepth)){reason="opaque depth resource mismatch; frustum fallback";return ()->{};}
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        if(owner!=null&&(viewport[0]!=0||viewport[1]!=0||viewport[2]!=owner.width()||viewport[3]!=owner.height())){reason="main viewport mismatch; frustum fallback";return ()->{};}
        if(GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD)||GL15.glGetQueryi(GL33.GL_ANY_SAMPLES_PASSED,GL15.GL_CURRENT_QUERY)!=0||GL15.glGetQueryi(GL15.GL_SAMPLES_PASSED,GL15.GL_CURRENT_QUERY)!=0){reason="incompatible raster/query scope; frustum fallback";return ()->{};}
        // Core profiles use one mode for both faces; some drivers return only that first value.
        int[] polygon=new int[2];GL11.glGetIntegerv(GL11.GL_POLYGON_MODE,polygon);
        if(polygon[0]!=GL11.GL_FILL||GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL)){reason="nonstandard polygon/depth bias; frustum fallback";return ()->{};}
        if(program==0){
            program=link("#version 150\nuniform vec4 Rect;uniform float Depth;void main(){vec2 p=vec2(gl_VertexID&1,gl_VertexID>>1);gl_Position=vec4(mix(Rect.xy,Rect.zw,p),Depth*2.-1.,1.);}","#version 150\nout vec4 color;void main(){color=vec4(1);}");
            vao=GL30.glGenVertexArrays();query=GL15.glGenQueries();
        }
        resetFlag(0);
        int oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),function=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean test=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),stencil=GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean sampleMask=GL11.glIsEnabled(GL32.GL_SAMPLE_MASK),sampleCoverage=GL11.glIsEnabled(GL13.GL_SAMPLE_COVERAGE);
        // Route no fragment outputs to the scene. Preserve independent MRT color masks.
        int[] drawBuffers=new int[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
        for(int i=0;i<drawBuffers.length;i++)drawBuffers[i]=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0+i);
        try{
            GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDisable(GL32.GL_SAMPLE_MASK);GL11.glDisable(GL13.GL_SAMPLE_COVERAGE);GL11.glDrawBuffer(GL11.GL_NONE);GL11.glDepthMask(false);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDisable(GL11.GL_CULL_FACE);
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);float dx=4f/Math.max(1,viewport[2]),dy=4f/Math.max(1,viewport[3]);GL20.glUniform4f(GL20.glGetUniformLocation(program,"Rect"),rectangle[0]-dx,rectangle[1]-dy,rectangle[2]+dx,rectangle[3]+dy);GL20.glUniform1f(GL20.glGetUniformLocation(program,"Depth"),rectangle[4]);
            GL15.glBeginQuery(GL33.GL_ANY_SAMPLES_PASSED,query);GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP,0,4);GL15.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED);
        }finally{GL20.glUseProgram(oldProgram);GL30.glBindVertexArray(oldVao);GL11.glDepthMask(mask);GL11.glDepthFunc(function);enable(GL11.GL_DEPTH_TEST,test);enable(GL11.GL_CULL_FACE,cull);enable(GL11.GL_SCISSOR_TEST,scissor);enable(GL11.GL_STENCIL_TEST,stencil);enable(GL32.GL_SAMPLE_MASK,sampleMask);enable(GL13.GL_SAMPLE_COVERAGE,sampleCoverage);GL20.glDrawBuffers(drawBuffers);}
        GL30.glBeginConditionalRender(query,GL30.GL_QUERY_WAIT);conditional=true;reason="same-frame GPU conditional";resetFlag(1);
        return ()->{GL30.glEndConditionalRender();conditional=false;};
    }
    public static boolean conditional(){return conditional;}
    private static void enable(int cap,boolean value){if(value)GL11.glEnable(cap);else GL11.glDisable(cap);}
    private static int link(String v,String f){int p=GL20.glCreateProgram();for(var entry:Map.of(GL20.GL_VERTEX_SHADER,v,GL20.GL_FRAGMENT_SHADER,f).entrySet()){int s=GL20.glCreateShader(entry.getKey());GL20.glShaderSource(s,entry.getValue());GL20.glCompileShader(s);GL20.glAttachShader(p,s);GL20.glDeleteShader(s);}GL20.glLinkProgram(p);if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(p));return p;}
    public static void close(){if(conditional)GL30.glEndConditionalRender();conditional=false;owner=null;if(program!=0)GL20.glDeleteProgram(program);if(vao!=0)GL30.glDeleteVertexArrays(vao);if(query!=0)GL15.glDeleteQueries(query);if(flag!=0)GL11.glDeleteTextures(flag);if(fbo!=0)GL30.glDeleteFramebuffers(fbo);program=vao=query=flag=fbo=0;}
}
