package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderResources;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL45.*;

/** Motion of CPU-submitted quads, matched by render object and stream across real frames. */
final class XessFgMotion implements AutoCloseable {
    // Keep additional submission work bounded in crowded scenes.
    private static final int MAX_VERTICES = 65_536;
    private static final int FLOATS_PER_POINT = 3;
    private static final int VERTEX_WORDS = 11, STRIDE = VERTEX_WORDS * Float.BYTES;
    enum Layer { WORLD, HAND, TRANSLUCENT }
    private record Key(Object object, Object stream, Layer layer) {}

    private static final class LayerBuffer {
        boolean dirty = true;
        int vbo, count;
        long meshVersion;
    }

    static final class Raster {
        int texture, sourceDepth;
        long depthVersion, meshVersion;
        XessFgCoordinates coordinates;
    }

    static final class RasterGrid implements AutoCloseable {
        final int width, height, framebuffer, depthTexture;
        private final Raster[] layers = {new Raster(), new Raster(), new Raster()};
        private int attachedColor;

        RasterGrid(int width, int height) {
            this.width = width;
            this.height = height;
            framebuffer = glCreateFramebuffers();
            depthTexture = texture(GL_DEPTH_COMPONENT32F, width, height);
            glNamedFramebufferTexture(framebuffer, GL_DEPTH_ATTACHMENT, depthTexture, 0);
        }

        Raster layer(Layer layer) {
            return layers[layer.ordinal()];
        }

        void attach(Raster raster) {
            boolean allocate = raster.texture == 0;
            if (allocate) raster.texture = texture(GL_RGBA32F, width, height);
            if (attachedColor != raster.texture) {
                glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, raster.texture, 0);
                attachedColor = raster.texture;
            }
            if (allocate && glCheckNamedFramebufferStatus(framebuffer, GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("XeSS-FG motion framebuffer is incomplete");
        }

        boolean matches(int w, int h) {
            return width == w && height == h;
        }

        @Override
        public void close() {
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(depthTexture);
            for (var layer : layers) if (layer.texture != 0) glDeleteTextures(layer.texture);
        }
    }

    /** Keep the two most recent grids so DLSS and XeSS-FG can alternate in one rendered frame. */
    static final class RasterTargets implements AutoCloseable {
        private RasterGrid recent, other;

        RasterGrid forSize(int width, int height) {
            if (recent != null && recent.matches(width, height)) return recent;
            if (other != null && other.matches(width, height)) {
                var selected = other;
                other = recent;
                recent = selected;
            } else {
                if (other != null) other.close();
                other = recent;
                recent = new RasterGrid(width, height);
            }
            return recent;
        }

        @Override
        public void close() {
            if (recent != null) recent.close();
            if (other != null) other.close();
            recent = other = null;
        }
    }

    private static final class Stream {
        final LayerBuffer layer;
        final Matrix4f clipFromVertex;
        final Matrix4f instancePose;
        final Consumer<Vector4f> deformation;
        float[] vertices = new float[12 * FLOATS_PER_POINT];
        float[] coverage;
        int count;
        boolean complete = true;

        Stream(LayerBuffer layer, Matrix4f clipFromVertex, Matrix4f instancePose, Consumer<Vector4f> deformation) {
            this.layer = layer;
            this.clipFromVertex = clipFromVertex;
            this.instancePose = instancePose;
            this.deformation = deformation;
        }

        void append(float x, float y, float z) {
            layer.dirty = true;
            if (count == vertices.length) vertices = Arrays.copyOf(vertices, count * 2);
            vertices[count++] = x;
            vertices[count++] = y;
            vertices[count++] = z;
        }
    }

    private Map<Key, Stream> previous = new HashMap<>(), current = new HashMap<>();
    private final boolean sodium = net.neoforged.fml.ModList.get().isLoaded("sodium");
    private Selection selection;
    private int vertices;
    private int program, vao, translucentLocation, extentLocation, scaleLocation, jitterLocation;
    private final LayerBuffer[] layers = {new LayerBuffer(), new LayerBuffer(), new LayerBuffer()};
    private final RasterTargets targets = new RasterTargets();
    private long depthVersion;
    private boolean sharedDepth;
    private final FloatBuffer upload = MemoryUtil.memAllocFloat(MAX_VERTICES * VERTEX_WORDS * 3 / 2);
    private final Vector4f now = new Vector4f(), then = new Vector4f();
    private record Selection(Object object, boolean hand, Matrix4f clipFromVertex, Consumer<Vector4f> deformation) {}

    void begin() {
        current.clear();
        vertices = 0;
        selection = null;
        sharedDepth = false;
        invalidateMeshes();
    }

    void finish(boolean keep) {
        if (keep) {
            var old = previous;
            previous = current;
            current = old;
        } else {
            previous.clear();
        }
        current.clear();
        invalidateMeshes();
    }

    private void invalidateMeshes() {
        for (var layer : layers) layer.dirty = true;
    }

    void beginSharedDepth() {
        sharedDepth = true;
        ++depthVersion;
    }

    void endSharedDepth() {
        sharedDepth = false;
    }

    XessFgRuntime.MotionScope track(Object object, boolean hand) {
        return track(object, hand, null);
    }

    XessFgRuntime.MotionScope track(Object object, boolean hand, Consumer<Vector4f> deformation) {
        var prior = selection;
        var matrices = DlssRuntime.stableProjection(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
        selection = new Selection(object, hand, matrices, deformation);
        return () -> selection = prior;
    }

    VertexConsumer buffers(VertexConsumer source, RenderType type) {
        var scope = selection;
        if (scope == null || type.mode() != VertexFormat.Mode.QUADS || source instanceof TrackingConsumer) return source;
        return track(source, new Key(scope.object, type, scope.hand ? Layer.HAND : Layer.WORLD),
                scope.clipFromVertex, scope.deformation);
    }

    VertexConsumer particle(VertexConsumer source, Object particle, Object type, boolean transparent) {
        return track(source, new Key(particle, type, transparent ? Layer.TRANSLUCENT : Layer.WORLD),
                DlssRuntime.stableProjection(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix()), null);
    }

    private VertexConsumer track(VertexConsumer source, Key key, Matrix4f matrices, Consumer<Vector4f> deformation) {
        if (vertices >= MAX_VERTICES) return source;
        var stream = current.computeIfAbsent(key, ignored -> {
            var result = new Stream(layers[key.layer.ordinal()], matrices, null, deformation);
            if (key.layer == Layer.TRANSLUCENT) result.coverage = new float[result.vertices.length];
            return result;
        });
        return sodium ? SodiumConsumer.wrap(this, source, stream) : new TrackingConsumer(source, stream);
    }

    void mesh(Object instance, Object model, float[] positions, Matrix4fc camera, Matrix4f pose) {
        int count = positions.length / FLOATS_PER_POINT;
        if (count > MAX_VERTICES - vertices) return;
        var layer = layers[Layer.WORLD.ordinal()];
        layer.dirty = true;
        var stream = new Stream(layer, new Matrix4f(camera).mul(pose), pose, null);
        stream.vertices = positions;
        stream.count = positions.length;
        current.put(new Key(instance, model, Layer.WORLD), stream);
        vertices += count;
    }

    private class TrackingConsumer implements VertexConsumer {
        private final VertexConsumer source;
        private final Stream stream;
        private int lastPoint = -1;

        private TrackingConsumer(VertexConsumer source, Stream stream) {
            this.source = source;
            this.stream = stream;
        }

        final void point(float x, float y, float z) {
            lastPoint = -1;
            if (vertices++ < MAX_VERTICES) {
                lastPoint = stream.count;
                stream.append(x, y, z);
                if (stream.coverage != null) {
                    if (stream.coverage.length < stream.vertices.length)
                        stream.coverage = Arrays.copyOf(stream.coverage, stream.vertices.length);
                    stream.coverage[lastPoint + 2] = 1;
                }
            } else {
                stream.complete = false;
                stream.layer.dirty = true;
            }
        }

        final void uv(float u, float v) {
            if (lastPoint >= 0 && stream.coverage != null) {
                stream.layer.dirty = true;
                stream.coverage[lastPoint] = u;
                stream.coverage[lastPoint + 1] = v;
            }
        }

        final void alpha(int alpha) {
            if (lastPoint >= 0 && stream.coverage != null) {
                stream.layer.dirty = true;
                stream.coverage[lastPoint + 2] = alpha / 255f;
            }
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            source.addVertex(x, y, z);
            point(x, y, z);
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                              float normalX, float normalY, float normalZ) {
            source.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
            point(x, y, z);
            uv(u, v);
            alpha(color >>> 24);
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { source.setColor(r, g, b, a); alpha(a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { source.setUv(u, v); uv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { source.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { source.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { source.setNormal(x, y, z); return this; }
    }

    private final class SodiumConsumer extends TrackingConsumer
            implements net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter {
        private final net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter writer;

        private SodiumConsumer(VertexConsumer source, Stream stream,
                               net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter writer) {
            super(source, stream);
            this.writer = writer;
        }

        static VertexConsumer wrap(XessFgMotion motion, VertexConsumer source, Stream stream) {
            var writer = net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter.tryOf(source);
            return writer == null ? motion.new TrackingConsumer(source, stream) : motion.new SodiumConsumer(source, stream, writer);
        }

        @Override
        public boolean canUseIntrinsics(VertexFormat format) {
            return writer.canUseIntrinsics(format);
        }

        @Override
        public void push(MemoryStack stack, long pointer, int count, VertexFormat format) {
            if (format.contains(VertexFormatElement.POSITION)) {
                long vertex = pointer;
                int positionOffset = format.getOffset(VertexFormatElement.POSITION);
                int uvOffset = format.getOffset(VertexFormatElement.UV0);
                int colorOffset = format.getOffset(VertexFormatElement.COLOR);
                for (int i = 0; i < count; i++, vertex += format.getVertexSize()) {
                    long position = vertex + positionOffset;
                    point(MemoryUtil.memGetFloat(position), MemoryUtil.memGetFloat(position + 4),
                            MemoryUtil.memGetFloat(position + 8));
                    if (uvOffset >= 0) uv(MemoryUtil.memGetFloat(vertex + uvOffset), MemoryUtil.memGetFloat(vertex + uvOffset + 4));
                    if (colorOffset >= 0) alpha(Byte.toUnsignedInt(MemoryUtil.memGetByte(vertex + colorOffset + 3)));
                }
            }
            writer.push(stack, pointer, count, format);
        }
    }

    int draw(Layer layer, boolean reset, int sceneDepth, int w, int h, XessFgCoordinates coordinates) {
        if (reset || sceneDepth == 0 || previous.isEmpty()) return 0;
        var buffer = layers[layer.ordinal()];
        if (buffer.dirty) uploadLayer(layer, buffer);
        if (buffer.count == 0) return 0;
        var grid = targets.forSize(w, h);
        var raster = grid.layer(layer);
        if (sharedDepth && raster.depthVersion == depthVersion && raster.meshVersion == buffer.meshVersion
                && raster.sourceDepth == sceneDepth && coordinates.equals(raster.coordinates)) return raster.texture;
        ensureProgram();
        grid.attach(raster);
        glVertexArrayVertexBuffer(vao, 0, buffer.vbo, 0, STRIDE);
        int atlas = layer == Layer.TRANSLUCENT ? net.minecraft.client.Minecraft.getInstance().getTextureManager()
                .getTexture(net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/atlas/particles.png")).getId() : 0;
        render(grid, sceneDepth, buffer.count, coordinates, atlas);
        raster.depthVersion = sharedDepth ? depthVersion : 0;
        raster.meshVersion = buffer.meshVersion;
        raster.sourceDepth = sceneDepth;
        raster.coordinates = coordinates;
        return raster.texture;
    }

    private void uploadLayer(Layer layer, LayerBuffer buffer) {
        upload.clear();
        current.forEach((key, stream) -> {
            if (key.layer != layer || !stream.complete) return;
            Stream prior = previous.get(key);
            if (prior == null || !prior.complete || prior.count != stream.count) return;
            if (stream.instancePose != null) {
                if (stream.vertices != prior.vertices || stream.instancePose.equals(prior.instancePose)) return;
                for (int offset = 0; offset < stream.count; offset += 3) point(stream, prior, offset);
                return;
            }
            if (stream.count % 12 != 0) return;
            if (layer == Layer.WORLD && Arrays.equals(stream.vertices, 0, stream.count, prior.vertices, 0, prior.count)) return;
            for (int offset = 0; offset < stream.count; offset += 12) {
                point(stream, prior, offset);
                point(stream, prior, offset + 3);
                point(stream, prior, offset + 6);
                point(stream, prior, offset + 6);
                point(stream, prior, offset + 9);
                point(stream, prior, offset);
            }
        });
        upload.flip();
        buffer.count = upload.remaining() / VERTEX_WORDS;
        buffer.dirty = false;
        ++buffer.meshVersion;
        if (buffer.count == 0) return;
        if (buffer.vbo == 0) buffer.vbo = glCreateBuffers();
        glNamedBufferData(buffer.vbo, upload, GL_STREAM_DRAW);
    }

    private void point(Stream current, Stream prior, int index) {
        transform(now, current, index);
        transform(then, prior, index);
        upload.put(now.x).put(now.y).put(now.z).put(now.w);
        upload.put(then.x).put(then.y).put(then.z).put(then.w);
        if (current.coverage == null) upload.put(0).put(0).put(1);
        else upload.put(current.coverage[index]).put(current.coverage[index + 1]).put(current.coverage[index + 2]);
    }

    private static void transform(Vector4f result, Stream stream, int index) {
        result.set(stream.vertices[index], stream.vertices[index + 1], stream.vertices[index + 2], 1);
        if (stream.deformation != null) stream.deformation.accept(result);
        else stream.clipFromVertex.transform(result);
    }

    private void ensureProgram() {
        if (program == 0) {
            var shaders = GpuShaderResources.loader(CreateManaIndustry.MODID, "shaders/xessfg/");
            program = GpuShaderCompiler.graphics("XeSS-FG object motion", shaders.apply("motion.vert"), shaders.apply("motion.frag"));
            translucentLocation = glGetUniformLocation(program, "translucent");
            extentLocation = glGetUniformLocation(program, "extent");
            scaleLocation = glGetUniformLocation(program, "sourceScale");
            jitterLocation = glGetUniformLocation(program, "jitter");
            vao = glCreateVertexArrays();
            for (int i = 0; i < 3; i++) {
                glEnableVertexArrayAttrib(vao, i);
                glVertexArrayAttribFormat(vao, i, i == 2 ? 3 : 4, GL_FLOAT, false, i * 4 * Float.BYTES);
                glVertexArrayAttribBinding(vao, i, 0);
            }
        }
    }

    private static int texture(int format, int w, int h) {
        int texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, format, w, h);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    private void render(RasterGrid grid, int sceneDepth, int count, XessFgCoordinates coordinates, int atlas) {
        int oldFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int oldUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabledi(GL_BLEND, 0);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        glActiveTexture(GL_TEXTURE0);
        int oldTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int oldSampler = glGetInteger(GL_SAMPLER_BINDING);
        if (atlas != 0) glActiveTexture(GL_TEXTURE1);
        int oldAtlas = atlas != 0 ? glGetInteger(GL_TEXTURE_BINDING_2D) : 0;
        int oldAtlasSampler = atlas != 0 ? glGetInteger(GL_SAMPLER_BINDING) : 0;
        if (atlas != 0) glActiveTexture(GL_TEXTURE0);
        try (var stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            var colors = stack.malloc(4);
            glGetIntegerv(GL_VIEWPORT, viewport);
            glGetBooleani_v(GL_COLOR_WRITEMASK, 0, colors);
            try {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, grid.framebuffer);
                glBindVertexArray(vao);
                glUseProgram(program);
                glBindTexture(GL_TEXTURE_2D, sceneDepth);
                glBindSampler(0, 0);
                if (atlas != 0) {
                    glBindTextureUnit(1, atlas);
                    glBindSampler(1, 0);
                }
                glUniform1i(translucentLocation, atlas != 0 ? 1 : 0);
                glUniform2f(extentLocation, grid.width, grid.height);
                glUniform2f(scaleLocation, coordinates.scaleX(), coordinates.scaleY());
                glUniform2f(jitterLocation, coordinates.jitterX(), coordinates.jitterY());
                glViewport(0, 0, grid.width, grid.height);
                glDisablei(GL_BLEND, 0);
                glDisable(GL_CULL_FACE);
                glDisable(GL_SCISSOR_TEST);
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(GL_LEQUAL);
                glDepthMask(true);
                glColorMaski(0, true, true, true, true);
                glClearBufferfv(GL_COLOR, 0, stack.floats(0, 0, 0, 0));
                glClearBufferfv(GL_DEPTH, 0, stack.floats(1));
                glDrawArrays(GL_TRIANGLES, 0, count);
                glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT);
            } finally {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldFramebuffer);
                glBindVertexArray(oldVao);
                glUseProgram(oldProgram);
                glBindTexture(GL_TEXTURE_2D, oldTexture);
                glBindSampler(0, oldSampler);
                if (atlas != 0) {
                    glActiveTexture(GL_TEXTURE1);
                    glBindTexture(GL_TEXTURE_2D, oldAtlas);
                    glBindSampler(1, oldAtlasSampler);
                }
                glActiveTexture(oldUnit);
                glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                glColorMaski(0, colors.get(0) != 0, colors.get(1) != 0, colors.get(2) != 0, colors.get(3) != 0);
                glDepthMask(depthMask);
                glDepthFunc(depthFunc);
                enable(GL_DEPTH_TEST, depthTest);
                if (blend) glEnablei(GL_BLEND, 0);
                else glDisablei(GL_BLEND, 0);
                enable(GL_CULL_FACE, cull);
                enable(GL_SCISSOR_TEST, scissor);
            }
        }
    }

    private static void enable(int capability, boolean enabled) {
        if (enabled) glEnable(capability);
        else glDisable(capability);
    }

    @Override
    public void close() {
        MemoryUtil.memFree(upload);
        targets.close();
        glDeleteVertexArrays(vao);
        for (var layer : layers) if (layer.vbo != 0) glDeleteBuffers(layer.vbo);
        if (program != 0) GpuPrograms.delete(program);
    }
}
