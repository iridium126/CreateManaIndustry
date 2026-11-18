package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.RT_SLOTS;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.*;
import static org.lwjgl.system.MemoryUtil.memAddress;

/** OpenGL ownership of DXR snapshots. The native worker owns the D3D12 allocations. */
final class StormDxrSharedDisplay implements AutoCloseable {
    private static final class Slot {
        int buffer, memory, semaphore;
        boolean acquired, released;

        void delete() {
            if (buffer != 0) GL45.glDeleteBuffers(buffer);
            if (memory != 0) glDeleteMemoryObjectsEXT(memory);
            if (semaphore != 0) glDeleteSemaphoresEXT(semaphore);
            buffer = memory = semaphore = 0;
        }
    }

    private final Slot[] slots = new Slot[RT_SLOTS];

    StormDxrSharedDisplay() {
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
    }

    static void requireInterop(StormGpuDevices.RenderAdapter renderer) {
        var caps = GL.getCapabilities();
        if (renderer.nodes() != 1 || !caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_semaphore_win32
                || caps.glNamedBufferStorageMemEXT == 0)
            throw new IllegalStateException("Same-adapter Storm DXR requires Win32 memory/semaphore sharing on D3D12 node 0");
    }

    int acquire(long worker, int index, long sequence) {
        var slot = slots[index];
        if (slot.acquired) throw new IllegalStateException("Storm DXR display already belongs to OpenGL");
        if (slot.buffer == 0) importSlot(worker, index, slot);
        try (var stack = MemoryStack.stackPush()) {
            glSemaphoreParameterui64EXT(slot.semaphore, GL_D3D12_FENCE_VALUE_EXT, sequence * 2 - 1);
            nglWaitSemaphoreEXT(slot.semaphore, 1, memAddress(stack.ints(slot.buffer)), 0, 0, 0);
        }
        checkGl("Acquiring shared Storm DXR display");
        slot.acquired = true;
        return slot.buffer;
    }

    private static void importSlot(long worker, int index, Slot slot) {
        long[] handles = StormDxrNative.sharedDisplayHandles(worker, index);
        try {
            slot.memory = glCreateMemoryObjectsEXT();
            glMemoryObjectParameteriEXT(slot.memory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL11.GL_TRUE);
            glImportMemoryWin32HandleEXT(slot.memory, handles[2], GL_HANDLE_TYPE_D3D12_RESOURCE_EXT, handles[0]);
            slot.buffer = GL45.glCreateBuffers();
            glNamedBufferStorageMemEXT(slot.buffer, handles[3], slot.memory, 0);
            slot.semaphore = glGenSemaphoresEXT();
            glImportSemaphoreWin32HandleEXT(slot.semaphore, GL_HANDLE_TYPE_D3D12_FENCE_EXT, handles[1]);
            checkGl("Importing shared Storm DXR display");
        } catch (RuntimeException | LinkageError error) {
            slot.delete();
            throw error;
        } finally {
            StormDxrNative.closeSharedDisplayHandles(handles[0], handles[1]);
        }
    }

    boolean recycle(long worker, int index, long sequence) {
        var slot = slots[index];
        if (slot.acquired && !slot.released) {
            try (var stack = MemoryStack.stackPush()) {
                glSemaphoreParameterui64EXT(slot.semaphore, GL_D3D12_FENCE_VALUE_EXT, sequence * 2);
                nglSignalSemaphoreEXT(slot.semaphore, 1, memAddress(stack.ints(slot.buffer)), 0, 0, 0);
            }
            checkGl("Releasing shared Storm DXR display");
            GL11.glFlush();
            slot.released = true;
        }
        if (!StormDxrNative.recycleStormDisplay(worker, index, sequence, slot.released ? 1 : 0)) return false;
        slot.acquired = slot.released = false;
        return true;
    }

    private static void checkGl(String operation) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR)
            throw new IllegalStateException(operation + " failed (GL 0x" + Integer.toHexString(error) + ")");
    }

    @Override public void close() {
        for (var slot : slots) slot.delete();
    }
}
