import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Domain screen: exact same frozen pool/grid, production generic-then-model order. */
public class UpdateDomainGpuValidation extends ModelPopulationGpuValidation {
    static String sharedSource;
    static final List<String> firstRows=new ArrayList<>(List.of("round,baseline_ms,candidate_ms"));
    static final List<String> compileRows=new ArrayList<>(List.of("round,program,compile_ms"));
    static int program(int domain,int round){
        if(sharedSource==null)sharedSource=source("update.comp");long start=System.nanoTime();String text=sharedSource;
        if(domain!=0)text=text.replace("uniform uint uModelPass;","const uint uModelPass="+domain+"u;")
            .replace("bool isModel = matId >= 1.5 && matId < 2.5;","bool isModel = "+(domain==2?"true":"false")+";");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),p=GL20.glCreateProgram();GL20.glShaderSource(shader,"#version 450 core\n"+ParticlePrograms.commonPrelude()+text);GL20.glCompileShader(shader);
        check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(shader));GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);GL20.glDeleteShader(shader);
        check(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(p));compileRows.add(round+","+domain+","+(System.nanoTime()-start)/1e6);return p;
    }
    static final class DomainFixture implements AutoCloseable {
        final Fixture q;final int n,models,grid,output,damage,correction,map;final int[] programs;final int gridProgram;final ByteBuffer zero=bytes(16);
        DomainFixture(int n,int mode,int[] programs)throws Exception{
            this.n=n;models=mode==0?0:mode==1?n:(n+1)/2;this.programs=programs;q=new Fixture(Math.max(1,n),Math.max(1,n),Math.max(1,n));gridProgram=compile("gridbuild.comp");var data=bytes(Math.max(1,n)*64);
            for(int i=0;i<n;i++){
                int header=mode==0?0:mode==1?2:i%2==0?2:0;
                particle(data,i,header,1500+(i%256-128)*.2f,-2300+((i/256)%256-128)*.2f,header==0?100:20);
                data.putFloat(i*64+4,128+(i/65536)*2f).putFloat(i*64+12,header==2?i+1:1).putInt(i*64+56,0x3f000000+i);
            }
            q.build(data,n);var hdr=headers();int hb=640;hdr.putFloat(hb+18*16,2).putFloat(hb+18*16+4,64).putFloat(hb+18*16+12,6).putFloat(hb+19*16,1500).putFloat(hb+19*16+4,128).putFloat(hb+19*16+8,-2300);put(q.emit,hdr);put(q.previous,bytes(16).putInt(0,n));
            grid=buffer(bytes((ParticleBuffers.GRID_TABLE+Math.max(1,n))*4));output=buffer(bytes(Math.max(1,n)*64));damage=buffer(bytes(16));correction=buffer(bytes(Math.max(1,n)*32));map=buffer(bytes(Math.max(1,n)*4));
            for(int p:programs){ui(p,"uCapacity",n);ui(p,"uKillEmit",-1);f(p,"uDt",1/60f);f(p,"uTimeSec",25);f(p,"uStormRotPhase",4);f(p,"uStormConvPhase",2);f(p,"uStormConvRate",.01f);bool(p,"uPopulationReadEnabled",true);gen(p,"uPopulationGeneration",7);ui(p,"uPopulationModelCapacity",Math.max(1,n));ui(p,"uPopulationStormCapacity",Math.max(1,n));}
            bindings();ui(gridProgram,"uCapacity",n);dispatch(gridProgram,Math.max(1,(n+63)/64));
        }
        void bindings(){q.bindings();bind(0,q.pool);bind(1,output);bind(13,grid);bind(16,damage);bind(19,correction);bind(21,map);bind(24,q.pop);}
        double frame(boolean candidate){
            bindings();put(q.counter,zero);int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);
            int generic=candidate?programs[1]:programs[0],model=candidate?programs[2]:programs[0];
            ui(generic,"uModelPass",1);dispatch(generic,Math.max(1,(n+63)/64));AllayAnimationGpu.barrier();
            ui(model,"uModelPass",2);dispatch(model,(models+63)/64);AllayAnimationGpu.barrier();
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double ms=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(query);return ms;
        }
        Map<Integer,int[]> snapshot(){
            int live=read(q.counter,16).getInt(0);check(live==n,"domain count "+live+" expected "+n);ByteBuffer data=read(output,n*64);var result=new TreeMap<Integer,int[]>();
            for(int i=0;i<n;i++){int[] words=new int[16];for(int k=0;k<16;k++)words[k]=data.getInt(i*64+k*4);check(result.put(words[14],words)==null,"Duplicate stable seed");}return result;
        }
        public void close(){q.close();for(int p:new int[]{gridProgram})deleteProgram(p);for(int b:new int[]{grid,output,damage,correction,map})GL15.glDeleteBuffers(b);}
    }
    public static void main(String[] args)throws Exception{
        GLFWErrorCallback.createPrint(System.err).set();check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);long window=GLFW.glfwCreateWindow(64,64,"Domain kernel screen",0,0);
        Path out=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/update-domain"));Files.createDirectories(out);
        try{GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();System.out.println(GL11.glGetString(GL11.GL_RENDERER));var rows=new ArrayList<String>();rows.add("count,mode,round,sample,baseline_ms,candidate_ms");
            for(int round=0;round<3;round++){
                boolean aa=Boolean.getBoolean("cmi.update.domain.aa");int unified=program(0,round);int[] programs={unified,aa?unified:program(1,round),aa?unified:program(2,round)};
                try(var first=new DomainFixture(1000,2,programs)){
                    double a,b;if(round%2==0){a=first.frame(false);b=first.frame(true);}else{b=first.frame(true);a=first.frame(false);}firstRows.add(round+","+a+","+b);
                }
                for(int count:new int[]{0,1000,10000,50000,131072})for(int mode=0;mode<3;mode++)try(var fixture=new DomainFixture(count,mode,programs)){
                    fixture.frame(false);var baseline=fixture.snapshot();fixture.frame(true);var candidate=fixture.snapshot();check(baseline.keySet().equals(candidate.keySet()),"Identity set");for(int key:baseline.keySet())check(Arrays.equals(baseline.get(key),candidate.get(key)),"State mismatch seed="+key);
                    for(int warm=0;warm<12;warm++)fixture.frame(warm%2==0);
                    for(int sample=0;sample<16;sample++){double a,b;if((sample+round)%2==0){a=fixture.frame(false);b=fixture.frame(true);}else{b=fixture.frame(true);a=fixture.frame(false);}rows.add(count+","+mode+","+round+","+sample+","+a+","+b);}
                    System.out.println("Domain exact+timed count="+count+" mode="+mode+" round="+round);
                }
                for(int p:Arrays.stream(programs).distinct().toArray())deleteProgram(p);Files.write(out.resolve("domain-screen.csv"),rows);Files.write(out.resolve("domain-compile.csv"),compileRows);
            }
            if(!Boolean.getBoolean("cmi.update.domain.aa"))for(int round=3;round<6;round++){
                int[] order=round%2==0?new int[]{0,1,2}:new int[]{1,2,0};for(int domain:order)deleteProgram(program(domain,round));
            }
            Files.write(out.resolve("domain-compile.csv"),compileRows);Files.write(out.resolve("domain-first.csv"),firstRows);
            check(GL11.glGetError()==0,"Final GL state");System.out.println("Domain GPU screen: "+checks+" checks passed");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
