package com.iridium126.createmanaindustry.client.dlss;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderResources;
import com.iridium126.createmanaindustry.client.xessfg.XessFgCoordinates;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL45.*;

final class DlssPass implements AutoCloseable {
    private static final int[] FORMATS = {GL_RGBA16F, GL_R32F, GL_RG16F};
    private final int program, extent, jitter, toPrevious, skyToPrevious, mode, colorEncoding, water, motionLayers;

    DlssPass() {
        this(GpuShaderResources.loader(CreateManaIndustry.MODID, "shaders/dlss/").apply("inputs.comp"));
    }

    DlssPass(String shader) {
        program = GpuShaderCompiler.compute("DLSS Super Resolution inputs", shader);
        extent = glGetUniformLocation(program, "extent");
        jitter = glGetUniformLocation(program, "jitter");
        toPrevious = glGetUniformLocation(program, "toPrevious");
        skyToPrevious = glGetUniformLocation(program, "skyToPrevious");
        mode = glGetUniformLocation(program, "mode");
        colorEncoding = glGetUniformLocation(program, "colorEncoding");
        water = glGetUniformLocation(program, "water");
        motionLayers = glGetUniformLocation(program, "motionLayers");
    }

    void dispatch(DlssInterop slot, int width, int height, int colorOrDepth, int operation, int winners,
                  float jitterX, float jitterY, Matrix4fc previous, Matrix4fc sky) {
        dispatch(slot, width, height, colorOrDepth, operation, winners, jitterX, jitterY, previous, sky, DlssPack.Encoding.SDR);
    }

    void dispatch(DlssInterop slot, int width, int height, int colorOrDepth, int operation, int winners,
                  float jitterX, float jitterY, Matrix4fc previous, Matrix4fc sky, DlssPack.Encoding encoding) {
        dispatch(slot, width, height, colorOrDepth, operation, winners, jitterX, jitterY, previous, sky,
                encoding, XessFgCoordinates.VANILLA, XessFgCoordinates.VANILLA);
    }

    void dispatch(DlssInterop slot, int width, int height, int colorOrDepth, int operation, int winners,
                  float jitterX, float jitterY, Matrix4fc previous, Matrix4fc sky, DlssPack.Encoding encoding,
                  XessFgCoordinates now, XessFgCoordinates prior) {
        dispatch(slot, width, height, colorOrDepth, operation, winners, jitterX, jitterY, previous, sky, encoding,
                now, prior, 0, 0);
    }

    void world(DlssInterop slot, int width, int height, int depth, float jitterX, float jitterY,
               Matrix4fc previous, Matrix4fc sky, XessFgCoordinates now, XessFgCoordinates prior,
               int opaque, int winners, int translucent) {
        dispatch(slot, width, height, depth, 1, winners, jitterX, jitterY, previous, sky, DlssPack.Encoding.SDR,
                now, prior, opaque, translucent);
    }

    private void dispatch(DlssInterop slot, int width, int height, int colorOrDepth, int operation, int winners,
                          float jitterX, float jitterY, Matrix4fc previous, Matrix4fc sky, DlssPack.Encoding encoding,
                          XessFgCoordinates now, XessFgCoordinates prior, int opaque, int translucent) {
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int ssbo = winners != 0 ? glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING) : 0;
        int base = winners != 0 ? glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0) : 0;
        long offset = winners != 0 ? glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 0) : 0;
        long length = winners != 0 ? glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 0) : 0;
        glActiveTexture(GL_TEXTURE0);
        int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int sampler = glGetInteger(GL_SAMPLER_BINDING);
        long oldOpaque = bindMotionTexture(1, opaque);
        long oldTranslucent = bindMotionTexture(2, translucent);
        if (opaque != 0 || translucent != 0) glActiveTexture(GL_TEXTURE0);
        try (var stack = MemoryStack.stackPush()) {
            var imageState = stack.mallocInt(FORMATS.length * 6);
            for (int i = 0; i < FORMATS.length; ++i) {
                imageState.put(glGetIntegeri(GL_IMAGE_BINDING_NAME, i)).put(glGetIntegeri(GL_IMAGE_BINDING_LEVEL, i))
                        .put(glGetIntegeri(GL_IMAGE_BINDING_LAYERED, i)).put(glGetIntegeri(GL_IMAGE_BINDING_LAYER, i))
                        .put(glGetIntegeri(GL_IMAGE_BINDING_ACCESS, i)).put(glGetIntegeri(GL_IMAGE_BINDING_FORMAT, i));
                glBindImageTexture(i, slot.textures[i], 0, false, 0, i == DlssInterop.DEPTH ? GL_READ_WRITE : GL_WRITE_ONLY, FORMATS[i]);
            }
            try {
                glBindSampler(0, 0);
                glBindTexture(GL_TEXTURE_2D, colorOrDepth);
                if (winners != 0) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, winners);
                glProgramUniform2i(program, extent, width, height);
                glProgramUniform2f(program, jitter, jitterX, jitterY);
                if (previous != null) {
                    glProgramUniformMatrix4fv(program, toPrevious, false, previous.get(stack.mallocFloat(16)));
                    glProgramUniformMatrix4fv(program, skyToPrevious, false, sky.get(stack.mallocFloat(16)));
                }
                glProgramUniform1i(program, mode, operation);
                if (operation == 1) {
                    int layers = (opaque != 0 ? 1 : 0) | (winners != 0 ? 2 : 0) | (translucent != 0 ? 4 : 0);
                    glProgramUniform1i(program, motionLayers, layers);
                }
                if (operation == 0)
                    glProgramUniform2f(program, colorEncoding, encoding.scale, encoding.gamma);
                glProgramUniform4f(program, water, now.time(), bslWater(now), prior.time(), bslWater(prior));
                glUseProgram(program);
                glDispatchCompute((width + 15) / 16, (height + 7) / 8, 1);
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
            } finally {
                imageState.flip();
                for (int i = 0; i < FORMATS.length; ++i) {
                    int image = imageState.get(), level = imageState.get();
                    boolean layered = imageState.get() != 0;
                    int layer = imageState.get(), access = imageState.get(), format = imageState.get();
                    glBindImageTexture(i, image, level, layered, layer, access, format);
                }
            }
        } finally {
            if (winners != 0) {
                if (base != 0 && length > 0) glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, base, offset, length);
                else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, base);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
            }
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindSampler(0, sampler);
            restoreMotionTexture(1, oldOpaque);
            restoreMotionTexture(2, oldTranslucent);
            glActiveTexture(previousActiveTexture);
            glUseProgram(previousProgram);
        }
    }

    private static long bindMotionTexture(int unit, int texture) {
        if (texture == 0) return -1;
        glActiveTexture(GL_TEXTURE0 + unit);
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        int sampler = glGetInteger(GL_SAMPLER_BINDING);
        glBindSampler(unit, 0);
        glBindTexture(GL_TEXTURE_2D, texture);
        return (long) sampler << 32 | Integer.toUnsignedLong(previous);
    }

    private static void restoreMotionTexture(int unit, long binding) {
        if (binding == -1) return;
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, (int) binding);
        glBindSampler(unit, (int) (binding >>> 32));
    }

    private static float bslWater(XessFgCoordinates coords) {
        return coords.warp() == XessFgCoordinates.Warp.BSL_WATER ? coords.strength() : 0;
    }

    @Override
    public void close() {
        GpuShaderCompiler.forget(program);
        glDeleteProgram(program);
    }
}
