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
    private static final int DEPTH_ONLY = 4, FINAL_WITH_MASK = 5;
    private final int program;
    private final int sourceSize, extent, rowWords, offsets, mode, currentToPrevious, skyToPrevious;
    private final int sourceScale, currentJitter, warpTypes, warpParameters, transportMode;
    private final int width, height;

    XessFgCapture(int width, int height) {
        this.width = width;
        this.height = height;
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
        int output = plane == FINAL && full && slot.color.sharedInputs ? FINAL_WITH_MASK : plane;
        dispatch(slot.color, texture, sourceWidth, sourceHeight, output);
    }

    boolean canReproject(XessFgTransport slot, int sourceWidth, int sourceHeight,
                         XessFgView current, XessFgView previous) {
        var now = current.coordinates();
        // With one source texel per output pixel, jitter inside this interval cannot
        // change which depth texel the existing OpenGL export selects.
        return slot.geometry.sharedInputs && sourceWidth == width && sourceHeight == height
                && now.scaleX() == 1 && now.scaleY() == 1
                && now.warp() == XessFgCoordinates.Warp.NONE && previous.coordinates().warp() == XessFgCoordinates.Warp.NONE
                && Math.abs(now.jitterX()) < 0.49f && Math.abs(now.jitterY()) < 0.49f;
    }

    void depthAndMotion(XessFgTransport slot, int texture, int sourceWidth, int sourceHeight,
                        XessFgView current, XessFgView previous, ByteBuffer reprojection) {
        var withTranslation = current.reproject(previous, true);
        var withoutTranslation = current.reproject(previous, false);
        var now = current.coordinates();
        if (reprojection != null) {
            matrix(reprojection, 0, withTranslation);
            matrix(reprojection, 64, withoutTranslation);
            reprojection.putFloat(128, now.jitterX()).putFloat(132, now.jitterY());
            dispatch(slot.geometry, texture, sourceWidth, sourceHeight, DEPTH_ONLY);
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            glProgramUniformMatrix4fv(program, currentToPrevious, false, withTranslation.get(stack.mallocFloat(16)));
            glProgramUniformMatrix4fv(program, skyToPrevious, false, withoutTranslation.get(stack.mallocFloat(16)));
        }
        var then = previous.coordinates();
        glProgramUniform2f(program, sourceScale, now.scaleX(), now.scaleY());
        glProgramUniform2f(program, currentJitter, now.jitterX(), now.jitterY());
        glProgramUniform2i(program, warpTypes, now.warp().ordinal(), then.warp().ordinal());
        glProgramUniform4f(program, warpParameters, now.time(), now.strength(), then.time(), then.strength());
        dispatch(slot.geometry, texture, sourceWidth, sourceHeight, DEPTH);
    }

    private void dispatch(XessFgTransport.Group group, int texture, int sourceWidth, int sourceHeight, int plane) {
        int oldProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int oldBuffer = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        int oldIndexed = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0);
        long oldOffset = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 0);
        long oldSize = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 0);
        int oldUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int oldTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int oldSampler = glGetInteger(GL_SAMPLER_BINDING);
        try {
            glBindSampler(0, 0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, group.buffer);
            glUseProgram(program);
            glUniform2i(sourceSize, sourceWidth, sourceHeight);
            glUniform2i(extent, width, height);
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
            glDispatchCompute((width + groupWidth - 1) / groupWidth, (height + 7) / 8, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            if (oldIndexed != 0 && oldSize > 0) {
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, oldIndexed, oldOffset, oldSize);
            } else {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, oldIndexed);
            }
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, oldBuffer);
            glBindTexture(GL_TEXTURE_2D, oldTexture);
            glBindSampler(0, oldSampler);
            glActiveTexture(oldUnit);
            glUseProgram(oldProgram);
        }
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
