import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.*;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages;
import com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** One current driver harness. Rule checks and benchmarks use the same public engine kernels. */
public final class ParticleEngineGpuValidation {
    static long checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static ByteBuffer bytes(int n){return BufferUtils.createByteBuffer(n).order(ByteOrder.nativeOrder());}
    static int buffer(ByteBuffer data){int b=GL45.glCreateBuffers();GL45.glNamedBufferData(b,data,GL15.GL_DYNAMIC_COPY);return b;}
    static ByteBuffer read(int b,int count){GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);int copy=GL45.glCreateBuffers();try{GL45.glNamedBufferData(copy,count,GL15.GL_STREAM_READ);GL45.glCopyNamedBufferSubData(b,copy,0,0,count);ByteBuffer out=bytes(count);GL45.glGetNamedBufferSubData(copy,0,out);return out;}finally{GL15.glDeleteBuffers(copy);}}
    static String source(String name){return ParticleShaderSource.loadParticle(name,path->{try{String text=Files.readString(Path.of("src/main/resources/assets/createmanaindustry",path));for(String hook:List.of("update","spawn"))if(text.contains("#pragma cmi_types "+hook))text=text.replace("#pragma cmi_types "+hook,com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.shaderHooks(hook));return text;}catch(Exception e){throw new IllegalStateException(e);}});}
    static int program(String name){return GpuShaderCompiler.compute(name,"#version 450 core\n"+ParticlePrograms.commonPrelude()+source(name));}
    static void bind(int slot,int buffer){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,slot,buffer);}
    static void ui(int p,String name,int n){GL41.glProgramUniform1ui(p,GL20.glGetUniformLocation(p,name),n);}
    static void phase(int p,int phase,int n){ui(p,"phase",phase);GL20.glUseProgram(p);GL43.glDispatchCompute(Math.max(1,(n+255)/256),1,1);GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);}
    static ByteBuffer packet(int n,int terrain,long nonce,long tick,int kind){
        var b=bytes(128+(n+terrain)*64);b.putInt(0,0x41313149).putInt(4,12).putLong(8,nonce).putLong(16,91).putLong(24,tick).putLong(32,tick+1).putInt(40,n).putFloat(48,1).putFloat(52,.05f).putFloat(56,.1f).putLong(64,91).putInt(72,1).putInt(76,2).putInt(80,kind).putInt(112,terrain).putInt(116,32+n*16);
        for(int i=0;i<n;i++){int at=128+i*64;b.putFloat(at,i*.8f).putFloat(at+12,.34f).putFloat(at+16,8).putFloat(at+28,kind==1?10:16).putInt(at+32,i).putInt(at+36,1234+i).putInt(at+40,9).putInt(at+44,1).putFloat(at+48,8);}
        return b;
    }
    static void interpolation(List<Object> cases){
        int n=6,p=program("display.comp");ByteBuffer old=bytes(n*64),current=bytes(n*64),edges=bytes(n*4);
        for(int i=0;i<n;i++){
            int before=(n-1-i)*64,after=i*64;
            old.putFloat(before,i).putFloat(before+12,1).putFloat(before+16,2).putFloat(before+28,-.5f).putFloat(before+48,2).putFloat(before+52,20).putFloat(before+56,.8f);
            current.putFloat(after,i+10).putFloat(after+12,1).putFloat(after+16,2).putFloat(after+28,.5f).putFloat(after+48,2.05f).putFloat(after+52,20).putFloat(after+56,1f);
            if(i<3)edges.putInt(i*4,n-i);
        }
        // Frame/brightness changes do not affect correspondence. Explicit zero edges snap.
        int stride=ParticleBuffers.VEC4_PER_EMITTER*16;
        var headers=bytes(stride*2);headers.putFloat(0,1).putFloat(stride,9);
        current.putInt(64+60,1);old.putInt((n-2)*64+60,1);
        current.putFloat(5*64+52,0);
        int a=buffer(current),b=buffer(old),out=buffer(bytes(n*64)),count=buffer(bytes(16).putInt(0,n)),map=buffer(edges),emitters=buffer(headers);
        try{
            bind(0,a);bind(1,b);bind(2,out);bind(3,count);bind(4,count);bind(5,map);bind(11,emitters);ui(p,"capacity",n);ui(p,"historyBase",0);
            for(int fps:new int[]{30,60,144,240}){
                for(int sample=0;sample<=fps;sample++){
                    float alpha=(float)sample/fps;GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,"alpha"),alpha);phase(p,2,n);var result=read(out,n*64);
                    for(int i=0;i<3;i++)check(Math.abs(result.getFloat(i*64)-(i+10*alpha))<2e-5,"compaction interpolation, fps="+fps);
                    check(Math.abs(result.getFloat(64+56)-(.8f+.2f*alpha))<1e-6,"gold brightness interpolates without breaking position");
                    check(Math.abs(result.getFloat(28)-(-.5f+alpha))<1e-6,"negative rotations interpolate");
                    for(int i=3;i<n;i++)check(result.getFloat(i*64)==current.getFloat(i*64),"birth/death/teleport snap via explicit edge");
                }
            }
            var preserved=read(a,n*64);for(int i=0;i<n*64;i++)check(preserved.get(i)==current.get(i),"display cannot write simulation");
            headers.putFloat(0,10);GL45.glNamedBufferSubData(emitters,0,headers);
            GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"currentCameraDelta"),-2,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"previousCameraDelta"),-5,0,0);
            GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,"alpha"),.5f);phase(p,2,n);
            check(Math.abs(read(out,n*64).getFloat(0)-1.5f)<1e-6,"camera-relative particles interpolate after rebasing both states");
            headers.putFloat(0,11);GL45.glNamedBufferSubData(emitters,0,headers);phase(p,2,n);
            check(read(out,n*64).getFloat(16)==current.getFloat(16),"ribbon node indices retain discrete semantics");
            cases.add(Map.of("case","compaction edges, gold brightness, rotation, 30/60/144/240 FPS","passed",true));
        }finally{for(int id:new int[]{a,b,out,count,map,emitters})GL15.glDeleteBuffers(id);GL20.glDeleteProgram(p);}
        externalHistory(cases);
        partitionHistory(cases);
    }
    static void partitionHistory(List<Object> cases){
        int p=program("partition_emit.comp"),n=18*64,baseWord=9;
        var inputs=bytes(64);inputs.putFloat(40,1).putInt(60,2);
        var jobs=bytes(n);jobs.putFloat(16,1).putInt(28,10).putInt(44,3);
        var previous=bytes(n*2);previous.putInt(n+28,4).putInt(n+44,3);
        int a=buffer(inputs),b=buffer(jobs),old=buffer(previous),pool=buffer(bytes(64*64)),edges=buffer(bytes((64+baseWord)*4));
        try{
            bind(0,a);bind(1,b);bind(5,pool);bind(6,old);bind(31,edges);
            ui(p,"uHistoryBase",baseWord);ui(p,"uEmitter",0);GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uHistoryEnabled"),1);GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,"uFadeDistance"),128);GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,"uViewportHeight"),64);
            GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,"uView"),false,new org.joml.Matrix4f().get(new float[16]));GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,"uProjection"),false,new org.joml.Matrix4f().get(new float[16]));
            for(int scenario=0;scenario<3;scenario++){
                int consecutive=scenario==1?0:1;
                if(scenario==2){previous.putInt(n+44,2);GL45.glNamedBufferSubData(old,0,previous);}
                GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uHistoryConsecutive"),consecutive);GL20.glUseProgram(p);GL43.glDispatchCompute(1,1,1);var mapped=read(edges,(64+baseWord)*4);
                for(int i=0;i<3;i++)check(mapped.getInt((baseWord+10+i)*4)==(scenario==0?5+i:0),
                    "partition ordinals match only across consecutive, equal-sized layouts");
            }
            cases.add(Map.of("case","Partition producer history across domain reorder, new ordinals and generation gaps","passed",true));
        }finally{for(int id:new int[]{a,b,old,pool,edges})GL15.glDeleteBuffers(id);GL20.glDeleteProgram(p);}
    }
    static void externalHistory(List<Object> cases){
        int stride=384,capacity=128,baseWord=7;
        var inputs=bytes(80);inputs.putInt(60,0);
        var jobs=bytes(stride);jobs.putInt(28,1).putFloat(48,1).putFloat(80,1).putFloat(96+8,1).putFloat(96+12,4).putFloat(112,3).putFloat(112+12,4).putFloat(144+8,1);
        var old=bytes(stride);old.putInt(144,0).putInt(144+8,8).putInt(144+12,10).putFloat(304,16).putFloat(308,16).putFloat(312,3);
        int input=buffer(inputs),job=buffer(jobs),before=buffer(old),next=buffer(bytes(stride)),count=buffer(bytes(16).putInt(0,5)),pool=buffer(bytes(capacity*64)),nodes=buffer(bytes(capacity*64)),debris=buffer(bytes(64)),edges=buffer(bytes((capacity+baseWord)*4));
        int reserve=program("shared_fate_reserve.comp"),emit=program("shared_fate_emit.comp"),display=program("shared_fate_display.comp");
        int oldNodes=buffer(bytes(capacity*64)),displayNodes=buffer(bytes(capacity*64));
        int ribbonDispatch=buffer(bytes(16).putInt(0,20));
        try{
            for(int variant=0;variant<4;variant++){
                jobs.putInt(28,1).putFloat(144+8,variant==3?0:1);GL45.glNamedBufferSubData(job,0,jobs);
                old.putFloat(304,variant==1?32:16);GL45.glNamedBufferSubData(before,0,old);
                GL45.glNamedBufferSubData(count,0,bytes(16).putInt(0,5));GL45.glNamedBufferSubData(edges,0,bytes((capacity+baseWord)*4));
                bind(0,input);bind(1,job);bind(2,before);bind(3,next);bind(4,count);bind(5,pool);bind(6,nodes);bind(7,debris);bind(8,debris);bind(31,edges);
                int gen=variant==2?11:9;
                ui(reserve,"uCount",1);ui(reserve,"uCapacity",capacity);ui(reserve,"uBudget",capacity);ui(reserve,"uSimulationGeneration",gen);GL20.glUseProgram(reserve);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                ui(emit,"uEmitter",0);ui(emit,"uRibbonEmitter",1);ui(emit,"uRibbonAlphaEmitter",2);ui(emit,"uDebrisSlots",0);ui(emit,"uSimulationGeneration",gen);ui(emit,"uHistoryBase",baseWord);GL41.glProgramUniform1i(emit,GL20.glGetUniformLocation(emit,"uHistoryEnabled"),1);
                GL41.glProgramUniformMatrix4fv(emit,GL20.glGetUniformLocation(emit,"uView"),false,new org.joml.Matrix4f().get(new float[16]));GL41.glProgramUniformMatrix4fv(emit,GL20.glGetUniformLocation(emit,"uProjection"),false,new org.joml.Matrix4f().get(new float[16]));GL41.glProgramUniform1f(emit,GL20.glGetUniformLocation(emit,"uViewportHeight"),64);
                GL20.glUseProgram(emit);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);
                ByteBuffer result=read(edges,(capacity+baseWord)*4),nodeResult=read(nodes,capacity*64),state=read(next,stride);
                for(int i=0;i<35;i++){
                    int expected=variant==0?11+i:variant==1&&i>=32?10+32+16+i-32+1:0;
                    check(result.getInt((baseWord+5+i)*4)==expected,"external compaction edge variant="+variant+" ordinal="+i);
                }
                for(int i=0;i<20;i++)check(nodeResult.getInt(i*64+56)==(variant==0?i+1:0),"ribbon node edge reflects generation and topology");
                check(state.getInt(156)==5&&state.getInt(152)==gen,"external reservation commits offset and generation together");
                if(variant==0){
                    var prior=bytes(capacity*64);for(int i=0;i<20;i++)prior.putFloat(i*64,20+i).putFloat(i*64+12,.1f).putFloat(i*64+20,1).putFloat(i*64+28,.2f).putFloat(i*64+32,.3f);
                    GL45.glNamedBufferSubData(oldNodes,0,prior);bind(0,nodes);bind(1,oldNodes);bind(2,displayNodes);bind(3,ribbonDispatch);
                    GL41.glProgramUniform3f(display,GL20.glGetUniformLocation(display,"uCurrentCameraDelta"),-2,0,0);GL41.glProgramUniform3f(display,GL20.glGetUniformLocation(display,"uPreviousCameraDelta"),-5,0,0);
                    for(int fps:new int[]{30,60,144,240})for(int sample=0;sample<=fps;sample++){
                        float alpha=(float)sample/fps;GL41.glProgramUniform1f(display,GL20.glGetUniformLocation(display,"uAlpha"),alpha);GL20.glUseProgram(display);GL43.glDispatchCompute(1,1,1);var rendered=read(displayNodes,20*64);
                        for(int i=0;i<20;i++)check(Math.abs(rendered.getFloat(i*64)-((20+i-5)*(1-alpha)+(nodeResult.getFloat(i*64)-2)*alpha))<1e-4,"ribbon interpolation in one camera frame fps="+fps);
                    }
                    var unchanged=read(nodes,capacity*64);for(int i=0;i<capacity*64;i++)check(unchanged.get(i)==nodeResult.get(i),"ribbon display cannot mutate simulation");
                }
            }
            cases.add(Map.of("case","Shared Fate producer edges, topology changes, identity reuse, skipped generations and ribbon display 30/60/144/240 FPS","passed",true));
        }finally{for(int id:new int[]{input,job,before,next,count,pool,nodes,debris,edges,oldNodes,displayNodes,ribbonDispatch})GL15.glDeleteBuffers(id);for(int p:new int[]{reserve,emit,display})GL20.glDeleteProgram(p);}
    }
    static byte readByte(int b,int offset){var v=bytes(1);GL45.glGetNamedBufferSubData(b,offset,v);return v.get(0);}
    static double sphereEntry(float[] row){
        double now=row[7],dt=row[11],start=row[12],end=row[13];
        if(dt<=0||end<=start||now-dt>=end)return Double.NaN;
        double lo=Math.max(0,(start-now+dt)/dt),hi=Math.min(1,(end-now+dt)/dt);
        if(lo>hi||hi<0||lo>1)return Double.NaN;
        double a=0,b=0,c=-row[3]*row[3],first=0;
        for(int k=0;k<3;k++){double step=row[4+k]-row[k],offset=row[k]-row[8+k];a+=step*step;b+=offset*step;c+=offset*offset;first+=(offset+step*lo)*(offset+step*lo);}
        double hit=lo;
        if(first>row[3]*row[3]){if(a<=1e-12||b*b-a*c<0)return Double.NaN;hit=Math.max(lo,(-b-Math.sqrt(b*b-a*c))/a);if(hit>hi||(-b+Math.sqrt(b*b-a*c))/a<lo)return Double.NaN;}
        return now-dt*(1-hit)<end?hit:Double.NaN;
    }
    static void contacts(List<Object> cases){
        int n=1024;var data=bytes(n*64);var random=new Random(11020);var rows=new ArrayList<float[]>();
        for(int i=0;i<n;i++){
            float[] row={-2,0,0,.5f,2,0,0,1,0,0,0,.05f,.95f,1.01f,0,0};
            if(i==1){row[12]=1.01f;row[13]=1.05f;} // phase begins after the movement
            else if(i==2){row[12]=.9f;row[13]=.96f;} // contact only after expiry
            else if(i==3){row[12]=.98f;row[13]=.99f;} // active subset of a fast crossing
            else if(i==4){row[11]=0;}else if(i==5){row[0]=row[4]=0;}else if(i==6){row[1]=row[5]=.5f;}
            else if(i>6){for(int k=0;k<3;k++){row[k]=random.nextFloat()*4-2;row[4+k]=random.nextFloat()*4-2;row[8+k]=random.nextFloat()-.5f;}row[12]=.94f+random.nextFloat()*.09f;row[13]=row[12]+.001f+random.nextFloat()*.05f;}
            rows.add(row);for(int k=0;k<16;k++)data.putFloat(i*64+k*4,row[k]);
        }
        String code="#version 450 core\nlayout(local_size_x=256)in;layout(std430,binding=0)readonly buffer In{vec4 rows[];}src;layout(std430,binding=1)writeonly buffer Out{vec4 rows[];}dst;\n"+source("chunks/contact_sweep.glsl")+"\nvoid main(){uint i=gl_GlobalInvocationID.x;vec4 a=src.rows[i*4u],b=src.rows[i*4u+1u],c=src.rows[i*4u+2u],d=src.rows[i*4u+3u];vec2 span;float t=0.;bool hit=cmiContactInterval(b.w,c.w,d.x,d.y,span)&&cmiContactHit(a.xyz,b.xyz,c.xyz,a.w,span,t)&&b.w-c.w*(1.-t)<d.y;dst.rows[i]=vec4(t,hit?1.:0.,0.,0.);}";
        int p=GpuShaderCompiler.compute("independent cross-tick contact fixture",code),input=buffer(data),output=buffer(bytes(n*16));
        try{bind(0,input);bind(1,output);GL20.glUseProgram(p);GL43.glDispatchCompute(n/256,1,1);var result=read(output,n*16);
            for(int i=0;i<n;i++){double expected=sphereEntry(rows.get(i));boolean hit=result.getFloat(i*16+4)==1;check(hit==Double.isFinite(expected),"FP64 independent contact/window case="+i);if(hit)check(Math.abs(result.getFloat(i*16)-expected)<2e-5,"earliest active contact case="+i);}
            cases.add(Map.of("case","50ms swept contact and phase boundaries","samples",n,"passed",true));
        }finally{GL20.glDeleteProgram(p);GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);}
    }
    static void displayPublication(List<Object> cases)throws Exception{
        var gpu=new ParticleBuffers();var shaders=new ParticlePrograms();
        try(var display=new ModelRenderPublication(ParticleEngineGpuValidation::source)){
            check(gpu.init(1024,4),"display physical arena");
            gpu.allayAnimation().ensure(4);var animationSeed=bytes(64).putInt(0,0x12345678);GL45.glNamedBufferSubData(gpu.allayAnimation().buffer(gpu.allayAnimation().readBank()),0,animationSeed);
            for(String field:new String[]{"keygen","ordinaryKeygen","radixHist","radixScan","radixScatter"}){
                String file=switch(field){case "ordinaryKeygen"->"keygen_ordinary.comp";case "radixHist"->"radix_hist.comp";case "radixScan"->"radix_scan.comp";case "radixScatter"->"radix_scatter.comp";default->"keygen_model.comp";};
                var f=ParticlePrograms.class.getDeclaredField(field);f.setAccessible(true);f.setInt(shaders,program(file));
            }
            var types=com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Material.values();
            for(int i=0;i<3;i++){
                var header=com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec.builder().type(com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.standard(types[i])).size(.1).life(100,100).build().packed();
                if(i==2)header[18*4]=1;
                gpu.setEmitterHeader(i,header);
            }gpu.uploadDirtyEmitters();
            var rows=bytes(4*64);for(int i=0;i<4;i++){int at=i*64;rows.putFloat(at,i*.1f).putFloat(at+8,-1).putFloat(at+12,1).putFloat(at+32,1).putFloat(at+36,1).putFloat(at+40,1).putFloat(at+44,1).putFloat(at+52,100).putInt(at+56,i+100).putInt(at+60,Math.min(i,2));}
            gpu.bindCounter(3,0);int currentCounter=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,3);gpu.bindDrawIndirect();int currentCommands=GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
            GL45.glNamedBufferSubData(gpu.particleReadBufferId(),0,rows);GL45.glNamedBufferSubData(currentCounter,0,bytes(16).putInt(0,4));gpu.commitPopulation(1);
            int population=gpu.populationBufferId(1);GL45.glNamedBufferSubData(population,0,bytes(64).putInt(0,2).putInt(8,4).putInt(12,1).putLong(16,1).putInt(24,gpu.modelIndexCapacity()).putInt(28,1024).putInt(32,2).putInt(36,2).putInt(40,3).putInt(44,3));
            var publication=new com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication(1,1,gpu.particleReadBufferId(),0,currentCommands,currentCounter,population,1,-1,-1);
            var matrix=new org.joml.Matrix4f().ortho(-10,10,-10,10,.01f,100);var camera=new net.minecraft.client.Camera();
            var frame=display.prepare(gpu,shaders,publication,null,.5f,1,camera,new org.joml.Matrix4f(),matrix);
            check(frame.alpha()==1&&Math.abs(frame.timeSec()-1.025f)<1e-6,"missing history uses matching current pose clock");
            var commands=read(frame.commands(),ParticleBuffers.INDIRECT_UINTS*4);check(commands.getInt(ParticleBuffers.IDX_CNT_ADD*4)==1,"display additive list");check(commands.getInt(ParticleBuffers.IDX_CNT_ALPHA*4)==1,"display alpha list");check(commands.getInt(ParticleBuffers.IDX_CNT_MODELOP*4)==2,"display MODEL list");
            check(read(frame.permutation(),(gpu.metaSlotIndex()+1)*8).getInt(gpu.metaSlotIndex()*8)==2,"display permutation metadata");check(read(gpu.particleReadBufferId(),rows.capacity()).equals(rows),"publication preserves simulation bytes");
            int playback=buffer(bytes(64).putFloat(0,-.5f).putFloat(16,.25f).putFloat(32,.5f).putInt(44,7));
            int playbackTexture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
            try{
                GL45.glTextureBuffer(playbackTexture,GL30.GL_RGBA32UI,playback);
                var view=new NativeStormDisplayClock.View(playbackTexture,7,new NativeStormDisplayClock.Weights(.5f,.5f,0,0));
                var projected=display.prepare(gpu,shaders,publication,null,.5f,1,camera,new org.joml.Matrix4f(),matrix,view);
                var rendered=read(projected.pool(),rows.capacity());
                check(Math.abs(rendered.getFloat(2*64)+.125f)<1e-6f&&rendered.getFloat(64)==rows.getFloat(64),
                        "Native playback weights affect Storm members only");
                check(read(gpu.particleReadBufferId(),rows.capacity()).equals(rows),"Native interpolation never mutates simulation");
            }finally{GL11.glDeleteTextures(playbackTexture);GL15.glDeleteBuffers(playback);}
            int frozenEmitter=gpu.particleDrawEmitterBufferId();check(frozenEmitter!=gpu.emitterBufferId(),"display owns emitter snapshot");var emitterSeed=read(frozenEmitter,64);GL45.glNamedBufferSubData(gpu.emitterBufferId(),0,bytes(64));check(read(frozenEmitter,64).equals(emitterSeed),"staged header cannot change rendered publication");
            int animationProbe=GpuShaderCompiler.compute("frozen animation binding","#version 450 core\nlayout(local_size_x=1)in;uniform usamplerBuffer cmi_AllayAnimation;layout(std430,binding=0)buffer Out{uvec4 row;};void main(){row=texelFetch(cmi_AllayAnimation,0);}");
            try{gpu.bindAllayAnimation(animationProbe);int old=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0+SAMPLER_ANIMATION_DRAW);int texture=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);GL13.glActiveTexture(old);int frozenAnimation=GL45.glGetTextureLevelParameteri(texture,0,GL31.GL_TEXTURE_BUFFER_DATA_STORE_BINDING);check(frozenAnimation!=0&&frozenAnimation!=gpu.allayAnimation().buffer(gpu.allayAnimation().readBank()),"display owns candidate pose records");}finally{GL20.glDeleteProgram(animationProbe);}
            gpu.clearDisplayResources();cases.add(Map.of("case","display complete publication","passed",true));
        }finally{shaders.delete();gpu.free();}
    }
    static void featherPixels(List<Object> cases)throws Exception{
        int p=GpuShaderCompiler.graphics("feather pixels","#version 450 core\n"+ParticlePrograms.commonPrelude()+source("feather.vsh"),"#version 450 core\n"+ParticlePrograms.commonPrelude()+source("textured.fsh"));
        int pool=buffer(bytes(64)),list=buffer(bytes(com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.BYTES));
        int vao=GL30.glGenVertexArrays(),fbo=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),atlas=GL11.glGenTextures();
        try(var image=new com.mojang.blaze3d.platform.NativeImage(64,64,true)){
            image.fillRect(0,0,64,64,0);int[] opaque=new int[12];
            for(int frame=0;frame<12;frame++)try(var stream=Files.newInputStream(Path.of("src/main/resources/assets/createmanaindustry/textures/particle/cherry_"+frame+".png"));var original=com.mojang.blaze3d.platform.NativeImage.read(stream)){
                AllayFeatherAppearance.bakeFrame(image,original,frame);
                for(int y=0;y<3;y++)for(int x=0;x<3;x++)if((original.getPixelRGBA(x,y)>>>24)>=26)opaque[frame]++;
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0+1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,atlas);
            var texels=bytes(64*64*4);for(int value:image.getPixelsRGBA())texels.putInt(value);texels.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,texels);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"feather FBO");
            GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DEPTH_TEST);GL30.glBindVertexArray(vao);GL20.glUseProgram(p);bind(1,pool);bind(20,list);
            for(String name:new String[]{"ModelViewMat","ProjMat"})GL41.glProgramUniformMatrix4fv(p,GL20.glGetUniformLocation(p,name),false,new org.joml.Matrix4f().scaling(name.equals("ProjMat")?10:1).get(new float[16]));
            GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamRight"),1,0,0);GL41.glProgramUniform3f(p,GL20.glGetUniformLocation(p,"uCamUp"),0,1,0);GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uSprite"),1);GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uMode"),1);GL41.glProgramUniform1f(p,GL20.glGetUniformLocation(p,"uFadeDist"),128);
            for(int frame=0;frame<36;frame++){
                GL45.glNamedBufferSubData(pool,0,bytes(64).putFloat(12,1).putInt(56,(frame%12)|((frame/12)<<16)));
                GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,1);
                var pixels=bytes(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);int visible=0;for(int i=0;i<64*64;i++)if((pixels.get(i*4+3)&255)>0)visible++;
                check(visible==opaque[frame%12]*256,"feather source alpha footprint rendered, frame="+frame+" pixels="+visible);
            }
            cases.add(Map.of("case","36 feather frames: actual cutout pixels and source alpha footprint","passed",true));
        }finally{GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glDeleteTextures(atlas);GL11.glDeleteTextures(color);GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(list);GL20.glDeleteProgram(p);}
    }
    static void conservativeVisibility(List<Object> cases,boolean nativeEnabled)throws Exception{
        // Center hits on a tiny occluder cannot hide a whole body or held item.
        float[][] bounds={{3,.5f,1,1,1,1},{3,.5f,1,1,3,0},{3,.5f,.01f,.01f,3,1},{3,.01f,1,1,3,0},{0,1,1,1,3,1},{10,1,1,1,3,1},{3,.5f,.15f,.15f,3,1}};
        for(int sample=0;sample<bounds.length;sample++){
            float[] spec=bounds[sample];var data=packet(1,1,1,7,1);data.putFloat(128+16,0);
            int at=192;data.putFloat(at,spec[0]).putFloat(at+16,spec[1]).putFloat(at+20,spec[2]).putFloat(at+24,spec[3]).putInt(at+44,(int)spec[4]);
            if(sample==6)data.putFloat(128+12,1); // Conservative carried-item bound.
            int input=buffer(data),output=buffer(bytes(data.capacity()));
            try(var index=new GlSpatialIndex(1,1,source("spatial/bvh.comp"))){
                index.query(input,output,1,91,7,91,2,1,0,0,0);var result=read(output,data.capacity());
                check(result.getInt(44)==0&&result.getFloat(128)==spec[5],"GL whole-bound occlusion case="+sample);
            }finally{GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);}
            if(nativeEnabled){var result=nativeBatch(data,1,1,2);check(result.getInt(44)==0&&result.getFloat(128)==spec[5],"RT whole-bound occlusion case="+sample);}
        }
        var proxies=packet(2,0,1,7,1);proxies.putFloat(128+16,0).putFloat(192,3).putInt(192+44,3);
        if(nativeEnabled)check(nativeBatch(proxies,2,0,2).getFloat(128)==1,"motion proxies never authorize opaque culling");
        cases.add(Map.of("case","whole-bound opaque proof, thin walls, partial coverage, camera overlap, held items and motion proxies","native",nativeEnabled,"passed",true));
    }
    static void glQueries(List<Object> cases){
        for(int n:new int[]{1,2,65,257,1000,65536}){
            int input=buffer(packet(n,0,1,7,0)),output=buffer(bytes(128+n*64));
            try(var index=new GlSpatialIndex(n,0,source("spatial/bvh.comp"))){
                long start=System.nanoTime();index.query(input,output,1,91,7,91,2,0,0,0,0);var result=read(output,128+n*64);
                check(result.getInt(44)==0&&result.getInt(72)==1,"complete GL descriptor n="+n);
                for(int i=0;i<Math.min(n,257);i++){int at=128+i*64;check(result.getInt(at+28)==1,"bounded LBVH query complete n="+n+" member="+i);check(Float.isFinite(result.getFloat(at)),"finite steering");if(i+1<n)check(result.getFloat(at)<0,"closest forward box n="+n+" member="+i);}
                cases.add(Map.of("case","GL nearest probes","members",n,"wall_ms",(System.nanoTime()-start)/1e6,"passed",true));
            }finally{GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);}
        }
        for(int mask:new int[]{1,2}){
            var data=packet(1,1,1,7,1);data.putFloat(128+16,0);int at=128+64;data.putFloat(at,3).putFloat(at+16,.5f).putFloat(at+20,1).putFloat(at+24,1).putInt(at+44,mask);
            int input=buffer(data),output=buffer(bytes(data.capacity()));try(var index=new GlSpatialIndex(1,1,source("spatial/bvh.comp"))){index.query(input,output,1,91,7,91,2,1,0,0,0);var r=read(output,data.capacity());check(r.getInt(44)==0,"terrain packet accepted");check(r.getFloat(128)==(mask==2?0:1),"collision does not imply opaque occlusion");}finally{GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);}
        }
    }
    static ByteBuffer geometryPacket(int n,int terrain){
        var data=packet(n,terrain,1,7,0);var random=new Random(6553612288L);
        for(int i=0;i<n+terrain;i++){int at=128+i*64;for(int k=0;k<3;k++)data.putFloat(at+k*4,random.nextFloat()*12-6);
            if(i<n){for(int k=0;k<3;k++)data.putFloat(at+16+k*4,random.nextFloat()*4-2);data.putFloat(at+28,16);for(int k=0;k<3;k++)data.putFloat(at+48+k*4,random.nextFloat()*16-8);}
            else{for(int k=0;k<3;k++)data.putFloat(at+16+k*4,.3f+random.nextFloat());data.putInt(at+44,1);}
        }
        return data;
    }
    static double clearanceReference(ByteBuffer data,int n,int terrain,int own,int probes){
        int at=128+own*64;double[] forward=new double[3];double speed=0;
        for(int k=0;k<3;k++){forward[k]=data.getFloat(at+48+k*4);speed+=forward[k]*forward[k];}speed=Math.sqrt(speed);
        if(speed<.01)return 0;for(int k=0;k<3;k++)forward[k]/=speed;
        double[] axis=Math.abs(forward[1])<.9?new double[]{0,1,0}:new double[]{1,0,0};
        double[] right={forward[1]*axis[2]-forward[2]*axis[1],forward[2]*axis[0]-forward[0]*axis[2],forward[0]*axis[1]-forward[1]*axis[0]};
        double norm=Math.sqrt(right[0]*right[0]+right[1]*right[1]+right[2]*right[2]);for(int k=0;k<3;k++)right[k]/=norm;
        double[] up={right[1]*forward[2]-right[2]*forward[1],right[2]*forward[0]-right[0]*forward[2],right[0]*forward[1]-right[1]*forward[0]};
        double reach=Math.max(.05,Math.min(speed,data.getFloat(at+28))*.1+.34),nearest=reach;double[] cs={1,-.5,-.5},sn={0,.866025404,-.866025404};
        for(int ray=0;ray<probes;ray++){int side=probes==2?1:ray-1;double[] dir=new double[3];for(int k=0;k<3;k++)dir[k]=ray==0?forward[k]:forward[k]*.906307787+(right[k]*cs[side]+up[k]*sn[side])*.422618262;
            for(int row=0;row<n+terrain;row++){if(row==own)continue;int b=128+row*64;double entry=-Double.MAX_VALUE,exit=reach;
                for(int k=0;k<3;k++){double center=data.getFloat(b+k*4)+(row<n?data.getFloat(b+16+k*4)*.05:0),extent=row<n?data.getFloat(b+12)+Math.abs(data.getFloat(b+16+k*4))*.05:data.getFloat(b+16+k*4),origin=data.getFloat(at+k*4);
                    if(Math.abs(dir[k])<1e-8){if(origin<center-extent||origin>center+extent){exit=-1;break;}}
                    else{double a=(center-extent-origin)/dir[k],z=(center+extent-origin)/dir[k];entry=Math.max(entry,Math.min(a,z));exit=Math.min(exit,Math.max(a,z));}
                }if(entry>=0&&exit>=entry&&entry<=reach)nearest=Math.min(nearest,entry);
            }
        }return nearest;
    }
    static void checkGeometry(ByteBuffer data,ByteBuffer result,int n,int terrain,int probes,String backend){
        check(result.getInt(44)==0&&result.getInt(72)==1,backend+" valid geometry descriptor");int known=0,hits=0;
        for(int i=0;i<n;i++){int at=128+i*64;if(result.getInt(at+28)==0){check(result.getFloat(at+12)<0,"unknown cannot masquerade as clear");continue;}
            known++;double expected=clearanceReference(data,n,terrain,i,probes);check(Math.abs(result.getFloat(at+12)-expected)<1e-4,backend+" FP64 nearest distance member="+i+" actual="+result.getFloat(at+12)+" expected="+expected);if(expected<.34)hits++;
        }check(known>n*.9,backend+" bounded query coverage");check(hits>0,backend+" near-hit geometry exercised");
    }
    static void geometry(List<Object> cases,boolean nativeEnabled)throws Exception{
        int n=257,terrain=19;
        for(int probes:new int[]{2,4}){
            var data=geometryPacket(n,terrain);data.putInt(76,probes);int input=buffer(data),output=buffer(bytes(data.capacity()));
            try(var index=new GlSpatialIndex(n,terrain,source("spatial/bvh.comp"))){index.query(input,output,1,91,7,91,probes,0,0,0,0);checkGeometry(data,read(output,data.capacity()),n,terrain,probes,"GL");}
            finally{GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);}
            if(nativeEnabled){var result=nativeBatch(data,n,terrain,probes);checkGeometry(data,result,n,terrain,probes,"RT");}
            cases.add(Map.of("case","independent FP64 box/probe geometry","probes",probes,"members",n,"native",nativeEnabled,"passed",true));
        }
        if(nativeEnabled){n=65536;var data=packet(n,0,1,7,0);var result=nativeBatch(data,n,0,2);check(result.getLong(8)==1&&result.getInt(44)==0&&result.getInt(72)==1,"65,536-member native IAS completion");check(result.getInt(128+(n-1)*64+28)==1,"last stable member published");cases.add(Map.of("case","RT 65,536 members","passed",true));}
    }
    static ByteBuffer nativeBatch(ByteBuffer data,int n,int terrain,int probes)throws Exception{
        SpatialNative.load(Path.of(".gradle/particle-rt/build"));long bridge=SpatialNative.createBuffers(data.capacity(),3),worker=0;boolean done=false;
        try{worker=SpatialNative.create(bridge,n,terrain,probes,91,Path.of(".gradle/particle-rt/build").toAbsolutePath().toString());int[] buffers=SpatialNative.buffers(bridge,0);GL45.glNamedBufferSubData(buffers[0],0,data);check(SpatialNative.submit(worker,0,1,7,91,data.getInt(80)),"native geometry submit");long end=System.nanoTime()+15_000_000_000L;while(SpatialNative.poll(worker,0,1)==1){check(System.nanoTime()<end,"native geometry timeout");Thread.sleep(1);}check(SpatialNative.poll(worker,0,1)==2,SpatialNative.diagnostics(worker));SpatialNative.consume(worker,0,1);var result=read(buffers[1],data.capacity());long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);while(!SpatialNative.recycle(worker,0,1,fence)){check(System.nanoTime()<end,"native geometry consumer retirement");Thread.sleep(1);}GL32.glDeleteSync(fence);done=true;return result;
        }finally{if(worker!=0){long end=System.nanoTime()+15_000_000_000L;while(!SpatialNative.close(worker)){if(System.nanoTime()>=end)throw new IllegalStateException("Native geometry worker did not retire; bridge retained");Thread.sleep(1);}}SpatialNative.closeBuffers(bridge);check(SpatialNative.ownedBytes()==0,"geometry worker allocations retired");}
    }
    static void localCorrection(List<Object> cases){
        int n=65536,total=146000,p=program("spatial/motion.comp");var state=bytes(n*64);var physical=bytes(total*64);var metadata=bytes(n*16);
        for(int i=0;i<n;i++){int pair=i/2,at=i*64;float x=3+(pair%32)*1.25f+(i%2)*.323f,y=3+((pair/32)%32)*1.25f,z=3+(pair/1024)*1.25f;
            state.putFloat(at,x).putFloat(at+4,y).putFloat(at+8,z).putFloat(at+12,.34f).putFloat(at+28,32).putInt(at+32,i).putInt(at+36,100+i).putInt(at+44,1|(i<12288?0x80000000:0));
            physical.putFloat(at,x).putFloat(at+4,y).putFloat(at+8,z);metadata.putFloat(i*16,x).putFloat(i*16+4,y).putFloat(i*16+8,z).putInt(i*16+12,i+1);
        }
        int rows=buffer(state),pool=buffer(physical),meta=buffer(metadata),scratch=buffer(bytes(n*64)),grid=buffer(bytes((262144+n)*4)),counter=buffer(bytes(16).putInt(0,total));int rowTexture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER),metaTexture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
        try(var collision=new com.iridium126.createmanaindustry.client.allaystorm.StormCollisionGpu()){
            GL45.glTextureBuffer(rowTexture,GL30.GL_RGBA32UI,rows);GL45.glTextureBuffer(metaTexture,GL30.GL_RGBA32UI,meta);GL42.glBindImageTexture(4,rowTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);GL42.glBindImageTexture(5,metaTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);
            var pages=new LinkedHashMap<com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Section,com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Page>();var air=com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.page(1,new int[128]);
            for(int x=-1;x<=4;x++)for(int y=-1;y<=4;y++)for(int z=-1;z<=4;z++)pages.put(new com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Section(x,y,z),air);
            collision.offer(pages);for(int attempt=0;attempt<1000&&!collision.settled();attempt++){collision.pump(131072,50_000_000);collision.bind(p);collision.submitted();GL11.glFlush();}check(collision.settled(),"local fixture terrain coverage");collision.bind(p);GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uStormCollisionEnabled"),1);
            bind(1,pool);bind(3,counter);bind(7,scratch);bind(9,grid);ui(p,"capacity",n);ui(p,"visitsLimit",32);ui(p,"nearestLimit",4);
            for(int step=0;step<2;step++){phase(p,4,262144);phase(p,5,n);bind(6,rows);phase(p,6,n);bind(6,scratch);phase(p,7,n);}
            phase(p,4,262144);phase(p,5,n);phase(p,8,n);var result=read(rows,n*64);
            for(int i=0;i<n;i++){int at=i*64;double distance=0;for(int k=0;k<3;k++){double d=result.getFloat(at+k*4)-state.getFloat(at+k*4);distance+=d*d;}check(Math.sqrt(distance)<=.0601,"two bounded Jacobi corrections");if(i<12288)check(distance==0,"analytic Dome path not modified by ordinary correction");else if(i%2==0)check(Math.abs(result.getFloat(at)-result.getFloat(at+64))>=.34-1e-4,"5 percent overlap resolved within two steps");}
            // Pathological pile: budget exhaustion stays explicit, and work does not grow.
            for(int i=0;i<512;i++){int at=i*64;state.putFloat(at,5).putFloat(at+4,5).putFloat(at+8,5).putInt(at+44,1);metadata.putFloat(i*16,5).putFloat(i*16+4,5).putFloat(i*16+8,5).putInt(i*16+12,i+1);}
            GL45.glNamedBufferSubData(rows,0,state);GL45.glNamedBufferSubData(meta,0,metadata);phase(p,4,262144);phase(p,5,n);bind(6,rows);phase(p,6,n);bind(6,scratch);phase(p,7,n);var flags=read(meta,n*16);check((flags.getInt(12)&0x20000000)!=0,"dense overflow explicitly unresolved");
            result=read(rows,n*64);for(int i=0;i<512;i++){double d=0;for(int k=0;k<3;k++){double x=result.getFloat(i*64+k*4)-5;d+=x*x;}check(Math.sqrt(d)<=.0301,"pathological pile bounded correction");}
            cases.add(Map.of("case","bounded local correction and analytic Dome isolation","members",n,"particles",total,"dome_members",12288,"passed",true));
        }finally{GL11.glDeleteTextures(rowTexture);GL11.glDeleteTextures(metaTexture);for(int b:new int[]{rows,pool,meta,scratch,grid,counter})GL15.glDeleteBuffers(b);GL20.glDeleteProgram(p);}
    }
    static void nativeQueries(List<Object> cases)throws Exception{
        Path modules=Path.of(".gradle/particle-rt/build");SpatialNative.load(modules);check(SpatialNative.abi()==12,"ABI 12");long bridge=0,worker=0;int n=257;long[] tickets=new long[3];
        try{
            bridge=SpatialNative.createBuffers(128+n*64,3);worker=SpatialNative.create(bridge,n,0,2,91,modules.toAbsolutePath().toString());
            for(int frame=0;frame<8;frame++){
                if(frame>=2&&frame<=4){
                    long deadline=System.nanoTime()+15_000_000_000L;
                    while(!SpatialNative.close(worker)){check(System.nanoTime()<deadline,"Faulted query session retirement");Thread.sleep(1);}
                    worker=SpatialNative.create(bridge,n,0,2,91,modules.toAbsolutePath().toString());
                }
                int slot=frame%3;long nonce=frame+1;int[] b=SpatialNative.buffers(bridge,slot);var data=packet(n,0,nonce,frame,0);if(frame==1)data.putLong(16,92);if(frame==2)data.putInt(72,0);if(frame==3)data.putFloat(128,Float.NaN);
                GL45.glNamedBufferSubData(b[0],0,data);if(frame==3)check(Float.isNaN(read(b[0],data.capacity()).getFloat(128)),"NaN uploaded");if(frame>=4&&frame<=6)SpatialNative.delay(worker,new int[]{10,50,500}[frame-4]);long start=System.nanoTime();check(SpatialNative.submit(worker,slot,nonce,frame,91,0),"submit");double submit=(System.nanoTime()-start)/1e6;
                tickets[slot]=nonce;check(!SpatialNative.submit(worker,slot,nonce+1000,frame,91,0),"no unretired slot reuse");long until=System.nanoTime()+15_000_000_000L;while(SpatialNative.poll(worker,slot,nonce)==1){check(System.nanoTime()<until,"bounded native completion");Thread.sleep(1);}check(SpatialNative.poll(worker,slot,nonce)==2,SpatialNative.diagnostics(worker));
                SpatialNative.consume(worker,slot,nonce);var result=read(b[1],data.capacity());check(result.getLong(8)==nonce,"native output belongs to this submission");check((result.getInt(44)!=0)==(frame>=1&&frame<=3),"whole-frame GPU rejection frame="+frame+" nonce="+result.getLong(8)+" error="+result.getInt(44)+" pos="+result.getFloat(160)+" id="+result.getInt(156));if(frame==0)check(result.getFloat(128)<0,"real precompiled OptiX nearest hit");
                long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);while(!SpatialNative.recycle(worker,slot,nonce,fence)){check(System.nanoTime()<until,"GL consumer retirement");Thread.sleep(1);}GL32.glDeleteSync(fence);tickets[slot]=0;
                if(frame>=1&&frame<=3)check(!SpatialNative.submit(worker,slot,nonce+1000,frame+1,91,0),"Faulted query session refuses new work");
                cases.add(Map.of("case","RT ownership/fault","frame",frame,"submit_ms",submit,"wall_ms",(System.nanoTime()-start)/1e6,"passed",true));
            }
        }catch(Throwable failure){failure.printStackTrace();throw failure;}finally{if(worker!=0){GL11.glFinish();for(int i=0;i<3;i++)if(tickets[i]!=0){int state=SpatialNative.poll(worker,i,tickets[i]);if(state==5){long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);while(!SpatialNative.recycle(worker,i,tickets[i],fence))Thread.sleep(1);GL32.glDeleteSync(fence);}else if(state==2){SpatialNative.consume(worker,i,tickets[i]);long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);while(!SpatialNative.recycle(worker,i,tickets[i],fence))Thread.sleep(1);GL32.glDeleteSync(fence);}}long end=System.nanoTime()+15_000_000_000L;while(!SpatialNative.close(worker)){check(System.nanoTime()<end,"worker retirement");Thread.sleep(1);}}if(bridge!=0)SpatialNative.closeBuffers(bridge);}
        check(SpatialNative.ownedBytes()==0,"all native allocations retired");
    }
    static void nativeWorkerQueue(List<Object> cases)throws Exception{
        Path modules=Path.of(".gradle/particle-rt/build");int n=65;long until=System.nanoTime()+15_000_000_000L;
        var worker=new RtQueryWorker(n,0,2,91,modules);
        try{
            var field=RtQueryWorker.class.getDeclaredField("worker");field.setAccessible(true);SpatialNative.delay(field.getLong(worker),500);
            for(int i=0;i<3;i++){
                var slot=worker.acquire(7+i,91,0);check(slot!=null,"worker export slot");GL45.glNamedBufferSubData(slot.input,0,packet(n,0,slot.nonce,7+i,0));check(worker.submit(slot,7+i,91,0),"worker submit");
            }
            check(worker.acquire(10,91,0)==null,"worker backpressure preserves all three slots");
            for(int i=0;i<3;i++){
                RtQueryWorker.Slot slot;
                while((slot=worker.take(1000,91,0))==null){check(System.nanoTime()<until,"late worker completion");Thread.sleep(1);}
                check(slot.nonce==i+1,"oldest result consumed even 990 ticks late");worker.finishConsumers();
            }
            var destroyed=worker.acquire(11,91,0);while(destroyed==null){check(System.nanoTime()<until,"worker retirement");Thread.sleep(1);destroyed=worker.acquire(11,91,0);}
            GL45.glNamedBufferSubData(destroyed.input,0,packet(n,0,destroyed.nonce,11,0));check(worker.submit(destroyed,11,91,0),"destroyed identity submit");
            while(worker.counts().consumed()<4){check(worker.take(1001,92,0)==null,"destroyed identity cannot publish into replacement");check(System.nanoTime()<until,"destroyed identity receipt");Thread.sleep(1);}
            var invalid=worker.acquire(12,92,0);while(invalid==null){check(System.nanoTime()<until,"error export slot");Thread.sleep(1);invalid=worker.acquire(12,92,0);}
            var data=packet(n,0,invalid.nonce,12,0).putLong(64,92).putFloat(128,Float.NaN);
            GL45.glNamedBufferSubData(invalid.input,0,data);check(worker.submit(invalid,12,92,0),"invalid producer input submitted for fault injection");
            while(worker.counts().consumed()<5){check(worker.take(1002,92,0)==null,"invalid output cannot publish");check(System.nanoTime()<until,"error receipt");Thread.sleep(1);}
            check(worker.failed()&&worker.acquire(13,92,0)==null,"RT error stops submissions");
            check(worker.counts().submitted()==5&&worker.counts().consumed()==5,"every worker submission consumed exactly once");
        }finally{worker.close();while(RtQueryWorker.resourcesRetiring()){check(System.nanoTime()<until,"worker close retirement");RtQueryWorker.retire();Thread.sleep(1);}}
        check(worker.counts().retired()==5&&SpatialNative.ownedBytes()==0,"worker slots retired without GL recomputation or reuse");
        cases.add(Map.of("case","production worker FIFO, 500ms delay, destroyed identity and error consumption","passed",true));
    }
    static void nativeRing(List<Object> cases)throws Exception{
        int n=65;Path modules=Path.of(".gradle/particle-rt/build");long bridge=SpatialNative.createBuffers(128+n*64,3),worker=0;
        try{worker=SpatialNative.create(bridge,n,0,2,91,modules.toAbsolutePath().toString());SpatialNative.delay(worker,500);
            for(int i=0;i<3;i++){var b=SpatialNative.buffers(bridge,i);GL45.glNamedBufferSubData(b[0],0,packet(n,0,i+1,i,0));check(SpatialNative.submit(worker,i,i+1,i,91,0),"ring slot submit");}
            long start=System.nanoTime();check(!SpatialNative.submit(worker,0,99,3,91,0),"full ring refuses reuse");check(!SpatialNative.close(worker),"reload retains pending resource ownership");check((System.nanoTime()-start)/1e6<50,"pending shutdown never waits for 500ms work");
            long end=System.nanoTime()+15_000_000_000L;for(int i=0;i<3;i++){while(SpatialNative.poll(worker,i,i+1)==1){check(System.nanoTime()<end,"ring retirement deadline");Thread.sleep(1);}check(SpatialNative.poll(worker,i,i+1)==2,"late task completed without publication");SpatialNative.consume(worker,i,i+1);long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                while(!SpatialNative.recycle(worker,i,i+1,fence)){check(System.nanoTime()<end,"late consumer retirement");Thread.sleep(1);}GL32.glDeleteSync(fence);}
            cases.add(Map.of("case","full ring and reload during 500ms delay","passed",true));
        }finally{if(worker!=0){long end=System.nanoTime()+15_000_000_000L;while(!SpatialNative.close(worker)){check(System.nanoTime()<end,"reload retirement");Thread.sleep(1);}}SpatialNative.closeBuffers(bridge);}
        check(SpatialNative.ownedBytes()==0,"ring retired after reload");
    }
    static java.lang.reflect.Method stormMethod(String name,Class<?>... types)throws Exception{
        var method=SpatialNative.class.getDeclaredMethod(name,types);
        method.setAccessible(true);
        return method;
    }
    static ByteBuffer stormTick(long sequence,long tick,long gameTime,long identity,boolean active,boolean birth)throws Exception{
        return stormTick(sequence,tick,gameTime,identity,active?1:0,birth);
    }
    static ByteBuffer stormTick(long sequence,long tick,long gameTime,long identity,int population,boolean birth)throws Exception{
        String journal="com.iridium126.createmanaindustry.client.particles.engine.spatial.StormControlJournal$";
        var params=Class.forName(journal+"Parameters").getDeclaredConstructors()[0];params.setAccessible(true);
        var p=params.newInstance(identity,population>0,population,19,0f,64f,0f,8f,sequence*.1f,0f,0f,1f,
                com.iridium126.createmanaindustry.content.allaystorm.StarfallColumnRules.renderSeconds(gameTime,0),6f,false);
        var target=Class.forName(journal+"Target").getDeclaredField("ABSENT");target.setAccessible(true);
        var spawn=Class.forName(journal+"Command").getDeclaredMethod("spawn",int.class,int.class,boolean.class);spawn.setAccessible(true);
        var type=Class.forName(journal+"Tick");var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var input=ctor.newInstance(sequence,tick,gameTime,p,birth?List.of(spawn.invoke(null,0,population,false)):List.of(),
                null,target.get(null),0L,List.of(),false,null,List.of(),false);
        var encode=type.getDeclaredMethod("encode",ByteBuffer.class);encode.setAccessible(true);
        var data=bytes(STORM_TICK_HEADER_BYTES+(birth?STORM_CONTROL_BYTES:0));encode.invoke(input,data);data.clear();
        return data;
    }
    static void nativeStormTimeline(List<Object> cases)throws Exception{
        SpatialNative.load(Path.of(".gradle/particle-rt/build"));
        var createBridge=stormMethod("createStormDisplayBuffers",long.class,int.class);
        var create=stormMethod("createStormControl",int.class,long.class,int.class);
        var delay=stormMethod("delayStormControl",long.class,int.class);
        var submit=stormMethod("submitStormTick",long.class,ByteBuffer.class,int.class,int.class,boolean.class);
        var poll=stormMethod("pollStormTick",long.class,long.class);
        var consume=stormMethod("consumeStormTick",long.class,long.class);
        var recycle=stormMethod("recycleStormDisplay",long.class,int.class,long.class,long.class);
        var close=stormMethod("closeStormControl",long.class);
        long bridge=(long)createBridge.invoke(null,STORM_DISPLAY_HEADER_BYTES+8L*STORM_DISPLAY_RECORD_BYTES+
                (long)STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES,RT_SLOTS);
        long worker=0,submitted=0,consumed=0;
        long until=System.nanoTime()+15_000_000_000L;
        try{
            worker=(long)create.invoke(null,8,bridge,2);
            long[] worldTimes={(1L<<21)-1,(1L<<21)+3,(1L<<21)+1};
            for(int slot=0;slot<RT_SLOTS;slot++){
                delay.invoke(null,worker,new int[]{10,50,500}[slot]);
                var packet=stormTick(slot+1,100+slot,worldTimes[slot],slot<2?1:2,slot<2?8:0,slot==0);
                check((boolean)submit.invoke(null,worker,packet,packet.capacity(),slot,true),"Storm control slot submitted");
                submitted++;
            }
            var replacement=stormTick(4,120,8,3,true,true);
            check(!(boolean)submit.invoke(null,worker,replacement,replacement.capacity(),0,false),"Full Storm ring retains replacement tick");
            check(!(boolean)close.invoke(null,worker),"Storm reload leaves outstanding receipts intact");
            float[] firstPosition=new float[3];
            for(int slot=0;slot<RT_SLOTS;slot++){
                long sequence=slot+1L;
                while((int)poll.invoke(null,worker,sequence)==1){check(System.nanoTime()<until,"Storm completion deadline");Thread.sleep(1);}
                check((int)consume.invoke(null,worker,sequence)==2,"Storm receipt applied once");
                consumed++;
                var output=read(SpatialNative.buffers(bridge,slot)[1],STORM_DISPLAY_HEADER_BYTES+8*STORM_DISPLAY_RECORD_BYTES);
                check(output.getLong(16)==sequence&&output.getLong(24)==100+slot
                        &&output.getLong(32)==(slot<2?1:2)&&output.getInt(8)==(slot<2?8:0),"Storm display publication identity and production packet population");
                int member=STORM_DISPLAY_HEADER_BYTES;
                while(member<output.capacity()&&output.getInt(member+64)!=0)member+=STORM_DISPLAY_RECORD_BYTES;
                check(slot==2||member<output.capacity(),"Native publication includes member zero");
                if(slot==0)for(int axis=0;axis<3;axis++)firstPosition[axis]=output.getFloat(member+axis*Float.BYTES);
                if(slot==1){
                    float displacement=0;
                    for(int axis=0;axis<3;axis++){
                        float delta=output.getFloat(member+axis*Float.BYTES)-firstPosition[axis];
                        displacement+=delta*delta;
                    }
                    check(displacement>1e-8f&&displacement<.35f*.35f,"World clock correction preserves one step of member motion");
                }
                long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                try{while(!(boolean)recycle.invoke(null,worker,slot,sequence,fence)){
                    check(System.nanoTime()<until,"Storm display retirement deadline");Thread.sleep(1);
                }}finally{GL32.glDeleteSync(fence);}
            }
            var malformed=stormTick(4,100,8,3,true,true);
            boolean rejected=false;
            try{submit.invoke(null,worker,malformed,malformed.capacity(),-1,false);}
            catch(java.lang.reflect.InvocationTargetException failure){rejected=failure.getCause() instanceof RuntimeException;}
            check(rejected,"Out-of-order Storm tick rejected before claiming a slot");
            check((boolean)submit.invoke(null,worker,replacement,replacement.capacity(),-1,false),"New Storm after inactive time gap");
            submitted++;
            while((int)poll.invoke(null,worker,4L)==1){check(System.nanoTime()<until,"Replacement Storm completion");Thread.sleep(1);}
            check((int)consume.invoke(null,worker,4L)==2,"Replacement Storm retains its own receipt");
            consumed++;
            cases.add(Map.of("case","Native Storm tick and display lifecycle","delays_ms",List.of(10,50,500),"consumed",consumed,"passed",true));
        }finally{
            if(worker!=0&&submitted==consumed){
                while(!(boolean)close.invoke(null,worker)){check(System.nanoTime()<until,"Storm session retirement");Thread.sleep(1);}
            }
            if(submitted==consumed)SpatialNative.closeBuffers(bridge);
        }
        check(SpatialNative.ownedBytes()==0,"Storm session and bridge retired");
    }
    static int stormCommand(ByteBuffer packet,int row,int op,int member,int span,int flags,long scope){
        int at=STORM_TICK_HEADER_BYTES+row*STORM_CONTROL_BYTES;
        packet.putInt(at,op).putInt(at+4,member).putInt(at+8,span).putInt(at+12,flags)
                .putLong(at+16,1).putLong(at+24,scope);
        return at+32;
    }
    static void nativeDomePartialTerrain(List<Object> cases)throws Exception{
        SpatialNative.load(Path.of(".gradle/particle-rt/build"));
        var createBridge=stormMethod("createStormDisplayBuffers",long.class,int.class);
        var create=stormMethod("createStormControl",int.class,long.class,int.class);
        var install=stormMethod("installStormDome",long.class,float[].class,int[].class,int[].class);
        var submit=stormMethod("submitStormTick",long.class,ByteBuffer.class,int.class,int.class,boolean.class);
        var poll=stormMethod("pollStormTick",long.class,long.class);
        var consume=stormMethod("consumeStormTick",long.class,long.class);
        var copy=stormMethod("copyStormDomeBatch",long.class,long.class,ByteBuffer.class);
        var diagnostics=stormMethod("stormControlDiagnostics",long.class);
        var close=stormMethod("closeStormControl",long.class);
        var records=new float[STORM_DOME_MEMBERS*STORM_DOME_RECORD_WORDS];var flows=new int[STORM_DOME_MEMBERS];
        for(int i=0;i<records.length;i++)records[i]=DomeTemplate.word(4+i);
        for(int i=0;i<flows.length;i++)flows[i]=DomeTemplate.flowIndex(i);
        int peer=DomeTemplate.ringCount(0)/2;
        check(peer>0&&flows[0]==flows[peer],"Dome terrain test uses two ranks in one flow");
        var hit=DomeTemplate.gatherPoint(0,0).add(0,63.7,0);
        int x=(int)Math.floor(hit.x),y=(int)Math.floor(hit.y+.3),z=(int)Math.floor(hit.z);
        var pages=new HashMap<StormCollisionPages.Section,Integer>();
        for(int sx=-4;sx<=4;sx++)for(int sy=0;sy<9;sy++)for(int sz=-4;sz<=4;sz++)
            pages.put(new StormCollisionPages.Section(sx,sy,sz),StormCollisionPages.AIR);
        pages.put(new StormCollisionPages.Section(x>>4,y>>4,z>>4),0);
        int[] terrain=StormCollisionPages.table(pages);
        int bit=(x&15)|((z&15)<<4)|((y&15)<<8);
        long bridge=(long)createBridge.invoke(null,STORM_DISPLAY_HEADER_BYTES+2L*STORM_DISPLAY_RECORD_BYTES+
                (long)STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES,RT_SLOTS);
        long worker=(long)create.invoke(null,2,bridge,2),until=System.nanoTime()+25_000_000_000L;
        boolean retired=false,ready=false,paused=false;
        try{
            install.invoke(null,worker,records,DomeTemplate.keys(),flows);
            var batch=bytes(STORM_DOME_BATCH_BYTES);
            for(long seq=1;seq<=320&&!(paused&&retired&&ready);seq++){
                int commands=seq==1?4:0;
                int terrainBytes=seq==1?terrain.length*4+STORM_TERRAIN_PAGE_BYTES:0;
                var input=bytes(STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES+terrainBytes);
                input.put(0,stormTick(seq,seq,100+seq,1,2,false),0,STORM_TICK_HEADER_BYTES);
                input.putInt(8,commands).putInt(40,3).putFloat(64,0);
                if(seq==1){
                    input.putInt(88,terrain.length/4).putInt(92,1);
                    stormCommand(input,0,STORM_CONTROL_SPAWN,0,2,0,0);
                    int at=stormCommand(input,1,STORM_CONTROL_DOME_STATE,0,1,STORM_DOME_CONTROL_CREATE|1,79);
                    input.putFloat(at+4,64).putFloat(at+16,input.getFloat(80)).putFloat(at+20,20)
                            .putFloat(at+24,80).putFloat(at+28,32);
                    stormCommand(input,2,STORM_CONTROL_DOME_ASSIGN,0,1,0,79);
                    stormCommand(input,3,STORM_CONTROL_DOME_ASSIGN,1,1,peer,79);
                    int base=STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES;
                    input.asIntBuffer().put(base/4,terrain);
                    input.putInt(base+terrain.length*4+(bit>>>5)*4,1<<(bit&31));
                }
                check((boolean)submit.invoke(null,worker,input,input.capacity(),-1,false),"partial Dome tick queued");
                int status;
                while((status=(int)poll.invoke(null,worker,seq))==1){check(System.nanoTime()<until,"partial Dome completion deadline");Thread.sleep(1);}
                boolean copied=status==2&&(boolean)copy.invoke(null,worker,seq,batch);
                consume.invoke(null,worker,seq);
                check(copied,"partial Dome tick failed "+diagnostics.invoke(null,worker));
                paused|=Float.isFinite(batch.getFloat(STORM_DOME_PAUSED_OFFSET));
                retired|=(batch.getInt(STORM_DOME_COMPLETED_OFFSET)&1)!=0;
                ready|=(batch.getInt(STORM_DOME_READY_OFFSET+(peer>>>5)*4)&(1<<(peer&31)))!=0;
            }
            check(paused&&retired&&ready,"partial Dome should pause the flow, release the blocked rank and ready the clear rank");
        }finally{
            while(!(boolean)close.invoke(null,worker)){check(System.nanoTime()<until,"partial Dome worker retirement");Thread.sleep(1);}
            SpatialNative.closeBuffers(bridge);
        }
        cases.add(Map.of("case","Native Dome shares a paused flow between blocked and ready ranks","passed",true));
    }
    static void nativeFeatherImpact(List<Object> cases)throws Exception{
        SpatialNative.load(Path.of(".gradle/particle-rt/build"));
        var createBridge=stormMethod("createStormDisplayBuffers",long.class,int.class);
        var create=stormMethod("createStormControl",int.class,long.class,int.class);
        var submit=stormMethod("submitStormTick",long.class,ByteBuffer.class,int.class,int.class,boolean.class);
        var poll=stormMethod("pollStormTick",long.class,long.class);
        var consume=stormMethod("consumeStormTick",long.class,long.class);
        var copy=stormMethod("copyStormFeatherImpacts",long.class,long.class,ByteBuffer.class);
        var diagnostics=stormMethod("stormControlDiagnostics",long.class);
        var close=stormMethod("closeStormControl",long.class);
        long bridge=(long)createBridge.invoke(null,STORM_DISPLAY_HEADER_BYTES+8L*STORM_DISPLAY_RECORD_BYTES+
                (long)STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES,RT_SLOTS);
        long worker=(long)create.invoke(null,8,bridge,2),until=System.nanoTime()+15_000_000_000L;
        try{
            float contactX=0,contactZ=0;
            for(long sequence=1;sequence<=5;sequence++){
                int terrainRows=sequence==1?32:0;
                int commands=sequence==4?3:sequence==5?0:1;
                var input=bytes(STORM_TICK_HEADER_BYTES+commands*STORM_CONTROL_BYTES+
                        terrainRows*STORM_TERRAIN_ROW_BYTES+(terrainRows==0?0:STORM_TERRAIN_PAGE_BYTES));
                input.put(0,stormTick(sequence,sequence+100,sequence+100,1,true,false),0,STORM_TICK_HEADER_BYTES);
                input.putInt(8,commands).putFloat(52,1.03f).putInt(88,terrainRows).putInt(92,terrainRows==0?0:1).putInt(156,sequence==3?0:1);
                if(sequence==1){
                    stormCommand(input,0,STORM_CONTROL_SPAWN,0,1,1,0);
                    int base=STORM_TICK_HEADER_BYTES+STORM_CONTROL_BYTES;
                    for(int i=0;i<terrainRows;i++)input.putInt(base+i*STORM_TERRAIN_ROW_BYTES+12,-1);
                    for(int x=7;x<=8;x++)for(int z=-1;z<=0;z++){
                        int h=x*0x8da6b343^z*0xcb1ab31f,index=(h^(h>>>16))&(terrainRows-1);
                        while(input.getInt(base+index*16+12)!=-1)index=(index+1)&(terrainRows-1);
                        input.putInt(base+index*16,x).putInt(base+index*16+8,z).putInt(base+index*16+12,0);
                    }
                    for(int i=0;i<8;i++)input.putInt(base+terrainRows*16+i*4,-1);
                }else if(sequence<=3){
                    int at=stormCommand(input,0,STORM_CONTROL_CORRECTION,0,1,0,0);
                    input.putFloat(at,128).putFloat(at+4,-2).putFloat(at+12,input.getFloat(80))
                            .putFloat(at+20,-12).putFloat(at+28,3);
                }else if(sequence==4){
                    // The forced downward correction was only for the model collision fixture.
                    stormCommand(input,0,STORM_CONTROL_CORRECTION,0,1,0,0);
                    long column=91;
                    int at=stormCommand(input,1,STORM_CONTROL_COLUMN_STATE,0,1,STORM_DOME_CONTROL_CREATE|1,column);
                    input.putFloat(at,contactX).putFloat(at+4,1).putFloat(at+8,contactZ).putFloat(at+12,7)
                            .putFloat(at+16,input.getFloat(80)-.1f).putFloat(at+20,.05f).putFloat(at+24,1).putFloat(at+28,5);
                    stormCommand(input,2,STORM_CONTROL_COLUMN_ASSIGN,0,1,0,column);
                }
                check((boolean)submit.invoke(null,worker,input,input.capacity(),-1,false),"native feather tick queued");
                int status;
                while((status=(int)poll.invoke(null,worker,sequence))==1){check(System.nanoTime()<until,"native feather receipt deadline");Thread.sleep(1);}
                if(status!=2){
                    String detail=(String)diagnostics.invoke(null,worker);
                    consume.invoke(null,worker,sequence);
                    throw new AssertionError("native feather receipt at "+sequence+": "+status+" "+detail);
                }
                var rows=bytes(64);
                int count=(int)copy.invoke(null,worker,sequence,rows);
                check((int)consume.invoke(null,worker,sequence)==2,"native feather tick consumed");
                check(count==(sequence==2||sequence==4?1:0),"only enabled downward or first column bloom impact reaches feather receipt at "+sequence+" count="+count);
                if(sequence==2){
                    check(rows.getFloat(4)>1&&rows.getFloat(4)<1.2f&&rows.getFloat(12)>.5f,"downward native impact is at the floor");
                    contactX=rows.getFloat(0);contactZ=rows.getFloat(8);
                }else if(sequence==4){
                    check(Math.abs(rows.getFloat(0)-contactX)<.35f&&Math.abs(rows.getFloat(8)-contactZ)<.35f
                            &&Math.abs(rows.getFloat(4)-1)<.04f&&rows.getFloat(12)>0,"Starfall bloom reports its actual touchdown");
                }
            }
        }finally{
            while(!(boolean)close.invoke(null,worker)){check(System.nanoTime()<until,"native feather session retired");Thread.sleep(1);}
            SpatialNative.closeBuffers(bridge);
        }
        cases.add(Map.of("case","Native Storm downward impact, first Starfall bloom and disabled feedback","passed",true));
    }
    static void nativeHelixLaunch(List<Object> cases)throws Exception{
        SpatialNative.load(Path.of(".gradle/particle-rt/build"));
        var createBridge=stormMethod("createStormDisplayBuffers",long.class,int.class);
        var create=stormMethod("createStormControl",int.class,long.class,int.class);
        var submit=stormMethod("submitStormTick",long.class,ByteBuffer.class,int.class,int.class,boolean.class);
        var prepare=stormMethod("prepareStormDisplaySlot",long.class,int.class);
        var poll=stormMethod("pollStormTick",long.class,long.class);
        var consume=stormMethod("consumeStormTick",long.class,long.class);
        var diagnostics=stormMethod("stormControlDiagnostics",long.class);
        var recycle=stormMethod("recycleStormDisplay",long.class,int.class,long.class,long.class);
        var copyBatch=stormMethod("copyStormDomeBatch",long.class,long.class,ByteBuffer.class);
        var close=stormMethod("closeStormControl",long.class);
        int members=STORM_HELIX_MEMBERS,slot=STORM_DOME_SLOTS-1,id=71;
        long scope=((long)slot<<32)|id;
        long bridge=(long)createBridge.invoke(null,STORM_DISPLAY_HEADER_BYTES+(long)members*STORM_DISPLAY_RECORD_BYTES+
                (long)STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES,RT_SLOTS);
        long worker=(long)create.invoke(null,members,bridge,2),until=System.nanoTime()+20_000_000_000L;
        boolean drained=true;
        try{
            float targetX=0,targetY=0,targetZ=0;
            for(long sequence=1;sequence<=5;sequence++){
                int count=sequence==1?1:sequence==2?members+5:sequence==3||sequence==5?1:0;
                int terrainRows=sequence==1?128:0;
                var packet=bytes(STORM_TICK_HEADER_BYTES+count*STORM_CONTROL_BYTES+terrainRows*STORM_TERRAIN_ROW_BYTES);
                packet.put(0,stormTick(sequence,200+sequence,200+sequence,1,true,false),0,STORM_TICK_HEADER_BYTES);
                packet.putInt(8,count).putInt(12,members).putInt(88,terrainRows);
                if(sequence==1){
                    stormCommand(packet,0,STORM_CONTROL_SPAWN,0,members,0,0);
                    int base=STORM_TICK_HEADER_BYTES+count*STORM_CONTROL_BYTES;
                    for(int i=0;i<terrainRows;i++)packet.putInt(base+i*STORM_TERRAIN_ROW_BYTES+12,-1);
                    for(int x=-1;x<=0;x++)for(int y=3;y<=4;y++)for(int z=-1;z<=0;z++){
                        int h=x*0x8da6b343^y*0xd8163841^z*0xcb1ab31f,index=(h^(h>>>16))&(terrainRows-1);
                        while(packet.getInt(base+index*STORM_TERRAIN_ROW_BYTES+12)!=-1)index=(index+1)&(terrainRows-1);
                        int at=base+index*STORM_TERRAIN_ROW_BYTES;
                        packet.putInt(at,x).putInt(at+4,y).putInt(at+8,z).putInt(at+12,-2);
                    }
                }
                if(sequence==2||sequence==3||sequence==5){
                    int phase=sequence==2?1:sequence==3?2:0;
                    int at=stormCommand(packet,0,STORM_CONTROL_HELIX_STATE,phase==0?0:1,1,
                            phase|(sequence==2?STORM_DOME_CONTROL_CREATE:0),scope);
                    if(phase!=0)packet.putFloat(at,10f).putFloat(at+4,.13f).putFloat(at+8,2f)
                            .putFloat(at+16,30f).putFloat(at+20,32f).putFloat(at+24,64f).putFloat(at+28,1f);
                }
                if(sequence==2){
                    int frame=stormCommand(packet,1,STORM_CONTROL_HELIX_FRAME,0,1,0,scope);
                    packet.putFloat(frame,17f).putFloat(frame+8,.25f).putFloat(frame+12,1f).putFloat(frame+20,64f);
                    stormCommand(packet,2,STORM_CONTROL_HELIX_FRAME,1,1,0,scope);
                    int orientation=stormCommand(packet,3,STORM_CONTROL_HELIX_FRAME,2,1,0,scope);
                    packet.putFloat(orientation+12,1f);
                    stormCommand(packet,4,STORM_CONTROL_HELIX_FRAME,3,1,0,scope);
                    for(int rank=0;rank<members;rank++){
                        int at=stormCommand(packet,5+rank,STORM_CONTROL_HELIX_ASSIGN,rank,1,rank,scope);
                        packet.putFloat(at,(rank%16-8)*.5f).putFloat(at+4,(rank%8-4)*.5f);
                    }
                }
                if(sequence==4){
                    int target=STORM_TICK_TARGET_OFFSET;
                    packet.putFloat(target,targetX-.1f).putFloat(target+4,targetY).putFloat(target+8,targetZ)
                            .putFloat(target+16,targetX+.1f).putFloat(target+20,targetY).putFloat(target+24,targetZ).putInt(target+28,89);
                }
                int display=(int)((sequence-1)%RT_SLOTS);
                while(!(boolean)prepare.invoke(null,worker,display)){
                    check(System.nanoTime()<until,"Helix display reservation deadline");
                    Thread.sleep(1);
                }
                check((boolean)submit.invoke(null,worker,packet,packet.capacity(),display,true),"Helix submission "+sequence);
                drained=false;
                int status;
                while((status=(int)poll.invoke(null,worker,sequence))==1){
                    check(System.nanoTime()<until,"Helix completion deadline "+sequence);Thread.sleep(1);
                }
                check(status==2,"Helix completion "+sequence+": "+diagnostics.invoke(null,worker));
                if(sequence>=2&&sequence<=4){
                    var batch=bytes(STORM_DOME_BATCH_BYTES);
                    check((boolean)copyBatch.invoke(null,worker,sequence,batch),"Helix batch available");
                    int at=STORM_HELIX_REFERENCE_OFFSET+slot*STORM_HELIX_REFERENCE_BYTES;
                    check(batch.getInt(slot*4)==id&&batch.getInt(at)==17&&batch.getInt(at+4)==1,"Helix reference published");
                    int contact=STORM_DOME_CONTACT_OFFSET+slot*STORM_DOME_CONTACT_BYTES;
                    check(batch.getInt(contact)==(sequence==4?89:0),"Helix reduces the moving target contact into its attack batch");
                }
                check((int)consume.invoke(null,worker,sequence)==2,"Helix receipt consumed "+sequence);
                var result=read(SpatialNative.buffers(bridge,display)[1],STORM_DISPLAY_HEADER_BYTES+members*STORM_DISPLAY_RECORD_BYTES);
                check(result.getInt(8)==members&&result.getLong(STORM_DISPLAY_SEQUENCE_OFFSET)==sequence,"Helix display published");
                if(sequence>=2){
                    int phase=sequence==2?1:sequence<=4?3:4;
                    float targetDistance=Float.POSITIVE_INFINITY;
                    for(int row=0;row<members;row++){
                        int record=STORM_DISPLAY_HEADER_BYTES+row*STORM_DISPLAY_RECORD_BYTES;
                        int key=record+STORM_DISPLAY_KEY_OFFSET;
                        int flags=result.getInt(key+12);
                        check(result.getInt(key+4)==id&&((flags>>>STORM_DISPLAY_KIND_SHIFT)&15)==2
                                &&((flags>>>STORM_DISPLAY_PHASE_SHIFT)&15)==phase,"Helix display member phase "+sequence);
                        if(sequence==3&&(flags&15)==0){
                            float x=result.getFloat(record),y=result.getFloat(record+4),z=result.getFloat(record+8);
                            float distance=x*x+(y-64)*(y-64)+z*z;
                            if(distance<targetDistance){targetX=x;targetY=y;targetZ=z;targetDistance=distance;}
                        }
                    }
                    if(sequence==3)check(Float.isFinite(targetDistance),"A thrust member is in known air for the contact fixture");
                }
                long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                try{while(!(boolean)recycle.invoke(null,worker,display,sequence,fence)){
                    check(System.nanoTime()<until,"Helix display retirement deadline");Thread.sleep(1);
                }}finally{GL32.glDeleteSync(fence);}
                drained=true;
            }
        }finally{
            if(drained){
                while(!(boolean)close.invoke(null,worker)){check(System.nanoTime()<until,"Helix session retirement");Thread.sleep(1);}
                SpatialNative.closeBuffers(bridge);
            }
        }
        check(SpatialNative.ownedBytes()==0,"Helix session and bridge retired");
        cases.add(Map.of("case","Native Helix gather, thrust and return", "members",members,"passed",true));
    }
    static void compactPoses(List<Object> cases){
        int p=program("pose_cache.comp");
        int visibility=program("spatial/visibility.comp");
        com.iridium126.createmanaindustry.client.gpu.shader.GpuProgramContract.verify(visibility,"spatial/visibility.comp");
        cameraOpaqueEnvelope(visibility,cases);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuProgramContract.installSamplers(p);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuProgramContract.verify(p,"pose_cache.comp");
        int modelOffset=ParticleBuffers.IDX_CNT_MODELOP*Integer.BYTES;
        int poseBase=POSE_ANIMATION_ROWS*16, stride=POSE_ROWS_PER_INSTANCE*16;
        int pool=buffer(bytes(128).putFloat(12,1).putFloat(52,20).putFloat(76,1).putFloat(116,20));
        int commands=buffer(bytes(ParticleBuffers.INDIRECT_UINTS*4).putInt(modelOffset,1));
        int items=buffer(bytes(16).putInt(0,1<<9));
        int live=buffer(bytes(4).putInt(0,2));
        int dispatch=buffer(bytes(20));
        int emitters=buffer(bytes(ParticleBuffers.VEC4_PER_EMITTER*16));
        int output=buffer(bytes(poseBase+3*stride));
        int animation=buffer(bytes(16*12));
        int texture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
        try{
            GL45.glTextureBuffer(texture,GL30.GL_RGBA32UI,animation);
            GL45.glBindTextureUnit(SAMPLER_ANIMATION_DRAW,texture);
            bind(0,pool);bind(1,commands);bind(2,items);bind(3,live);bind(4,dispatch);bind(5,emitters);bind(POSE_OUTPUT_BINDING,output);
            GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,dispatch);
            GL20.glUseProgram(p);ui(p,"poseStamp",71);
            GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uMeasurePose"),1);
            for(int view=0;view<2;view++){
                if(view==1){
                    GL45.glNamedBufferSubData(commands,modelOffset,bytes(4).putInt(0,2));
                    GL45.glNamedBufferSubData(items,8,bytes(8).putInt(0,2<<9).putInt(4,4));
                    GL45.glNamedBufferSubData(output,poseBase+stride+8,bytes(4).putInt(0,0x12345678));
                }
                GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPreparePoseDispatch"),1);
                GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                GL41.glProgramUniform1i(p,GL20.glGetUniformLocation(p,"uPreparePoseDispatch"),0);
                GL43.glDispatchComputeIndirect(0);
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                var receipt=bytes(2*stride);
                GL45.glGetNamedBufferSubData(output,poseBase+stride,receipt);
                check(receipt.getInt(4)==71&&receipt.getInt(stride+4)==(view==0?0:71),"View list produces only its drawable poses");
                if(view==1)check(receipt.getInt(8)==0x12345678,"Shadow view reused the camera pose");
                var stats=read(dispatch,20);
                check(stats.getInt(12)==view+1&&stats.getInt(16)==1,"Only computed poses contribute to statistics");
            }
            cases.add(Map.of("case","Compact camera and shadow pose lists", "passed",true));
        }finally{
            GL11.glDeleteTextures(texture);
            for(int id:new int[]{pool,commands,items,live,dispatch,emitters,output,animation})GL15.glDeleteBuffers(id);
            GL20.glDeleteProgram(p);GL20.glDeleteProgram(visibility);
        }
    }
    static void cameraOpaqueEnvelope(int program,List<Object> cases){
        int bounds=buffer(bytes(32784)),masks=buffer(bytes(32+1024*48)),packet=buffer(bytes(128+1024*64));
        try{
            bind(1,bounds);bind(3,packet);bind(6,masks);GL20.glUseProgram(program);
            ui(program,"phase",0);GL43.glDispatchCompute(16,1,1);GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);
            var group=bytes(32);
            for(int axis=0;axis<3;axis++)group.putInt(axis*4,~Float.floatToRawIntBits(-.25f))
                    .putInt(16+axis*4,Float.floatToRawIntBits(.25f)^0x80000000);
            group.putInt(12,1);GL45.glNamedBufferSubData(bounds,0,group);
            GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"filterOpaque"),1);
            GL41.glProgramUniform3f(program,GL20.glGetUniformLocation(program,"camera"),0,0,8);
            for(int sample=0;sample<3;sample++){
                boolean light=sample==2;float extent=sample==1?0:20;
                GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"shadowEnabled"),light?1:0);
                GL41.glProgramUniform3f(program,GL20.glGetUniformLocation(program,"opaqueMin"),extent-1,-1,4);
                GL41.glProgramUniform3f(program,GL20.glGetUniformLocation(program,"opaqueMax"),extent+1,1,6);
                GL45.glNamedBufferSubData(bounds,32768,bytes(4));
                ui(program,"phase",2);GL43.glDispatchCompute(16,1,1);
                check(read(bounds,32772).getInt(32768)==(sample==1?1:0),"camera candidate count after opaque extent test");
                check(read(packet,192).getInt(128+44)==(light?257:sample==1?1:0),"light selection is independent from camera coverage");
            }
            cases.add(Map.of("case","Camera proof candidates use opaque coverage; shadow remains independent","passed",true));
        }finally{for(int buffer:new int[]{bounds,masks,packet})GL15.glDeleteBuffers(buffer);}
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(ParticleFixtureOptions.parse(args).getOrDefault("output","build/particle-engine/verify"));Files.createDirectories(out);var result=new LinkedHashMap<String,Object>();var cases=new ArrayList<Object>();result.put("passed",false);
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);long window=GLFW.glfwCreateWindow(32,32,"particle engine driver checks",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            compactPoses(cases);
            if(Arrays.asList(args).contains("--native-helix-only"))nativeHelixLaunch(cases);
            else if(Arrays.asList(args).contains("--storm-timeline-only")){nativeStormTimeline(cases);nativeDomePartialTerrain(cases);nativeFeatherImpact(cases);}
            else{localCorrection(cases);contacts(cases);interpolation(cases);displayPublication(cases);featherPixels(cases);conservativeVisibility(cases,!Arrays.asList(args).contains("--gl-only"));glQueries(cases);geometry(cases,!Arrays.asList(args).contains("--gl-only"));for(String shader:List.of("spatial/motion.comp","update.comp","wavecontact.comp","dome_contacts.comp","dome_contact_control.comp","dome_contact_apply.comp","dome_contact_audit.comp","storm_projection.comp"))GL20.glDeleteProgram(program(shader));if(!Arrays.asList(args).contains("--gl-only")){nativeQueries(cases);nativeRing(cases);nativeWorkerQueue(cases);nativeStormTimeline(cases);nativeHelixLaunch(cases);}}
            if(!Arrays.asList(args).contains("--storm-timeline-only")&&!Arrays.asList(args).contains("--native-helix-only")
                    &&!Arrays.asList(args).contains("--gl-only")){nativeDomePartialTerrain(cases);nativeFeatherImpact(cases);}
            check(GL11.glGetError()==0,"GL state");result.put("passed",true);
        }finally{result.put("checks",checks);result.put("cases",cases);result.put("gpu",GL11.glGetString(GL11.GL_RENDERER));result.put("driver",GL11.glGetString(GL11.GL_VERSION));Files.writeString(out.resolve("gpu.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
