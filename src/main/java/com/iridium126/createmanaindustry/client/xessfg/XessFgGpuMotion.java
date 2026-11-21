package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL45.*;

/** Records evaluated vertices and fragment coverage in the original draw, without a GPU readback. */
public final class XessFgGpuMotion implements AutoCloseable {
    private static final int GRID = 31, PREVIOUS = 32, WINNERS = 34, TOKENS = 35;
    private static final int VERTEX_BYTES = 32, HISTORY_ENTRIES = 1 << 20, GRID_BYTES = 64;
    private static GLCapabilities shaderContext;
    private static int inactiveGrid;
    private static boolean available;

    private final int width, height, grid, winners;
    private final int[] history = new int[2];
    private int epoch, lastEpoch, lastSlot;
    private boolean active, used, suspended;

    /** Decoration requires the atomic feature and storage bindings beyond the particle engine namespace. */
    public static boolean shaderSupport() {
        if (!ClientConfig.xessFg().enabled() && !ClientConfig.dlss().enabled()) return false;
        GLCapabilities caps = GL.getCapabilities();
        if (shaderContext != caps) {
            shaderContext = caps;
            available = caps.OpenGL45 && caps.GL_ARB_gpu_shader_int64 && caps.GL_NV_shader_atomic_int64
                    && glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) > TOKENS
                    && glGetInteger(GL_MAX_UNIFORM_BUFFER_BINDINGS) > GRID
                    && glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE) >= (long) VERTEX_BYTES * HISTORY_ENTRIES;
            if (available) {
                inactiveGrid = storage(GRID_BYTES);
                bind(GL_UNIFORM_BUFFER, GRID, inactiveGrid);
            }
        }
        return available;
    }

    XessFgGpuMotion(int width, int height) {
        this.width = width;
        this.height = height;
        grid = storage(GRID_BYTES);
        history[0] = storage((long) VERTEX_BYTES * HISTORY_ENTRIES);
        history[1] = storage((long) VERTEX_BYTES * HISTORY_ENTRIES);
        winners = storage((long) width * height * 16);
    }

    int width() { return width; }
    int height() { return height; }

    private static int storage(long bytes) {
        int buffer = glCreateBuffers();
        glNamedBufferStorage(buffer, bytes, GL_DYNAMIC_STORAGE_BIT);
        glClearNamedBufferData(buffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        return buffer;
    }

    private static void bind(int target, int index, int id) {
        try (var stack = MemoryStack.stackPush()) {
            glBindBuffersBase(target, index, stack.ints(id));
        }
    }

    void begin(Vec3 camera, int sourceWidth, int sourceHeight, XessFgCoordinates coords) {
        if (active) return;
        active = true;
        used = false;
        if (++epoch == 0) {
            for (int buffer : history) glClearNamedBufferData(buffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
            epoch = 1;
            lastEpoch = 0;
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        glClearNamedBufferData(winners, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        try (var stack = MemoryStack.stackPush()) {
            int x = (int) Math.floor(camera.x), y = (int) Math.floor(camera.y), z = (int) Math.floor(camera.z);
            var data = stack.malloc(GRID_BYTES);
            data.putInt(epoch).putInt(lastEpoch).putInt(width).putInt(height);
            data.putFloat(sourceWidth * coords.scaleX()).putFloat(sourceHeight * coords.scaleY());
            data.putFloat(coords.jitterX()).putFloat(coords.jitterY());
            data.putInt(x).putInt(y).putInt(z).putInt(0);
            data.putFloat((float) (camera.x - x)).putFloat((float) (camera.y - y)).putFloat((float) (camera.z - z))
                    .putFloat(DlssRuntime.externalJitter() ? 1 : 0);
            glNamedBufferSubData(grid, 0, data.flip());
            glBindBuffersBase(GL_SHADER_STORAGE_BUFFER, PREVIOUS, stack.ints(history[lastSlot], history[1 - lastSlot], winners));
            bind(GL_UNIFORM_BUFFER, GRID, grid);
        }
    }

    void terrain() {
        used |= active;
    }

    void discardUnfinishedFrame() {
        if (active) finish(false);
    }

    XessFgRuntime.MotionScope suspend() {
        if (!active || suspended) return XessFgRuntime.MotionScope.INACTIVE;
        suspended = true;
        bind(GL_UNIFORM_BUFFER, GRID, inactiveGrid);
        return () -> {
            if (active) bind(GL_UNIFORM_BUFFER, GRID, grid);
            suspended = false;
        };
    }

    void particle(int shader, int identities, int wordBase) {
        if (!active || identities == 0) return;
        int location = GpuUniforms.loc(shader, "cmiFgIdentityBase");
        if (location < 0) return;
        bind(GL_SHADER_STORAGE_BUFFER, TOKENS, identities);
        glProgramUniform1ui(shader, location, wordBase);
        used = true;
    }

    void lod(int shader, Matrix4fc scene, Matrix4fc lod) {
        if (!active) return;
        int location = GpuUniforms.loc(shader, "cmiFgLodDepth");
        if (location < 0) return;
        // StormLodGpu extends Z while preserving XY and clip W.
        float scale = scene.m32() / lod.m32();
        float bias = (1 - scene.m22() - scale * (1 - lod.m22())) * 0.5f;
        glProgramUniform2f(shader, location, scale, bias);
        used = true;
    }

    boolean hasWinners(boolean reset) {
        return active && used && !reset;
    }

    int winners() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        return winners;
    }

    void finish(boolean keep) {
        if (active) {
            bind(GL_UNIFORM_BUFFER, GRID, inactiveGrid);
            try (var stack = MemoryStack.stackPush()) {
                glBindBuffersBase(GL_SHADER_STORAGE_BUFFER, PREVIOUS, stack.ints(0, 0, 0, 0));
            }
        }
        if (active && keep) {
            lastSlot = 1 - lastSlot;
            lastEpoch = epoch;
        } else {
            lastEpoch = 0;
        }
        active = false;
    }

    @Override
    public void close() {
        finish(false);
        glDeleteBuffers(history);
        glDeleteBuffers(grid);
        glDeleteBuffers(winners);
    }
}
