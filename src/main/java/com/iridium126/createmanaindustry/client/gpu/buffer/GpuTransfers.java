package com.iridium126.createmanaindustry.client.gpu.buffer;

import java.nio.*;
import org.lwjgl.opengl.GL15;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger;

/** Actual transfer boundary. Allocation and device copies are not host transfer bytes. */
public final class GpuTransfers {
    private GpuTransfers() {}
    public static void allocate(int target,long bytes,int usage) { GL15.glBufferData(target,bytes,usage); }
    public static void allocate(int target,ByteBuffer data,int usage) { GL15.glBufferData(target,data,usage);if(data!=null)GpuBudgetLedger.uploaded(data.remaining()); }
    public static void allocate(int target,FloatBuffer data,int usage) { GL15.glBufferData(target,data,usage);if(data!=null)GpuBudgetLedger.uploaded((long)data.remaining()*Float.BYTES); }
    public static void allocate(int target,IntBuffer data,int usage) { GL15.glBufferData(target,data,usage);if(data!=null)GpuBudgetLedger.uploaded((long)data.remaining()*Integer.BYTES); }
    public static void texture3d(int target,int level,int x,int y,int z,int width,int height,int depth,int format,int type,ByteBuffer data) {
        org.lwjgl.opengl.GL12.glTexSubImage3D(target,level,x,y,z,width,height,depth,format,type,data);
        GpuBudgetLedger.uploaded(data.remaining());
    }
    public static void upload(int target,long offset,ByteBuffer data) { GL15.glBufferSubData(target,offset,data);GpuBudgetLedger.uploaded(data.remaining()); }
    public static void upload(int target,long offset,FloatBuffer data) { GL15.glBufferSubData(target,offset,data);GpuBudgetLedger.uploaded((long)data.remaining()*Float.BYTES); }
    public static void upload(int target,long offset,IntBuffer data) { GL15.glBufferSubData(target,offset,data);GpuBudgetLedger.uploaded((long)data.remaining()*Integer.BYTES); }
    public static void upload(int target,long offset,float[] data) { GL15.glBufferSubData(target,offset,data);GpuBudgetLedger.uploaded((long)data.length*Float.BYTES); }
    public static void upload(int target,long offset,int[] data) { GL15.glBufferSubData(target,offset,data);GpuBudgetLedger.uploaded((long)data.length*Integer.BYTES); }
    public static void download(int target,long offset,ByteBuffer data) { GL15.glGetBufferSubData(target,offset,data);GpuBudgetLedger.downloaded(data.remaining()); }
    public static void download(int target,long offset,IntBuffer data) { GL15.glGetBufferSubData(target,offset,data);GpuBudgetLedger.downloaded((long)data.remaining()*Integer.BYTES); }
    public static void download(int target,long offset,FloatBuffer data) { GL15.glGetBufferSubData(target,offset,data);GpuBudgetLedger.downloaded((long)data.remaining()*Float.BYTES); }
}
