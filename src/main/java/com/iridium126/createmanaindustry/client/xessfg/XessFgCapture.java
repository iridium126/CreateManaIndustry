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
    private final int program;
    private final int sourceSize, extent, rowWords, offsets, mode, currentToPrevious, skyToPrevious;
    private final int sourceScale, currentJitter, warpTypes, warpParameters;
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
        currentToPrevious = glGetUniformLocation(program, "currentToPrevious");
        skyToPrevious = glGetUniformLocation(program, "skyToPrevious");
        sourceScale = glGetUniformLocation(program, "sourceScale");
        currentJitter = glGetUniformLocation(program, "currentJitter");
        warpTypes = glGetUniformLocation(program, "warpTypes");
        warpParameters = glGetUniformLocation(program, "warpParameters");
    }

    void color(XessFgTransport slot, int texture, int sourceWidth, int sourceHeight, int plane) {
        dispatch(slot.color, texture, sourceWidth, sourceHeight, plane);
    }

    void depthAndMotion(XessFgTransport slot, int texture, int sourceWidth, int sourceHeight,
                        XessFgView current, XessFgView previous) {
        try (var stack = MemoryStack.stackPush()) {
            glProgramUniformMatrix4fv(program, currentToPrevious, false,
                    current.reproject(previous, true).get(stack.mallocFloat(16)));
            glProgramUniformMatrix4fv(program, skyToPrevious, false,
                    current.reproject(previous, false).get(stack.mallocFloat(16)));
        }
        var now = current.coordinates();
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
            glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
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
