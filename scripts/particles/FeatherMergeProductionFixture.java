import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout;

/** Same delivered merged program, particle data, atlas, MRTs and order; only topology differs. */
final class FeatherMergeProductionFixture implements AutoCloseable {
    static final List<String> rows=new ArrayList<>(List.of("pack,width,height,pose,scene,count,distance,round,sample,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms"));
    static final List<String> first=new ArrayList<>(List.of("pack,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms"));
    static final List<String> coverage=new ArrayList<>(List.of("pack,width,height,pose,scene,count,distance,baseline_covered,candidate_covered"));
    final ModelMergeProductionFixture q;
    final int indices,commands;
    int program,dedicated,count,scene,wordBase,baselineFlag,candidateFlag;float distance=6;
    FeatherMergeProductionFixture(ModelMergeGpuValidation.Inputs inputs,int id)throws Exception {
        q=new ModelMergeProductionFixture(inputs,id);indices=GL15.glGenBuffers();commands=GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indices);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2,2,1,5},GL15.GL_STATIC_DRAW);
    }
    void prepare(int count,int scene,int pose,int wordBase){
        this.count=count;this.scene=scene;this.wordBase=wordBase;Arrays.fill(q.pool,0);Arrays.fill(q.order,0);
        for(int i=0;i<count;i++){
            int b=i*16;boolean resting=pose==1||pose==2&&i%2==0;
            q.pool[b]=scene==1?(i%32-15.5f)*.005f:(i%128-63.5f)*.04f;
            q.pool[b+1]=resting?-1.5f:scene==1?-.1f:((i/128)%64-31.5f)*.04f;
            q.pool[b]*=distance/6;q.pool[b+1]*=distance/6;
            q.pool[b+2]=-distance-2*distance*i/Math.max(1,count);q.pool[b+3]=(i%2==0?2f/3:1)*(resting?-1:1);
            q.pool[b+7]=i*.071f;q.pool[b+10]=.5f;q.pool[b+11]=.02f;q.pool[b+12]=i%60+.125f;q.pool[b+13]=60;
            int palette=i%100<60?0:i%100<92?1:2;q.pool[b+14]=Float.intBitsToFloat((i%12)|(palette<<16));
            q.order[wordBase+AllayFeatherLayout.VISIBLE+i]=count-1-i;
        }
        q.upload(1,q.pool);q.upload(3,q.order);
        // Array and indexed commands share instance counts; all other words are explicit.
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,commands);GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER,new int[]{6,count,0,0,6,count,0,0,0},GL15.GL_DYNAMIC_DRAW);
        for(int program:new int[]{program,dedicated==0?program:dedicated}){q.bind(program,false);ModelMergeGpuValidation.integer(program,"cmi_FeatherPass",1);ModelMergeGpuValidation.integer(program,"cmi_FeatherWordBase",wordBase);
        GL20.glUniform3f(GL20.glGetUniformLocation(program,"cmi_FeatherRight"),1,0,0);GL20.glUniform3f(GL20.glGetUniformLocation(program,"cmi_FeatherUp"),0,1,0);
        }
        GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);
    }
    void draw(boolean indexed){
        GL20.glUseProgram(indexed&&dedicated!=0?dedicated:program);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,commands);
        int flag=indexed&&dedicated!=0?candidateFlag:baselineFlag;
        if(flag>=0)GL20.glUniform1i(flag,1);
        if(indexed&&dedicated==0&&System.getProperty("cmi.feather.variant","thin").equals("indexed")){GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indices);try{GL40.glDrawElementsIndirect(GL11.GL_TRIANGLES,GL11.GL_UNSIGNED_INT,16L);}finally{GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,q.buffers[6]);}}
        else GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES,0L);
        if(flag>=0)GL20.glUniform1i(flag,0);
        if(indexed&&dedicated!=0&&!ModelMergeGpuValidation.featherBranch())GL20.glUseProgram(program);
    }
    boolean covers(ModelMergeGpuValidation.Target target,boolean indexed){
        target.clear(scene);int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_ANY_SAMPLES_PASSED,query);draw(indexed);GL15.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED);
        boolean covered=GL15.glGetQueryObjecti(query,GL15.GL_QUERY_RESULT)!=0;GL15.glDeleteQueries(query);return covered;
    }
    double[] measure(ModelMergeGpuValidation.Target target,boolean indexed){
        return measure(target,indexed,count>=50000?1:4);
    }
    double[] measure(ModelMergeGpuValidation.Target target,boolean indexed,int batch){
        int[] queries=new int[batch];GL15.glGenQueries(queries);double cpu=0,gpu=0;
        for(int query:queries){target.clear(scene);GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long start=System.nanoTime();draw(indexed);cpu+=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);}
        for(int query:queries)gpu+=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;
        GL15.glDeleteQueries(queries);return new double[]{gpu/queries.length,cpu/queries.length};
    }
    void run(int program,int dedicated,String pack,boolean benchmark,Path root)throws Exception{
        this.program=program;this.dedicated=dedicated;
        baselineFlag=GL20.glGetUniformLocation(program,"cmi_FeatherPass");
        candidateFlag=dedicated==0?baselineFlag:GL20.glGetUniformLocation(dedicated,"cmi_FeatherPass");
        boolean aa=Boolean.getBoolean("cmi.feather.aa");
        // Native source eligibility is supplied by the caller; geometry/side effects are handled conservatively.
        boolean indexed=Boolean.parseBoolean(System.getProperty("cmi.feather.indexed.eligible","true"))&&!aa;
        boolean quick=Boolean.getBoolean("cmi.model.draw.quick");
        try(var target=new ModelMergeGpuValidation.Target(program,256,256)){
            q.inputs.realistic.viewport(256,256);
            prepare(65,0,2,64);var initialOld=measure(target,false,1);var initialNext=measure(target,indexed,1);first.add(pack+","+initialOld[0]+","+initialNext[0]+","+initialOld[1]+","+initialNext[1]);
            for(int pose=0;pose<3;pose++)for(int scene=0;scene<3;scene++)for(int n:new int[]{0,1,2,63,64,65,257})for(int base:new int[]{0,64}){
                prepare(n,scene,pose,base);target.clear(scene);draw(false);var a=target.read();target.clear(scene);draw(indexed);
                ModelMergeGpuValidation.compare(a,target.read(),target,pack+" feather pose="+pose+" scene="+scene+" count="+n+" base="+base);
            }
        }
        float[] distances=System.getProperty("cmi.feather.distance","all").equals("all")?new float[]{6,64,256}:new float[]{Float.parseFloat(System.getProperty("cmi.feather.distance"))};
        for(float sampleDistance:distances){distance=sampleDistance;
        for(int[] size:quick?new int[][]{{1920,1080}}:new int[][]{{1920,1080},{2560,1506}}){
            q.inputs.realistic.viewport(size[0],size[1]);
            try(var target=new ModelMergeGpuValidation.Target(program,size[0],size[1])){
                if(!quick)for(int s:new int[]{0,1}){
                    prepare(1000,s,2,64);target.clear(s);draw(false);var a=target.read();target.clear(s);draw(indexed);ModelMergeGpuValidation.compare(a,target.read(),target,pack+" full-frame feather");
                }
                if(benchmark)for(int pose=0;pose<3;pose++)for(int s=0;s<3;s++)for(int n:quick?new int[]{10000,131072}:new int[]{0,1000,10000,50000,131072}){
                    prepare(n,s,pose,64);boolean a=covers(target,false),b=covers(target,indexed);
                    ModelMergeGpuValidation.check(a==(n>0&&s!=2)&&a==b,"Feather benchmark coverage "+pack+" "+pose+"/"+s+"/"+n);
                    coverage.add(pack+","+size[0]+","+size[1]+","+pose+","+s+","+n+","+distance+","+a+","+b);
                    for(int i=0;i<12;i++){target.clear(s);draw(i%2==0&&indexed);}
                    for(int round=0;round<3;round++)for(int sample=0;sample<16;sample++){
                        double[] old,next;if((round+sample)%2==0){old=measure(target,false);next=measure(target,indexed);}else{next=measure(target,indexed);old=measure(target,false);}
                        rows.add(String.format(Locale.ROOT,"%s,%d,%d,%d,%d,%d,%.1f,%d,%d,%.6f,%.6f,%.6f,%.6f",pack,size[0],size[1],pose,s,n,distance,round,sample,old[0],next[0],old[1],next[1]));
                    }
                }
            }
        }
        }
        Files.write(ModelMergeGpuValidation.output.resolve("feather-first.csv"),first);Files.write(ModelMergeGpuValidation.output.resolve("feather.csv"),rows);Files.write(ModelMergeGpuValidation.output.resolve("feather-coverage.csv"),coverage);
        ModelMergeGpuValidation.check(GL11.glGetError()==0,"Feather GL state");System.out.println(pack+": feather raw MRT/depth and actual coverage passed; candidate="+System.getProperty("cmi.feather.variant","indexed")+"; A/A="+aa);
    }
    public void close(){GL15.glDeleteBuffers(indices);GL15.glDeleteBuffers(commands);q.close();}
}
