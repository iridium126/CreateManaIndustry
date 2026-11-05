package com.iridium126.createmanaindustry.client.gpu.shader;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4fc;
import java.nio.FloatBuffer;

/** Uniform operations for the current GL context. Program owners retire names before deletion. */
public final class GpuUniforms {
    private record Context(org.lwjgl.opengl.GLCapabilities gl,GpuUniformCache locations) {}
    private static final ThreadLocal<Context> CONTEXT=new ThreadLocal<>();
    private GpuUniforms() {}
    public static void clear() {var context=CONTEXT.get();if(context!=null)context.locations().clear();}
    public static void foreignBoundary(){var context=CONTEXT.get();if(context!=null)context.locations().invalidateValues();}
    public static void invalidateProgramValues(int program){var context=CONTEXT.get();if(context!=null)context.locations().invalidateValues(program);}


    public static void clearUniformProgram(int program){var context=CONTEXT.get();if(context!=null)context.locations().retire(program);}
    /** Owners may retain this context's cache for one binding scope; retirement clears it in place. */
    public static GpuUniformCache currentCache() {
        var gl=org.lwjgl.opengl.GL.getCapabilities();var context=CONTEXT.get();
        if(context==null||context.gl()!=gl){context=new Context(gl,new GpuUniformCache());CONTEXT.set(context);}
        return context.locations();
    }
    public static int loc(int prog, String name) { return currentCache().location(prog,name); }
    /** Sampler assignments persist with the linked program, independent of GL binding scopes. */
    public static void sampler(int program,String name,int unit) {
        var cache=currentCache();int location=cache.location(program,name);
        if(cache.samplerChanged(program,location,unit))uniform1i(program,location,unit);
    }

    public static void setUIntUniform(int prog, String name, int value) {
        uniform1ui(prog,loc(prog,name),value);
    }

    public static void setIntUniform(int prog, String name, int value) {
        uniform1i(prog,loc(prog,name),value);
    }

    public static void setFloatUniform(int prog, String name, float value) {
        uniform1f(prog,loc(prog,name),value);
    }

    public static void setFloatUniform(int prog, String name, float x, float y, float z) {
        uniform3f(prog,loc(prog,name),x,y,z);
    }

    public static void uniform1i(int program,int location,int x){
        if(currentCache().changed(program,location,1,x,0,0,0))org.lwjgl.opengl.GL41.glProgramUniform1i(program,location,x);
    }
    public static void uniform1ui(int program,int location,int x){
        if(currentCache().changed(program,location,1,x,0,0,0))org.lwjgl.opengl.GL41.glProgramUniform1ui(program,location,x);
    }
    public static void uniform1f(int program,int location,float x){
        if(currentCache().changed(program,location,1,Float.floatToRawIntBits(x),0,0,0))org.lwjgl.opengl.GL41.glProgramUniform1f(program,location,x);
    }
    public static void uniform2i(int program,int location,int x,int y){
        if(currentCache().changed(program,location,2,x,y,0,0))org.lwjgl.opengl.GL41.glProgramUniform2i(program,location,x,y);
    }
    public static void uniform2ui(int program,int location,int x,int y){
        if(currentCache().changed(program,location,2,x,y,0,0))org.lwjgl.opengl.GL41.glProgramUniform2ui(program,location,x,y);
    }
    public static void uniform2f(int program,int location,float x,float y){
        if(currentCache().changed(program,location,2,Float.floatToRawIntBits(x),Float.floatToRawIntBits(y),0,0))org.lwjgl.opengl.GL41.glProgramUniform2f(program,location,x,y);
    }
    public static void uniform3i(int program,int location,int x,int y,int z){
        if(currentCache().changed(program,location,3,x,y,z,0))org.lwjgl.opengl.GL41.glProgramUniform3i(program,location,x,y,z);
    }
    public static void uniform3ui(int program,int location,int x,int y,int z){
        if(currentCache().changed(program,location,3,x,y,z,0))org.lwjgl.opengl.GL41.glProgramUniform3ui(program,location,x,y,z);
    }
    public static void uniform3f(int program,int location,float x,float y,float z){
        if(currentCache().changed(program,location,3,Float.floatToRawIntBits(x),Float.floatToRawIntBits(y),Float.floatToRawIntBits(z),0))org.lwjgl.opengl.GL41.glProgramUniform3f(program,location,x,y,z);
    }
    public static void uniform4i(int program,int location,int x,int y,int z,int w){
        if(currentCache().changed(program,location,4,x,y,z,w))org.lwjgl.opengl.GL41.glProgramUniform4i(program,location,x,y,z,w);
    }
    public static void uniform4ui(int program,int location,int x,int y,int z,int w){
        if(currentCache().changed(program,location,4,x,y,z,w))org.lwjgl.opengl.GL41.glProgramUniform4ui(program,location,x,y,z,w);
    }
    public static void uniform4f(int program,int location,float x,float y,float z,float w){
        if(currentCache().changed(program,location,4,Float.floatToRawIntBits(x),Float.floatToRawIntBits(y),Float.floatToRawIntBits(z),Float.floatToRawIntBits(w)))org.lwjgl.opengl.GL41.glProgramUniform4f(program,location,x,y,z,w);
    }

    public static void current1i(int location,int x){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform1i(program,location,x);else org.lwjgl.opengl.GL20.glUniform1i(location,x);
    }
    public static void current1ui(int location,int x){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform1ui(program,location,x);else org.lwjgl.opengl.GL30.glUniform1ui(location,x);
    }
    public static void current1f(int location,float x){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform1f(program,location,x);else org.lwjgl.opengl.GL20.glUniform1f(location,x);
    }
    public static void current2i(int location,int x,int y){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform2i(program,location,x,y);else org.lwjgl.opengl.GL20.glUniform2i(location,x,y);
    }
    public static void current2ui(int location,int x,int y){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform2ui(program,location,x,y);else org.lwjgl.opengl.GL30.glUniform2ui(location,x,y);
    }
    public static void current2f(int location,float x,float y){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform2f(program,location,x,y);else org.lwjgl.opengl.GL20.glUniform2f(location,x,y);
    }
    public static void current3i(int location,int x,int y,int z){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform3i(program,location,x,y,z);else org.lwjgl.opengl.GL20.glUniform3i(location,x,y,z);
    }
    public static void current3ui(int location,int x,int y,int z){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform3ui(program,location,x,y,z);else org.lwjgl.opengl.GL30.glUniform3ui(location,x,y,z);
    }
    public static void current3f(int location,float x,float y,float z){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform3f(program,location,x,y,z);else org.lwjgl.opengl.GL20.glUniform3f(location,x,y,z);
    }
    public static void current4i(int location,int x,int y,int z,int w){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform4i(program,location,x,y,z,w);else org.lwjgl.opengl.GL20.glUniform4i(location,x,y,z,w);
    }
    public static void current4ui(int location,int x,int y,int z,int w){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform4ui(program,location,x,y,z,w);else org.lwjgl.opengl.GL30.glUniform4ui(location,x,y,z,w);
    }
    public static void current4f(int location,float x,float y,float z,float w){
        int program=com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.currentProgram();
        if(program>=0)uniform4f(program,location,x,y,z,w);else org.lwjgl.opengl.GL20.glUniform4f(location,x,y,z,w);
    }

    /** Uploads a vec4 uniform array ({@code values.length} must be a multiple of 4). */
    public static void setVec4ArrayUniform(int prog, String name, float[] values) {
        int l = loc(prog, name);
        if (l >= 0 && values.length >= 4)
            GL20.glUniform4fv(l, values);
    }

    /** Uploads a float uniform array ({@code uniform float name[N]}). */
    public static void setFloatArrayUniform(int prog, String name, float[] values) {
        int l = loc(prog, name);
        if (l >= 0 && values.length > 0)
            GL20.glUniform1fv(l, values);
    }

    public static void setMat4Uniform(int prog, String name, Matrix4fc matrix) {
        int l = loc(prog, name);
        if (l < 0)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            matrix.get(buf);
            GL20.glUniformMatrix4fv(l, false, buf);
        }
    }
}
