import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling;

/** Production hierarchy/query kernels, including full rectangles, NPOT partitions and native collect. */
public class NativeShadowDepthGpuValidation extends ModelPopulationGpuValidation {
    static int texture(int w,int h,float[] data){int t=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(t,1,GL30.GL_DEPTH_COMPONENT32F,w,h);GL45.glTextureSubImage2D(t,0,0,0,w,h,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,data);GL45.glTextureParameteri(t,GL14.GL_TEXTURE_COMPARE_MODE,GL30.GL_COMPARE_REF_TO_TEXTURE);return t;}
    static void properties()throws Exception{
        String text="#version 450 core\n"+source("chunks/shadow_visibility.glsl")+source("chunks/shadow_depth_test.glsl")+"\nlayout(local_size_x=64)in;layout(std430,binding=0)readonly buffer Boxes{vec4 boxes[];};layout(std430,binding=1)writeonly buffer Result{uint rejected[];};uniform uint count;void main(){uint i=gl_GlobalInvocationID.x;if(i<count)rejected[i]=cmiShadowDepthReject(boxes[2u*i].xyz,boxes[2u*i+1u].xyz)?1u:0u;}";
        int query=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("native interval bounds",text);
        try(var hierarchy=new ShadowDepthPyramid(NativeShadowDepthGpuValidation::source)){
            var random=new Random(0x51ade);
            for(int[] size:new int[][]{{3,5},{63,65},{127,91},{1024,1024}}){int w=size[0],h=size[1];float[] data=new float[w*h];Arrays.fill(data,.2f);int depth=texture(w,h,data);
                try{
                    for(boolean reversed:new boolean[]{false,true}){
                        Arrays.fill(data,reversed?.8f:.2f);int holeX=w/3,holeY=h/3;data[holeY*w+holeX]=reversed?0:1;
                        GL45.glTextureSubImage2D(depth,0,0,0,w,h,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,data);hierarchy.build(depth,w,h,reversed);
                        check(GL45.glGetTextureParameteri(depth,GL14.GL_TEXTURE_COMPARE_MODE)==GL30.GL_COMPARE_REF_TO_TEXTURE,"native comparison state modified");
                        int n=4096;var boxes=bytes(n*32);boolean[] coversHole=new boolean[n];
                        for(int i=0;i<n;i++){
                            float x0=random.nextFloat(),x1=random.nextFloat(),y0=random.nextFloat(),y1=random.nextFloat();float loX=Math.min(x0,x1),hiX=Math.max(x0,x1),loY=Math.min(y0,y1),hiY=Math.max(y0,y1);
                            // Half the cases deliberately straddle an exact source partition boundary.
                            if((i&1)==0){loX=Math.max(0,(holeX-.1f)/w);hiX=Math.min(1,(holeX+1.1f)/w);loY=Math.max(0,(holeY-.1f)/h);hiY=Math.min(1,(holeY+1.1f)/h);}
                            int o=i*32;boxes.putFloat(o,loX+hiX-1).putFloat(o+4,loY+hiY-1).putFloat(o+8,reversed?-.6f:.6f);
                            boxes.putFloat(o+16,hiX-loX).putFloat(o+20,hiY-loY).putFloat(o+24,.001f);
                            coversHole[i]=(int)Math.floor(loX*w)<=holeX&&(int)Math.floor(hiX*w)>=holeX&&(int)Math.floor(loY*h)<=holeY&&(int)Math.floor(hiY*h)>=holeY;
                        }
                        int bounds=buffer(boxes),result=buffer(bytes(n*4));
                        try{bind(0,bounds);bind(1,result);GL45.glBindTextureUnit(4,hierarchy.texture());GL33.glBindSampler(4,hierarchy.sampler());GL41.glProgramUniformMatrix4fv(query,GL20.glGetUniformLocation(query,"uShadowDepthMatrix"),false,new org.joml.Matrix4f().get(new float[16]));GL41.glProgramUniform4f(query,GL20.glGetUniformLocation(query,"uShadowDepthParams"),0,0,1,1e-5f);GL41.glProgramUniform2i(query,GL20.glGetUniformLocation(query,"uShadowDepthSourceSize"),w,h);GL41.glProgramUniform1i(query,GL20.glGetUniformLocation(query,"uShadowDepthReverse"),reversed?1:0);ui(query,"count",n);dispatch(query,(n+63)/64);
                            var values=read(result,n*4);int rejects=0;for(int i=0;i<n;i++){int rejected=values.getInt(i*4);check(!coversHole[i]||rejected==0,"NPOT hole falsely rejected "+w+"x"+h+" index="+i);rejects+=rejected;}if(w>3)check(rejects>0,"query never rejects fully hidden rectangles");
                        }finally{GL15.glDeleteBuffers(bounds);GL15.glDeleteBuffers(result);}
                    }
                }finally{GL11.glDeleteTextures(depth);}
            }
            nonlinearAndInvalid(query);
        }finally{deleteProgram(query);}
    }
    static void nonlinearAndInvalid(int query){
        int w=127,h=91;float[] data=new float[w*h];Arrays.fill(data,.2f);int holeY=(int)((.06/(.9*.06+.1)*.5+.5)*h);data[holeY*w+w/2]=1;int depth=texture(w,h,data);
        var boxes=bytes(64);boxes.putFloat(4,.055f).putFloat(8,.6f).putFloat(16,.9f).putFloat(20,.005f).putFloat(24,.001f);boxes.putFloat(40,.6f).putFloat(48,.01f).putFloat(52,.01f).putFloat(56,.001f);
        int input=buffer(boxes),output=buffer(bytes(8));
        try(var hierarchy=new ShadowDepthPyramid(NativeShadowDepthGpuValidation::source)){
            hierarchy.build(depth,w,h,false);bind(0,input);bind(1,output);GL45.glBindTextureUnit(4,hierarchy.texture());GL33.glBindSampler(4,hierarchy.sampler());ui(query,"count",2);
            float[] identity=new org.joml.Matrix4f().get(new float[16]);GL41.glProgramUniformMatrix4fv(query,GL20.glGetUniformLocation(query,"uShadowDepthMatrix"),false,identity);GL41.glProgramUniform2i(query,GL20.glGetUniformLocation(query,"uShadowDepthSourceSize"),w,h);GL41.glProgramUniform1i(query,GL20.glGetUniformLocation(query,"uShadowDepthReverse"),0);
            for(int mode:new int[]{1,2}){GL41.glProgramUniform4f(query,GL20.glGetUniformLocation(query,"uShadowDepthParams"),mode,.9f,1,1e-5f);dispatch(query,1);var result=read(output,8);check(result.getInt(0)==0,"radial interior extremum/sky falsely rejected");check(result.getInt(4)==1,"fully hidden radial bounds never rejected");}
            GL41.glProgramUniform4f(query,GL20.glGetUniformLocation(query,"uShadowDepthParams"),0,0,1,1e-5f);GL41.glProgramUniform1f(query,GL20.glGetUniformLocation(query,"uShadowDepthFilter"),.25f);dispatch(query,1);check(read(output,8).getInt(4)==0,"filter footprint ignored sky edge");
            GL41.glProgramUniform1f(query,GL20.glGetUniformLocation(query,"uShadowDepthFilter"),0);float[] invalid=identity.clone();invalid[0]=Float.NaN;GL41.glProgramUniformMatrix4fv(query,GL20.glGetUniformLocation(query,"uShadowDepthMatrix"),false,invalid);dispatch(query,1);var kept=read(output,8);check(kept.getInt(0)==0&&kept.getInt(4)==0,"nonfinite projection rejected a caster");
            for(int row=0;row<2;row++)boxes.putFloat(row*32+8,-.05f).putFloat(row*32+24,1);put(input,boxes);GL41.glProgramUniformMatrix4fv(query,GL20.glGetUniformLocation(query,"uShadowDepthMatrix"),false,new org.joml.Matrix4f().perspective((float)Math.PI/2,1,.1f,100).get(new float[16]));dispatch(query,1);kept=read(output,8);check(kept.getInt(0)==0&&kept.getInt(4)==0,"near-plane crossing rejected a caster");
        }finally{GL11.glDeleteTextures(depth);GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);}
    }
    static double timed(Runnable action){int q=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,q);action.run();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double t=GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(q);return t;}
    static ShadowDepthHistory.Stamp stamp(long frame){return new ShadowDepthHistory.Stamp(1,1,"overworld",17,19,frame,0,0,0,0,0,1,new org.joml.Matrix4f().get(new float[16]));}
    static float[] hierarchy(ShadowDepthContext c){float[] data=new float[((c.width()+1)/2)*((c.height()+1)/2)];GL45.glGetTextureImage(c.texture(),0,GL11.GL_RED,GL11.GL_FLOAT,data);return data;}
    static void history()throws Exception{
        int w=17,h=19;float[] data=new float[w*h];Arrays.fill(data,.2f);int depth=texture(w,h,data),mask=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(mask,1,GL30.GL_R32F,w,h);Arrays.fill(data,1);GL45.glTextureSubImage2D(mask,0,0,0,w,h,GL11.GL_RED,GL11.GL_FLOAT,data);
        float[] identity=new org.joml.Matrix4f().get(new float[16]);
        try(var history=new ShadowHistoryGpu(NativeShadowDepthGpuValidation::source)){
            check(history.capture(stamp(0),depth,mask,identity,identity,1e-4f),"history bootstrap capture");
            var first=history.context(stamp(1),1e-5f);check(first!=null,"history bank not published");for(float d:hierarchy(first))check(d==1,"bootstrap became rejection evidence");
            check(history.capture(stamp(1),depth,mask,identity,identity,1e-4f),"history matched capture");
            var second=history.context(stamp(2),1e-5f);boolean interior=false;for(float d:hierarchy(second)){check(d==1||Math.abs(d-.2f)<1e-5,"history depth encoding");interior|=d<1;}check(interior,"GPU history validity never accepted static receiver");
            data[(h/2)*w+w/2]=0;GL45.glTextureSubImage2D(mask,0,0,0,w,h,GL11.GL_RED,GL11.GL_FLOAT,data);check(history.capture(stamp(2),depth,mask,identity,identity,1e-4f),"history masked capture");
            var masked=hierarchy(history.context(stamp(3),1e-5f));check(masked[(h/4)*((w+1)/2)+w/4]==1,"unreliable dynamic receiver became an occluder");
            check(history.context(stamp(4),1e-5f)==null,"history discontinuity accepted");
            check(!history.capture(stamp(3),depth,0,identity,identity,1e-4f),"missing reliability attachment accepted");check(history.context(stamp(4),1e-5f)==null,"failed history capture published a bank");
        }finally{GL11.glDeleteTextures(depth);GL11.glDeleteTextures(mask);}
    }
    static void collect(Path out)throws Exception{
        var rows=new ArrayList<String>(List.of("count,size,comparison,pair,baseline_ms,candidate_ms"));
        try{
            for(int size:new int[]{1024,2048}){float[] data=new float[size*size];Arrays.fill(data,.2f);int depth=texture(size,size,data);
                try(var pyramid=new ShadowDepthPyramid(NativeShadowDepthGpuValidation::source)){
                    pyramid.build(depth,size,size,false);float[] matrix=new org.joml.Matrix4f().scaling(.001f).get(new float[16]);
                    for(int n:new int[]{65536,131072})try(var q=new Fixture(n,n,n);var shadow=new ModelShadowGpu(n,ParticleBuffers.CARRIER_CAP,NativeShadowDepthGpuValidation::source,true)){
                        var particles=bytes(n*64);for(int i=0;i<n;i++){particle(particles,i,2,(i%256-128)*.2f,600,20);particles.putFloat(i*64+4,(i/256-256)*.2f);}q.build(particles,n);
                        var culling=new PackageDrawCulling();culling.clear();var context=new ShadowDepthContext(pyramid.texture(),pyramid.sampler(),size,size,matrix,0,0,1,1e-5f,0,false,0,0,0);
                        Runnable original=()->{shadow.depth(null,null);shadow.prepare(q.pool,q.emit,q.counter,culling,0,0,0,.35f,.6f,100,0,new float[16],new float[16],new float[4],q.pop,7);};
                        Runnable candidate=()->{pyramid.build(depth,size,size,false);shadow.depth(context,null);shadow.prepare(q.pool,q.emit,q.counter,culling,0,0,0,.35f,.6f,100,0,new float[16],new float[16],new float[4],q.pop,7);};
                        original.run();check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==n,"native collect baseline lost models");candidate.run();check(read(shadow.commands(),(ParticleBuffers.INDIRECT_UINTS+4)*4).getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==0,"native collect did not consume hierarchy");
                        for(int warm=0;warm<16;warm++){original.run();candidate.run();}
                        for(String comparison:List.of("AA","AB"))for(int pair=0;pair<64;pair++){Runnable b=comparison.equals("AA")?original:candidate;double aTime,bTime;if((pair&1)==0){aTime=timed(original);bTime=timed(b);}else{bTime=timed(b);aTime=timed(original);}rows.add(n+","+size+","+comparison+","+pair+","+aTime+","+bTime);}
                    }
                }finally{GL11.glDeleteTextures(depth);}
            }
        }finally{Files.write(out.resolve("native-depth-collect.csv"),rows);}
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Native shadow depth",0,0);
        Path out=Path.of("build/shadow-completion/depth-"+Integer.getInteger("cmi.shadow.round",0));Files.createDirectories(out);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();properties();history();collect(out);check(GL11.glGetError()==0,"final GL error");Files.writeString(out.resolve("checks.txt"),"checks="+checks+"\nProduction hierarchy, history reliability and collect only; pack raster/side effects and full-chain qualification remain required.\n");System.out.println("Native shadow depth GPU: "+checks+" checks");}
        finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
