package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferRetirement;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState;
import com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout;
import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafSway;
import com.iridium126.createmanaindustry.client.particles.shaderpack.ShaderPackProgramCompiler;
import com.iridium126.createmanaindustry.mixin.iris.NativeSkyPipelineAccess;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.*;

/** Evaluates only faces referenced by the current frozen feather draw list. */
public final class FeatherLeafDisplayGpu implements AutoCloseable {
    public static final FeatherLeafDisplayGpu INSTANCE = new FeatherLeafDisplayGpu();
    public static final int SPLIT_STRIDE = 7;
    public static final int FACE_BASE = 2 + AllayFeatherLayout.MAX_FEATHERS;
    public static final int SPLIT_BASE = FACE_BASE + 9 * FeatherSupportGpu.CACHE_LIMIT;
    private static final long BYTES = (SPLIT_BASE + (long) SPLIT_STRIDE * AllayFeatherLayout.MAX_FEATHERS) * 16;
    private static final int STORAGE = GL43.GL_SHADER_STORAGE_BUFFER;
    private static final int NOISE_UNIT = 0;
    private static final int RESULT_UNIT = ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE + 2;
    private final int[] priorBuffers = new int[4];
    private final long[] priorStarts = new long[4], priorSizes = new long[4];
    private FeatherLeafSway.Plan attempted;
    private GpuShaderCompiler.Compilation pending;
    private String status = "waiting for a visible leaf attachment";
    private int program, buffer, texture, stamp, noise;
    private ProgramUniforms uniforms;
    private CustomUniforms custom;

    private FeatherLeafDisplayGpu() {}

    public static String source(String adapters, java.util.function.Function<String, String> shaders) {
        return "#version 430 core\nuniform ivec3 cmi_LeafCameraBlock; uniform vec3 cmi_LeafCameraFraction;\n" +
                "const uint CMI_LEAF_SUPPORT_ROWS=" + FeatherSupportGpu.ROWS + "u;\n" +
                "const uint CMI_LEAF_GROUND_OFFSET=" + AllayFeatherLayout.GROUND + "u;\n" +
                "const uint CMI_LEAF_VISIBLE_OFFSET=" + AllayFeatherLayout.VISIBLE + "u;\n" +
                "const uint CMI_LEAF_FACE_BASE=" + FACE_BASE + "u;\n" +
                "const uint CMI_LEAF_SPLIT_BASE=" + SPLIT_BASE + "u;\n" +
                "const uint CMI_LEAF_SPLIT_STRIDE=" + SPLIT_STRIDE + "u;\n" +
                adapters + shaders.apply("feather_leaf_display.comp");
    }

    public String status() { return status; }
    int stamp() { return stamp; }

    private boolean ready() {
        var plan = FeatherLeafSway.current();
        if (plan == null) return false;
        try {
            if (plan != attempted) {
                close();
                attempted = plan;
                status = "compiling terrain leaf evaluator";
                pending = GpuShaderCompiler.beginCompute("Iris leaf attachment",
                        source(FeatherLeafSway.displaySource(), ParticlePrograms::loadParticlePlain));
            }
            if (pending == null) return program != 0;
            if (!pending.poll()) return false;
            program = pending.take();
            pending.close();
            pending = null;
            var pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
            var builder = ProgramUniforms.builder("CMI terrain leaf attachment", program);
            custom = pipeline.getCustomUniforms();
            CommonUniforms.addDynamicUniforms(builder, FogMode.PER_VERTEX);
            custom.assignTo(builder);
            BuiltinReplacementUniforms.addBuiltinReplacementUniforms(builder);
            custom.mapholderToPass(builder, this);
            uniforms = builder.buildUniforms();
            int location = GL20.glGetUniformLocation(program, "noisetex");
            if (location >= 0) {
                var textures = ((NativeSkyPipelineAccess) pipeline).cmi$textures();
                var stage = textures.getCustomTextureIdMap().get(TextureStage.GBUFFERS_AND_SHADOW);
                var noiseTexture = stage == null ? null : stage.get("noisetex");
                if (noiseTexture == null) noiseTexture = textures.getIrisCustomTextures().get("noisetex");
                if (noiseTexture == null) noiseTexture = textures.getNoiseTexture();
                noise = noiseTexture == null ? 0 : noiseTexture.getTextureId().getAsInt();
                if (noise == 0) throw new IllegalStateException("Iris terrain noise texture is unavailable");
                GL41.glProgramUniform1i(program, location, NOISE_UNIT);
            }
            buffer = GL45.glCreateBuffers();
            GL45.glNamedBufferData(buffer, BYTES, GL15.GL_DYNAMIC_DRAW);
            clear();
            texture = GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
            GL45.glTextureBuffer(texture, GL30.GL_RGBA32F, buffer);
            status = "using terrain mesh vertices";
            return true;
        } catch (RuntimeException error) {
            close();
            attempted = plan;
            status = "static feather fallback (" + error.getMessage() + ")";
            CreateManaIndustry.LOGGER.warn("[CMI particles] Iris leaf attachment unavailable", error);
            return false;
        }
    }

    private void clear() {
        GL45.glClearNamedBufferSubData(buffer, GL30.GL_R32UI, 0, SPLIT_BASE * 16L,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        GL45.glNamedBufferSubData(buffer, 0, new int[] {18, 0, 0, 0});
    }

    boolean prepare(int entityProgram, int particles, int feather, Vec3 camera) {
        if (!FeatherSupportGpu.INSTANCE.drawable() || !ready()) return false;
        try (var ignored = ParticleFrameStages.begin("draw.pack.feather.leaf-attachment")) {
            int dispatch = GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
            int storage = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
            for (int slot = 0; slot < priorBuffers.length; slot++) {
                int prior = priorBuffers[slot] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, slot);
                priorStarts[slot] = prior == 0 ? 0 : GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, slot);
                priorSizes[slot] = prior == 0 ? 0 : GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, slot);
            }
            try (var textureState = noise == 0 ? null : new GpuTexture2DState(IrisRenderSystem::bindSamplerToUnit, NOISE_UNIT)) {
                GpuBindings.program(program);
                uniforms.update();
                custom.push(this);
                if (noise != 0) {
                    IrisRenderSystem.bindSamplerToUnit(NOISE_UNIT, 0);
                    GpuTexture2DState.bind(NOISE_UNIT, noise);
                }
                if (++stamp == 1 << 24) {
                    clear();
                    stamp = 1;
                }
                GL45.glClearNamedBufferSubData(buffer, GL30.GL_R32UI, 4, 4, GL30.GL_RED_INTEGER,
                        GL11.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
                GpuBindings.bindBase(STORAGE, 0, particles);
                GpuBindings.bindBase(STORAGE, 1, feather);
                GpuBindings.bindBase(STORAGE, 2, FeatherSupportGpu.INSTANCE.buffer());
                GpuBindings.bindBase(STORAGE, 3, buffer);
                int x = (int) Math.floor(camera.x), y = (int) Math.floor(camera.y), z = (int) Math.floor(camera.z);
                GL41.glProgramUniform3i(program, GpuUniforms.loc(program, "cmi_LeafCameraBlock"), x, y, z);
                GpuUniforms.setFloatUniform(program, "cmi_LeafCameraFraction",
                        (float) (camera.x - x), (float) (camera.y - y), (float) (camera.z - z));
                GpuUniforms.setUIntUniform(program, "cmi_LeafStamp", stamp);
                GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER, feather);
                GL43.glDispatchComputeIndirect(AllayFeatherLayout.LEAF_DISPATCH * 4L);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT
                        | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            } finally {
                for (int slot = 0; slot < priorBuffers.length; slot++) {
                    if (priorSizes[slot] == 0) GpuBindings.bindBase(STORAGE, slot, priorBuffers[slot]);
                    else GpuBindings.bindRange(STORAGE, slot, priorBuffers[slot], priorStarts[slot], priorSizes[slot]);
                }
                GpuBindings.bind(STORAGE, storage);
                GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER, dispatch);
                GpuBindings.program(entityProgram);
            }
            GpuBindings.texture(RESULT_UNIT, GL31.GL_TEXTURE_BUFFER, texture);
            return true;
        }
    }

    void drawSplits() {
        GpuBindings.bind(GL40.GL_DRAW_INDIRECT_BUFFER, buffer);
        GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES, 0L);
    }

    @Override public void close() {
        attempted = null;
        status = "waiting for a visible leaf attachment";
        uniforms = null;
        custom = null;
        if (pending != null) {
            pending.close();
            pending = null;
        }
        GpuPrograms.delete(program);
        GpuBufferNames.delete(buffer);
        if (texture != 0) {
            GpuBufferRetirement.retiringTexture(texture);
            GpuBindings.forgetTexture(texture);
            GL11.glDeleteTextures(texture);
        }
        program = buffer = texture = noise = stamp = 0;
    }
}
