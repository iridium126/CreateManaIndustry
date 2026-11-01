import com.iridium126.createmanaindustry.client.particles.engine.*;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.util.*;

/** Real production merged vertices, indexed allay mesh, TBO ABI and three segments.
 * Static inputs replace simulation/world resources; no game or simulation timing is claimed. */
final class ModelMergeProductionFixture implements AutoCloseable {
    static final int CAP=131072;
    final int[] buffers=new int[8],textures=new int[7];
    final int vao,opaque,body,indices;
    final float[] pool=new float[CAP*16],header=new float[80];
    final int[] order=new int[(CAP*2+1)*2];
    final ModelMergeGpuValidation.Inputs inputs;
    final int allayId;
    final int[] animation=new int[CAP*16],storm=new int[160000];
    int[] segmentQueries;
    int workload,carrierCount,colorCount=1;float lifeFraction=-1;float sampleTime=12,animationAlpha=.4f;
    static int bits(float f){return Float.floatToRawIntBits(f);}
    final Set<Integer> referencePrograms=new HashSet<>();
    ModelMergeProductionFixture(ModelMergeGpuValidation.Inputs inputs,int allayId)throws Exception{
        this.inputs=inputs;this.allayId=allayId;
        var vertices=AllayModelGeometry.class.getDeclaredField("VERTICES");vertices.setAccessible(true);
        var indexField=AllayModelGeometry.class.getDeclaredField("INDICES");indexField.setAccessible(true);
        var opaqueField=AllayModelGeometry.class.getDeclaredField("OPAQUE_INDEX_COUNT");opaqueField.setAccessible(true);
        float[] geo=(float[])vertices.get(null);int[] mesh=(int[])indexField.get(null);indices=mesh.length;
        body=AllayModelGeometry.BODY_OPAQUE_INDEX_COUNT;opaque=opaqueField.getInt(null);
        vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);GL15.glGenBuffers(buffers);GL11.glGenTextures(textures);
        upload(7,packedGeometry(geo));upload(0,geo);upload(1,pool);upload(2,header);upload(3,order);upload(4,storm);upload(5,animation);upload(4,storm);upload(5,animation);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,buffers[6]);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,mesh,GL15.GL_STATIC_DRAW);
        for(int i=0;i<7;i++){GL13.glActiveTexture(GL13.GL_TEXTURE10+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,new int[]{GL30.GL_R32F,GL30.GL_RGBA32F,GL30.GL_RGBA32F,GL32.GL_RG32UI,GL30.GL_R32UI,GL30.GL_RGBA32UI,GL30.GL_RGBA32F}[i],buffers[i==6?7:i]);}
    }
    static float[] packedGeometry(float[] source){float[] result=new float[source.length/7*8];for(int i=0,j=0;i<source.length;i+=7,j+=8)System.arraycopy(source,i,result,j,7);return result;}
    void bindGeometry(int p){
        GL13.glActiveTexture(GL13.GL_TEXTURE10);
        boolean reference=referencePrograms.contains(p)||!System.getProperty("cmi.model.draw.variant","full").equals("Geometry");
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,reference?textures[0]:textures[6]);
    }
    void upload(int index,float[] data){GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,buffers[index]);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,data,GL15.GL_DYNAMIC_DRAW);}
    void upload(int index,int[] data){GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,buffers[index]);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,data,GL15.GL_DYNAMIC_DRAW);}
    void prepare(int count,int scene,int pose,int sword){
        // LOD reuses this storage with an 8-float record. Clear every MODEL lane,
        // especially p3.w (emitter identity), before restoring 16-float records.
        Arrays.fill(pool,0,count*16,0f);
        Arrays.fill(header,0);header[22]=header[23]=ModelMergeGpuValidation.ROUND2?(count<=64?.3f:.04f):(count<=64?.07f:.008f);header[24]=1;header[26]=colorCount;
        for(int key=0;key<8;key++){int at=32+key*4;header[at]=.1f+key*.1f;header[at+1]=.8f-key*.08f;header[at+2]=key%2==0?.25f:.9f;header[at+3]=1;}header[70]=workload<2?sword:0;
        header[72]=workload>=2?1:0;header[73]=100;
        carrierCount=count/16;
        Arrays.fill(storm,0);storm[2]=1;
        for(int slot=0;slot<6;slot++){
            int b=131088+slot*312;storm[b]=bits(1);storm[b+1]=bits(1);
            storm[b+4]=bits(2);storm[b+5]=bits(4);storm[b+6]=bits(8);storm[b+7]=bits(40);
            storm[b+16]=bits(sword);storm[b+20]=bits(slot==4?3:slot==1?2:slot==2?1:0);storm[b+21]=bits(3);
            storm[b+292]=bits(2);storm[b+293]=bits(4);storm[b+294]=bits(30);storm[b+295]=bits(40);
        }
        for(int i=0;i<count;i++){
            int at=i*16;float x,y;
            if(scene==1){x=(i%40)*.006f-.12f;y=((i/40)%40)*.006f-.12f;}
            else {x=(i%160)*.01f-.8f;y=((i/160)%89)*.018f-.8f;}
            pool[at]=x;pool[at+1]=y;pool[at+2]=-.2f-.6f*i/Math.max(count,1);pool[at+3]=workload>=2?i+1:1;
            if(ModelMergeGpuValidation.ROUND2){
                pool[at]=count<=64?(i%8-3.5f)*.12f:scene==1?(i%128-64)*.02f:(i%256-128)*.10f;
                pool[at+1]=count<=64?(i/8-3.5f)*.12f-.3f:scene==1?((i/128)%64-32)*.02f:((i/256)%128-64)*.10f;
                pool[at+2]=count<=64?-2:-12-12f*i/Math.max(count,1);
            }
            pool[at+4]=1.5f;pool[at+5]=-.2f;pool[at+6]=-1;pool[at+7]=-4;pool[at+8]=pool[at+9]=pool[at+10]=1;pool[at+11]=pose==1?.4f:0;
            pool[at+12]=lifeFraction<0?8:60*lifeFraction;pool[at+13]=pose==2?-.25f:60;pool[at+14]=.6f;
            int aa=i*16;
            animation[aa]=0x80008000;animation[aa+1]=0x88008500;
            animation[aa+2]=0x80008000;animation[aa+3]=0xb0008500;
            animation[aa+4]=bits(2);animation[aa+5]=bits(2.2f);animation[aa+6]=0x90008000;
            animation[aa+7]=(i%7==0?8:63)<<6;animation[aa+10]=0x80000000;
            if(workload>=2){int slot=workload==2?4:workload==3?1:workload==4?2:new int[]{4,1,2}[i%3];
                storm[16+i]=slot==4?0x40000001|((i%64)<<8):1|(slot<<1)|((i%2048)<<8);}
            order[i*2]=i<<9;order[i*2+1]=i<<2;order[(CAP+i)*2]=i<<9;order[(CAP+i)*2+1]=i<<2;
        }
        order[CAP*4]=count;upload(1,pool);upload(2,header);upload(3,order);upload(4,storm);upload(5,animation);
    }
    void bind(int p,boolean carrier){
        inputs.bind(p,carrier?0:allayId);GL30.glBindVertexArray(vao);
        String[] names={"cmi_Geo","cmi_Pool","cmi_Emitters","cmi_Sorted","cmi_Starfall","cmi_AllayAnimation"};
        for(int i=0;i<6;i++){ModelMergeGpuValidation.integer(p,names[i],10+i);GL13.glActiveTexture(GL13.GL_TEXTURE10+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);}
        ModelMergeGpuValidation.integer(p,"cmi_MetaSlot",CAP*2);ModelMergeGpuValidation.integer(p,"cmi_CarrierBase",CAP);
        ModelMergeGpuValidation.integer(p,"cmi_LodPass",0);ModelMergeGpuValidation.integer(p,"cmi_FeatherPass",0);ModelMergeGpuValidation.integer(p,"cmi_ShadowPass",0);
        ModelMergeGpuValidation.number(p,"cmi_FadeDist",4096);ModelMergeGpuValidation.number(p,"uTimeSec",sampleTime);
        ModelMergeGpuValidation.integer(p,"uAllayAnimationEnabled",workload>=1?1:0);
        ModelMergeGpuValidation.number(p,"uAllayAnimationAlpha",animationAlpha);
        ModelMergeGpuValidation.integer(p,"uStarfallEnabled",workload>=2?1:0);
        ModelMergeGpuValidation.integer(p,"uDomePreciseClock",1);
        GL20.glUniform4f(GL20.glGetUniformLocation(p,"uDomeElapsed"),sampleTime-2,sampleTime-2,sampleTime-2,sampleTime-2);
        ModelMergeGpuValidation.number(p,"iris_currentAlphaTest",.1f);ModelMergeGpuValidation.number(p,"alphaTestRef",.1f);
        GL20.glUniform4fv(GL20.glGetUniformLocation(p,"uHeldItemUV[0]"),HeldItemGeometry.uvTable());
    }
    void render(int[] programs,ModelMergeGpuValidation.Target target,int count,int scene){
        if(ModelMergeGpuValidation.ROUND2)inputs.realistic.viewport(target.w,target.h);
        target.clear(scene);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);GL11.glEnable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
        draw(programs,target,count);
    }
    void use(int p,boolean carrier){GL20.glUseProgram(p);bindGeometry(p);ModelMergeGpuValidation.integer(p,"entityId",carrier?0:allayId);}
    void draw(int[] programs,ModelMergeGpuValidation.Target target,int count){
        GL11.glDisable(GL11.GL_BLEND);
        stamp(0);use(programs[0],false);ModelMergeGpuValidation.integer(programs[0],"cmi_SegmentMode",1);
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,body,GL11.GL_UNSIGNED_INT,0L,count);
        stamp(1);use(programs[1],true);ModelMergeGpuValidation.integer(programs[1],"cmi_SegmentMode",2);
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,opaque-body,GL11.GL_UNSIGNED_INT,body*4L,carrierCount);
        stamp(2);use(programs[2],false);ModelMergeGpuValidation.integer(programs[2],"cmi_SegmentMode",0);
        if(target.types[0]==GL11.GL_FLOAT){GL30.glEnablei(GL11.GL_BLEND,0);GL40.glBlendFuncSeparatei(0,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);}
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,indices-opaque,GL11.GL_UNSIGNED_INT,opaque*4L,count);stamp(3);
    }
    void stamp(int index){if(segmentQueries!=null)GL33.glQueryCounter(segmentQueries[index],GL33.GL_TIMESTAMP);}
    void validate(int[] baseline,int[] candidate,String pack)throws Exception{
        try(var target=new ModelMergeGpuValidation.Target(baseline[0],64,64)){
            if(ModelMergeGpuValidation.ROUND2)inputs.realistic.viewport(target.w,target.h);
            initialize(baseline,candidate);
            boolean covered=false,first=true;double[] firstBaseline=null;
            for(workload=0;workload<6;workload++)for(int pose=0;pose<3;pose++)for(int sword=1;sword<=6;sword++){
                initialize(baseline,candidate);prepare(64,0,pose,sword);initialize(baseline,candidate);if(ModelMergeGpuValidation.ROUND2&&first)firstBaseline=measure(baseline,target,64,0);else render(baseline,target,64,0);var a=target.read();
                for(int word:a[8])covered|=word!=Float.floatToIntBits(1);
                if(ModelMergeGpuValidation.ROUND2&&first){var initial=measure(candidate,target,64,0);ModelMergeGpuValidation.firstDrawRows.add(pack+",64,64,64,0,"+firstBaseline[0]+","+initial[0]+","+firstBaseline[1]+","+initial[1]);first=false;}else render(candidate,target,64,0);ModelMergeGpuValidation.compare(a,target.read(),target,pack+" production workload="+workload+" pose="+pose+" sword="+sword);
            }
            for(int colors:new int[]{0,1,2,4,8,9})for(float life:new float[]{0,.125f,.5f,.999999f,1}){
                workload=1;colorCount=colors;lifeFraction=life;prepare(64,0,0,3);initialize(baseline,candidate);
                render(baseline,target,64,0);var reference=target.read();render(candidate,target,64,0);
                ModelMergeGpuValidation.compare(reference,target.read(),target,pack+" keyframes="+colors+" life="+life);
            }
            colorCount=1;lifeFraction=-1;
            for(float time:new float[]{1.99999f,2,4,8,30,40,40.00001f})for(float alpha:new float[]{0,.5f,1}){
                workload=5;sampleTime=time;animationAlpha=alpha;prepare(64,0,0,5);initialize(baseline,candidate);
                render(baseline,target,64,0);var reference=target.read();render(candidate,target,64,0);
                ModelMergeGpuValidation.compare(reference,target.read(),target,pack+" attack boundary="+time+" alpha="+alpha);
            }
            sampleTime=12;animationAlpha=.4f;
            ModelMergeGpuValidation.check(covered,"Production fixture generated no covered model pixels: "+pack);
            ModelMergeGpuValidation.check(GL11.glGetError()==0,"Production model draw GL state: "+pack);
        }
    }
    void validateFullFrame(int[] baseline,int[] candidate,String pack){
        for(int[] size:new int[][]{{1920,1080},{2560,1506}})try(var target=new ModelMergeGpuValidation.Target(baseline[0],size[0],size[1])){
            inputs.realistic.viewport(target.w,target.h);workload=5;colorCount=8;
            for(int scene:new int[]{0,1,2}){
                prepare(1000,scene,1,6);initialize(baseline,candidate);render(baseline,target,1000,scene);var original=target.read();
                render(candidate,target,1000,scene);ModelMergeGpuValidation.compare(original,target.read(),target,pack+" full-frame scene="+scene);
            }
            colorCount=1;
        }
    }
    void initialize(int[] baseline,int[] candidate){referencePrograms.clear();for(int p:baseline)referencePrograms.add(p);for(int[] programs:new int[][]{baseline,candidate})for(int i=0;i<programs.length;i++)bind(programs[i],i==1);}
    void validateLod(int[] baseline,int[] candidate,String pack)throws Exception{
        for(int i=0;i<CAP;i++){
            int at=i*8;pool[at]=(i%512-256)*4;pool[at+1]=(i/512-128)*4;pool[at+2]=-1500;pool[at+3]=.3f;
            pool[at+4]=0;pool[at+5]=0;pool[at+6]=-1;pool[at+7]=.5f;
            order[i*2]=i;order[(CAP+i)*2]=i;
        }
        upload(1,pool);upload(3,order);
        try(var target=new ModelMergeGpuValidation.Target(baseline[0],128,128)){
            initialize(baseline,candidate);
            for(int mode:new int[]{1,2}){
                drawLod(baseline[0],target,mode);var a=target.read();drawLod(candidate[0],target,mode);
                ModelMergeGpuValidation.compare(a,target.read(),target,pack+" native LOD"+mode);
            }
            ModelMergeGpuValidation.check(GL11.glGetError()==0,"Native LOD draw GL state: "+pack);
        }
    }
    void drawLod(int program,ModelMergeGpuValidation.Target target,int mode){
        target.clear(0);use(program,false);GL30.glBindVertexArray(vao);ModelMergeGpuValidation.integer(program,"cmi_LodPass",mode);
        var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(70),1,.1f,8192);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"iris_ProjMat"),false,projection.get(new float[16]));
        GL11.glDisable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
        if(mode==1)GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,body,GL11.GL_UNSIGNED_INT,0L,CAP);
        else GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES,0,6,CAP);
    }
    double[] measure(int[] programs,ModelMergeGpuValidation.Target target,int count,int scene){
        target.clear(scene);segmentQueries=new int[4];GL15.glGenQueries(segmentQueries);int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);
        long start=System.nanoTime();draw(programs,target,count);double cpu=(System.nanoTime()-start)/1e6;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double gpu=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(query);
        double[] result={gpu,cpu,0,0,0};long previous=GL33.glGetQueryObjectui64(segmentQueries[0],GL15.GL_QUERY_RESULT);
        for(int i=1;i<4;i++){long next=GL33.glGetQueryObjectui64(segmentQueries[i],GL15.GL_QUERY_RESULT);result[i+1]=(next-previous)/1e6;previous=next;}
        GL15.glDeleteQueries(segmentQueries);segmentQueries=null;return result;
    }
    void benchmark(int[] baseline,int[] candidate,String pack,List<String> csv){
        boolean quick=Boolean.getBoolean("cmi.model.draw.quick");
        for(int[] size:quick?new int[][]{{1920,1080}}:new int[][]{{1920,1080},{2560,1506}}){
          try(var target=new ModelMergeGpuValidation.Target(baseline[0],size[0],size[1],true)){
            int[] workloads=quick?new int[]{1,5}:new int[]{0,1,2,3,4,5};
            if(ModelMergeGpuValidation.ROUND2)inputs.realistic.viewport(target.w,target.h);
            for(int mode:workloads)for(int scene=0;scene<3;scene++)for(int count:quick?new int[]{50000}:new int[]{0,1000,10000,50000,131072}){
                workload=mode;prepare(count,scene,0,5);initialize(baseline,candidate);
                for(int i=0;i<12;i++)render(i%2==0?baseline:candidate,target,count,scene);GL11.glFinish();
                boolean[] coverageResults=new boolean[2];int coverageIndex=0;
                for(int[] programs:new int[][]{baseline,candidate}){
                    int coverage=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_ANY_SAMPLES_PASSED,coverage);
                    render(programs,target,count,scene);GL15.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED);
                    boolean covered=GL15.glGetQueryObjecti(coverage,GL15.GL_QUERY_RESULT)!=0;GL15.glDeleteQueries(coverage);
                    coverageResults[coverageIndex++]=covered;
                    ModelMergeGpuValidation.check(covered==(count>0&&scene!=2),"Benchmark coverage pack="+pack+" scene="+scene+" count="+count+" workload="+mode);
                }
                ModelMergeGpuValidation.coverageRows.add(pack+","+size[0]+","+size[1]+","+mode+","+scene+","+count+","+coverageResults[0]+","+coverageResults[1]);
                for(int round=0;round<Integer.getInteger("cmi.draw.rounds",3);round++)for(int sample=0;sample<Integer.getInteger("cmi.draw.samples",quick?12:16);sample++){
                    double[] a,b;if((round+sample)%2==0){a=measure(baseline,target,count,scene);b=measure(candidate,target,count,scene);}
                    else{b=measure(candidate,target,count,scene);a=measure(baseline,target,count,scene);}
                    csv.add(String.format(Locale.ROOT,"%s,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",pack,scene,count,round,sample,a[0],b[0],a[1],b[1],size[0],size[1],mode,a[2],b[2],a[3],b[3],a[4],b[4]));
                }
            }
            ModelMergeGpuValidation.check(GL11.glGetError()==0,"Production benchmark GL state: "+pack);
          }
        }
    }
    public void close(){GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(buffers);GL11.glDeleteTextures(textures);}
}
