package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnPacket;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL45;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

public final class StormDisplayTransferGpuValidation {
    private static final int CAPACITY = 16;
    private static final int MEMBERS = 2;
    private static final int COLUMN_BYTES = STORM_COLUMN_MEMBERS * STORM_DISPLAY_COLUMN_BYTES;
    private static final int COLUMN_BASE = STORM_DISPLAY_HEADER_BYTES + CAPACITY * STORM_DISPLAY_RECORD_BYTES;
    private static final int HIGH_COLUMN = STORM_COLUMN_SLOTS - 1;
    private static final String MISSING = "GPU-00000000-0000-0000-0000-000000000000";

    public static void main(String[] args) throws Exception {
        check(GLFW.glfwInit(), "GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        long window = GLFW.glfwCreateWindow(32, 32, "Storm display transfer", 0, 0);
        check(window != 0, "OpenGL context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GL_RENDERER=" + GL11.glGetString(GL11.GL_RENDERER));
            if (List.of(args).contains("--renderer-only")) return;
            var devices = StormGpuDevices.discover();
            check(!devices.isEmpty(), "CUDA device enumeration");
            String sameAdapter = SpatialNative.stormOpenGlCudaUuid();
            String device = sameAdapter != null ? sameAdapter : devices.getFirst().selection().uuid();
            System.out.println("CUDA_DEVICE=" + StormGpuDevices.cudaDevice(device).label());
            try (var staged = new StormStagedDisplay(CAPACITY)) {
                try {
                    SpatialNative.createStagedStormControl(CAPACITY, MISSING, staged.mappings, 2);
                    throw new AssertionError("Missing CUDA UUID resolved to another adapter");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("unavailable"), "Missing CUDA device diagnostics");
                }
                var stagedResult = exercise(SpatialNative.createStagedStormControl(CAPACITY, device, staged.mappings, 2),
                        0, staged);
                if (sameAdapter != null) {
                    long bridge = SpatialNative.createStormDisplayBuffers(COLUMN_BASE + STORM_COLUMN_SLOTS * COLUMN_BYTES, RT_SLOTS);
                    var sharedResult = exercise(SpatialNative.createStormControl(CAPACITY, bridge, 2), bridge, null);
                    check(stagedResult.equals(sharedResult), "Staged and shared display records differ");
                }
            }
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL display lifecycle error");
            System.out.println("Storm display transfer passed (" + (sameAdapter == null ? "cross-adapter" : "staged/shared parity") + ")");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static List<ByteBuffer> exercise(long worker, long bridge, StormStagedDisplay staged) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        var journal = new StormControlJournal();
        var packet = new ClientboundStormColumnPacket(null, 1, 79, false, 13,
                .01f, 8, 12, 15, 17, 0, 62, 0, 68, 0, 0, 8, new int[] {0});
        var birth = List.of(StormControlJournal.Command.lifecycle(true), StormControlJournal.Command.spawn(0, MEMBERS, false));
        var column = List.of(StormControlJournal.Command.columnState(HIGH_COLUMN, packet, true),
                StormControlJournal.Command.columnAssign(packet.id(), HIGH_COLUMN, 0, 0));
        var death = List.of(StormControlJournal.Command.damage(1, 999, 0, 0, true));
        var frames = new java.util.ArrayList<ByteBuffer>(RT_SLOTS);
        for (int i = 0; i < 4; i++) {
            journal.append(i + 1, new StormControlJournal.Parameters(2, true, MEMBERS, 19, 0, 64, 0, 8,
                    0, 0, 0, 1, (i + 1) * .05f, 6, false), switch (i) {
                        case 0 -> birth;
                        case 1 -> column;
                        case 2 -> death;
                        default -> List.of();
                    });
            var tick = journal.sendable();
            int slot = i < RT_SLOTS ? i : -1;
            if (slot >= 0) {
                while (!SpatialNative.prepareStormDisplaySlot(worker, slot)) {
                    check(System.nanoTime() < deadline, "Display slot unavailable");
                    Thread.sleep(1);
                }
            }
            var input = BufferUtils.createByteBuffer(tick.bytes());
            int size = tick.encode(input);
            check(SpatialNative.submitStormTick(worker, input, size, slot, true), "Tick submission");
            journal.sent(tick);
            while (SpatialNative.pollStormTick(worker, tick.sequence()) == 1) {
                check(System.nanoTime() < deadline, "Gameplay receipt timeout");
                Thread.sleep(1);
            }
            int status = SpatialNative.consumeStormTick(worker, tick.sequence());
            check(status == 2, "Gameplay receipt: " + SpatialNative.stormControlDiagnostics(worker));
            journal.receive(tick.sequence(), true);
        }
        check(journal.committed() == 4, "Gameplay advanced with all display slots held");
        long[] metadata = new long[2];
        for (int slot = 0; slot < RT_SLOTS; slot++) {
            int buffer;
            if (staged != null) {
                while (SpatialNative.stagedStormDisplayTime(worker, slot, slot + 1L, metadata) < 0) {
                    check(System.nanoTime() < deadline, "Staged copy timeout");
                    Thread.sleep(1);
                }
                check(metadata[0] == MEMBERS, "Compacted member count");
                long expected = slot == 0 ? 0 : 1L << HIGH_COLUMN;
                check(metadata[1] == expected, "Sparse column mask at slot " + slot);
                int copied = staged.upload(slot, (int) metadata[0], metadata[1]);
                check(copied == STORM_DISPLAY_HEADER_BYTES + MEMBERS * STORM_DISPLAY_RECORD_BYTES
                        + (expected == 0 ? 0 : COLUMN_BYTES), "Valid byte ranges only");
                buffer = staged.buffer(slot);
            } else {
                buffer = SpatialNative.buffers(bridge, slot)[1];
            }
            var records = BufferUtils.createByteBuffer(MEMBERS * STORM_DISPLAY_RECORD_BYTES);
            GL45.glGetNamedBufferSubData(buffer, STORM_DISPLAY_HEADER_BYTES, records);
            check(records.getInt(STORM_DISPLAY_KEY_OFFSET) == 0, "First member identity");
            check(records.getInt(STORM_DISPLAY_RECORD_BYTES + STORM_DISPLAY_KEY_OFFSET) == 1, "Second member identity");
            if (slot == 0) check((records.getInt(STORM_DISPLAY_KEY_OFFSET + 12) & STORM_DISPLAY_BIRTH) != 0, "Birth flag");
            if (slot > 0) check(records.getInt(STORM_DISPLAY_KEY_OFFSET + 4) == packet.id(), "Column assignment");
            frames.add(records);
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            try {
                while (!SpatialNative.recycleStormDisplay(worker, slot, slot + 1L, fence)) {
                    check(System.nanoTime() < deadline, "Display lease retirement");
                    Thread.sleep(1);
                }
            } finally {
                GL32.glDeleteSync(fence);
            }
        }
        while (!SpatialNative.closeStormControl(worker)) {
            check(System.nanoTime() < deadline, "Native session retirement");
            Thread.sleep(1);
        }
        if (bridge != 0) SpatialNative.closeBuffers(bridge);
        return List.copyOf(frames);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
