import java.nio.ByteBuffer;

import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterPresets;
import com.iridium126.createmanaindustry.client.particles.emitter.ManaBeamEmission;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.SIMULATION_PARAMETERS_BINDING;
import static com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.*;

/** Actual emit, ordinary update and additive draw programs in a hidden GL context. */
public final class ManaBeamGpuValidation {
    private static final int CAPACITY = 512;
    private static final int RECORD_BYTES = 64;
    private static final int AGE_OFFSET = 48;
    private static final int SIZE_X = 260;
    private static final int SIZE_Y = 160;
    private static final int CORE = 0, HEAD = 1, ORIGIN_RING = 2, IMPACT = 7;
    private static final Vec3 SIDE_VIEW_ORIGIN = new Vec3(0, 0, -5);
    private static final Vec3 EAST = new Vec3(1, 0, 0);

    private static ByteBuffer bytes(int count) { return ParticleEngineGpuValidation.bytes(count); }
    private static int buffer(int count) { return ParticleEngineGpuValidation.buffer(bytes(count)); }
    private static ByteBuffer read(int buffer, int bytes) { return ParticleEngineGpuValidation.read(buffer, bytes); }
    private static void bind(int slot, int buffer) { ParticleEngineGpuValidation.bind(slot, buffer); }
    private static void ui(int program, String name, int value) { ParticleEngineGpuValidation.ui(program, name, value); }
    private static void check(boolean condition, String message) {
        ParticleEngineGpuValidation.check(condition, message);
    }

    private static String shader(String name) {
        return "#version 450 core\n" + ParticlePrograms.commonPrelude() + ParticleEngineGpuValidation.source(name);
    }

    private static void dispatch(int program, int groups) {
        GL20.glUseProgram(program);
        GL43.glDispatchCompute(groups, 1, 1);
        GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);
    }

    private static final class Fixture implements AutoCloseable {
        final int emit = ParticleEngineGpuValidation.program("emit.comp");
        final int update = ParticleEngineGpuValidation.program("update_ordinary.comp");
        final int render = GpuShaderCompiler.graphics("mana beam additive", shader("additive.vsh"), shader("additive.fsh"));
        final int pool = buffer(CAPACITY * RECORD_BYTES), updated = buffer(CAPACITY * RECORD_BYTES);
        final int counter = buffer(16), previousCount = buffer(16), parameters = buffer(48);
        final int commands = buffer(2 * (EmitCommandWriter.WORDS + EmitCommandWriter.PARAMETER_WORDS) * 4);
        final int headers = buffer(2 * VEC4_PER_EMITTER * 16), order = buffer(CAPACITY * 4);
        final int vao = GL30.glGenVertexArrays();

        Fixture() {
            ByteBuffer packed = bytes(2 * VEC4_PER_EMITTER * 16);
            packed.asFloatBuffer().put(ManaBeamEmission.SPEC.packed()).put(EmitterPresets.MANA_SPARK.packed());
            GL45.glNamedBufferSubData(headers, 0, packed);
            bind(EMITTER_BB, headers);
            bind(COUNTER_BB, counter);
            bind(PREVCOUNTER_BINDING, previousCount);
            bind(EMIT_BB, commands);
            bind(ORDERADD_BINDING, order);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, SIMULATION_PARAMETERS_BINDING, parameters);

            GL30.glBindVertexArray(vao);
            GL11.glViewport(0, 0, SIZE_X, SIZE_Y);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GL11.glClearColor(0, 0, 0, 0);
            GL41.glProgramUniformMatrix4fv(render, GL20.glGetUniformLocation(render, "ProjMat"), false,
                    new Matrix4f().ortho(-2, 50, -6, 6, 0.01f, 100).get(new float[16]));
            GL41.glProgramUniformMatrix4fv(render, GL20.glGetUniformLocation(render, "ModelViewMat"), false,
                    new Matrix4f().get(new float[16]));
            GL41.glProgramUniform3f(render, GL20.glGetUniformLocation(render, "uCamRight"), 1, 0, 0);
            GL41.glProgramUniform3f(render, GL20.glGetUniformLocation(render, "uCamUp"), 0, 1, 0);
            GL41.glProgramUniform1f(render, GL20.glGetUniformLocation(render, "uGlow"), 1);
            GL41.glProgramUniform1f(render, GL20.glGetUniformLocation(render, "uFadeDist"), 96);
        }

        int spawn(ManaBeamEmission beam, int capacity, boolean ordinary) {
            EmitCommandWriter writer = new EmitCommandWriter(2);
            check(beam.stage(writer, 0, 1), "beam command rejected");
            if (ordinary) check(writer.basic(1, 5, SIDE_VIEW_ORIGIN, 0), "ordinary command rejected");
            GL45.glNamedBufferSubData(commands, 0, writer.encode(7));
            GL45.glNamedBufferSubData(counter, 0, bytes(16));
            bind(PARTICLE_BB_WRITE, pool);
            ui(emit, "uCapacity", capacity);
            ui(emit, "uTotalSpawn", writer.particles());
            ui(emit, "uEmitCount", writer.size());
            ui(emit, "uManaBeamCommands", 1);
            dispatch(emit, ordinary ? 2 : 1);
            int live = read(counter, 16).getInt(0);
            check(live == Math.min(writer.particles(), capacity), "incorrect pool grant");
            return live;
        }

        ByteBuffer draw(float age, int... slots) {
            ByteBuffer indices = bytes(slots.length * 4);
            for (int slot : slots) {
                indices.putInt(slot);
                GL45.glNamedBufferSubData(pool, (long) slot * RECORD_BYTES + AGE_OFFSET, bytes(4).putFloat(0, age));
            }
            GL45.glNamedBufferSubData(order, 0, indices.flip());
            bind(PARTICLE_BB_WRITE, pool);
            GL20.glUseProgram(render);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, slots.length);
            ByteBuffer pixels = bytes(SIZE_X * SIZE_Y * 4);
            GL11.glReadPixels(0, 0, SIZE_X, SIZE_Y, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            return pixels;
        }

        int step(int live) {
            bind(PARTICLE_BB_READ, pool);
            bind(PARTICLE_BB_WRITE, updated);
            GL45.glNamedBufferSubData(previousCount, 0, bytes(16).putInt(0, live));
            GL45.glNamedBufferSubData(counter, 0, bytes(16));
            GL45.glNamedBufferSubData(parameters, 0, bytes(48).putInt(0, CAPACITY)
                    .putInt(4, -1).putFloat(16, 0.05f));
            dispatch(update, 1);
            return read(counter, 16).getInt(0);
        }

        @Override public void close() {
            GL30.glDeleteVertexArrays(vao);
            for (int program : new int[] {emit, update, render}) GL20.glDeleteProgram(program);
            for (int buffer : new int[] {pool, updated, counter, previousCount, parameters, commands, headers, order})
                GL15.glDeleteBuffers(buffer);
        }
    }

    private static int role(ByteBuffer records, int live, int role) {
        for (int i = 0; i < live; i++) {
            int offset = i * RECORD_BYTES;
            if (records.getInt(offset + 60) == 0 && records.getFloat(offset + 40) == role) return i;
        }
        throw new AssertionError("missing beam role " + role);
    }

    private static int rightmostLit(ByteBuffer pixels) {
        int result = -1;
        for (int y = 0; y < SIZE_Y; y++) {
            for (int x = result + 1; x < SIZE_X; x++) {
                int pixel = (y * SIZE_X + x) * 4;
                if (Math.max(Byte.toUnsignedInt(pixels.get(pixel)), Byte.toUnsignedInt(pixels.get(pixel + 2))) > 5)
                    result = x;
            }
        }
        return result;
    }

    private static int pixelX(float worldX) { return (int) ((worldX + 2) * SIZE_X / 52); }

    private static void verify(Fixture f) {
        var wallCast = new ManaBeamEmission(SIDE_VIEW_ORIGIN, EAST, 24, Direction.WEST);
        int live = f.spawn(wallCast, CAPACITY, true);
        ByteBuffer particles = read(f.pool, live * RECORD_BYTES);
        int ordinary = 0;
        for (int i = 0; i < live; i++) if (particles.getInt(i * RECORD_BYTES + 60) == 1) ordinary++;
        check(ordinary == 5, "mixed dispatch lost ordinary entries after the beam prefix");
        int core = role(particles, live, CORE), head = role(particles, live, HEAD);
        int impact = role(particles, live, IMPACT);
        int early = rightmostLit(f.draw(0.1f, core, head));
        int later = rightmostLit(f.draw(0.25f, core, head));
        check(early >= pixelX(6) && early < pixelX(8), "beam head does not reach six blocks at 0.1 seconds");
        check(later >= pixelX(15) && later < pixelX(17), "beam head does not advance linearly");
        check(rightmostLit(f.draw(0.35f, impact)) < 0, "impact spark visible before the head arrives");
        check(rightmostLit(f.draw(0.54f, impact)) >= 0, "missing impact spark after contact");
        int terminal = rightmostLit(f.draw(0.53f, core, head, impact));
        check(terminal >= pixelX(23) && terminal < pixelX(24), "visuals cross the wall plane or stop short");
        check(rightmostLit(f.draw(1.3f, core, head, impact)) < 0, "expired beam is still luminous");

        live = f.spawn(wallCast, 4, false);
        particles = read(f.pool, live * RECORD_BYTES);
        for (int i = 0; i < 3; i++) check(particles.getFloat(i * RECORD_BYTES + 40) == i, "pool priority lost a silhouette role");
        check(f.step(live) == 4, "beam disappeared during ordinary update");
        ByteBuffer updated = read(f.updated, live * RECORD_BYTES);
        for (int i = 0; i < live; i++) {
            check(Math.abs(updated.getFloat(i * RECORD_BYTES + AGE_OFFSET) - 0.05f) < 0.0001f, "beam age did not advance");
            GL45.glNamedBufferSubData(f.pool, (long) i * RECORD_BYTES + AGE_OFFSET, bytes(4).putFloat(0, 1.3f));
        }
        check(f.step(live) == 0, "retired beam records remained in the pool");

        var openCast = new ManaBeamEmission(SIDE_VIEW_ORIGIN, EAST, 48, null);
        live = f.spawn(openCast, CAPACITY, false);
        particles = read(f.pool, live * RECORD_BYTES);
        check(rightmostLit(f.draw(0.80f, role(particles, live, HEAD))) >= pixelX(48), "open beam did not reach 48 blocks");
        var headOn = new ManaBeamEmission(new Vec3(12, 0, -5), new Vec3(0, 0, -1), 48, null);
        live = f.spawn(headOn, 4, false);
        particles = read(f.pool, live * RECORD_BYTES);
        check(rightmostLit(f.draw(0.3f, role(particles, live, ORIGIN_RING))) > pixelX(12), "head-on ring vanished");
        var vertical = new ManaBeamEmission(SIDE_VIEW_ORIGIN, new Vec3(0, 1, 0), 48, null);
        live = f.spawn(vertical, 4, false);
        particles = read(f.pool, live * RECORD_BYTES);
        check(rightmostLit(f.draw(0.06f, role(particles, live, HEAD))) >= 0, "vertical beam head vanished");
        check(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error during beam validation");
    }

    public static void main(String[] args) {
        long window = GpuValidationSupport.open(SIZE_X, SIZE_Y, "Mana beam validation", true);
        try (var fixture = new Fixture()) {
            verify(fixture);
            System.out.println("Mana beam GPU checks passed on " + GL11.glGetString(GL11.GL_RENDERER));
        } finally {
            GpuValidationSupport.close(window);
        }
    }
}
