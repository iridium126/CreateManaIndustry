package com.iridium126.createmanaindustry.client.dlss;

import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.state.GpuGlErrors;
import com.iridium126.createmanaindustry.client.xessfg.XessFgPack;
import com.iridium126.createmanaindustry.mixin.iris.DlssIrisAccess;
import java.util.HashMap;
import java.util.Map;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.targets.RenderTargets;
import org.joml.Vector2i;
import static org.lwjgl.opengl.GL45.*;

/** Supplies output-sized Iris attachments between the reconstruction and final passes. */
final class DlssIrisTargets implements AutoCloseable {
    private static final int[] NO_METADATA = {}, BLISS_EXPOSURE = {10, 37, 14, 37},
            BOTTOM_LEFT = {0, 0}, TOP_LEFT = {0, -1}, OPPOSITE_CORNERS = {0, 0, -1, -1};
    private final Map<Integer, Plane> planes = new HashMap<>();
    private final Map<Integer, Plane> highPlanes = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> substitutions = new HashMap<>();
    private final int source = glCreateFramebuffers();
    private final int destination = glCreateFramebuffers();
    private int program, vao;
    private int frame;
    private RenderTargets owner;
    private Vector2i[] outputSizes;

    private static final class Plane {
        final int low, width, height, outputWidth, outputHeight, format, attachment, copyMask;
        final boolean persistent;
        final int[] metadata;
        int high, initialized, written;

        Plane(int low, int width, int height, int outputWidth, int outputHeight, boolean depth, boolean persistent,
              int[] metadata) {
            this.low = low;
            this.width = width;
            this.height = height;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.persistent = persistent;
            this.metadata = metadata;
            format = storageFormat(low);
            boolean stencil = depth && (format == GL_DEPTH24_STENCIL8 || format == GL_DEPTH32F_STENCIL8);
            attachment = depth ? stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT : GL_COLOR_ATTACHMENT0;
            copyMask = depth ? GL_DEPTH_BUFFER_BIT | (stencil ? GL_STENCIL_BUFFER_BIT : 0) : GL_COLOR_BUFFER_BIT;
        }

        void allocate() {
            if (high != 0) return;
            String operation = "Allocating DLSS Iris texture, format 0x" + Integer.toHexString(format)
                    + ", size " + outputWidth + "x" + outputHeight;
            GpuGlErrors.begin(operation);
            int texture = glCreateTextures(GL_TEXTURE_2D);
            try {
                glTextureStorage2D(texture, attachment != GL_COLOR_ATTACHMENT0 ? 1
                                : 32 - Integer.numberOfLeadingZeros(Math.max(outputWidth, outputHeight)),
                        format, outputWidth, outputHeight);
                glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                GpuGlErrors.check(operation);
                high = texture;
            } catch (RuntimeException | LinkageError failure) {
                glDeleteTextures(texture);
                throw failure;
            }
        }
    }

    private static int storageFormat(int texture) {
        int format = glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_INTERNAL_FORMAT);
        if (format == GL_DEPTH_COMPONENT || format == GL_DEPTH_STENCIL) {
            // Mutable Iris targets may report the base enum. Match the actual
            // storage so the immutable allocation and the depth blit both work.
            boolean floating = glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_DEPTH_TYPE) == GL_FLOAT;
            if (format == GL_DEPTH_STENCIL) return floating ? GL_DEPTH32F_STENCIL8 : GL_DEPTH24_STENCIL8;
            if (floating) return GL_DEPTH_COMPONENT32F;
            int bits = glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_DEPTH_SIZE);
            return switch (bits) {
                case 16 -> GL_DEPTH_COMPONENT16;
                case 24 -> GL_DEPTH_COMPONENT24;
                case 32 -> GL_DEPTH_COMPONENT32;
                default -> throw new IllegalStateException("Unsupported Iris depth precision " + bits);
            };
        }
        // RGBA is Iris's legacy color target format, allocated with unsigned bytes.
        return format == GL_RGBA ? GL_RGBA8 : format;
    }

    void begin(RenderTargets targets, int outputWidth, int outputHeight, XessFgPack family) {
        if (owner != targets) {
            deletePlanes();
            owner = targets;
            outputSizes = new Vector2i[targets.getRenderTargetCount()];
        }
        nextFrame();
        var access = (DlssIrisAccess.Targets) targets;
        for (int i = 0; i < targets.getRenderTargetCount(); i++) {
            var target = targets.get(i);
            if (target == null) continue;
            var size = outputSizes[i];
            if (size == null) {
                size = access.cmi$directives().getTextureScaleOverride(i, outputWidth, outputHeight);
                outputSizes[i] = size;
            }
            var settings = access.cmi$settings().get(i);
            boolean persistent = settings != null && !settings.shouldClear();
            var metadata = fixedTexels(family, i);
            register(target.getMainTexture(), size.x, size.y, false, persistent, metadata);
            register(target.getAltTexture(), size.x, size.y, false, persistent, metadata);
        }
        register(targets.getDepthTexture(), outputWidth, outputHeight, true, false);
        register(targets.getDepthTextureNoHand().getTextureId(), outputWidth, outputHeight, true, false);
        register(targets.getDepthTextureNoTranslucents().getTextureId(), outputWidth, outputHeight, true, false);
    }

    Vector2i outputSize(int attachment) {
        return outputSizes[attachment];
    }

    void nextFrame() {
        ++frame;
    }

    void register(int low, int width, int height, boolean depth, boolean persistent) {
        register(low, width, height, depth, persistent, NO_METADATA);
    }

    void register(int low, int width, int height, boolean depth, boolean persistent, int[] metadata) {
        if (low == 0 || planes.containsKey(low)) return;
        int inputWidth = glGetTextureLevelParameteri(low, 0, GL_TEXTURE_WIDTH);
        int inputHeight = glGetTextureLevelParameteri(low, 0, GL_TEXTURE_HEIGHT);
        if (inputWidth > 0 && inputHeight > 0)
            planes.put(low, new Plane(low, inputWidth, inputHeight, width, height, depth, persistent, metadata));
    }

    private static int[] fixedTexels(XessFgPack family, int buffer) {
        return switch (family) {
            case BLISS -> buffer == 4 ? BLISS_EXPOSURE : NO_METADATA;
            case PHOTON -> buffer == 5 ? BOTTOM_LEFT : NO_METADATA;
            case ITERATION -> buffer == 2 ? TOP_LEFT : NO_METADATA;
            case SUNDIAL -> buffer == 6 || buffer == 7 ? OPPOSITE_CORNERS : NO_METADATA;
            default -> NO_METADATA;
        };
    }

    int texture(int low) {
        Plane plane = planes.get(low);
        return !needsPromotion(plane) ? low : initialize(plane);
    }

    private boolean needsPromotion(Plane plane) {
        return plane != null && (plane.high != 0 || plane.width != plane.outputWidth || plane.height != plane.outputHeight);
    }

    private void allocate(Plane plane) {
        if (plane.high == 0) {
            plane.allocate();
            highPlanes.put(plane.high, plane);
        }
    }

    private int initialize(Plane plane) {
        if (plane.initialized != frame) {
            allocate(plane);
            copy(plane, plane.low, plane.high, plane.width, plane.height, plane.outputWidth, plane.outputHeight, GL_NEAREST);
            plane.initialized = frame;
        }
        return plane.high;
    }

    void framebuffer(GlFramebuffer framebuffer, it.unimi.dsi.fastutil.ints.Int2IntMap attachments, boolean write) {
        framebuffer(framebuffer.getId(), framebuffer.hasDepthAttachment(), attachments, write);
    }

    void framebuffer(int fb, boolean hasDepth, it.unimi.dsi.fastutil.ints.Int2IntMap attachments, boolean write) {
        if (attachments.isEmpty()) return;
        var replaced = substitutions.computeIfAbsent(fb, ignored -> new HashMap<>());
        for (var entry : attachments.int2IntEntrySet()) {
            var plane = planes.get(entry.getIntValue());
            if (!needsPromotion(plane)) continue;
            int attachment = GL_COLOR_ATTACHMENT0 + entry.getIntKey();
            if (!replaced.containsKey(attachment)) {
                glNamedFramebufferTexture(fb, attachment, initialize(plane), 0);
                replaced.put(attachment, plane.low);
            }
            if (write) plane.written = frame;
        }
        if (!replaced.isEmpty() && hasDepth && !replaced.containsKey(GL_DEPTH_ATTACHMENT)
                && !replaced.containsKey(GL_DEPTH_STENCIL_ATTACHMENT)) {
            int low = glGetNamedFramebufferAttachmentParameteri(fb, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            var plane = planes.get(low);
            if (needsPromotion(plane)) {
                glNamedFramebufferTexture(fb, plane.attachment, initialize(plane), 0);
                replaced.put(plane.attachment, low);
            }
        }
    }

    void markImageWrite(int texture) {
        var plane = highPlanes.get(texture);
        if (plane != null) plane.written = frame;
    }

    void resolve(int low, int reconstructed, int inputWidth, int inputHeight, DlssPack.Encoding encoding) {
        Plane plane = planes.get(low);
        if (plane == null) throw new IllegalStateException("Iris reconstruction target is unavailable");
        allocate(plane);
        if (reconstructed == 0) {
            copy(plane, plane.low, plane.high, inputWidth, inputHeight, plane.outputWidth, plane.outputHeight, GL_LINEAR);
            plane.initialized = frame;
            return;
        }
        if (program == 0) {
            program = GpuShaderCompiler.graphics("DLSS Iris reconstruction", """
                    #version 450
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """, """
                    #version 450
                    layout(binding=0) uniform sampler2D rebuilt;
                    layout(binding=1) uniform sampler2D original;
                    layout(location=0) uniform vec2 encoding;
                    layout(location=1) uniform vec2 inputSize;
                    layout(location=0) out vec4 color;
                    void main() {
                        ivec2 p = ivec2(gl_FragCoord.xy);
                        ivec2 highSize = textureSize(rebuilt, 0);
                        ivec2 q = ivec2(gl_FragCoord.xy / vec2(highSize) * inputSize);
                        vec3 rgb = texelFetch(rebuilt, ivec2(p.x, highSize.y - 1 - p.y), 0).rgb;
                        if (encoding.y != 1.0) rgb = pow(max(vec3(0.0), rgb), vec3(encoding.y));
                        color = vec4(rgb * encoding.x, texelFetch(original, q, 0).a);
                    }
                    """);
            vao = glCreateVertexArrays();
        }
        attach(destination, plane, plane.high);
        int framebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        boolean depth = glIsEnabled(GL_DEPTH_TEST), scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean blend = glIsEnabledi(GL_BLEND, 0);
        boolean srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            glGetBooleani_v(GL_COLOR_WRITEMASK, 0, mask);
            var textures = stack.mallocInt(2);
            var samplers = stack.mallocInt(2);
            for (int i = 0; i < 2; i++) {
                glActiveTexture(GL_TEXTURE0 + i);
                textures.put(i, glGetInteger(GL_TEXTURE_BINDING_2D));
                samplers.put(i, glGetInteger(GL_SAMPLER_BINDING));
            }
            try {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination);
                glViewport(0, 0, plane.outputWidth, plane.outputHeight);
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_SCISSOR_TEST);
                glDisablei(GL_BLEND, 0);
                glDisable(GL_FRAMEBUFFER_SRGB);
                glColorMaski(0, true, true, true, true);
                bindTexture(0, reconstructed, 0);
                bindTexture(1, low, 0);
                glProgramUniform2f(program, 0, encoding.scale, encoding.gamma);
                glProgramUniform2f(program, 1, inputWidth, inputHeight);
                glUseProgram(program);
                glBindVertexArray(vao);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                plane.initialized = frame;
            } finally {
                glColorMaski(0, mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
                if (blend) glEnablei(GL_BLEND, 0);
                if (depth) glEnable(GL_DEPTH_TEST);
                if (scissor) glEnable(GL_SCISSOR_TEST);
                if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);
                bindTexture(0, textures.get(0), samplers.get(0));
                bindTexture(1, textures.get(1), samplers.get(1));
                glActiveTexture(active);
                glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                glBindVertexArray(previousVao);
                glUseProgram(previousProgram);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            }
        }
    }

    private static void bindTexture(int unit, int texture, int sampler) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindSampler(unit, sampler);
    }

    void finish() {
        try {
            for (var plane : planes.values()) {
                if (plane.persistent && plane.written == frame)
                    copy(plane, plane.high, plane.low, plane.outputWidth, plane.outputHeight, plane.width, plane.height, GL_NEAREST);
                if (plane.initialized == frame) glTextureParameteri(plane.high, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            }
        } finally {
            substitutions.forEach((fb, entries) -> entries.forEach((attachment, texture) ->
                    glNamedFramebufferTexture(fb, attachment, texture, 0)));
            substitutions.clear();
        }
    }

    private void copy(Plane plane, int from, int to, int width, int height, int outputWidth, int outputHeight, int filter) {
        attach(source, plane, from);
        attach(destination, plane, to);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        if (srgb) glDisable(GL_FRAMEBUFFER_SRGB);
        try {
            glBlitNamedFramebuffer(source, destination, 0, 0, width, height, 0, 0, outputWidth, outputHeight,
                    plane.copyMask, filter);
            for (int i = 0; i < plane.metadata.length; i += 2) {
                // The packs store exposure or depth metadata at explicit texel coordinates.
                int x = plane.metadata[i], y = plane.metadata[i + 1];
                if (x >= Math.min(width, outputWidth) || y >= Math.min(height, outputHeight)) continue;
                glCopyImageSubData(from, GL_TEXTURE_2D, 0, x < 0 ? width - 1 : x, y < 0 ? height - 1 : y, 0,
                        to, GL_TEXTURE_2D, 0, x < 0 ? outputWidth - 1 : x, y < 0 ? outputHeight - 1 : y, 0, 1, 1, 1);
            }
        } finally {
            if (scissor) glEnable(GL_SCISSOR_TEST);
            if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);
        }
    }

    private static void attach(int framebuffer, Plane plane, int texture) {
        glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, 0, 0);
        glNamedFramebufferTexture(framebuffer, GL_DEPTH_STENCIL_ATTACHMENT, 0, 0);
        glNamedFramebufferTexture(framebuffer, plane.attachment, texture, 0);
        int buffer = plane.attachment == GL_COLOR_ATTACHMENT0 ? GL_COLOR_ATTACHMENT0 : GL_NONE;
        glNamedFramebufferReadBuffer(framebuffer, buffer);
        glNamedFramebufferDrawBuffer(framebuffer, buffer);
    }

    private void deletePlanes() {
        planes.values().forEach(plane -> glDeleteTextures(plane.high));
        planes.clear();
        highPlanes.clear();
    }

    @Override
    public void close() {
        deletePlanes();
        glDeleteFramebuffers(source);
        glDeleteFramebuffers(destination);
        if (vao != 0) glDeleteVertexArrays(vao);
        if (program != 0) {
            GpuShaderCompiler.forget(program);
            glDeleteProgram(program);
        }
    }
}
