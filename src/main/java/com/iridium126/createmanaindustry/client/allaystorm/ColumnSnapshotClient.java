package com.iridium126.createmanaindustry.client.allaystorm;

import com.iridium126.createmanaindustry.client.gpu.buffer.*;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

public final class ColumnSnapshotClient {
    public static final ColumnSnapshotClient INSTANCE = new ColumnSnapshotClient();
    public static final int PER_FRAME = 4;
    private static final class Capture {
        GpuReadbackBuffer buffer;
        long fence, epoch, tick;
        float seconds;
        List<ClientboundStormColumnSnapshotRequest> requests = List.of();
    }
    private static final class Restore {
        final StormColumnSnapshotPacket packet;
        final BitSet installed = new BitSet();
        Restore(StormColumnSnapshotPacket packet) { this.packet = packet; }
    }
    private final Map<Integer, ClientboundStormColumnSnapshotRequest> requests = new LinkedHashMap<>();
    private final Map<Integer, Restore> restores = new LinkedHashMap<>();
    private final Capture[] captures = {new Capture(), new Capture(), new Capture(), new Capture()};
    private final ByteBuffer staging = BufferUtils.createByteBuffer(PER_FRAME * StarfallColumnSnapshot.BYTES);
    private final int[] slots = {-1,-1,-1,-1}, tokens = new int[PER_FRAME];
    private final float[] times = new float[PER_FRAME];
    private List<Restore> batch = List.of();
    private int input;
    private int inFlight;
    private long revision, uploaded = -1;

    private boolean matches(StormIdentity identity) {
        var minecraft = Minecraft.getInstance();
        return minecraft.level != null && identity.equals(AllayStormRuntime.INSTANCE.identity())
                && identity.dimension().equals(minecraft.level.dimension().location());
    }

    public void request(ClientboundStormColumnSnapshotRequest request) {
        var player = Minecraft.getInstance().player;
        if (!matches(request.identity()) || player == null || !player.getUUID().equals(request.source())
                || AllayStormRuntime.INSTANCE.columnPacket(request.id()) == null
                || Minecraft.getInstance().level.getGameTime() - request.requestedAt() >= 20) return;
        var previous = requests.get(request.id());
        if (previous == null || previous.token() < request.token()) requests.put(request.id(), request);
    }

    public void receive(StormColumnSnapshotPacket packet) {
        var runtime = AllayStormRuntime.INSTANCE;
        int slot = runtime.columnSlot(packet.id());
        if (!matches(packet.identity()) || slot < 4 || runtime.columnPacket(packet.id()) == null
                || !StarfallGpuBuffers.INSTANCE.columnWaiting(slot, packet.id())) return;
        var previous = restores.get(packet.id());
        if (previous != null && previous.packet.token() >= packet.token()) return;
        restores.put(packet.id(), new Restore(packet)); revision++;
    }

    public boolean restoring() { return !restores.isEmpty(); }

    public void installed(int id, int rank, int token) {
        var restore = restores.get(id);
        if (restore == null || restore.packet.token() != token || rank < 0 || rank >= 64) return;
        restore.installed.set(rank);
        var runtime = AllayStormRuntime.INSTANCE;
        var column = runtime.columnPacket(id);
        if (column == null) return;
        var missing = new BitSet(); missing.set(0, 64); missing.andNot(restore.installed); missing.andNot(runtime.columnCompleted(id));
        for (int member = missing.nextSetBit(0); member >= 0; member = missing.nextSetBit(member + 1))
            if (!runtime.livingMember(column.members()[member])) missing.clear(member);
        if (missing.isEmpty()) {
            restores.remove(id); runtime.columnRestored(id, 0); revision++;
        }
    }

    public void poll(long epoch) {
        if (requests.isEmpty() && restores.isEmpty() && inFlight == 0) return;
        var runtime = AllayStormRuntime.INSTANCE;
        requests.values().removeIf(request -> !matches(request.identity()) || runtime.columnPacket(request.id()) == null
                || Minecraft.getInstance().level.getGameTime() - request.requestedAt() >= 20);
        if (restores.values().removeIf(restore -> !matches(restore.packet.identity()) || runtime.columnPacket(restore.packet.id()) == null)) revision++;
        for (var capture : captures) {
            if (capture.fence == 0) continue;
            int status = GL32.glClientWaitSync(capture.fence, 0, 0);
            if (status == GL32.GL_TIMEOUT_EXPIRED) continue;
            try {
                if (status == GL32.GL_WAIT_FAILED || capture.epoch != epoch) continue;
                staging.clear(); staging.limit(capture.requests.size() * StarfallColumnSnapshot.BYTES);
                capture.buffer.readCompleted(0, staging.remaining(), staging); staging.flip();
                for (int entry = 0; entry < capture.requests.size(); entry++) {
                    var request = capture.requests.get(entry);
                    if (!matches(request.identity()) || Minecraft.getInstance().level.getGameTime() - request.requestedAt() >= 20) continue;
                    var column = runtime.columnPacket(request.id()); if (column == null) continue;
                    float[] values = new float[StarfallColumnSnapshot.FLOATS];
                    for (int field = 0; field < values.length; field++) values[field] = staging.getFloat((entry * values.length + field) * 4);
                    var completed = runtime.columnCompleted(request.id()); boolean ready = true;
                    for (int rank = 0; rank < 64; rank++) {
                        int offset = rank * StarfallColumnSnapshot.STRIDE;
                        if (completed.get(rank) || !runtime.livingMember(column.members()[rank])) { Arrays.fill(values, offset, offset + StarfallColumnSnapshot.STRIDE, 0); values[offset] = 6; }
                        else if (values[offset] == 255) ready = false;
                    }
                    if (!ready) { requests.putIfAbsent(request.id(), request); continue; }
                    PacketDistributor.sendToServer(new StormColumnSnapshotPacket(request.dimension(), request.epoch(), request.id(), request.token(), request.source(), capture.tick, capture.seconds, new StarfallColumnSnapshot(values)));
                }
            } finally { GL32.glDeleteSync(capture.fence); capture.fence = 0; capture.requests = List.of(); inFlight--; }
        }
    }

    public void prepare() {
        if (restores.isEmpty() && batch.isEmpty()) return;
        var runtime = AllayStormRuntime.INSTANCE;
        var selected = restores.values().stream().limit(PER_FRAME).toList();
        if (!selected.equals(batch)) { batch = selected; revision++; }
        if (batch.isEmpty()) return;
        Arrays.fill(slots, -1); Arrays.fill(tokens, 0); Arrays.fill(times, 0);
        for (int entry = 0; entry < batch.size(); entry++) {
            var packet = batch.get(entry).packet;
            slots[entry] = runtime.columnSlot(packet.id()); tokens[entry] = packet.token(); times[entry] = packet.sampleSec();
            if (slots[entry] >= 4 && uploaded != revision) runtime.columnRestored(packet.id(), packet.token());
        }
        if (uploaded == revision) return;
        if (input == 0) {
            input = GL15.glGenBuffers(); GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, input);
            GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER, PER_FRAME * StarfallColumnSnapshot.BYTES, GL15.GL_DYNAMIC_DRAW);
        }
        staging.clear();
        for (var restore : batch) for (float value : restore.packet.snapshot().values()) staging.putFloat(value);
        staging.flip(); GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, input); GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER, 0, staging); uploaded = revision;
    }

    public void bind(int program) {
        GpuUniforms.setIntUniform(program, "uColumnRestoreCount", batch.size());
        if (batch.isEmpty()) return;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 22, input);
        GL41.glProgramUniform4iv(program, GpuUniforms.loc(program, "uColumnRestoreSlots"), slots);
        GL41.glProgramUniform4iv(program, GpuUniforms.loc(program, "uColumnRestoreTokens"), tokens);
        GL41.glProgramUniform4fv(program, GpuUniforms.loc(program, "uColumnRestoreTimes"), times);
    }

    public void capture(int program, int pool, int count, int emitters, long epoch, long tick, float seconds, int upper) {
        if (requests.isEmpty()) return;
        Capture target = null;
        for (var capture : captures) if (capture.fence == 0) { target = capture; break; }
        if (target == null) return;
        var selected = new ArrayList<ClientboundStormColumnSnapshotRequest>();
        var selectedSlots = new int[]{-1,-1,-1,-1};
        for (var request : requests.values()) {
            int slot = AllayStormRuntime.INSTANCE.columnSlot(request.id());
            if (slot < 4 || StarfallGpuBuffers.INSTANCE.columnWaiting(slot, request.id())) continue;
            selectedSlots[selected.size()] = slot; selected.add(request); if (selected.size() == PER_FRAME) break;
        }
        if (selected.isEmpty()) return;
        if (target.buffer == null) target.buffer = new GpuReadbackBuffer(PER_FRAME * StarfallColumnSnapshot.BYTES);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, target.buffer.id());
        GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT, new float[]{255});
        GL20.glUseProgram(program); StarfallGpuBuffers.INSTANCE.bind(program, false);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, pool); GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, count);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, emitters); GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 22, target.buffer.id());
        GpuUniforms.setIntUniform(program, "uColumnCount", selected.size());
        GL41.glProgramUniform4iv(program, GpuUniforms.loc(program, "uColumnSlots"), selectedSlots);
        GL43.glDispatchCompute(Math.max(1, (upper + 63) / 64), 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        target.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (target.fence == 0) throw new IllegalStateException("Starfall snapshot fence failed");
        inFlight++;
        target.epoch = epoch; target.tick = tick; target.seconds = seconds; target.requests = List.copyOf(selected);
        for (var request : selected) requests.remove(request.id(), request);
    }

    public void clear() {
        requests.clear(); restores.clear(); batch = List.of(); revision++; uploaded = -1; inFlight = 0;
        for (var capture : captures) {
            if (capture.fence != 0) GL32.glDeleteSync(capture.fence);
            capture.fence = 0; capture.requests = List.of();
            if (capture.buffer != null) { capture.buffer.close(); capture.buffer = null; }
        }
        if (input != 0) { GpuBufferNames.delete(input); input = 0; }
    }
}
