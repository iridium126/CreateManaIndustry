import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.opengl.*;
import org.lwjgl.glfw.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Experimental 16-block layout; GPU packing is a measured prototype, not the production CPU upload owner. */
public class UpdateStorageGpuValidation extends UpdateDomainGpuValidation {
    static String remove(String s,String bind,String alias){String pattern="layout\\(std430,\\s*binding\\s*=\\s*"+bind+"\\)\\s*readonly buffer.*?}\\s*"+alias+"\\s*;";var m=Pattern.compile(pattern,Pattern.DOTALL).matcher(s);if(!m.find())throw new IllegalStateException("Missing "+alias);return m.replaceFirst("");}
    static String array(String s,String alias,String function){String prefix=alias+"[";int start=0;StringBuilder out=new StringBuilder();for(;;){int at=s.indexOf(prefix,start);if(at<0){out.append(s,start,s.length());return out.toString();}out.append(s,start,at).append(function).append('(');int end=at+prefix.length(),depth=1;for(;end<s.length()&&depth>0;end++){char c=s.charAt(end);if(c=='[')depth++;if(c==']')depth--;}if(depth!=0)throw new IllegalArgumentException(alias);out.append(s,at+prefix.length(),end-1).append(')');start=end;}}
    static String candidate(String s){
        s=remove(s,"BIND_BAKEMETA","bakeMeta");s=remove(s,"BIND_PLAYERS","players");s=remove(s,"BIND_DAMAGE","damageQ");
        s=s.replace("bakeMeta.m.length()","int(uFrameCounts.x)").replace("players.pl.length()","int(uFrameCounts.y)").replace("damageQ.entries.length()","int(uFrameCounts.z)");
        s=array(s,"bakeMeta.m","cmiFrameBake");s=array(s,"players.pl","cmiFramePlayer");s=array(s,"damageQ.entries","cmiFrameDamage");s=s.replace("damageQ.header","floatBitsToUint(frameInputs.rows[uFrameOffsets.z])");
        s=s.replace("layout(r32ui,binding=2)coherent uniform uimageBuffer cmi_ModelMembersWrite;","layout(std430,binding=14)buffer CandidateMembers {uint words[];} candidateMembers;");
        s=s.replace("layout(r32ui,binding=3)coherent uniform uimageBuffer cmi_ModelAllocator;","layout(std430,binding=15)coherent buffer ModelAllocator {uint words[];} modelAllocator;");
        s=s.replace("imageAtomicOr(cmi_ModelAllocator,","cmiAllocatorOr(").replace("imageAtomicAnd(cmi_ModelAllocator,","cmiAllocatorAnd(").replace("imageStore(cmi_ModelMembersWrite,","cmiMemberStore(");
        // These are trusted generated declarations, not shader-pack source.
        s=s.replace("binding=27","binding=10").replace("binding=28","binding=11").replace("binding=26","binding=12");
        StringBuilder header=new StringBuilder();String[] macros={"BIND_POOL_READ","BIND_POOL_WRITE","BIND_COUNTER","BIND_EMITTER","BIND_PREV_COUNTER","BIND_GRID","BIND_CORRECTION","BIND_MEMBERMAP","BIND_IDENTITY","BIND_POPULATION_READ"};int[] slots={0,1,2,3,4,6,7,8,9,13};for(int i=0;i<macros.length;i++)header.append("#undef ").append(macros[i]).append("\n#define ").append(macros[i]).append(' ').append(slots[i]).append('\n');
        String functions="\nlayout(std430,binding=5)readonly buffer FrameInputs{vec4 rows[];}frameInputs;uniform uvec3 uFrameOffsets,uFrameCounts;\n"
            +"vec4 cmiFrameBake(uint i){return frameInputs.rows[uFrameOffsets.x+i];}vec4 cmiFrameBake(int i){return cmiFrameBake(uint(i));}\n"
            +"vec4 cmiFramePlayer(uint i){return frameInputs.rows[uFrameOffsets.y+i];}vec4 cmiFramePlayer(int i){return cmiFramePlayer(uint(i));}\n"
            +"float cmiFrameDamage(uint i){return frameInputs.rows[uFrameOffsets.z+1u+(i>>2u)][i&3u];}float cmiFrameDamage(int i){return cmiFrameDamage(uint(i));}\n";
        // Helper definitions must follow their storage declarations, before the first user.
        s=s.replace("uniform bool uModelMembersEnabled;","uint cmiAllocatorOr(int i,uint v){return atomicOr(modelAllocator.words[i],v);}\nuint cmiAllocatorAnd(int i,uint v){return atomicAnd(modelAllocator.words[i],v);}\nvoid cmiMemberStore(int i,uvec4 v){candidateMembers.words[i]=v.x;}\nuniform bool uModelMembersEnabled;");
        return header+functions+s;
    }
    static int compileText(String label,String text){long start=System.nanoTime();int p=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute(label,"#version 450 core\n"+ParticlePrograms.commonPrelude()+text);System.out.println(label+" compile_ms="+(System.nanoTime()-start)/1e6+" active_blocks="+GL43.glGetProgramInterfacei(p,GL43.GL_SHADER_STORAGE_BLOCK,GL43.GL_ACTIVE_RESOURCES));check(GL43.glGetProgramInterfacei(p,GL43.GL_SHADER_STORAGE_BLOCK,GL43.GL_ACTIVE_RESOURCES)<=16,"exceeded 16 blocks");return p;}
    static final class Packed implements AutoCloseable{
        final int frame,dummy;final int[] bindings=new int[16],original=new int[32],sizes=new int[3],offsets=new int[3];
        Packed(int p){for(int i=0;i<32;i++)original[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);int[] source={original[ParticleBuffers.BAKEMETA_BINDING],original[ParticleBuffers.PLAYERS_BB],original[ParticleBuffers.DAMAGE_BB]};
            int total=0;for(int i=0;i<3;i++){offsets[i]=total;if(source[i]!=0){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,source[i]);sizes[i]=Math.max(16,GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE));}else sizes[i]=16;total+=sizes[i];}
            frame=buffer(bytes(total));dummy=buffer(bytes(16));int[] old={0,1,3,5,14,-1,13,19,21,ParticleBuffers.IDENTITY_BB,27,28,26,24,-1,-1};for(int i=0;i<16;i++)bindings[i]=old[i]<0?dummy:original[old[i]];bindings[5]=frame;
            bindings[14]=imageBuffer(2);bindings[15]=imageBuffer(3);GL41.glProgramUniform3ui(p,GL20.glGetUniformLocation(p,"uFrameOffsets"),offsets[0]/16,offsets[1]/16,offsets[2]/16);GL41.glProgramUniform3ui(p,GL20.glGetUniformLocation(p,"uFrameCounts"),sizes[0]/16,sizes[1]/16,Math.max(0,sizes[2]/4-4));copy();}
        int imageBuffer(int image){int t=GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,image);return t==0?dummy:GL45.glGetTextureLevelParameteri(t,0,GL31.GL_TEXTURE_BUFFER_DATA_STORE_BINDING);}
        void copy(){int[] sources={original[ParticleBuffers.BAKEMETA_BINDING],original[ParticleBuffers.PLAYERS_BB],original[ParticleBuffers.DAMAGE_BB]};GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,frame);for(int i=0;i<3;i++)if(sources[i]!=0){GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,sources[i]);GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,offsets[i],sizes[i]);}GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
        void bind(){for(int i=0;i<16;i++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,bindings[i]);}
        public void close(){GL15.glDeleteBuffers(frame);GL15.glDeleteBuffers(dummy);}
    }
    static double frame(DomainFixture f,Packed packed,int p,boolean copy){f.bindings();put(f.q.counter,bytes(16));int q=GL15.glGenQueries();try{GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,q);if(copy)packed.copy();packed.bind();ui(p,"uModelPass",1);dispatch(p,(f.n+63)/64);AllayAnimationGpu.barrier();ui(p,"uModelPass",2);dispatch(p,(f.models+63)/64);AllayAnimationGpu.barrier();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);return GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT)/1e6;}finally{GL15.glDeleteQueries(q);}}
    static void equal(Map<Integer,int[]> a,Map<Integer,int[]> b){check(a.keySet().equals(b.keySet()),"identity set differs");for(int key:a.keySet())check(Arrays.equals(a.get(key),b.get(key)),"update raw bits differ for "+key);}
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long w=GLFW.glfwCreateWindow(64,64,"Update storage probe",0,0);Path out=Path.of(System.getProperty("cmi.benchmark.output","build/shadow-quality/storage"));Files.createDirectories(out);var rows=new ArrayList<String>(List.of("count,mode,round,pair,baseline_ms,packed_kernel_ms,packed_copy_chain_ms"));
        try{GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));String original=source("update.comp"),packedSource=candidate(original);Files.writeString(out.resolve("update-packed.comp"),packedSource);int a=compileText("original update",original),b=compileText("packed update",packedSource);
            try{for(int count:new int[]{1000,65536,131072})for(int mode=0;mode<3;mode++)try(var f=new DomainFixture(count,mode,new int[]{a,b,b})){
                f.bindings();try(var packed=new Packed(b)){f.frame(false);var first=f.snapshot();f.frame(false);equal(first,f.snapshot());frame(f,packed,b,false);equal(first,f.snapshot());
                    for(int warm=0;warm<16;warm++){f.frame(false);frame(f,packed,b,true);}
                    for(int round=0;round<3;round++)for(int pair=0;pair<64;pair++){double av,bv,cv;if((pair&1)==0){av=f.frame(false);bv=frame(f,packed,b,false);cv=frame(f,packed,b,true);}else{cv=frame(f,packed,b,true);bv=frame(f,packed,b,false);av=f.frame(false);}rows.add(count+","+mode+","+round+","+pair+","+av+","+bv+","+cv);}
                }
            }}finally{deleteProgram(a);deleteProgram(b);}check(GL11.glGetError()==0,"final GL error");System.out.println("Update storage probe: "+checks+" checks; prototype packing is not the CPU upload owner");
        }finally{Files.write(out.resolve("update-storage-samples.csv"),rows);GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();}
    }
}
