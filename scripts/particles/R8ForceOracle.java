import java.nio.*;import org.lwjgl.opengl.*;
/** Same FP32 terms, independently enumerated and summed in FP64; no trajectory bit-equivalence requirement. */
public final class R8ForceOracle extends ModelPopulationGpuValidation{
 static int helper,deltaBuffer;static ByteBuffer deltas;static long queries,overlapTerms;
 static String instrument(String source){return source.replace("uniform uint uR7Stage;","layout(rgba32f,binding=5)writeonly uniform imageBuffer r8Force;uniform uint uR8SampleStride;\nuniform uint uR7Stage;").replace("        sepW *= STORM_W_SEP;","if(uR7Stage==2u&&(r7Lane%max(1u,uR8SampleStride))==0u){imageStore(r8Force,int(r7Lane*2u),vec4(pos,separationRadius));imageStore(r8Force,int(r7Lane*2u+1u),vec4(sepW,uintBitsToFloat(idx)));}\n        sepW *= STORM_W_SEP;");}
 static void ensure()throws Exception{if(deltas!=null)return;String fn=source("chunks/storm_separation.glsl");helper=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("independent overlap directions","#version 450 core\nlayout(local_size_x=64)in;layout(std430,binding=0)buffer O{vec4 d[];};"+fn+"\nvoid main(){uint i=gl_GlobalInvocationID.x;if(i<131072u)d[i]=vec4(cmiStormSeparationDelta(vec3(0),i,i+1u),0);}");deltaBuffer=buffer(bytes(131072*16));bind(0,deltaBuffer);dispatch(helper,2048);deltas=read(deltaBuffer,131072*16);}
 static java.util.List<Integer> all(int live){var ids=new java.util.ArrayList<Integer>(live);for(int i=0;i<live;i++)ids.add(i);return ids;}
 static void validate(ByteBuffer pool,ByteBuffer emit,ByteBuffer trace,int live,int rows){var ids=all(live);validateWith(pool,emit,trace,live,rows,row->ids);}
 record Cell(long x,long y,long z){}
 static long cell(double x){return (long)Math.floor(x/2.5);}
 static void validateSpatial(ByteBuffer pool,ByteBuffer emit,ByteBuffer trace,int live,int rows){
  var grid=new java.util.HashMap<Cell,java.util.List<Integer>>();var ids=all(live);
  for(int j=0;j<live;j++){int hb=pool.getInt(j*64+60)*com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.VEC4_PER_EMITTER*16;float mat=emit.getFloat(hb+112);if(emit.getFloat(hb+288)<.5f||mat<1.5f||mat>=2.5f)continue;grid.computeIfAbsent(new Cell(cell(pool.getFloat(j*64)),cell(pool.getFloat(j*64+4)),cell(pool.getFloat(j*64+8))),k->new java.util.ArrayList<>()).add(j);}
  validateWith(pool,emit,trace,live,rows,row->{int at=row*32;if(trace.getInt(at+28)==-1)return java.util.List.of();double x=trace.getFloat(at),y=trace.getFloat(at+4),z=trace.getFloat(at+8),r=trace.getFloat(at+12);check(Double.isFinite(r)&&r>=0,"finite query radius");double pad=1e-4+r*1e-5+Math.max(Math.ulp((float)x),Math.max(Math.ulp((float)y),Math.ulp((float)z)));long ax=cell(x-r-pad)-1,bx=cell(x+r+pad)+1,ay=cell(y-r-pad)-1,by=cell(y+r+pad)+1,az=cell(z-r-pad)-1,bz=cell(z+r+pad)+1;if((double)(bx-ax+1)*(by-ay+1)*(bz-az+1)>200000)return ids;var found=new java.util.ArrayList<Integer>();for(long a=ax;a<=bx;a++)for(long b=ay;b<=by;b++)for(long c=az;c<=bz;c++){var list=grid.get(new Cell(a,b,c));if(list!=null)found.addAll(list);}return found;});
 }
 static void validateWith(ByteBuffer pool,ByteBuffer emit,ByteBuffer trace,int live,int rows,java.util.function.IntFunction<Iterable<Integer>> candidates){
  for(int row=0;row<rows;row++){int at=row*32,self=trace.getInt(at+28);if(self==-1)continue;check(self>=0&&self<live,"force identity");float x=trace.getFloat(at),y=trace.getFloat(at+4),z=trace.getFloat(at+8),radius=trace.getFloat(at+12);int mine=((int)(pool.getFloat(self*64+12)+.5f)&262143)-1;double[] sum=new double[3],abs=new double[3];int count=0;
   for(int j:candidates.apply(row)){if(j==self)continue;int hb=pool.getInt(j*64+60)*com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.VEC4_PER_EMITTER*16;float material=emit.getFloat(hb+112);if(emit.getFloat(hb+288)<.5f||material<1.5f||material>=2.5f)continue;float dx=x-pool.getFloat(j*64),dy=y-pool.getFloat(j*64+4),dz=z-pool.getFloat(j*64+8),sq=(dx*dx+dy*dy)+dz*dz;if(sq>=radius*radius)continue;
    if(sq<=1e-8f){overlapTerms++;int other=((int)(pool.getFloat(j*64+12)+.5f)&262143)-1,mi=Math.min(mine,other),sign=mine<other?1:-1;check(mi>=0&&mi<131072,"overlap member");dx=deltas.getFloat(mi*16)*sign;dy=deltas.getFloat(mi*16+4)*sign;dz=deltas.getFloat(mi*16+8)*sign;sq=(dx*dx+dy*dy)+dz*dz;}
    if(sq<radius*radius&&sq>1e-8f){float len=(float)Math.sqrt(sq),w=(radius-len)/(len*radius);float[] term={dx*w,dy*w,dz*w};for(int k=0;k<3;k++){sum[k]+=term[k];abs[k]+=Math.abs(term[k]);}count++;}}
   double u=Math.scalb(1.,-24),gamma=Math.max(0,count-1)*u/(1-Math.max(0,count-1)*u);for(int k=0;k<3;k++){float value=trace.getFloat(at+16+k*4);check(Float.isFinite(value)&&Math.abs(value-sum[k])<=(gamma+32*u)*abs[k]+2e-5,"FP32 force bound row="+row+" component="+k+" observed="+value+" expected="+sum[k]);}queries++;
  }
 }
 static void close(){if(helper!=0)deleteProgram(helper);if(deltaBuffer!=0)GL15.glDeleteBuffers(deltaBuffer);helper=deltaBuffer=0;deltas=null;}
}
