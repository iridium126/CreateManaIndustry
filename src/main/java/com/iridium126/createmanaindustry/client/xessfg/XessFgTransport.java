package com.iridium126.createmanaindustry.client.xessfg;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.*;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.system.MemoryUtil.memAddress;

final class XessFgTransport implements AutoCloseable {
    final Group geometry, color, hudBase;

    XessFgTransport(long session, int index) {
        geometry = index < XessFgNative.GEOMETRY_SLOTS ? new Group(session, index, XessFgNative.GEOMETRY) : null;
        Group packedColor = null;
        try {
            packedColor = new Group(session, index, XessFgNative.COLOR);
            hudBase = geometry != null && packedColor.sharedInputs ? new Group(session, index, XessFgNative.HUD_BASE) : null;
            color = packedColor;
        } catch (RuntimeException | LinkageError failure) {
            if (packedColor != null) packedColor.close();
            if (geometry != null) geometry.close();
            throw failure;
        }
    }

    static boolean supported() {
        var gl = GL.getCapabilities();
        return gl.OpenGL45 && gl.GL_EXT_memory_object_win32 && gl.GL_EXT_semaphore_win32
                && gl.glNamedBufferStorageMemEXT != 0;
    }

    static void checkError(String call) {
        int code = glGetError();
        if (code != GL_NO_ERROR) throw new IllegalStateException(call + " (OpenGL 0x" + Integer.toHexString(code) + ")");
    }

    @Override
    public void close() {
        if (geometry != null) geometry.close();
        if (hudBase != null) hudBase.close();
        color.close();
    }

    static final class Group implements AutoCloseable {
        private int memory, produced, released;
        final int phase, rowWords, secondOffset, transportMode, width, height;
        final boolean sharedInputs;
        int buffer;
        private long lastToken;

        Group(long session, int index, int phase) {
            this.phase = phase;
            var handles = XessFgNative.exportGroup(session, index, phase);
            try {
                memory = glCreateMemoryObjectsEXT();
                glMemoryObjectParameteriEXT(memory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
                glImportMemoryWin32HandleEXT(memory, handles[3], GL_HANDLE_TYPE_D3D12_RESOURCE_EXT, handles[0]);
                buffer = glCreateBuffers();
                glNamedBufferStorageMemEXT(buffer, handles[4], memory, 0);
                produced = glGenSemaphoresEXT();
                released = glGenSemaphoresEXT();
                glImportSemaphoreWin32HandleEXT(produced, GL_HANDLE_TYPE_D3D12_FENCE_EXT, handles[1]);
                glImportSemaphoreWin32HandleEXT(released, GL_HANDLE_TYPE_D3D12_FENCE_EXT, handles[2]);
                checkError("Importing XeSS-FG shared storage");
                rowWords = Math.toIntExact(handles[5] / Integer.BYTES);
                secondOffset = Math.toIntExact(handles[6] / Integer.BYTES);
                transportMode = Math.toIntExact(handles[7]);
                sharedInputs = handles[8] != 0;
                width = Math.toIntExact(handles[9]);
                height = Math.toIntExact(handles[10]);
            } catch (RuntimeException | LinkageError failure) {
                close();
                throw failure;
            } finally {
                for (int i = 0; i < 3; i++) XessFgNative.closeHandle(handles[i]);
            }
        }

        void acquire() {
            if (lastToken == 0) return;
            try (var stack = MemoryStack.stackPush()) {
                glSemaphoreParameterui64EXT(released, GL_D3D12_FENCE_VALUE_EXT, lastToken);
                nglWaitSemaphoreEXT(released, 1, memAddress(stack.ints(buffer)), 0, 0, 0);
            }
            checkError("Acquiring XeSS-FG transport slot");
        }

        void publish(long sequence) {
            long token = phase == XessFgNative.HUD_BASE ? sequence : 2 * sequence + phase;
            try (var stack = MemoryStack.stackPush()) {
                glSemaphoreParameterui64EXT(produced, GL_D3D12_FENCE_VALUE_EXT, token);
                nglSignalSemaphoreEXT(produced, 1, memAddress(stack.ints(buffer)), 0, 0, 0);
            }
            checkError("Publishing XeSS-FG transport slot");
            glFlush();
            lastToken = token;
        }

        @Override
        public void close() {
            if (buffer != 0) glDeleteBuffers(buffer);
            if (memory != 0) glDeleteMemoryObjectsEXT(memory);
            if (produced != 0) glDeleteSemaphoresEXT(produced);
            if (released != 0) glDeleteSemaphoresEXT(released);
            buffer = memory = produced = released = 0;
        }
    }
}
