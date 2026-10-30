import com.iridium126.createmanaindustry.client.particles.engine.*;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.util.*;

/** Real production merged vertices, indexed allay mesh, TBO ABI and three segments.
 * Static inputs replace simulation/world resources; no game or simulation timing is claimed. */
final class ModelMergeProductionFixture implements AutoCloseable {
    static final int CAP=131072;
    final int[] buffers=new int[7],textures=new int[6];
    final int vao,opaque,body,indices;
    final float[] pool=new float[CAP*16],header=new float[80];
    final int[] order=new int[(CAP*2+1)*2];
    final ModelMergeGpuValidation.Inputs inputs;
    final int allayId;
    ModelMergeProductionFixture(ModelMergeGpuValidation.Inputs inputs,int allayId)throws Exception{
        this.inputs=inputs;this.allayId=allayId;
        var vertices=AllayModelGeometry.class.getDeclaredField("VERTICES");vertices.setAccessible(true);
        var indexField=AllayModelGeometry.class.getDeclaredField("INDICES");indexField.setAccessible(true);
        var opaqueField=AllayModelGeometry.class.getDeclaredField("OPAQUE_INDEX_COUNT");opaqueField.setAccessible(true);
        float[] geo=(float[])vertices.get(null);int[] mesh=(int[])indexField.get(null);indices=mesh.length;
        body=AllayModelGeometry.BODY_OPAQUE_INDEX_COUNT;opaque=opaqueField.getInt(null);
        vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);GL15.glGenBuffers(buffers);GL11.glGenTextures(textures);
        upload(0,geo);upload(1,pool);upload(2,header);upload(3,order);upload(4,new int[64]);upload(5,new int[16]);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,buffers[6]);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,mesh,GL15.GL_STATIC_DRAW);
        for(int i=0;i<6;i++){GL13.glActiveTexture(GL13.GL_TEXTURE10+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,new int[]{GL30.GL_R32F,GL30.GL_RGBA32F,GL30.GL_RGBA32F,GL32.GL_RG32UI,GL30.GL_R32UI,GL30.GL_RGBA32UI}[i],buffers[i]);}
    }
    void upload(int index,float[] data){GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,buffers[index]);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,data,GL15.GL_DYNAMIC_DRAW);}
    void upload(int index,int[] data){GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,buffers[index]);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,data,GL15.GL_DYNAMIC_DRAW);}
    void prepare(int count,int scene,int pose,int sword){
        Arrays.fill(header,0);header[22]=header[23]=count<=64?.07f:.008f;header[24]=1;header[26]=1;
        Arrays.fill(header,32,64,1);header[70]=sword;
        for(int i=0;i<count;i++){
            int at=i*16;float x,y;
            if(scene==1){x=(i%40)*.006f-.12f;y=((i/40)%40)*.006f-.12f;}
            else {x=(i%160)*.01f-.8f;y=((i/160)%89)*.018f-.8f;}
            pool[at]=x;pool[at+1]=y;pool[at+2]=-.2f-.6f*i/Math.max(count,1);pool[at+3]=1;
            pool[at+6]=-1;pool[at+8]=pool[at+9]=pool[at+10]=1;pool[at+11]=pose==1?.4f:0;
            pool[at+12]=8;pool[at+13]=pose==2?-.25f:60;pool[at+14]=.6f;
            order[i*2]=0;order[i*2+1]=i<<2;order[(CAP+i)*2]=0;order[(CAP+i)*2+1]=i<<2;
        }
        order[CAP*4]=count;upload(1,pool);upload(2,header);upload(3,order);
    }
    void bind(int p,boolean carrier){
        inputs.bind(p,carrier?0:allayId);GL30.glBindVertexArray(vao);
        String[] names={"cmi_Geo","cmi_Pool","cmi_Emitters","cmi_Sorted","cmi_Starfall","cmi_AllayAnimation"};
        for(int i=0;i<6;i++){ModelMergeGpuValidation.integer(p,names[i],10+i);GL13.glActiveTexture(GL13.GL_TEXTURE10+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i]);}
        ModelMergeGpuValidation.integer(p,"cmi_MetaSlot",CAP*2);ModelMergeGpuValidation.integer(p,"cmi_CarrierBase",CAP);
        ModelMergeGpuValidation.integer(p,"cmi_LodPass",0);ModelMergeGpuValidation.integer(p,"cmi_FeatherPass",0);ModelMergeGpuValidation.integer(p,"cmi_ShadowPass",0);
        ModelMergeGpuValidation.number(p,"cmi_FadeDist",4096);ModelMergeGpuValidation.number(p,"uTimeSec",12);
        ModelMergeGpuValidation.number(p,"iris_currentAlphaTest",.1f);ModelMergeGpuValidation.number(p,"alphaTestRef",.1f);
        GL20.glUniform4fv(GL20.glGetUniformLocation(p,"uHeldItemUV[0]"),HeldItemGeometry.uvTable());
    }
    void render(int[] programs,ModelMergeGpuValidation.Target target,int count,int scene){
        target.clear(scene);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
        draw(programs,target,count);
    }
    void use(int p,boolean carrier){GL20.glUseProgram(p);ModelMergeGpuValidation.integer(p,"entityId",carrier?0:allayId);}
    void draw(int[] programs,ModelMergeGpuValidation.Target target,int count){
        GL11.glDisable(GL11.GL_BLEND);
        use(programs[0],false);ModelMergeGpuValidation.integer(programs[0],"cmi_SegmentMode",1);
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,body,GL11.GL_UNSIGNED_INT,0L,count);
        use(programs[1],true);ModelMergeGpuValidation.integer(programs[1],"cmi_SegmentMode",2);
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,opaque-body,GL11.GL_UNSIGNED_INT,body*4L,count);
        use(programs[2],false);ModelMergeGpuValidation.integer(programs[2],"cmi_SegmentMode",0);
        if(target.types[0]==GL11.GL_FLOAT){GL30.glEnablei(GL11.GL_BLEND,0);GL40.glBlendFuncSeparatei(0,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);}
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,indices-opaque,GL11.GL_UNSIGNED_INT,opaque*4L,count);
    }
    void validate(int[] baseline,int[] candidate,String pack)throws Exception{
        try(var target=new ModelMergeGpuValidation.Target(baseline[0],64,64)){
            initialize(baseline,candidate);
            boolean covered=false;
            for(int pose=0;pose<3;pose++)for(int sword=1;sword<=6;sword++){
                prepare(64,0,pose,sword);render(baseline,target,64,0);var a=target.read();
                for(int word:a[8])covered|=word!=Float.floatToIntBits(1);
                render(candidate,target,64,0);ModelMergeGpuValidation.compare(a,target.read(),target,pack+" production pose="+pose+" sword="+sword);
            }
            ModelMergeGpuValidation.check(covered,"Production fixture generated no covered model pixels: "+pack);
            ModelMergeGpuValidation.check(GL11.glGetError()==0,"Production model draw GL state: "+pack);
        }
    }
    void initialize(int[] baseline,int[] candidate){for(int[] programs:new int[][]{baseline,candidate})for(int i=0;i<programs.length;i++)bind(programs[i],i==1);}
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
        target.clear(scene);int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);
        long start=System.nanoTime();draw(programs,target,count);double cpu=(System.nanoTime()-start)/1e6;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);double gpu=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(query);return new double[]{gpu,cpu};
    }
    void benchmark(int[] baseline,int[] candidate,String pack,List<String> csv){
        try(var target=new ModelMergeGpuValidation.Target(baseline[0],1920,1080)){
            initialize(baseline,candidate);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);GL11.glDisable(GL11.GL_CULL_FACE);
            for(int scene=0;scene<3;scene++)for(int count:new int[]{0,1000,10000,50000}){
                prepare(count,scene,0,5);
                for(int i=0;i<20;i++)render(i%2==0?baseline:candidate,target,count,scene);GL11.glFinish();
                for(int round=0;round<3;round++)for(int sample=0;sample<24;sample++){
                    double[] a,b;if((round+sample)%2==0){a=measure(baseline,target,count,scene);b=measure(candidate,target,count,scene);}
                    else{b=measure(candidate,target,count,scene);a=measure(baseline,target,count,scene);}
                    csv.add(String.format(Locale.ROOT,"%s,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f",pack,scene,count,round,sample,a[0],b[0],a[1],b[1]));
                }
            }
            ModelMergeGpuValidation.check(GL11.glGetError()==0,"Production benchmark GL state: "+pack);
        }
    }
    public void close(){GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(buffers);GL11.glDeleteTextures(textures);}
}
