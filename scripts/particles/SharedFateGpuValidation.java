import com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu;
import java.nio.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Real production compute kernels in a hidden GL context; never starts Minecraft. */
public class SharedFateGpuValidation {
    static int checks;
    static int preparation,reservation;
    static java.nio.file.Path sourceRoot;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static ByteBuffer bytes(int size){return BufferUtils.createByteBuffer(size);}
    static String source(String name){return ParticleShaderSource.loadParticle(name,path->{
        if(sourceRoot!=null)try{return java.nio.file.Files.readString(sourceRoot.resolve(path.substring("shaders/particles/".length())));}catch(Exception e){throw new IllegalStateException(path,e);}
        try(var in=SharedFateGpuValidation.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)){
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException(path,e);}
    });}
    static int program(String name,int type,String prefix){
        int shader=GL20.glCreateShader(type);GL20.glShaderSource(shader,"#version 450 core\n"+prefix+source(name));
        GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,name+": "+GL20.glGetShaderInfoLog(shader));
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
        check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
        GL20.glDeleteShader(shader);return program;
    }
    static int buffer(int size){int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes(size),GL15.GL_DYNAMIC_COPY);return id;}
    static void bind(int slot,int id){GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,slot,id);}
    static void put(int id,ByteBuffer value){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,value);}
    static ByteBuffer read(int id,int size){GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);var result=bytes(size);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);return result;}
    static void ui(int p,String key,int value){GL41.glProgramUniform1ui(p,GL20.glGetUniformLocation(p,key),value);if(p==preparation)GL41.glProgramUniform1ui(reservation,GL20.glGetUniformLocation(reservation,key),value);}
    static void f(int p,String key,float value){GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,key),value);}
    static void matrix(int p,String key,Matrix4f value){GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,key),false,value.get(new float[16]));}
    static void dispatch(int p,int n){GL20.glUseProgram(p);GL43.glDispatchCompute(n,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);if(p==preparation){GL20.glUseProgram(reservation);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}}
    static ByteBuffer input(float x,float y,float z,float time,float stop,boolean animate,int slot){
        return bytes(80).putFloat(0,0).putFloat(4,0).putFloat(8,-10).putFloat(12,2)
            .putFloat(16,x).putFloat(20,y).putFloat(24,z).putFloat(28,2)
            .putFloat(32,time).putFloat(36,stop).putFloat(40,stop<0?0:1).putFloat(44,37)
            .putInt(48,slot).putInt(52,1).putInt(56,animate?1:0);
    }


    static void math(){
        // Exercise production catenary endpoints directly, without jitter or rendering.
        String text="#version 450 core\n"+source("chunks/shared_fate.glsl")+"\nlayout(local_size_x=1)in;layout(std430,binding=0)buffer Result{vec4 points[];};uniform vec3 uA,uB;void main(){vec3 c=cmiFateCoefficients(uA,uB);for(int i=0;i<=100;i++)points[i]=vec4(cmiFateCurve(uA,uB,c,float(i)/100.),1);}";
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,text);GL20.glCompileShader(shader);check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(shader);
        int out=buffer(101*16);bind(0,out);
        try{for(float d:new float[]{0,.0001f,.001f,.1f,1,4,50,1000})for(float dy:new float[]{0,10,300,-300}){
            GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uA"),0,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uB"),d,dy,0);dispatch(p,1);
            var points=read(out,101*16);check(points.getFloat(0)==0&&points.getFloat(100*16)==d&&points.getFloat(100*16+4)==dy,"exact catenary endpoints");
            for(int i=0;i<=100;i++){float y=points.getFloat(i*16+4);check(Float.isFinite(y),"finite catenary");check(y<=dy*i/100f+.001f,"catenary never rises over its chord");}
        }}finally{GL15.glDeleteBuffers(out);GL20.glDeleteProgram(p);}
    }


    static void samplingMath(){
        String text="#version 450 core\n"+source("chunks/shared_fate.glsl")+source("chunks/shared_fate_sampling.glsl")+"""
            layout(local_size_x=1)in;layout(std430,binding=0)buffer Result{vec4 values[];};uniform vec3 uB;
            void main(){
                vec3 a=vec3(0),coefficient=cmiFateCoefficients(a,uB),last=a;float lengths[33];lengths[0]=0.;
                for(int i=1;i<=32;i++){vec3 p=cmiFateCurve(a,uB,coefficient,float(i)/32.);lengths[i]=lengths[i-1]+length(p-last);last=p;}
                for(int i=0;i<=100;i++){
                    float s=lengths[32]*float(i)/100.,t=cmiFateArcToT(s,lengths),epsilon=.0005;
                    values[i]=vec4(cmiFateCurve(a,uB,coefficient,t),s);
                    float low=max(0.,t-epsilon),high=min(1.,t+epsilon);
                    vec3 derivative=cmiFateDerivative(a,uB,coefficient,t),finite=(cmiFateCurve(a,uB,coefficient,high)-cmiFateCurve(a,uB,coefficient,low))/(high-low);
                    values[101+i]=vec4(derivative,length(derivative-finite)/(1.+length(derivative)));
                }
                uint first=cmiFateCount(3.2,0.,16.),second=cmiFateCount(4.2,0.,16.);
                for(uint i=0u;i<first;i++){CmiFateSample p=cmiFateSample(i,3.2,0.,16.);values[202u+i]=vec4(p.distance,p.weight,p.spacing,uintBitsToFloat(p.identity));}
                for(uint i=0u;i<second;i++){CmiFateSample p=cmiFateSample(i,4.2,0.,16.);values[520u+i]=vec4(p.distance,p.weight,p.spacing,uintBitsToFloat(p.identity));}
                values[1100]=vec4(float(first),float(second),lengths[32],0.);
                values[1101]=vec4(cmiFatePulse(2.,1.),cmiFatePulse(3.,1.5),cmiFatePulse(1.8,1.),cmiFatePulse(2.2,1.));
                float enter,leave;bool hit=cmiFateClip(vec4(0.,0.,-2.,1.),vec4(.5,.5,0.,1.),enter,leave);
                float originalEnter=enter,originalLeave=leave;bool hidden=cmiFateClip(vec4(0.,0.,-3.,1.),vec4(0.,0.,-2.,1.),enter,leave);
                values[1102]=vec4(originalEnter,originalLeave,hit?1.:0.,hidden?1.:0.);
                values[1103]=vec4(cmiFateSmoothLevel(4.,4.05,.016),cmiFateSmoothLevel(4.,3.95,.016),cmiFateSmoothLevel(4.,4.2,.016),cmiFateSmoothLevel(4.,3.8,.016));
            }
            """;
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,text);GL20.glCompileShader(shader);
        check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);GL20.glDeleteShader(shader);
        check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));int out=buffer(1200*16);bind(0,out);
        try{for(float[] b:new float[][]{{4,0,0},{4,30,0},{50,-20,0},{1000,300,0},{0,8,0},{0,0,0}}){
            GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uB"),b[0],b[1],b[2]);dispatch(p,1);var values=read(out,1200*16);
            float length=values.getFloat(1100*16+8);double ideal=length/100.;
            for(int i=0;i<=100;i++){
                check(values.getFloat((101+i)*16+12)<.015,"analytic catenary derivative matches numerical differentiation");
                if(i>0&&ideal>.0001){double distance=0;for(int k=0;k<3;k++)distance+=Math.pow(values.getFloat(i*16+k*4)-values.getFloat((i-1)*16+k*4),2);
                    check(Math.abs(Math.sqrt(distance)-ideal)<ideal*.025,"arc inversion produces evenly spaced samples");}
            }
            int first=(int)values.getFloat(1100*16),second=(int)values.getFloat(1100*16+4);var identities=new java.util.HashMap<Integer,Float>();double optical1=0,optical2=0;
            for(int i=0;i<first;i++){int offset=(202+i)*16;identities.put(values.getInt(offset+12),values.getFloat(offset));optical1+=values.getFloat(offset+4)*values.getFloat(offset+8);}
            int shared=0;for(int i=0;i<second;i++){int offset=(520+i)*16;Float original=identities.get(values.getInt(offset+12));if(original!=null){check(original==values.getFloat(offset),"refinement preserves exact physical sample identity");shared++;}optical2+=values.getFloat(offset+4)*values.getFloat(offset+8);}
            check(shared==first,"refined lattice contains all previous physical samples");
            check(Math.abs(optical1-optical2)<.15,"weighted density changes preserve optical depth per unit length");
            check(values.getFloat(1101*16)==1&&values.getFloat(1101*16+4)==1,"directed flow advances exactly two blocks per second");
            check(values.getFloat(1101*16+8)>values.getFloat(1101*16+12),"flow has a narrow forward edge and trailing glow");
            check(values.getFloat(1103*16)==4&&values.getFloat(1103*16+4)==4&&values.getFloat(1103*16+8)>4&&values.getFloat(1103*16+12)<4,"density threshold noise is held by hysteresis while real LOD changes proceed");
            check(values.getFloat(1102*16)==.5f&&values.getFloat(1102*16+4)==1&&values.getFloat(1102*16+8)==1&&values.getFloat(1102*16+12)==0,"homogeneous near-plane clipping retains crossing segments and rejects hidden ones");
        }}finally{GL15.glDeleteBuffers(out);GL20.glDeleteProgram(p);}
    }
    static void frames(){
        String text="#version 450 core\n"+source("chunks/shared_fate.glsl")+"""
            layout(local_size_x=1)in;layout(std430,binding=0)buffer Result{vec4 frames[];};
            void main(){
                vec3 from=normalize(vec3(-.1,1,0)),basis=cmiFateBasis(from);
                for(int i=0;i<=200;i++){
                    vec3 to=normalize(vec3(float(i)*.001-.1,1,0));
                    basis=cmiFateTransport(basis,from,to);frames[i]=vec4(basis,dot(basis,to));from=to;
                }
                frames[201]=vec4(cmiFateTransport(basis,from,-from),0);
                frames[202]=vec4(cmiFateRibbonRadius(.1),cmiFateRibbonRadius(1),cmiFateRibbonRadius(32),0);
                frames[203]=vec4(cmiFateGlow(.3,0,true),cmiFateGlow(.6,0,true),cmiFateGlow(0,0,false),0);
            }
            """;
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,text);GL20.glCompileShader(shader);
        check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);GL20.glDeleteShader(shader);
        check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));
        int out=buffer(204*16);bind(0,out);
        try{
            dispatch(p,1);var result=read(out,204*16);
            for(int i=0;i<=201;i++){
                double norm=0,continuity=0;
                for(int k=0;k<3;k++){
                    float component=result.getFloat(i*16+k*4);check(Float.isFinite(component),"vertical frame remains finite");norm+=component*component;
                    if(i>0)continuity+=component*result.getFloat((i-1)*16+k*4);
                }
                check(Math.abs(norm-1)<1e-5&&Math.abs(result.getFloat(i*16+12))<1e-5,"transported frame is orthonormal");
                if(i>0)check(continuity>.999,"moving chord across vertical does not flip the ribbon");
            }
            check(result.getFloat(202*16)<.01&&result.getFloat(202*16+4)==.05f&&result.getFloat(202*16+8)==.05f,"short ribbons narrow and normal widths stay at .1 block overall diameter");
            check(Math.abs(result.getFloat(203*16)-.27)<1e-5&&result.getFloat(203*16+4)==.45f&&result.getFloat(203*16+8)==.45f,"condensation reaches sustained glow at .6 seconds");
        }finally{GL15.glDeleteBuffers(out);GL20.glDeleteProgram(p);}
    }
    static void lateHeaders() throws Exception {
        var pool=new ParticleBuffers();
        try {
            check(pool.init(1024,2),"native particle pool for late emitter upload");
            pool.uploadDirtyEmitters(); // The normal engine upload has already finished.
            var field=SharedFateGpu.class.getDeclaredField("SPEC");field.setAccessible(true);
            float[] header=((com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec)field.get(null)).packed();
            pool.setEmitterHeader(1,header);pool.uploadDirtyEmitters();
            var uploaded=read(pool.emitterBufferId(),2*header.length*4);
            for(int i=0;i<header.length;i++)check(uploaded.getInt((header.length+i)*4)==Float.floatToRawIntBits(header[i]),
                    "late Shared Fate emitter header is available in the first frame");
        } finally {pool.free();}
    }
    static int graphics(String prefix) {
        return graphics(prefix,false);
    }
    static int graphics(String prefix,boolean alpha){
        int program=GL20.glCreateProgram();
        for(String name:alpha?new String[]{"textured.vsh","textured.fsh"}:new String[]{"additive.vsh","additive.fsh"}) {
            int shader=GL20.glCreateShader(name.endsWith("vsh")?GL20.GL_VERTEX_SHADER:GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(shader,"#version 450 core\n"+prefix+source(name));GL20.glCompileShader(shader);
            check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(program,shader);GL20.glDeleteShader(shader);
        }
        GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));
        return program;
    }
    static int boundBuffer(int binding){return GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,binding);}
    static void drawHandoff() throws Exception {
        String prefix=ParticlePrograms.commonPrelude();
        int prepare=program("shared_fate_prepare.comp",GL43.GL_COMPUTE_SHADER,"");
        int reserve=program("shared_fate_reserve.comp",GL43.GL_COMPUTE_SHADER,"");
        int emit=program("shared_fate_emit.comp",GL43.GL_COMPUTE_SHADER,"");
        int publish=program("prepare_dispatch.comp",GL43.GL_COMPUTE_SHADER,prefix);
        int keygen=program("keygen.comp",GL43.GL_COMPUTE_SHADER,prefix),draw=graphics(prefix),alpha=graphics(prefix,true);
        int hist=program("radix_hist.comp",GL43.GL_COMPUTE_SHADER,prefix),scan=program("radix_scan.comp",GL43.GL_COMPUTE_SHADER,prefix),scatter=program("radix_scatter.comp",GL43.GL_COMPUTE_SHADER,prefix);
        int input=buffer(320),jobs=buffer(1536),nodes=buffer((32768+16)*64),debrisRead=buffer(2048*64),debrisWrite=buffer(2048*64),previous=buffer(1536),next=buffer(1536),dummy=buffer(64);
        int framebuffer=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),depthBuffer=GL30.glGenRenderbuffers();var pool=new ParticleBuffers();
        final int size=512;
        try {
            check(pool.init(32768,3),"mixed alpha/additive native pool");
            for(int id=0;id<3;id++){
                var field=SharedFateGpu.class.getDeclaredField(id==0?"SPEC":id==1?"ALPHA_SPEC":"RIBBON_SPEC");field.setAccessible(true);
                pool.setEmitterHeader(id,((com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec)field.get(null)).packed());
            }
            pool.uploadDirtyEmitters();
            Matrix4f view=new Matrix4f(),projection=new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,1024);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,size,size,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depthBuffer);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL30.GL_DEPTH_COMPONENT24,size,size);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,depthBuffer);
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"mixed-material framebuffer");
            for(int p:new int[]{prepare,emit}){matrix(p,"uView",view);matrix(p,"uProjection",projection);f(p,"uViewportHeight",size);f(p,"uTime",1);}
            GL41.glProgramUniform4fv(prepare,GL20.glGetUniformLocation(prepare,"uFrustum"),new float[24]);
            for(float animationTime:new float[]{.2f,1f,3.7f})for(float distance:new float[]{3,10,48})for(int copies:new int[]{1,4}){
                f(prepare,"uTime",animationTime);f(emit,"uTime",animationTime);
                pool.beginComputeFrame();pool.clearParticles();pool.beginBindings();
                pool.bindCounter(ParticleBuffers.COUNTER_BB,0);int counter=boundBuffer(ParticleBuffers.COUNTER_BB);put(counter,bytes(16));
                pool.bindParticleWrite(ParticleBuffers.PARTICLE_BB_READ);int output=boundBuffer(ParticleBuffers.PARTICLE_BB_READ);
                pool.bindDispatch();int dispatchBuffer=boundBuffer(ParticleBuffers.DISPATCH_BB);
                pool.bindIndirect(ParticleBuffers.INDIRECT_BB);int indirect=boundBuffer(ParticleBuffers.INDIRECT_BB);
                pool.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING,1);
                var data=bytes(copies*80);
                for(int i=0;i<copies;i++)data.put(input(2,0,-distance,0,-1,false,i).putFloat(0,-2).putFloat(8,-distance).putFloat(12,0).putFloat(28,0).putFloat(44,37+i*17));
                data.flip();put(input,data);put(previous,bytes(1536));
                bind(0,input);bind(1,jobs);bind(2,previous);bind(3,next);bind(4,counter);bind(5,output);bind(6,nodes);bind(7,debrisRead);bind(8,debrisWrite);
                ui(prepare,"uCount",copies);f(prepare,"uFadeDistance",256);dispatch(prepare,1);
                ui(reserve,"uCount",copies);ui(reserve,"uCapacity",pool.capacity());ui(reserve,"uBudget",32768);dispatch(reserve,1);
                ui(emit,"uEmitter",0);ui(emit,"uRibbonAlphaEmitter",1);ui(emit,"uRibbonEmitter",2);ui(emit,"uDebrisSlots",1);dispatch(emit,copies);
                int live=read(counter,16).getInt(0);check(live>0,"kernels generated mixed-material samples");
                var particleData=read(output,live*64);var firstJob=read(jobs,384);int core=(int)firstJob.getFloat(304);
                ui(publish,"uPhase",1);ui(publish,"uCapacity",pool.capacity());ui(publish,"uPackageStaged",0);
                if(distance==3&&copies==1&&animationTime==.2f){
                    pool.beginBindings();pool.bindDispatch();dispatch(publish,1);
                    check(read(dispatchBuffer,36).getInt(12)!=(live+63)/64,"missing counter restoration publishes wire-state bits instead of native work count");
                }
                pool.beginBindings();pool.bindDispatchInputs(0,1);dispatch(publish,1);GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
                check(read(dispatchBuffer,36).getInt(12)==(live+63)/64,"restored publication covers every generated sample");
                pool.bindParticleWrite(ParticleBuffers.PARTICLE_BB_READ);pool.bindEmitters(ParticleBuffers.EMITTER_BB);
                pool.bindOrderAdd();pool.bindOrderOpaque();pool.bindSort(ParticleBuffers.SORTWRITE_BINDING,pool.sortBuffer(0));pool.bindCarrierSink();bind(28,dummy);
                ui(keygen,"uUpper",pool.capacity());ui(keygen,"uPackageStaged",0);matrix(keygen,"uView",view);f(keygen,"uMaxDepth",1024);f(keygen,"uDepthLogRange",10);
                GL41.glProgramUniform3f(keygen,GL20.glGetUniformLocation(keygen,"uCamPos"),300,80,-900);
                GL41.glProgramUniform4fv(keygen,GL20.glGetUniformLocation(keygen,"uFrustum"),new float[24]);
                GL20.glUseProgram(keygen);pool.dispatch(1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
                var commands=read(indirect,ParticleBuffers.INDIRECT_UINTS*4);int nAlpha=commands.getInt(ParticleBuffers.IDX_CNT_ALPHA*4),nAdd=commands.getInt(ParticleBuffers.IDX_CNT_ADD*4);
                check(nAlpha>0&&nAdd>0&&nAlpha+nAdd==live,"alpha and additive draw counts exactly partition emitted samples");
                var keys=read(pool.sortBuffer(0),nAlpha*8);
                int expected=255-Math.min(255,(int)(Math.log(distance)/Math.log(2)/10*256));
                check(Math.abs((keys.getInt(0)&255)-expected)<=1,"camera-relative alpha depth key ignores absolute camera position");
                pool.bindIndirect(ParticleBuffers.INDIRECT_BB);pool.bindSort(ParticleBuffers.SORTREAD_BINDING,pool.sortBuffer(0));pool.bindSort(ParticleBuffers.SORTWRITE_BINDING,pool.sortBuffer(1));pool.clearHist();pool.bindHist();pool.bindOffsets();
                int groups=(nAlpha+(nAlpha>=ParticleBuffers.SORT_GROUP_THRESHOLD?1023:63))/(nAlpha>=ParticleBuffers.SORT_GROUP_THRESHOLD?1024:64);
                dispatch(hist,groups);dispatch(scan,1);dispatch(scatter,groups);GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
                int permutation=pool.sortBuffer(1);pool.swap();
                for(int p:new int[]{draw,alpha}){
                    matrix(p,"ModelViewMat",view);matrix(p,"ProjMat",projection);f(p,"uFadeDist",256);f(p,"uGlow",1);
                    GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamRight"),1,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamUp"),0,1,0);
                    GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamPos"),300,80,-900);
                }
                GL11.glViewport(0,0,size,size);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDisable(GL11.GL_CULL_FACE);
                for(float[] background:new float[][]{{0,0,0},{.85f,.8f,.65f},{.5f,.72f,.95f},{1,1,1}}){
                    GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClearColor(background[0],background[1],background[2],1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);GL11.glDepthMask(false);
                    GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
                    pool.beginBindings();pool.bindBillboardDrawInputs(2,permutation);bind(13,nodes);GL20.glUseProgram(alpha);GL41.glProgramUniform1i(alpha,GL20.glGetUniformLocation(alpha,"uMode"),0);pool.drawIndirect(5);
                    GL11.glBlendFunc(GL11.GL_ONE,GL11.GL_ONE);pool.beginBindings();pool.bindBillboardDrawInputs(0,permutation);bind(13,nodes);GL20.glUseProgram(draw);pool.drawIndirect(0);
                    var pixels=bytes(size*size*4);GL11.glReadPixels(0,0,size,size,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                    int changed=0,blue=0,white=0;
                    for(int i=0;i<size*size;i++){
                        int r=pixels.get(i*4)&255,g=pixels.get(i*4+1)&255,b=pixels.get(i*4+2)&255;
                        int deviation=Math.abs(r-Math.round(background[0]*255))+Math.abs(g-Math.round(background[1]*255))+Math.abs(b-Math.round(background[2]*255));
                        if(deviation>20){changed++;if((b>r&&g>r)||((b-r)-(background[2]-background[0])*255>5&&(g-r)-(background[1]-background[0])*255>2))blue++;if(r>240&&g>240&&b>240&&Math.min(background[0],Math.min(background[1],background[2]))<.94f)white++;}
                    }
                    check(changed>10&&blue>changed*.65,"mixed materials remain visibly blue: distance="+distance+" copies="+copies+" background="+java.util.Arrays.toString(background)+" changed="+changed+" blue="+blue+" white="+white);
                    check(white<Math.max(1,changed*.01),"white saturation: distance="+distance+" copies="+copies+" background="+java.util.Arrays.toString(background)+" changed="+changed+" white="+white);
                    for(int sample=2;sample<48;sample++){
                        int index=sample*core/50;float x=particleData.getFloat(index*64),y=particleData.getFloat(index*64+4),z=particleData.getFloat(index*64+8);
                        int px=(int)((projection.m00()*x/-z*.5+.5)*size),py=(int)((projection.m11()*y/-z*.5+.5)*size);boolean covered=false;float maxChange=0,maxBlue=0;
                        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                            int pixel=((py+dy)*size+px+dx)*4;
                            if(pixel<0||pixel+2>=pixels.capacity())continue;
                            int r=pixels.get(pixel)&255,g=pixels.get(pixel+1)&255,b=pixels.get(pixel+2)&255;
                            maxChange=Math.max(maxChange,Math.abs(r-background[0]*255)+Math.abs(g-background[1]*255)+Math.abs(b-background[2]*255));maxBlue=Math.max(maxBlue,b-r);
                            // Global checks enforce blue contrast; local checks detect even faint translucent coverage on a similar blue sky.
                            int contrast=3;
                            covered|=((b-r>contrast&&g-r>contrast*.4)||((b-r)-(background[2]-background[0])*255>contrast&&(g-r)-(background[1]-background[0])*255>contrast*.4))&&(Math.abs(r-background[0]*255)+Math.abs(g-background[1]*255)+Math.abs(b-background[2]*255)>contrast);
                        }
                        if(!covered)System.out.println("Uncovered sample="+sample+" index="+index+" xyz="+x+","+y+","+z+" screen="+px+","+py+" change="+maxChange+" blue="+maxBlue);
                        check(covered,"four spiral strips have continuous blue pixel coverage, distance="+distance+" copies="+copies+" time="+animationTime+" background="+java.util.Arrays.toString(background));
                    }
                }
                // A foreground depth surface must occlude both materials completely.
                GL11.glDepthMask(true);GL11.glClearDepth(0);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);GL11.glDepthMask(false);
                for(int mode:new int[]{2,0}){GL11.glBlendFunc(mode==2?GL11.GL_SRC_ALPHA:GL11.GL_ONE,mode==2?GL11.GL_ONE_MINUS_SRC_ALPHA:GL11.GL_ONE);pool.beginBindings();pool.bindBillboardDrawInputs(mode,permutation);bind(13,nodes);GL20.glUseProgram(mode==2?alpha:draw);pool.drawIndirect(mode==2?5:0);}
                var occluded=bytes(size*size*4);GL11.glReadPixels(0,0,size,size,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,occluded);
                for(int i=0;i<occluded.capacity();i+=4)check(occluded.getInt(i)==0,"scene depth hides both alpha and glow");
                System.out.println("Blue filament pixels: time="+animationTime+", distance="+distance+", bindings="+copies+", alpha="+nAlpha+", glow/stars="+nAdd+"; four backgrounds, continuity and occlusion passed");
            }
        }finally{
            GL11.glDepthMask(true);pool.free();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(framebuffer);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(color);GL30.glDeleteRenderbuffers(depthBuffer);
            for(int p:new int[]{prepare,reserve,emit,publish,keygen,draw,alpha,hist,scan,scatter})GL20.glDeleteProgram(p);
            for(int id:new int[]{input,jobs,previous,next,dummy,nodes,debrisRead,debrisWrite})GL15.glDeleteBuffers(id);
        }
    }
    static void partitionDrawHandoff() throws Exception {
        var prelude=ParticlePrograms.class.getDeclaredField("PRELUDE");prelude.setAccessible(true);String prefix=(String)prelude.get(null);
        int prepare=program("partition_prepare.comp",GL43.GL_COMPUTE_SHADER,"");
        int reserve=program("partition_reserve.comp",GL43.GL_COMPUTE_SHADER,"");
        int emit=program("partition_emit.comp",GL43.GL_COMPUTE_SHADER,"");
        int publish=program("prepare_dispatch.comp",GL43.GL_COMPUTE_SHADER,prefix);
        int keygen=program("keygen.comp",GL43.GL_COMPUTE_SHADER,prefix),draw=graphics(prefix);
        int input=buffer(64),jobs=buffer(18*64),previous=buffer(80),next=buffer(80),dummy=buffer(64);
        int framebuffer=GL30.glGenFramebuffers(),color=GL11.glGenTextures();var pool=new ParticleBuffers();
        try {
            check(pool.init(4096,2),"draw handoff native pool");pool.beginComputeFrame();pool.clearParticles();
            pool.bindCounter(ParticleBuffers.COUNTER_BB,0);int counter=boundBuffer(ParticleBuffers.COUNTER_BB);put(counter,bytes(16));
            pool.bindParticleWrite(ParticleBuffers.PARTICLE_BB_READ);int output=boundBuffer(ParticleBuffers.PARTICLE_BB_READ);
            pool.bindDispatch();int dispatchBuffer=boundBuffer(ParticleBuffers.DISPATCH_BB);
            pool.bindIndirect(ParticleBuffers.INDIRECT_BB);int indirect=boundBuffer(ParticleBuffers.INDIRECT_BB);
            pool.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING,1);
            var field=SharedFateGpu.class.getDeclaredField("SPEC");field.setAccessible(true);
            pool.setEmitterHeader(0,((com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec)field.get(null)).packed());
            pool.uploadDirtyEmitters();
            Matrix4f view=new Matrix4f(),projection=new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,256);
            for(int p:new int[]{prepare,emit}) {
                matrix(p,"uView",view);matrix(p,"uProjection",projection);f(p,"uViewportHeight",64);f(p,"uTime",1);
            }
            put(input,bytes(64).putFloat(0,-1).putFloat(4,-1).putFloat(8,-11).putFloat(16,1).putFloat(20,1).putFloat(24,-9).putFloat(32,1).putFloat(36,-1));
            bind(0,input);bind(1,jobs);bind(2,previous);bind(3,next);bind(4,counter);bind(5,output);
            ui(prepare,"uCount",1);f(prepare,"uFadeDistance",256);
            GL41.glProgramUniform4fv(prepare,GL20.glGetUniformLocation(prepare,"uFrustum"),new float[24]);
            dispatch(prepare,1);ui(reserve,"uCount",1);ui(reserve,"uCapacity",pool.capacity());ui(reserve,"uBudget",4096);
            dispatch(reserve,1);ui(emit,"uEmitter",0);f(emit,"uFadeDistance",256);dispatch(emit,18);
            int live=read(counter,16).getInt(0);check(live>0,"wire kernels generated particles before publication");
            ui(publish,"uPhase",1);ui(publish,"uCapacity",pool.capacity());ui(publish,"uPackageStaged",0);
            pool.beginBindings();pool.bindDispatchInputs(0,1);dispatch(publish,1);
            GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
            check(read(dispatchBuffer,36).getInt(12)==(live+63)/64,"restored handoff publishes every generated wire sample");
            check(boundBuffer(ParticleBuffers.COUNTER_BB)==counter&&boundBuffer(ParticleBuffers.INDIRECT_BB)==indirect,
                    "publication restored the native counter and indirect inputs");
            pool.bindParticleWrite(ParticleBuffers.PARTICLE_BB_READ);pool.bindEmitters(ParticleBuffers.EMITTER_BB);
            pool.bindOrderAdd();pool.bindOrderOpaque();pool.bindSort(ParticleBuffers.SORTWRITE_BINDING,pool.sortBuffer(0));pool.bindCarrierSink();bind(28,dummy);
            ui(keygen,"uUpper",pool.capacity());ui(keygen,"uPackageStaged",0);matrix(keygen,"uView",view);
            GL41.glProgramUniform4fv(keygen,GL20.glGetUniformLocation(keygen,"uFrustum"),new float[24]);
            GL20.glUseProgram(keygen);pool.dispatch(1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
            check(read(indirect,ParticleBuffers.INDIRECT_UINTS*4).getInt(ParticleBuffers.IDX_CNT_ADD*4)==live,
                    "all wire samples reach the additive draw permutation");
            pool.swap();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"wire visibility framebuffer");
            GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_ONE,GL11.GL_ONE);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            matrix(draw,"ModelViewMat",view);matrix(draw,"ProjMat",projection);f(draw,"uFadeDist",256);f(draw,"uGlow",1);
            GL41.glProgramUniform3f(draw,GL20.glGetUniformLocation(draw,"uCamRight"),1,0,0);
            GL41.glProgramUniform3f(draw,GL20.glGetUniformLocation(draw,"uCamUp"),0,1,0);
            pool.bindBillboardDrawInputs(0,pool.sortBuffer(0));GL20.glUseProgram(draw);
            GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES,0L);
            var pixels=bytes(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            int lit=0;for(int i=0;i<64*64;i++)if((pixels.get(i*4)&255)+(pixels.get(i*4+1)&255)+(pixels.get(i*4+2)&255)>0)lit++;
            check(lit>10,"published wire draws visible pixels, not just valid compute records");
            System.out.println("Partition handoff: "+live+" samples, "+lit+" visible pixels");
        } finally {
            pool.free();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(framebuffer);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(color);
            for(int p:new int[]{prepare,reserve,emit,publish,keygen,draw})GL20.glDeleteProgram(p);
            for(int b:new int[]{input,jobs,previous,next,dummy})GL15.glDeleteBuffers(b);
        }
    }
    static void partitionKernels(){
        preparation=reservation=0;
        int prepare=program("partition_prepare.comp",GL43.GL_COMPUTE_SHADER,"");
        int reserve=program("partition_reserve.comp",GL43.GL_COMPUTE_SHADER,"");
        int emit=program("partition_emit.comp",GL43.GL_COMPUTE_SHADER,"");
        int input=buffer(128),jobs=buffer(36*64),counter=buffer(16),pool=buffer(8193*64);
        try{
            bind(0,input);bind(1,jobs);bind(4,counter);bind(5,pool);
            ui(prepare,"uCount",1);ui(reserve,"uCount",1);ui(reserve,"uCapacity",8192);ui(reserve,"uBudget",8192);
            ui(emit,"uEmitter",0);f(prepare,"uFadeDistance",256);f(emit,"uFadeDistance",256);
            matrix(emit,"uView",new Matrix4f());matrix(emit,"uProjection",new Matrix4f());f(emit,"uViewportHeight",10000);
            GL41.glProgramUniform4fv(prepare,GL20.glGetUniformLocation(prepare,"uFrustum"),new float[24]);
            var data=bytes(128).putFloat(0,-1).putFloat(4,-1).putFloat(8,-11).putFloat(16,1).putFloat(20,1).putFloat(24,-9).putFloat(32,.1f).putFloat(36,-1).putFloat(40,1);
            put(input,data);dispatch(prepare,1);dispatch(reserve,1);dispatch(emit,18);
            int count=read(counter,16).getInt(0);check(count>1000&&count<=8192,"partition edge and membrane allocation");
            var result=read(pool,8193*64);float forming=result.getFloat(44);
            int edges=0,faces=0;for(int i=0;i<count;i++){
                for(int k=0;k<15;k++)check(Float.isFinite(result.getFloat(i*64+k*4)),"finite partition sample");
                float size=result.getFloat(i*64+12);if(size<.05)edges++;else faces++;
            }
            check(edges>0&&faces>0,"partition emits faces and edges");check(result.getFloat(8192*64)==0,"partition pool sentinel untouched");
            // Ending while forming starts from exactly the current glow, then fades.
            data.putFloat(36,0);put(input,data);put(counter,bytes(16));dispatch(prepare,1);dispatch(reserve,1);dispatch(emit,18);
            check(Math.abs(read(pool,64).getFloat(44)-forming)<1e-5,"partition interrupted condensation has no brightness jump");
            data.putFloat(32,.5f).putFloat(36,.4f);put(input,data);put(counter,bytes(16));dispatch(prepare,1);dispatch(reserve,1);dispatch(emit,18);
            check(read(pool,64).getFloat(44)<forming,"partition dissipates from current brightness");
            data.putFloat(36,.81f);put(input,data);put(counter,bytes(16));dispatch(prepare,1);dispatch(reserve,1);
            check(read(counter,16).getInt(0)==0,"expired partition consumes no GPU slots");
            data.putFloat(36,-1);for(int i=0;i<64;i++)data.put(64+i,data.get(i));put(input,data);put(counter,bytes(16));
            ui(prepare,"uCount",2);ui(reserve,"uCount",2);ui(reserve,"uBudget",37);dispatch(prepare,1);dispatch(reserve,1);dispatch(emit,36);
            check(read(counter,16).getInt(0)==37,"partition global budget respected across domains");
            put(counter,bytes(16).putInt(0,8190));ui(reserve,"uBudget",8192);dispatch(prepare,1);dispatch(reserve,1);dispatch(emit,36);
            check(read(counter,16).getInt(0)==8192,"partition shared pool capacity respected");
        }finally{for(int p:new int[]{prepare,reserve,emit})GL20.glDeleteProgram(p);for(int b:new int[]{input,jobs,counter,pool})GL15.glDeleteBuffers(b);}
    }

    static void geometryPublication() throws Exception {
        var gpu=new SharedFateGpu();
        try{
            gpu.bindDrawInputs();int original=boundBuffer(SharedFateGpu.RIBBON_BINDING),candidate=buffer(128);
            var write=SharedFateGpu.class.getDeclaredField("nodeWrite");write.setAccessible(true);write.setInt(gpu,candidate);
            var capacity=SharedFateGpu.class.getDeclaredField("nodeWriteCapacity");capacity.setAccessible(true);capacity.setInt(gpu,2);
            var staged=SharedFateGpu.class.getDeclaredField("staged");staged.setAccessible(true);
            gpu.commit();gpu.bindDrawInputs();check(boundBuffer(13)==original,"uncommitted geometry never replaces draw inputs");
            staged.setBoolean(gpu,true);gpu.commit();gpu.bindDrawInputs();check(boundBuffer(13)==candidate,"geometry publication uses the newly committed generation");
            gpu.reset();gpu.bindDrawInputs();check(boundBuffer(13)==candidate,"reset cannot publish a partially prepared geometry buffer");
            gpu.close();check(!GL15.glIsBuffer(original)&&!GL15.glIsBuffer(candidate),"reload/close releases both node generations");
        }finally{gpu.close();}
    }
    static void benchmarkComparison() throws Exception {
        System.out.println("GPU comparison at 1920x1080; generation / publication+cull+sort / draw; milliseconds. No Minecraft or shader pack.");
        System.out.println("mode,bindings,length,records,body_segments,stars,generate_ms,sort_ms,draw_ms,total_ms");
        for(boolean baseline:new boolean[]{true,false}){
            sourceRoot=baseline?java.nio.file.Path.of("build/shared-fate-baseline/shaders"):null;
            if(baseline&&!java.nio.file.Files.isDirectory(sourceRoot))throw new IllegalStateException("Preserve the pre-change shader tree in build/shared-fate-baseline/shaders before comparing");
            String prefix=ParticlePrograms.commonPrelude();
            int prepare=program("shared_fate_prepare.comp",GL43.GL_COMPUTE_SHADER,""),reserve=program("shared_fate_reserve.comp",GL43.GL_COMPUTE_SHADER,""),emit=program("shared_fate_emit.comp",GL43.GL_COMPUTE_SHADER,"");
            int publish=program("prepare_dispatch.comp",GL43.GL_COMPUTE_SHADER,prefix),keygen=program("keygen.comp",GL43.GL_COMPUTE_SHADER,prefix),hist=program("radix_hist.comp",GL43.GL_COMPUTE_SHADER,prefix),scan=program("radix_scan.comp",GL43.GL_COMPUTE_SHADER,prefix),scatter=program("radix_scatter.comp",GL43.GL_COMPUTE_SHADER,prefix);
            int alpha=graphics(prefix,true),add=graphics(prefix),fb=GL30.glGenFramebuffers(),tex=GL11.glGenTextures();
            int[] queries=new int[4*24];GL15.glGenQueries(queries);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fb);GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1920,1080,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"benchmark framebuffer");
            try{for(int links:new int[]{1,4,16})for(int length:new int[]{4,16,64}){
                var pool=new ParticleBuffers();check(pool.init(131072,3),"benchmark native pool");
                int input=buffer(links*80),jobs=buffer(links*384),previous=buffer(links*384),next=buffer(links*384),nodes=buffer((131072+4*links)*64),dummy=buffer(64);
                try{
                    for(int id=0;id<3;id++){
                        var field=SharedFateGpu.class.getDeclaredField(id==0?"SPEC":id==1?"ALPHA_SPEC":"RIBBON_SPEC");field.setAccessible(true);float[] h=((com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec)field.get(null)).packed().clone();
                        if(baseline)h[17*4+1]=8;pool.setEmitterHeader(id,h);
                    }
                    pool.uploadDirtyEmitters();float distance=Math.max(10,length*.75f);var data=bytes(links*80);
                    for(int i=0;i<links;i++)data.put(input(length*.5f,0,-distance,0,-1,false,i).putFloat(0,-length*.5f).putFloat(8,-distance).putFloat(12,0).putFloat(28,0));data.flip();put(input,data);
                    var view=new Matrix4f();var projection=new Matrix4f().perspective((float)Math.toRadians(70),1920f/1080,.05f,20000);
                    for(int p:new int[]{prepare,emit}){matrix(p,"uView",view);matrix(p,"uProjection",projection);f(p,"uViewportHeight",1080);f(p,"uTime",1);}
                    for(int p:new int[]{alpha,add}){matrix(p,"ModelViewMat",view);matrix(p,"ProjMat",projection);f(p,"uFadeDist",256);f(p,"uGlow",1);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamRight"),1,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamUp"),0,1,0);}
                    f(prepare,"uFadeDistance",256);GL41.glProgramUniform4fv(prepare,GL20.glGetUniformLocation(prepare,"uFrustum"),new float[24]);ui(prepare,"uCount",links);ui(reserve,"uCount",links);ui(reserve,"uCapacity",pool.capacity());ui(reserve,"uBudget",131072);
                    ui(emit,"uEmitter",0);ui(emit,"uAlphaEmitter",1);ui(emit,"uRibbonAlphaEmitter",1);ui(emit,"uRibbonEmitter",2);
                    ui(publish,"uCapacity",pool.capacity());ui(publish,"uPackageStaged",0);ui(keygen,"uUpper",pool.capacity());matrix(keygen,"uView",view);f(keygen,"uMaxDepth",1024);f(keygen,"uDepthLogRange",10);GL41.glProgramUniform4fv(keygen,GL20.glGetUniformLocation(keygen,"uFrustum"),new float[24]);
                    for(int frame=-8;frame<24;frame++){
                        pool.beginComputeFrame();pool.clearParticles();pool.beginBindings();pool.bindCounter(3,0);int counter=boundBuffer(3);put(counter,bytes(16));pool.bindParticleWrite(0);int output=boundBuffer(0);
                        GL11.glViewport(0,0,1920,1080);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                        if(frame>=0)GL33.glQueryCounter(queries[frame*4],GL33.GL_TIMESTAMP);
                        bind(0,input);bind(1,jobs);bind(2,previous);bind(3,next);bind(4,counter);bind(5,output);bind(6,nodes);bind(7,dummy);bind(8,dummy);
                        dispatch(prepare,(links+63)/64);dispatch(reserve,1);dispatch(emit,links);
                        if(frame>=0)GL33.glQueryCounter(queries[frame*4+1],GL33.GL_TIMESTAMP);
                        pool.beginBindings();pool.bindDispatchInputs(0,1);ui(publish,"uPhase",1);dispatch(publish,1);GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
                        pool.bindParticleWrite(0);pool.bindEmitters(5);pool.bindOrderAdd();pool.bindOrderOpaque();pool.bindSort(7,pool.sortBuffer(0));pool.bindCarrierSink();bind(28,dummy);
                        GL20.glUseProgram(keygen);pool.dispatch(1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                        pool.bindDispatchInputs(0,1);ui(publish,"uPhase",2);dispatch(publish,1);GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
                        pool.bindSort(6,pool.sortBuffer(0));pool.bindSort(7,pool.sortBuffer(1));pool.clearHist();pool.bindHist();pool.bindOffsets();GL20.glUseProgram(hist);pool.dispatch(2);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);dispatch(scan,1);GL20.glUseProgram(scatter);pool.dispatch(2);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
                        if(frame>=0)GL33.glQueryCounter(queries[frame*4+2],GL33.GL_TIMESTAMP);
                        int permutation=pool.sortBuffer(1);pool.swap();GL11.glEnable(GL11.GL_BLEND);
                        for(int mode:new int[]{2,0}){GL11.glBlendFunc(mode==2?GL11.GL_SRC_ALPHA:GL11.GL_ONE,mode==2?GL11.GL_ONE_MINUS_SRC_ALPHA:GL11.GL_ONE);pool.beginBindings();pool.bindBillboardDrawInputs(mode,permutation);bind(13,nodes);GL20.glUseProgram(mode==2?alpha:add);GL41.glProgramUniform1i(alpha,GL20.glGetUniformLocation(alpha,"uMode"),0);pool.drawIndirect(mode==2?5:0);}
                        if(frame>=0)GL33.glQueryCounter(queries[frame*4+3],GL33.GL_TIMESTAMP);
                    }
                    GL11.glFinish();double[] sums=new double[3];for(int frame=0;frame<24;frame++)for(int stage=0;stage<3;stage++)sums[stage]+=(GL33.glGetQueryObjectui64(queries[frame*4+stage+1],GL15.GL_QUERY_RESULT)-GL33.glGetQueryObjectui64(queries[frame*4+stage],GL15.GL_QUERY_RESULT))/24e6;
                    var j=read(jobs,baseline?320:384);int records=0;for(int i=0;i<links;i++)records+=read(jobs,links*(baseline?320:384)).getInt(i*(baseline?320:384)+28);
                    System.out.printf(java.util.Locale.ROOT,"%s,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f%n",baseline?"particles":"ribbons",links,length,records,baseline?0:(int)j.getFloat(304)*links,baseline?0:(int)j.getFloat(312)*links,sums[0],sums[1],sums[2],sums[0]+sums[1]+sums[2]);
                }finally{pool.free();for(int b:new int[]{input,jobs,previous,next,nodes,dummy})GL15.glDeleteBuffers(b);}
            }}finally{for(int p:new int[]{prepare,reserve,emit,publish,keygen,hist,scan,scatter,alpha,add})GL20.glDeleteProgram(p);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(fb);com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(tex);GL15.glDeleteQueries(queries);}
        }
        sourceRoot=null;
    }
    static final class RibbonRig implements AutoCloseable {
        final int links,capacity,prepare,reserve,emit,input,jobs,previous,next,counter,pool,nodes,cache,cacheNext;
        RibbonRig(int links,int capacity){
            preparation=reservation=0;this.links=links;this.capacity=capacity;
            prepare=program("shared_fate_prepare.comp",GL43.GL_COMPUTE_SHADER,"");reserve=program("shared_fate_reserve.comp",GL43.GL_COMPUTE_SHADER,"");emit=program("shared_fate_emit.comp",GL43.GL_COMPUTE_SHADER,"");
            input=buffer(links*80);jobs=buffer(links*384);previous=buffer(links*384);next=buffer(links*384);counter=buffer(16);
            pool=buffer((capacity+1)*64);nodes=buffer((capacity+4*links)*64);cache=buffer(2048*64);cacheNext=buffer(2048*64);
            for(int p:new int[]{prepare,emit}){matrix(p,"uView",new Matrix4f());matrix(p,"uProjection",new Matrix4f().perspective((float)Math.toRadians(70),1.777f,.05f,20000));f(p,"uViewportHeight",1080);}
            f(prepare,"uFadeDistance",256);GL41.glProgramUniform4fv(prepare,GL20.glGetUniformLocation(prepare,"uFrustum"),new float[24]);
            ui(prepare,"uCount",links);ui(reserve,"uCount",links);ui(reserve,"uCapacity",capacity);ui(emit,"uEmitter",0);ui(emit,"uRibbonAlphaEmitter",1);ui(emit,"uRibbonEmitter",2);ui(emit,"uDebrisSlots",1);
        }
        int run(float time,ByteBuffer data,int budget,boolean commit){
            put(input,data);put(counter,bytes(16));ui(reserve,"uBudget",budget);f(prepare,"uTime",time);f(emit,"uTime",time);
            bind(0,input);bind(1,jobs);bind(2,previous);bind(3,next);bind(4,counter);bind(5,pool);bind(6,nodes);bind(7,cache);bind(8,cacheNext);
            dispatch(prepare,(links+63)/64);dispatch(reserve,1);dispatch(emit,links);
            if(commit){put(previous,read(next,links*384));put(cache,read(cacheNext,2048*64));}
            return read(counter,16).getInt(0);
        }
        public void close(){for(int p:new int[]{prepare,reserve,emit})GL20.glDeleteProgram(p);for(int b:new int[]{input,jobs,previous,next,counter,pool,nodes,cache,cacheNext})GL15.glDeleteBuffers(b);}
    }
    static void geometry(){
        try(var rig=new RibbonRig(1,32768)){
            for(float[] end:new float[][]{{4,0,-10},{32,8,-30},{1000,300,-1000},{0,8,-10},{0,0,-10},{.001f,0,-10},{0,0,1000},{0,0,-.01f}}){
                put(rig.previous,bytes(384));int count=rig.run(1,input(end[0],end[1],end[2],0,-1,false,0),32768,false);
                var job=read(rig.jobs,384);int n=(int)job.getFloat(108);
                check(n>=16&&n<=512&&(n&(n-1))==0,"16..512 power-of-two shared spiral segments");
                if(count==0)continue;
                check(job.getFloat(304)==4*n&&job.getFloat(308)==4*n,"four complete body and halo strips; no main line");
                check(job.getFloat(312)==Math.min(1024,Math.ceil(job.getFloat(124)*32)),"fourfold star density and 1024 cap");
                var nodes=read(rig.nodes,4*(n+1)*64);var particles=read(rig.pool,count*64);
                for(int i=0;i<4*(n+1);i++)for(int k=0;k<16;k++)check(Float.isFinite(nodes.getFloat(i*64+k*4)),"finite shared geometry node");
                for(int lane=0;lane<4;lane++)for(int j=0;j<n;j++){
                    int rec=(lane*n+j)*64,a=particles.getInt(rec+16),b=particles.getInt(rec+20);
                    check(a==lane*(n+1)+j&&b==a+1,"adjacent segments share exact endpoint indices");
                    check(particles.getInt(rec+60)==1&&particles.getInt((4*n+lane*n+j)*64+60)==2,"body/halo material routing");
                    if(j>0){double dot=0;for(int k=0;k<3;k++)dot+=nodes.getFloat(a*64+16+k*4)*nodes.getFloat((a-1)*64+16+k*4);check(dot>=-.00001,"strip sides do not flip");}
                }
                for(int j=0;j<=n;j++){
                    double r2=0;for(int k=0;k<3;k++){float a=nodes.getFloat(j*64+k*4),b=nodes.getFloat((2*(n+1)+j)*64+k*4);r2+=Math.pow((a-b)*.5,2);}
                    check(r2<=.0501*.0501,"helix centre diameter stays at .1 block");
                }
                check(read(rig.pool,(32768+1)*64).getFloat(32768*64)==0,"shared-pool sentinel untouched");
            }
            put(rig.previous,bytes(384));var active=input(4,0,-10,0,-1,true,0);rig.run(.2f,active,32768,true);
            var ended=input(8,0,-10,0,.2f,true,0).putInt(60,1);rig.run(.3f,ended,32768,true);
            var first=read(rig.cache,2048*64);int released=-1;for(int i=0;i<2048;i++)if(first.getFloat(i*64+60)>.5){released=i;break;}
            check(released>=0,"interrupted condensation releases fragments");float frozen=read(rig.jobs,384).getFloat(380);
            for(int frame=1;frame<=48;frame++){
                float time=.3f+frame/60f,x=8+frame*.1f,cam=frame>=24?10:0;ended.putFloat(0,-cam).putFloat(16,x-cam).putFloat(64,frame==24?-10:0);GL41.glProgramUniform3f(rig.emit,GL20.glGetUniformLocation(rig.emit,"uCamPos"),cam,0,0);rig.run(time,ended,32768,true);
                var job=read(rig.jobs,384);int n=(int)job.getFloat(108);var nodes=read(rig.nodes,4*(n+1)*64);
                check(Math.abs(nodes.getFloat(n*64)-(x-cam))<.0001,"dissolving geometry keeps following moving target");
                check(Math.abs(job.getFloat(380)-frozen)<.00001,"interrupted condensation does not regain brightness");
                var now=read(rig.cache,2048*64);for(int k=0;k<3;k++)check(Math.abs(now.getFloat(released*64+k*4)+(k==0?cam:0)-first.getFloat(released*64+k*4))<.00001,"released particles retain world-space birth positions while camera moves");
            }
            check(rig.run(1.401f,ended,32768,true)==0,"all geometry and fragments end at 1.2 seconds");
            GL41.glProgramUniform3f(rig.emit,GL20.glGetUniformLocation(rig.emit,"uCamPos"),0,0,0);
            for(int budget:new int[]{32768,1024,256,64,63,0}){
                int count=rig.run(2,active,budget,true);var job=read(rig.jobs,384);check(count<=budget,"all decoration and geometry count toward shared budget");
                int body=(int)job.getFloat(304);check(body==0||body>=64&&body%4==0,"budget preserves four continuous strands or hides whole binding");
            }
            // A failed frame never publishes its next state. Re-running from the committed pose is deterministic.
            put(rig.previous,bytes(384));rig.run(3,active,32768,false);var uncommitted=read(rig.nodes,4096);
            rig.run(3,active,32768,false);var retry=read(rig.nodes,4096);check(uncommitted.equals(retry),"uncommitted geometry generation can be retried without stale node references");
        }
        try(var rig=new RibbonRig(16,131072)){
            var data=bytes(16*80);for(int i=0;i<16;i++)data.put(input(64,0,-48,0,-1,false,i));data.flip();
            int count=rig.run(1,data,131072,false);check(count<=131072,"concurrent links share total pool budget");var jobs=read(rig.jobs,16*384);
            int previousEnd=0;for(int i=0;i<16;i++){int base=i*384,start=jobs.getInt(base+12),n=jobs.getInt(base+28);check(start==previousEnd,"bindings reserve disjoint dense ranges");previousEnd=start+n;}
            check(previousEnd==count,"published total includes all bands and decorations");
        }
    }
    public static void main(String[] args) throws Exception {
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Shared Fate shader validation",0,0);
        try{check(window!=0,"hidden GPU context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println(GL11.glGetString(GL11.GL_RENDERER));
            if(java.util.Arrays.asList(args).contains("--benchmark-only"))benchmarkComparison();
            else {lateHeaders();math();samplingMath();frames();geometry();geometryPublication();drawHandoff();partitionKernels();partitionDrawHandoff();}
            check(GL11.glGetError()==0,"no GL error");
            System.out.println("Shared Fate GPU: "+checks+" assertions passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}

