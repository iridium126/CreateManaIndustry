package com.iridium126.createmanaindustry.client.gpu.state;

import java.util.Arrays;
import org.lwjgl.opengl.*;

/** Cache only inside an owned engine boundary; foreign GL code invalidates at entry/exit. */
public final class GpuBindings {
    private static final class State {
        Object context;int depth,program=-1;long submitted,avoided;
        final int[] generic=new int[12];
        final int[][] buffers=new int[4][64];
        final long[][] offsets=new long[4][64],sizes=new long[4][64];
        final int[][] textures=new int[64][7];
        // Texture, level, layered, layer, access, format; indexed independently of samplers.
        final int[][] images=new int[32][6];
        void clear(){program=-1;Arrays.fill(generic,-1);for(var row:buffers)Arrays.fill(row,-1);for(var row:textures)Arrays.fill(row,-1);for(var row:images)row[0]=-1;}
    }
    private static final ThreadLocal<State> LOCAL=ThreadLocal.withInitial(State::new);
    private GpuBindings() {}
    private static int targetSlot(int target){return switch(target){
        case GL43.GL_SHADER_STORAGE_BUFFER -> 0;case GL31.GL_UNIFORM_BUFFER -> 1;case GL42.GL_ATOMIC_COUNTER_BUFFER -> 2;case GL30.GL_TRANSFORM_FEEDBACK_BUFFER -> 3;
        case GL43.GL_DISPATCH_INDIRECT_BUFFER -> 4;case GL40.GL_DRAW_INDIRECT_BUFFER -> 5;
        case GL31.GL_COPY_READ_BUFFER -> 6;case GL31.GL_COPY_WRITE_BUFFER -> 7;
        case GL21.GL_PIXEL_PACK_BUFFER -> 8;case GL21.GL_PIXEL_UNPACK_BUFFER -> 9;
        case GL15.GL_ARRAY_BUFFER -> 10;default -> -1;
    };}
    public static void enter(){
        var s=LOCAL.get();var context=GL.getCapabilities();
        if(s.context!=context){s.context=context;s.depth=0;s.clear();}
        if(s.depth++==0){s.clear();com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.foreignBoundary();}
    }
    public static void exit(){var s=LOCAL.get();if(s.depth<=0)throw new IllegalStateException("GPU binding scope underflow");if(--s.depth==0){s.clear();com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.foreignBoundary();}}
    public static void invalidate(){LOCAL.get().clear();}
    public static void forget(int buffer){var s=LOCAL.get();for(int t=0;t<s.generic.length;t++)if(s.generic[t]==buffer)s.generic[t]=-1;for(int t=0;t<4;t++)for(int i=0;i<64;i++)if(s.buffers[t][i]==buffer)s.buffers[t][i]=-1;}
    public static void forgetProgram(int program){var s=LOCAL.get();if(s.program==program)s.program=-1;}
    public static void invalidateProgram(){LOCAL.get().program=-1;}
    /** Iris has bound this program and may have refreshed its uniforms. */
    public static void externalProgramApplied(int program){
        var s=LOCAL.get();if(s.depth>0)s.program=program;
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.invalidateProgramValues(program);
    }
    public static int currentProgram(){var s=LOCAL.get();return s.depth>0?s.program:-1;}
    public static void program(int program){var s=LOCAL.get();if(s.depth>0&&s.program==program){s.avoided++;return;}GL20.glUseProgram(program);s.submitted++;if(s.depth>0)s.program=program;}
    public static void forgetTexture(int texture){var s=LOCAL.get();for(var row:s.textures)for(int i=0;i<row.length;i++)if(row[i]==texture)row[i]=-1;for(var row:s.images)if(row[0]==texture)row[0]=-1;}
    public static void image(int unit,int texture,int level,boolean layered,int layer,int access,int format){
        var s=LOCAL.get();int[] row=s.depth>0&&unit>=0&&unit<s.images.length?s.images[unit]:null;
        int layers=layered?1:0;
        if(row!=null&&row[0]==texture&&row[1]==level&&row[2]==layers&&row[3]==layer&&row[4]==access&&row[5]==format){s.avoided++;return;}
        GL42.glBindImageTexture(unit,texture,level,layered,layer,access,format);
        s.submitted++;
        if(row!=null){row[0]=texture;row[1]=level;row[2]=layers;row[3]=layer;row[4]=access;row[5]=format;}
    }
    private static int textureSlot(int target){return switch(target){
        case GL11.GL_TEXTURE_1D -> 0;case GL11.GL_TEXTURE_2D -> 1;case GL12.GL_TEXTURE_3D -> 2;
        case GL13.GL_TEXTURE_CUBE_MAP -> 3;case GL30.GL_TEXTURE_2D_ARRAY -> 4;
        case GL31.GL_TEXTURE_BUFFER -> 5;case GL31.GL_TEXTURE_RECTANGLE -> 6;
        default -> throw new IllegalArgumentException("Undescribed texture target "+target);
    };}
    /** DSA keeps the active texture unit untouched; callers create and validate views once. */
    public static void texture(int unit,int target,int texture){
        var s=LOCAL.get();int t=textureSlot(target);boolean cache=s.depth>0&&unit>=0&&unit<s.textures.length;
        if(cache&&s.textures[unit][t]==texture){s.avoided++;return;}
        if(texture!=0){GL45.glBindTextureUnit(unit,texture);if(cache)s.textures[unit][t]=texture;}
        else {
            // DSA zero clears every target on the unit. Only release this target.
            int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(target,0);GL13.glActiveTexture(active);
            if(cache)s.textures[unit][t]=0;
        }
        s.submitted++;
    }
    public static long submitted(){return LOCAL.get().submitted;}
    public static long avoided(){return LOCAL.get().avoided;}
    public static void bind(int target,int buffer){
        var s=LOCAL.get();int t=targetSlot(target);
        if(s.depth>0&&t>=0){if(s.generic[t]==buffer){s.avoided++;return;}s.generic[t]=buffer;}
        GL15.glBindBuffer(target,buffer);s.submitted++;
    }
    public static void bindBase(int target,int index,int buffer){bindIndexed(target,index,buffer,0,-1);}
    public static void bindRange(int target,int index,int buffer,long offset,long size){bindIndexed(target,index,buffer,offset,size);}
    public static void bindBases(int target,int first,java.nio.IntBuffer buffers){
        var s=LOCAL.get();int t=targetSlot(target);int count=buffers.remaining();
        boolean cache=s.depth>0&&t>=0&&first>=0&&first+count<=64;
        boolean same=cache;
        if(cache)for(int k=0;k<count;k++)if(s.buffers[t][first+k]!=buffers.get(buffers.position()+k)||s.sizes[t][first+k]!=-1){same=false;break;}
        if(same){s.avoided++;return;}
        GL44.glBindBuffersBase(target,first,buffers);s.submitted++;
        if(cache)for(int k=0;k<count;k++){int at=first+k;s.buffers[t][at]=buffers.get(buffers.position()+k);s.offsets[t][at]=0;s.sizes[t][at]=-1;}
    }
    private static void bindIndexed(int target,int index,int buffer,long offset,long size){
        var s=LOCAL.get();int t=targetSlot(target);boolean cache=s.depth>0&&t>=0&&t<4&&index>=0&&index<64;
        if(cache&&s.buffers[t][index]==buffer&&s.offsets[t][index]==offset&&s.sizes[t][index]==size){s.avoided++;bind(target,buffer);return;}
        if(size<0)GL30.glBindBufferBase(target,index,buffer);else GL30.glBindBufferRange(target,index,buffer,offset,size);
        s.submitted++;
        if(cache){s.buffers[t][index]=buffer;s.offsets[t][index]=offset;s.sizes[t][index]=size;s.generic[t]=buffer;}
    }
}
