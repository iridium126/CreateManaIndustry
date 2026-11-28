package com.iridium126.createmanaindustry.client.allaystorm;

import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames;
import com.iridium126.createmanaindustry.client.particles.emitter.ManaBeamEmission;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import com.iridium126.createmanaindustry.content.allaystorm.StarfallColumns;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormRingPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormRingPacket.Frame;
import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormRingReadyPacket;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import static com.iridium126.createmanaindustry.content.allaystorm.StormRingGeometry.*;

/** GPU ring reference frames and a small, success-only readiness readback. */
public final class StormRingClient {
    public static final StormRingClient INSTANCE = new StormRingClient();
    private static final int MEMBER_LIMIT = 131072, IMAGE = 7;
    private static final int FRAME_OFFSET = MEMBER_LIMIT * 4, FRAME_BYTES = 64;
    private static final int READY_OFFSET = FRAME_OFFSET + CAPACITY * FRAME_BYTES;
    private static final int READY_BYTES = CAPACITY * 4;
    private static final int READY_MEMBERS = MEMBERS * 3 / 4;

    private final Slot[] slots = new Slot[CAPACITY];
    private final long[] lastPacketTick = new long[CAPACITY];
    private final int[] assignments = new int[MEMBER_LIMIT];
    private final BitSet dirtyMembers = new BitSet(MEMBER_LIMIT), dirtySlots = new BitSet(CAPACITY);
    private final ByteBuffer staging = BufferUtils.createByteBuffer(CAPACITY * FRAME_BYTES);
    private final ByteBuffer zeros = BufferUtils.createByteBuffer(READY_BYTES);
    private final ArrayDeque<Capture> pending = new ArrayDeque<>(), free = new ArrayDeque<>();
    private final ByteBuffer samples = BufferUtils.createByteBuffer(READY_BYTES);
    private StormIdentity identity;
    private int buffer, texture, active;
    private boolean collecting,reportIssued;
    private long lastCapture = Long.MIN_VALUE;

    private StormRingClient() {
        Arrays.fill(lastPacketTick, Long.MIN_VALUE);
    }

    private static final class Slot {
        final int id;
        final int[] roster;
        long sample;
        Frame frame;
        boolean emitted;
        int streak;
        Slot(int id, int[] roster) { this.id = id; this.roster = roster; }
    }
    private static final class Capture {
        final GpuReadbackBuffer data = new GpuReadbackBuffer(READY_BYTES);
        final Frame[] frames = new Frame[CAPACITY];
        long fence, tick;
    }

    public void accept(ClientboundStormRingPacket packet, Consumer<Frame> nativeInput) {
        var runtime = AllayStormRuntime.INSTANCE;
        if (!packet.identity().equals(runtime.identity()) || !runtime.active()) return;
        if (!packet.identity().equals(identity)) {
            clear();
            identity = packet.identity();
        }
        for (Frame frame : packet.frames()) {
            int at = frame.slot();
            Slot slot = slots[at];
            long previousTick = lastPacketTick[at];
            if (packet.tick() < previousTick || packet.tick() == previousTick
                    && (slot == null || slot.id != frame.id() || frame.phase() <= slot.frame.phase())) continue;
            if (frame.phase() == END) {
                if (slot != null && slot.id != frame.id()) continue;
                lastPacketTick[at] = packet.tick();
                if (slot != null) {
                    remove(at, slot);
                    nativeInput.accept(frame);
                }
                continue;
            }
            boolean create = slot == null || slot.id != frame.id();
            if (create) {
                if (frame.roster().length != MEMBERS) continue;
                if (slot != null) remove(at, slot);
                slot = new Slot(frame.id(), frame.roster());
                slots[at] = slot;
                for (int rank = 0; rank < MEMBERS; rank++) {
                    int member = slot.roster[rank];
                    assignments[member] = (at + 1) | (rank << 8);
                    dirtyMembers.set(member);
                }
                active++;
            } else if (frame.phase() < slot.frame.phase()) continue;
            if (!create && frame.phase() != slot.frame.phase()) slot.streak = 0;
            slot.sample = packet.tick();
            slot.frame = frame;
            lastPacketTick[at] = packet.tick();
            dirtySlots.set(at);
            nativeInput.accept(frame);
            if (frame.shot() != null && !slot.emitted) {
                slot.emitted = true;
                play(frame);
            }
        }
    }

    private static void play(Frame frame) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var shot = frame.shot();
        float age = Math.max(0, (level.getGameTime() - shot.tick()) / 20f);
        if (age >= shot.distance() / BEAM_SPEED + HOLD + FADE) return;
        CMIParticleEngine.INSTANCE.spawnManaBeam(level,
                new ManaBeamEmission(frame.center(), frame.axis(), shot.distance(), shot.face(), age));
    }

    private void remove(int at, Slot slot) {
        for (int member : slot.roster) {
            if ((assignments[member] & 255) != at + 1) continue;
            assignments[member] = 0;
            dirtyMembers.set(member);
        }
        slots[at] = null;
        dirtySlots.set(at);
        active--;
    }

    List<Frame> frames() {
        if (active == 0) return List.of();
        var result = new ArrayList<Frame>(active);
        for (Slot slot : slots) if (slot != null) result.add(slot.frame);
        return result;
    }

    public void prepare(long tick, boolean authority) {
        poll(authority);
        collecting = false;
        reportIssued = false;
        if (active == 0) return;
        if (buffer == 0) {
            buffer = GL45.glCreateBuffers();
            GL45.glNamedBufferData(buffer, READY_OFFSET + READY_BYTES, GL15.GL_DYNAMIC_COPY);
            texture = GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
            GL45.glTextureBuffer(texture,GL30.GL_R32UI,buffer);
            GpuReplayCommands.clearNamedBufferData(buffer, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer) null);
        }
        for (int start = dirtyMembers.nextSetBit(0); start >= 0;) {
            int end = Math.min(dirtyMembers.nextClearBit(start), start + staging.capacity() / 4);
            staging.clear();
            for (int member = start; member < end; member++) staging.putInt(assignments[member]);
            GpuReplayCommands.namedBufferSubData(buffer, start * 4L, staging.flip());
            start = dirtyMembers.nextSetBit(end);
        }
        for (int start = dirtySlots.nextSetBit(0); start >= 0;) {
            int end = dirtySlots.nextClearBit(start);
            staging.clear();
            for (int at = start; at < end; at++) pack(slots[at]);
            GpuReplayCommands.namedBufferSubData(buffer, FRAME_OFFSET + start * FRAME_BYTES, staging.flip());
            start = dirtySlots.nextSetBit(end);
        }
        dirtyMembers.clear();
        dirtySlots.clear();
        if (authority && tick > lastCapture && pending.size() < 3 && hasWaiting()) {
            GpuReplayCommands.namedBufferSubData(buffer, READY_OFFSET, zeros);
            collecting = true;
        }
    }

    private boolean hasWaiting() {
        for (Slot slot : slots) if (slot != null && waiting(slot.frame)) return true;
        return false;
    }

    private static boolean waiting(Frame frame) {
        return frame.phase() == HOLDING;
    }

    private void pack(Slot slot) {
        if (slot == null) {
            for (int i = 0; i < FRAME_BYTES / 4; i++) staging.putInt(0);
            return;
        }
        var frame = slot.frame;
        vector(frame.center(), StarfallColumns.clock(slot.sample));
        vector(frame.axis(), StarfallColumns.clock(frame.startTick()));
        vector(frame.right(), frame.phase());
        vector(frame.velocity(), frame.id());
    }

    private void vector(Vec3 value, float w) {
        staging.putFloat((float) value.x).putFloat((float) value.y).putFloat((float) value.z).putFloat(w);
    }

    public void bind(int program, float seconds, boolean report) {
        boolean enabled = buffer != 0 && active != 0;
        GpuUniforms.setIntUniform(program, "uStormRingEnabled", enabled ? 1 : 0);
        if (!enabled) return;
        GpuBindings.image(IMAGE,texture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);
        GpuUniforms.setFloatUniform(program, "uStormRingSeconds", seconds);
        boolean write=report&&collecting;
        GpuUniforms.setIntUniform(program, "uStormRingReport", write ? 1 : 0);
        reportIssued|=write;
    }

    public void committed(long tick) {
        if (!collecting || !reportIssued) return;
        collecting = false;
        lastCapture = tick;
        Capture capture = free.pollFirst();
        if (capture == null) capture = new Capture();
        for (int i = 0; i < CAPACITY; i++) capture.frames[i] = slots[i] == null ? null : slots[i].frame;
        GpuReplayCommands.memoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuReplayCommands.copyNamedBufferSubData(buffer, capture.data.id(), READY_OFFSET, 0, READY_BYTES);
        GpuReplayCommands.memoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        capture.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (capture.fence == 0) { capture.data.close(); return; }
        capture.tick = tick;
        pending.addLast(capture);
    }

    private void poll(boolean authority) {
        while (!pending.isEmpty()) {
            Capture capture = pending.getFirst();
            int status = GL32.glClientWaitSync(capture.fence, 0, 0);
            if (status == GL32.GL_TIMEOUT_EXPIRED) break;
            pending.removeFirst();
            GL32.glDeleteSync(capture.fence);
            capture.fence = 0;
            if (authority) consume(capture, status != GL32.GL_WAIT_FAILED);
            free.addLast(capture);
        }
        if (!authority) for (Slot slot : slots) if (slot != null) slot.streak = 0;
    }

    private void consume(Capture capture, boolean completed) {
        if (completed) {
            samples.clear();
            capture.data.readCompleted(0, READY_BYTES, samples);
        }
        int[] ids = new int[CAPACITY];
        int count = 0;
        for (int i = 0; i < CAPACITY; i++) {
            Frame frame = capture.frames[i];
            Slot slot = slots[i];
            if (frame == null || !waiting(frame) || slot == null || slot.id != frame.id()
                    || slot.frame.phase() != frame.phase()) continue;
            if (!completed || Integer.bitCount(samples.getInt(i * 4)) < READY_MEMBERS) {
                slot.streak = 0;
                continue;
            }
            if (++slot.streak >= 3) ids[count++] = slot.id;
        }
        if (count > 0) PacketDistributor.sendToServer(new ServerboundStormRingReadyPacket(identity,
                capture.tick, Arrays.copyOf(ids, count)));
    }

    public void clear() {
        identity = null;
        active = 0;
        lastCapture = Long.MIN_VALUE;
        collecting = false;
        Arrays.fill(slots, null);
        Arrays.fill(lastPacketTick, Long.MIN_VALUE);
        Arrays.fill(assignments, 0);
        dirtyMembers.clear();
        dirtySlots.clear();
        if (buffer != 0) {
            GpuObjectNames.deleteTexture(texture);
            GpuBufferNames.delete(buffer);
            buffer = texture = 0;
        }
        for (Capture capture : pending) {
            GL32.glDeleteSync(capture.fence);
            capture.data.close();
        }
        for (Capture capture : free) capture.data.close();
        pending.clear();
        free.clear();
    }
}
