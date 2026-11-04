package com.iridium126.createmanaindustry.client.gpu.buffer;

import java.nio.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

/** Opt-in diagnostic command journal. No GL queries, data copies or parameter
 * arrays are created when disabled. The sink must not throw into simulation. */
public final class GpuReplayCommands {
    public interface Sink {void before(String operation,long[] parameters,ByteBuffer bytes);}
    private static Sink sink;
    private GpuReplayCommands(){}
    public static boolean active(){return sink!=null;}
    public static void install(Sink value){if(value!=null&&sink!=null&&value!=sink)throw new IllegalStateException("GPU command capture already active");sink=value;}
    public static void before(String name,long[] parameters,ByteBuffer data){var value=sink;if(value!=null)value.before(name,parameters,data);}
    public static ByteBuffer bytes(Buffer value){
        if(value==null)return null;if(value instanceof ByteBuffer b)return b.duplicate();
        int width=value instanceof IntBuffer||value instanceof FloatBuffer?4:0;if(width==0||!value.isDirect())throw new IllegalArgumentException("Direct transfer buffer required");return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(value),Math.multiplyExact(value.remaining(),width));
    }
    public static void upload(int target,long offset,Buffer data,boolean allocation,int usage){if(sink!=null)before(allocation?"allocate-upload":"upload",new long[]{target,offset,usage},bytes(data));}
    public static void upload(int target,long offset,float[] data){if(sink!=null){var bytes=ByteBuffer.allocate(data.length*4).order(ByteOrder.nativeOrder());for(float v:data)bytes.putFloat(v);bytes.flip();before("upload",new long[]{target,offset,0},bytes);}}
    public static void upload(int target,long offset,int[] data){if(sink!=null){var bytes=ByteBuffer.allocate(data.length*4).order(ByteOrder.nativeOrder());for(int v:data)bytes.putInt(v);bytes.flip();before("upload",new long[]{target,offset,0},bytes);}}
    public static void allocation(int target,long bytes,int usage){if(sink!=null)before("allocate",new long[]{target,bytes,usage},null);}
    public static void texture3d(int target,int level,int x,int y,int z,int width,int height,int depth,int format,int type,ByteBuffer data){if(sink!=null)before("texture-upload",new long[]{target,level,x,y,z,width,height,depth,format,type},data);}
    public static void dispatchCompute(int x,int y,int z){if(sink!=null)before("dispatch",new long[]{x,y,z},null);GL43.glDispatchCompute(x,y,z);}
    public static void memoryBarrier(int bits){if(sink!=null)before("barrier",new long[]{Integer.toUnsignedLong(bits)},null);GL42.glMemoryBarrier(bits);}
    public static void copyNamedBufferSubData(int read,int write,long from,long to,long size){
        if(sink!=null){int oldRead=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER),oldWrite=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,write);before("copy",new long[]{GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,from,to,size},null);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,oldRead);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,oldWrite);}
        GL45.glCopyNamedBufferSubData(read,write,from,to,size);
    }
    public static void clearNamedBufferData(int buffer,int internal,int format,int type,IntBuffer data){
        if(sink!=null){int old=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,buffer);before("clear",new long[]{GL31.GL_COPY_WRITE_BUFFER,internal,format,type},bytes(data));GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,old);}
        GL45.glClearNamedBufferData(buffer,internal,format,type,data);
    }
    public static void namedBufferSubData(int buffer,long offset,ByteBuffer data){
        if(sink!=null){int old=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,buffer);upload(GL31.GL_COPY_WRITE_BUFFER,offset,data,false,0);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,old);}
        GL45.glNamedBufferSubData(buffer,offset,data);
    }
    public static void dispatchComputeIndirect(long offset){if(sink!=null)before("dispatch-indirect",new long[]{offset},null);GL43.glDispatchComputeIndirect(offset);}
    public static void copyBufferSubData(int read,int write,long from,long to,long bytes){if(sink!=null)before("copy",new long[]{read,write,from,to,bytes},null);GL31.glCopyBufferSubData(read,write,from,to,bytes);}
    public static void clearBufferData(int target,int internal,int format,int type,IntBuffer data){if(sink!=null)before("clear",new long[]{target,internal,format,type},bytes(data));GL43.glClearBufferData(target,internal,format,type,data);}
    public static void clearBufferSubData(int target,int internal,long offset,long length,int format,int type,IntBuffer data){if(sink!=null)before("clear-range",new long[]{target,internal,offset,length,format,type},bytes(data));GL43.glClearBufferSubData(target,internal,offset,length,format,type,data);}
    public static void clearBufferData(int target,int internal,int format,int type,ByteBuffer data){if(sink!=null)before("clear",new long[]{target,internal,format,type},data);GL43.glClearBufferData(target,internal,format,type,data);}
    public static void clearBufferSubData(int target,int internal,long offset,long length,int format,int type,ByteBuffer data){if(sink!=null)before("clear-range",new long[]{target,internal,offset,length,format,type},data);GL43.glClearBufferSubData(target,internal,offset,length,format,type,data);}
    private static ByteBuffer array(int[] values){var b=ByteBuffer.allocate(values.length*4).order(ByteOrder.nativeOrder());for(int v:values)b.putInt(v);return b.flip();}
    private static ByteBuffer array(float[] values){var b=ByteBuffer.allocate(values.length*4).order(ByteOrder.nativeOrder());for(float v:values)b.putFloat(v);return b.flip();}
    public static void clearBufferData(int target,int internal,int format,int type,float[] data){if(sink!=null)before("clear",new long[]{target,internal,format,type},data==null?null:array(data));GL43.glClearBufferData(target,internal,format,type,data);}
    public static void clearBufferData(int target,int internal,int format,int type,int[] data){if(sink!=null)before("clear",new long[]{target,internal,format,type},data==null?null:array(data));GL43.glClearBufferData(target,internal,format,type,data);}
    public static void clearBufferSubData(int target,int internal,long offset,long length,int format,int type,int[] data){if(sink!=null)before("clear-range",new long[]{target,internal,offset,length,format,type},data==null?null:array(data));GL43.glClearBufferSubData(target,internal,offset,length,format,type,data);}
}
