package com.iridium126.createmanaindustry.client.dlss;

import com.iridium126.createmanaindustry.client.gpu.state.GpuGlErrors;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.*;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.*;
import static org.lwjgl.opengl.GL45.*;
import static org.lwjgl.system.MemoryUtil.memAddress;

final class DlssInterop implements AutoCloseable {
    static final int COLOR = 0, DEPTH = 1, MOTION = 2, OUTPUT = 3;
    private static final int[] FORMATS = {GL_RGBA16F, GL_R32F, GL_RG16F, GL_RGBA16F};
    final int[] textures = new int[FORMATS.length];
    private int published, evaluated;

    static boolean supported() {
        var gl = GL.getCapabilities();
        return gl.OpenGL45 && gl.GL_EXT_memory_object_win32 && gl.GL_EXT_semaphore_win32
                && gl.glTextureStorageMem2DEXT != 0;
    }

    DlssInterop(long session, int slot, int width, int height, int outputWidth, int outputHeight) {
        long[] handles = new long[2 * FORMATS.length + 2];
        DlssNative.exportTextures(session, slot, handles);
        try {
            GpuGlErrors.begin("Importing DLSS textures and fences");
            for (int i = 0; i < textures.length; ++i) {
                int memory = glCreateMemoryObjectsEXT();
                try {
                    glMemoryObjectParameteriEXT(memory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
                    glImportMemoryWin32HandleEXT(memory, handles[i + FORMATS.length + 2],
                            GL_HANDLE_TYPE_D3D12_RESOURCE_EXT, handles[i]);
                    int texture = glCreateTextures(GL_TEXTURE_2D);
                    textures[i] = texture;
                    glTextureStorageMem2DEXT(texture, 1, FORMATS[i],
                            i == OUTPUT ? outputWidth : width, i == OUTPUT ? outputHeight : height, memory, 0);
                    glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                    glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                    glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                } finally {
                    glDeleteMemoryObjectsEXT(memory);
                }
            }
            published = glGenSemaphoresEXT();
            evaluated = glGenSemaphoresEXT();
            glImportSemaphoreWin32HandleEXT(published, GL_HANDLE_TYPE_D3D12_FENCE_EXT, handles[FORMATS.length]);
            glImportSemaphoreWin32HandleEXT(evaluated, GL_HANDLE_TYPE_D3D12_FENCE_EXT, handles[FORMATS.length + 1]);
            GpuGlErrors.check("Importing DLSS textures and fences");
        } catch (RuntimeException | LinkageError failure) {
            close();
            throw failure;
        } finally {
            for (int i = 0; i < FORMATS.length + 2; ++i) DlssNative.closeHandle(handles[i]);
        }
    }

    void publish(long token) {
        GpuGlErrors.begin("Publishing DLSS inputs");
        try (var stack = MemoryStack.stackPush()) {
            glSemaphoreParameterui64EXT(published, GL_D3D12_FENCE_VALUE_EXT, token);
            nglSignalSemaphoreEXT(published, 0, 0, textures.length, memAddress(stack.ints(textures)),
                    memAddress(stack.ints(GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT,
                            GL_LAYOUT_GENERAL_EXT)));
        }
        glFlush();
        GpuGlErrors.check("Publishing DLSS inputs");
    }

    void acquire(long token) {
        GpuGlErrors.begin("Acquiring the DLSS output");
        try (var stack = MemoryStack.stackPush()) {
            glSemaphoreParameterui64EXT(evaluated, GL_D3D12_FENCE_VALUE_EXT, token);
            nglWaitSemaphoreEXT(evaluated, 0, 0, textures.length, memAddress(stack.ints(textures)),
                    memAddress(stack.ints(GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT,
                            GL_LAYOUT_GENERAL_EXT)));
        }
        GpuGlErrors.check("Acquiring the DLSS output");
    }

    @Override
    public void close() {
        glDeleteTextures(textures);
        if (published != 0) glDeleteSemaphoresEXT(published);
        if (evaluated != 0) glDeleteSemaphoresEXT(evaluated);
    }
}
