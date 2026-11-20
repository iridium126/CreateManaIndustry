package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderResources;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.glGetInteger64i;
import static org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.*;

final class XessFgCapture implements AutoCloseable {
    static final int FINAL = 0, HUDLESS = 1, DEPTH = 2, MOTION = 3;
    private static final int DEPTH_ONLY = 4, FINAL_WITH_MASK = 5, APPLY_MOTION = 6, INITIALIZE_MOTION = 7;
    private final int program;
    private final int sourceSize, extent, rowWords, offsets, mode, currentToPrevious, skyToPrevious;
    private final int sourceScale, currentJitter, warpTypes, warpParameters, transportMode;

    XessFgCapture() {
        String shader = GpuShaderResources.loader(CreateManaIndustry.MODID, "shaders/xessfg/").apply("export.comp");
        program = GpuShaderCompiler.compute("XeSS-FG GPU transport", shader);
        sourceSize = glGetUniformLocation(program, "sourceSize");
        extent = glGetUniformLocation(program, "extent");
        rowWords = glGetUniformLocation(program, "rowWords");
        offsets = glGetUniformLocation(program, "offsets");
        mode = glGetUniformLocation(program, "mode");
        transportMode = glGetUniformLocation(program, "transport");
        currentToPrevious = glGetUniformLocation(program, "currentToPrevious");
        skyToPrevious = glGetUniformLocation(program, "skyToPrevious");
        sourceScale = glGetUniformLocation(program, "sourceScale");
        currentJitter = glGetUniformLocation(program, "currentJitter");
        warpTypes = glGetUniformLocation(program, "warpTypes");
        warpParameters = glGetUniformLocation(program, "warpParameters");
    }

    void color(XessFgTransport slot, int texture, int sourceWidth, int sourceHeight, int plane, boolean full) {
        if (plane == HUDLESS && slot.hudBase != null) {
            slot.hudBase.acquire();
            dispatch(slot.hudBase, texture, sourceWidth, sourceHeight, FINAL, 0);
        } else if (plane == FINAL && full && slot.hudBase != null) {
            slot.hudBase.acquire();
            dispatch(slot.color, texture, sourceWidth, sourceHeight, FINAL_WITH_MASK, slot.hudBase.buffer);
        } else {
            dispatch(slot.color, texture, sourceWidth, sourceHeight, plane, 0);
        }
    }

    boolean canReproject(XessFgTransport slot, XessFgView current, XessFgView previous) {
        return slot.geometry.sharedInputs && current.coordinates().warp() == XessFgCoordinates.Warp.NONE
                && previous.coordinates().warp() == XessFgCoordinates.Warp.NONE;
    }

    void applyMotion(XessFgTransport slot, int texture, boolean initialize) {
        var geometry = slot.geometry;
        dispatch(geometry, texture, geometry.width, geometry.height, initialize ? INITIALIZE_MOTION : APPLY_MOTION, 0);
    }

    void depthAndMotion(XessFgTransport slot, int texture, int sourceWidth, int sourceHeight,
                        XessFgView current, XessFgView previous, ByteBuffer reprojection) {
        var withTranslation = current.reproject(previous, true);
        var withoutTranslation = current.reproject(previous, false);
        var now = current.coordinates();
        var then = previous.coordinates();
        glProgramUniform2f(program, sourceScale, now.scaleX(), now.scaleY());
        glProgramUniform2f(program, currentJitter, now.jitterX(), now.jitterY());
        glProgramUniform2i(program, warpTypes, now.warp().ordinal(), then.warp().ordinal());
        glProgramUniform4f(program, warpParameters, now.time(), now.strength(), then.time(), then.strength());
        if (reprojection != null) {
            matrix(reprojection, 0, withTranslation);
            matrix(reprojection, 64, withoutTranslation);
            reprojection.putFloat(128, now.jitterX()).putFloat(132, now.jitterY());
            reprojection.putFloat(136, sourceWidth * now.scaleX()).putFloat(140, sourceHeight * now.scaleY());
            dispatch(slot.geometry, texture, sourceWidth, sourceHeight, DEPTH_ONLY, 0);
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            glProgramUniformMatrix4fv(program, currentToPrevious, false, withTranslation.get(stack.mallocFloat(16)));
            glProgramUniformMatrix4fv(program, skyToPrevious, false, withoutTranslation.get(stack.mallocFloat(16)));
        }
        dispatch(slot.geometry, texture, sourceWidth, sourceHeight, DEPTH, 0);
    }

    private void dispatch(XessFgTransport.Group group, int texture, int sourceWidth, int sourceHeight, int plane, int base) {
        int oldProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int oldBuffer = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        int oldIndexed = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0);
        long oldOffset = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 0);
        long oldSize = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 0);
        int oldBase = base != 0 ? glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 1) : 0;
        long oldBaseOffset = base != 0 ? glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 1) : 0;
        long oldBaseSize = base != 0 ? glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 1) : 0;
        int oldUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int oldTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int oldSampler = glGetInteger(GL_SAMPLER_BINDING);
        try {
            glBindSampler(0, 0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, group.buffer);
            if (base != 0) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, base);
            glUseProgram(program);
            glUniform2i(sourceSize, sourceWidth, sourceHeight);
            glUniform2i(extent, group.width, group.height);
            glUniform1i(rowWords, group.rowWords);
            glUniform4i(offsets, 0, group.secondOffset, 0, group.secondOffset);
            glUniform1i(mode, plane);
            glUniform1i(transportMode, group.transportMode);
            int pixelsPerInvocation = switch (group.transportMode) {
                case 1 -> 4;
                case 2 -> 2;
                default -> 1;
            };
            int groupWidth = plane == FINAL_WITH_MASK ? 128 : 16 * pixelsPerInvocation;
            glDispatchCompute((group.width + groupWidth - 1) / groupWidth, (group.height + 7) / 8, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            if (base != 0) restoreStorage(1, oldBase, oldBaseOffset, oldBaseSize);
            restoreStorage(0, oldIndexed, oldOffset, oldSize);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, oldBuffer);
            glBindTexture(GL_TEXTURE_2D, oldTexture);
            glBindSampler(0, oldSampler);
            glActiveTexture(oldUnit);
            glUseProgram(oldProgram);
        }
    }

    private static void restoreStorage(int index, int buffer, long offset, long size) {
        if (buffer != 0 && size > 0) glBindBufferRange(GL_SHADER_STORAGE_BUFFER, index, buffer, offset, size);
        else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, index, buffer);
    }

    static void matrix(ByteBuffer buffer, int offset, Matrix4fc matrix) {
        // JOML stores column-vector matrices by column. XeSS consumes the equivalent
        // row-vector matrices by row, so their serialized floats have the same order.
        matrix.get(offset, buffer);
    }

    static Matrix4f dxProjection(Matrix4fc projection) {
        return new Matrix4f().translation(0, 0, 0.5f).scale(1, 1, 0.5f).mul(projection);
    }

    @Override
    public void close() {
        GpuPrograms.delete(program);
    }
}
