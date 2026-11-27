package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.content.allaystorm.DomeGatherState;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages;
import com.iridium126.createmanaindustry.client.allaystorm.StormTerrainEdits;
import com.iridium126.createmanaindustry.content.allaystorm.HelixGatherFrame;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnPacket;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationBackend;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationGpu;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
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
            if (List.of(args).contains("--ring-only")) {
                validateStagedRing(List.of(args).contains("--dxr"));
                return;
            }
            validateOpenGlStatus();
            if (List.of(args).contains("--dxr")) {
                validateDxr();
                return;
            }
            var devices = StormGpuDevices.discoverCuda();
            check(!devices.isEmpty(), "CUDA device enumeration");
            String sameAdapter = SpatialNative.stormOpenGlCudaUuid();
            String device = sameAdapter != null ? sameAdapter : devices.getFirst().uuid();
            System.out.println("CUDA_DEVICE=" + devices.stream().filter(gpu -> gpu.uuid().equals(device)).findFirst().orElseThrow().model());
            try (var staged = new StormStagedDisplay(CAPACITY)) {
                try {
                    SpatialNative.createStagedStormControl(CAPACITY, MISSING, staged.mappings, 2);
                    throw new AssertionError("Missing CUDA UUID resolved to another adapter");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("unavailable"), "Missing CUDA device diagnostics");
                }
                var stagedResult = exercise(new StormControlNative(false), SpatialNative.createStagedStormControl(CAPACITY, device, staged.mappings, 2),
                        0, staged, null, null);
                if (sameAdapter != null) {
                    long bridge = SpatialNative.createStormDisplayBuffers(COLUMN_BASE + STORM_COLUMN_SLOTS * COLUMN_BYTES, RT_SLOTS);
                    var sharedResult = exercise(new StormControlNative(false), SpatialNative.createStormControl(CAPACITY, bridge, 2), bridge, null, null, null);
                    check(stagedResult.equals(sharedResult), "Staged and shared display records differ");
                    validateOptixQueryTiming();
                }
            }
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL display lifecycle error");
            System.out.println("Storm display transfer passed (" + (sameAdapter == null ? "cross-adapter" : "staged/shared parity") + ")");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void validateStagedRing(boolean dxr) throws Exception {
        var api=new StormControlNative(dxr);
        String device=dxr?StormGpuDevices.discoverDxr().getFirst().selection().uuid()
                :StormGpuDevices.discoverCuda().getFirst().uuid();
        try(var stage=new StormStagedDisplay(CAPACITY)){
            long worker=dxr?StormDxrNative.create(CAPACITY,device,stage.mappings,2,0,0)
                    :SpatialNative.createStagedStormControl(CAPACITY,device,stage.mappings,2);
            long deadline=System.nanoTime()+180_000_000_000L;
            try{
                verifyRingTerrainEdits(api,worker,new StormControlJournal(),0,stage,null,deadline);
            }finally{
                while(!api.closeStormControl(worker)){
                    check(System.nanoTime()<deadline,"Staged ring worker retirement");Thread.sleep(1);
                }
            }
        }
    }

    private static void validateOpenGlStatus() {
        var before = ClientConfig.stormSimulation;
        var runtime = SpatialQueryRuntime.INSTANCE;
        try {
            ClientConfig.stormSimulation = new ClientConfig.StormSimulation(StormSimulationBackend.OPENGL,
                    StormSimulationGpu.FOLLOW_MINECRAFT);
            runtime.beforeStormActivation(com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE);
            var status = runtime.hardwareStatus();
            check(status.phase() == SpatialQueryRuntime.HardwarePhase.RUNNING && status.api().equals("opengl"),
                    "OpenGL route");
            check(status.transport().equals("opengl-local") && status.simulationGpu().equals(status.renderer()),
                    "OpenGL uses the rendering GPU");
            check(!status.diagnostics().contains("gl_queries") && !status.diagnostics().contains("GL Storm submissions"),
                    "OpenGL hardware diagnostics have no cumulative counters");
        } finally {
            ClientConfig.stormSimulation = before;
            runtime.close();
        }
    }

    private static void validateOptixQueryTiming() throws Exception {
        int packetBytes = SPATIAL_HEADER_BYTES + SPATIAL_RECORD_BYTES;
        long bridge = SpatialNative.createBuffers(packetBytes, RT_SLOTS);
        long worker = SpatialNative.create(bridge, 1, 0, 2, 1, "");
        long deadline = System.nanoTime() + 10_000_000_000L;
        for (int sequence = 1; sequence <= 3; sequence++) {
            boolean timed = sequence == 2;
            var input = BufferUtils.createByteBuffer(packetBytes).order(ByteOrder.LITTLE_ENDIAN);
            input.putInt(0, 0x41313149).putInt(4, ABI).putLong(8, sequence).putLong(16, 1)
                    .putLong(24, sequence).putLong(32, sequence + 1).putInt(40, 1)
                    .putFloat(48, 1).putFloat(52, .05f).putFloat(56, .1f).putLong(64, 1)
                    .putInt(72, 1).putInt(76, 2).putInt(116, 48);
            input.putFloat(SPATIAL_HEADER_BYTES + 12, .34f).putFloat(SPATIAL_HEADER_BYTES + 16, 8)
                    .putFloat(SPATIAL_HEADER_BYTES + 28, 16).putInt(SPATIAL_HEADER_BYTES + 36, 1234)
                    .putInt(SPATIAL_HEADER_BYTES + 40, 9).putInt(SPATIAL_HEADER_BYTES + 44, 1)
                    .putFloat(SPATIAL_HEADER_BYTES + 48, 8);
            GL45.glNamedBufferSubData(SpatialNative.buffers(bridge, 0)[0], 0, input);
            check(timed ? SpatialNative.submitRange(worker, 0, sequence, sequence, 1, 0, 1, 0)
                    : SpatialNative.submitRangeUntimed(worker, 0, sequence, sequence, 1, 0, 1, 0),
                    "OptiX query submission");
            while (SpatialNative.poll(worker, 0, sequence) == 1) {
                check(System.nanoTime() < deadline, "OptiX query timeout");
                Thread.sleep(1);
            }
            check(SpatialNative.poll(worker, 0, sequence) == 2, SpatialNative.diagnostics(worker));
            int receipt = SpatialNative.consume(worker, 0, sequence);
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            try {
                check(receipt == 0, "OptiX query receipt " + receipt);
                if (timed) {
                    check(java.util.Arrays.stream(SpatialNative.timing(worker, 0, sequence)).sum() > 0,
                            "OptiX query timing enabled");
                } else {
                    try {
                        SpatialNative.timing(worker, 0, sequence);
                        throw new AssertionError("Untimed OptiX query exposed timings");
                    } catch (IllegalStateException expected) {
                        check(expected.getMessage().contains("timing was not requested"), "OptiX query timing disabled");
                    }
                }
            } finally {
                while (!SpatialNative.recycle(worker, 0, sequence, fence)) {
                    check(System.nanoTime() < deadline, "OptiX query retirement");
                    Thread.sleep(1);
                }
                GL32.glDeleteSync(fence);
            }
        }
        while (!SpatialNative.close(worker)) {
            check(System.nanoTime() < deadline, "OptiX worker retirement");
            Thread.sleep(1);
        }
        SpatialNative.closeBuffers(bridge);
    }

    private static void validateDxr() throws Exception {
        var devices = StormGpuDevices.discoverDxr();
        var render = StormGpuDevices.renderAdapter();
        check(render.luid() != 0, "OpenGL renderer LUID required");
        check(!devices.isEmpty(), "DXR device enumeration");
        check(StormGpuDevices.discoverDxr().equals(devices), "Stable PnP selection across device enumeration");
        var catalog = StormGpuDevices.discover();
        check(catalog.stream().map(StormGpuDevices.Device::luid).distinct().count() == catalog.size(), "One choice per physical GPU");
        var renderer = catalog.stream().filter(device -> device.luid() == render.luid()).findFirst().orElseThrow();
        var auto = new ClientConfig.StormSimulation(StormSimulationBackend.AUTO, StormSimulationGpu.FOLLOW_MINECRAFT);
        var autoRoute = StormGpuDevices.resolve(auto);
        check(autoRoute.shared() && autoRoute.dxr() == renderer.cudaUuid().isEmpty(), "AUTO follows this GPU and prefers OptiX");
        check(autoRoute.equals(StormGpuDevices.resolve(new ClientConfig.StormSimulation(
                StormSimulationBackend.AUTO, renderer.selection()))), "Pinning the rendering GPU uses the same route");
        var api = new StormControlNative(true);
        try (var staged = new StormStagedDisplay(CAPACITY)) {
            var missing = StormDxrNative.create(CAPACITY, "PNP-" + "0".repeat(64), staged.mappings, 2, render.luid(), render.nodes());
            var journal = new StormControlJournal();
            journal.append(1, new StormControlJournal.Parameters(2, true, MEMBERS, 19, 0, 64, 0, 8,
                    0, 0, 0, 1, .05f, 6, false), List.of(StormControlJournal.Command.spawn(0, MEMBERS, false)));
            var tick = journal.sendable();
            var input = BufferUtils.createByteBuffer(tick.bytes());
            int bytes = tick.encode(input);
            check(api.submitStormTick(missing, input, bytes, -1, false), "Missing DXR input submission");
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (api.pollStormTick(missing, 1) == 1) {
                check(System.nanoTime() < deadline, "Missing DXR device detection");
                Thread.sleep(1);
            }
            check(api.consumeStormTick(missing, 1) == 4, "Missing DXR device failed");
            check(api.stormControlDiagnostics(missing).contains("unavailable"), "Missing DXR device diagnostics");
            while (!api.closeStormControl(missing)) Thread.sleep(1);
        }
        for (var device : devices) {
            var selection = device.selection();
            check(selection.equals(StormSimulationGpu.parse(selection.value())), "DXR device serialization");
            boolean same = device.luid() == render.luid();
            System.out.println("DXR_DEVICE=" + selection.label() + " PCI=" + device.pci() +
                    " transport=" + (same ? "d3d12-shared" : "cross-adapter-staged"));
            try (var staged = same ? null : new StormStagedDisplay(CAPACITY);
                 var shared = same ? new StormDxrSharedDisplay() : null) {
                if (same) {
                    StormDxrSharedDisplay.requireInterop(render);
                    try {
                        StormDxrSharedDisplay.requireInterop(new StormGpuDevices.RenderAdapter(render.luid(), 2));
                        throw new AssertionError("Unsupported node mask accepted for DXR sharing");
                    } catch (IllegalStateException expected) {
                        check(expected.getMessage().contains("node 0"), "Same-adapter interoperability diagnostics");
                    }
                }
                var route = StormGpuDevices.resolve(new ClientConfig.StormSimulation(StormSimulationBackend.DXR, selection));
                check(route.dxr() && route.shared() == same && route.id().equals(selection.uuid()), "Explicit DXR route");
                long worker = StormDxrNative.create(CAPACITY, route.id(), same ? null : staged.mappings,
                        2, render.luid(), render.nodes());
                check(exercise(api, worker, 0, staged, shared, selection.label()).size() == RT_SLOTS, "DXR display snapshots");
            }
        }
        check(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL DXR display lifecycle");
        System.out.println("Storm DXR display transfer passed");
    }

    private static List<ByteBuffer> exercise(StormControlNative api, long worker, long bridge, StormStagedDisplay staged,
                                             StormDxrSharedDisplay shared, String dxrModel) throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        var journal = new StormControlJournal();
        var packet = new ClientboundStormColumnPacket(null, 1, 79, false, 13,
                .01f, .12f, 12, 15, 17, 0, 62, 0, 68, 0, 0, 8, new int[] {0});
        var birth = List.of(StormControlJournal.Command.lifecycle(true), StormControlJournal.Command.spawn(0, MEMBERS, false));
        var column = List.of(StormControlJournal.Command.columnState(HIGH_COLUMN, packet, true),
                StormControlJournal.Command.columnAssign(packet.id(), HIGH_COLUMN, 0, 0));
        var death = List.of(StormControlJournal.Command.damage(1, 999, 0, 0, true));
        var frames = new java.util.ArrayList<ByteBuffer>(RT_SLOTS);
        var receipts = BufferUtils.createByteBuffer(STORM_DOME_BATCH_BYTES);
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
                while (!api.prepareStormDisplaySlot(worker, slot)) {
                    check(System.nanoTime() < deadline, "Display slot unavailable");
                    Thread.sleep(1);
                }
            }
            var input = BufferUtils.createByteBuffer(tick.bytes());
            int size = tick.encode(input);
            boolean timed = i == 1 || i == 2;
            check(api.submitStormTick(worker, input, size, slot, timed), "Tick submission");
            journal.sent(tick);
            while (api.pollStormTick(worker, tick.sequence()) == 1) {
                check(System.nanoTime() < deadline, "Gameplay receipt timeout at sequence " + tick.sequence());
                Thread.sleep(1);
            }
            if (i == 2 && api.pollStormTick(worker, tick.sequence()) == 2) {
                check(api.copyStormDomeBatch(worker, tick.sequence(), receipts), "Starfall gameplay receipt");
                check(receipts.getInt(STORM_COLUMN_BATCH_OFFSET + HIGH_COLUMN * STORM_COLUMN_BATCH_STRIDE)
                        == packet.id(), "Starfall gameplay identity");
            }
            check(api.pollStormTick(worker, tick.sequence()) == 2, api.stormControlDiagnostics(worker));
            if (timed) {
                var timings = api.stormTickTiming(worker, tick.sequence());
                check(java.util.Arrays.stream(timings, 0, 7).sum() > 0, "Opt-in stage timing");
            } else {
                try {
                    api.stormTickTiming(worker, tick.sequence());
                    throw new AssertionError("Untimed tick exposed stage timings");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("timing was not requested"), "Timing disabled");
                }
            }
            int status = api.consumeStormTick(worker, tick.sequence());
            check(status == 2, "Gameplay receipt: " + api.stormControlDiagnostics(worker));
            journal.receive(tick.sequence(), true);
        }
        check(journal.committed() == 4, "Gameplay advanced with all display slots held");
        if (api.dxr()) {
            String model = StormGpuDevices.shortModel(StormDxrNative.gpuModel(worker));
            check(model.equals(dxrModel), "The selected DXR adapter actually ran the simulation");
            System.out.println("DXR_ACTIVE=" + model);
        }
        long[] metadata = new long[2];
        for (int slot = 0; slot < RT_SLOTS; slot++) {
            int buffer;
            if (staged != null) {
                while (api.stagedStormDisplayTime(worker, slot, slot + 1L, metadata) < 0) {
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
            } else if (shared != null) {
                try {
                    api.stagedStormDisplayTime(worker, slot, slot + 1L, metadata);
                    throw new AssertionError("DXR shared snapshot exposed a CPU transfer");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("display request"), "DXR shared slots have no staged readback");
                }
                buffer = shared.acquire(worker, slot, slot + 1L);
            } else {
                buffer = SpatialNative.buffers(bridge, slot)[1];
            }
            var records = BufferUtils.createByteBuffer(MEMBERS * STORM_DISPLAY_RECORD_BYTES);
            GL45.glGetNamedBufferSubData(buffer, STORM_DISPLAY_HEADER_BYTES, records);
            check(records.getInt(STORM_DISPLAY_KEY_OFFSET) == 0, "First member identity");
            check(records.getInt(STORM_DISPLAY_RECORD_BYTES + STORM_DISPLAY_KEY_OFFSET) == 1, "Second member identity");
            if (slot == 0) check((records.getInt(STORM_DISPLAY_KEY_OFFSET + 12) & STORM_DISPLAY_BIRTH) != 0, "Birth flag");
            if (slot > 0) check(records.getInt(STORM_DISPLAY_KEY_OFFSET + 4) == packet.id(), "Column assignment");
            if (slot == 2) {
                var curve = BufferUtils.createByteBuffer(STORM_DISPLAY_COLUMN_BYTES);
                GL45.glGetNamedBufferSubData(buffer, COLUMN_BASE + HIGH_COLUMN * COLUMN_BYTES, curve);
                check(curve.getInt(32) == packet.id(), "Starfall descent curve publication");
                check(curve.getFloat(STORM_DISPLAY_COLUMN_ARC_OFFSET + 64 * Float.BYTES) > 0, "Starfall arc table");
                check(records.getFloat(STORM_DISPLAY_RECORD_BYTES + STORM_DISPLAY_APPEARANCE_OFFSET) <= 0,
                        "Member death reached display");
            }
            frames.add(records);
            long fence = shared == null ? GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0) : 0;
            try {
                while (!(shared == null ? api.recycleStormDisplay(worker, slot, slot + 1L, fence)
                        : shared.recycle(worker, slot, slot + 1L))) {
                    check(System.nanoTime() < deadline, "Display lease retirement");
                    Thread.sleep(1);
                }
            } finally {
                if (fence != 0) GL32.glDeleteSync(fence);
            }
        }
        verifyHelixGatherReceipt(api, worker, journal, deadline);
        if (shared != null) {
            journal.append(6, new StormControlJournal.Parameters(3, true, MEMBERS, 19, 0, 64, 0, 8,
                    0, 0, 0, 1, .30f, 6, false), birth);
            var tick = journal.sendable();
            var input = BufferUtils.createByteBuffer(tick.bytes());
            check(api.prepareStormDisplaySlot(worker, 0), "Shared slot returned after OpenGL release");
            check(api.submitStormTick(worker, input, tick.encode(input), 0, false), "New identity reused shared slot");
            journal.sent(tick);
            while (api.pollStormTick(worker, tick.sequence()) == 1) {
                check(System.nanoTime() < deadline, "Reused slot receipt timeout");
                Thread.sleep(1);
            }
            check(api.consumeStormTick(worker, tick.sequence()) == 2, "New identity receipt");
            journal.receive(tick.sequence(), true);
            var header = BufferUtils.createByteBuffer(STORM_DISPLAY_HEADER_BYTES);
            int sharedBuffer = shared.acquire(worker, 0, tick.sequence());
            // Inspect GPU-visible bytes because a direct CPU read of the imported buffer can return stale data.
            int copiedHeader = GL45.glCreateBuffers();
            try {
                GL45.glNamedBufferStorage(copiedHeader, STORM_DISPLAY_HEADER_BYTES, 0);
                GL45.glCopyNamedBufferSubData(sharedBuffer, copiedHeader, 0, 0, STORM_DISPLAY_HEADER_BYTES);
                GL45.glGetNamedBufferSubData(copiedHeader, 0, header);
            } finally {
                GL45.glDeleteBuffers(copiedHeader);
            }
            long sequence = header.getLong(16), identity = header.getLong(32);
            check(sequence == tick.sequence() && identity == tick.parameters().identity(),
                    "Reused snapshot header has sequence " + sequence + " and identity " + identity
                            + "; expected " + tick.sequence() + " and " + tick.parameters().identity());
            while (!shared.recycle(worker, 0, tick.sequence())) {
                check(System.nanoTime() < deadline, "Reused slot fence retirement");
                Thread.sleep(1);
            }
        }
        verifyRingTerrainEdits(api,worker,journal,bridge,staged,shared,deadline);
        while (!api.closeStormControl(worker)) {
            check(System.nanoTime() < deadline, "Native session retirement");
            Thread.sleep(1);
        }
        if (bridge != 0) SpatialNative.closeBuffers(bridge);
        return List.copyOf(frames);
    }

    private static void verifyRingTerrainEdits(StormControlNative api,long worker,StormControlJournal journal,
                                               long bridge,StormStagedDisplay staged,StormDxrSharedDisplay shared,
                                               long deadline) throws Exception {
        var pages=new java.util.HashMap<StormCollisionPages.Section,StormCollisionPages.Page>();
        var masks=new java.util.HashMap<StormCollisionPages.Section,int[]>();
        int[] clear=new int[128];java.util.Arrays.fill(clear,-1);
        for(int x=-1;x<=0;x++)for(int y=3;y<=4;y++)for(int z=-1;z<=0;z++){
            var section=new StormCollisionPages.Section(x,y,z);
            pages.put(section,new StormCollisionPages.Page(1,StormCollisionPages.SOLID,null));
            masks.put(section,clear);
        }
        int ringId=123,ringSlot=STORM_RING_SLOTS-1;
        long scope=(long)ringSlot<<32|ringId;
        var zero=new StormControlJournal.Float4(0,0,0,0);
        var ring=List.of(
                new StormControlJournal.Command(STORM_CONTROL_RING_STATE,0,1,1,scope,
                        new StormControlJournal.Float4(0,64,0,1),new StormControlJournal.Float4(0,0,1,1)),
                new StormControlJournal.Command(STORM_CONTROL_RING_BASIS,0,1,0,scope,
                        new StormControlJournal.Float4(1,0,0,0),zero),
                new StormControlJournal.Command(STORM_CONTROL_RING_ASSIGN,0,1,0,scope,zero,zero),
                new StormControlJournal.Command(STORM_CONTROL_RING_ASSIGN,1,1,1,scope,zero,zero));
        for(int i=0;i<4;i++){
            var commands=i==0?List.of(StormControlJournal.Command.lifecycle(true),
                    StormControlJournal.Command.spawn(0,MEMBERS,false)):i==1?ring:List.<StormControlJournal.Command>of();
            var edits=i==2?StormTerrainEdits.pack(1,masks):i==3?StormTerrainEdits.pack(2,java.util.Map.of()):null;
            journal.appendWorldSample(20+i,new StormControlJournal.Parameters(4,true,MEMBERS,19,0,64,0,2,
                    0,0,0,1,1+i*.05f,6,false),commands,i==0?StormControlTerrain.capture(pages):null,
                    StormControlJournal.Target.ABSENT,0,List.of(),false,null,List.of(),false,edits);
            var tick=journal.sendable();int slot=i==0?-1:0;
            if(slot>=0)check(api.prepareStormDisplaySlot(worker,slot),"Ring display slot");
            var input=BufferUtils.createByteBuffer(tick.bytes());
            check(api.submitStormTick(worker,input,tick.encode(input),slot,false),"Ring edit tick submission");
            journal.sent(tick);
            while(api.pollStormTick(worker,tick.sequence())==1){
                check(System.nanoTime()<deadline,"Ring edit worker timeout");Thread.sleep(1);
            }
            check(api.consumeStormTick(worker,tick.sequence())==2,api.stormControlDiagnostics(worker));
            journal.receive(tick.sequence(),true);
            if(slot<0)continue;
            int buffer;
            if(staged!=null){
                var meta=new long[2];
                while(api.stagedStormDisplayTime(worker,slot,tick.sequence(),meta)<0){
                    check(System.nanoTime()<deadline,"Ring staged copy timeout");Thread.sleep(1);
                }
                staged.upload(slot,(int)meta[0],meta[1]);buffer=staged.buffer(slot);
            }else if(shared!=null)buffer=shared.acquire(worker,slot,tick.sequence());
            else buffer=SpatialNative.buffers(bridge,slot)[1];
            int copy=GL45.glCreateBuffers();
            var records=BufferUtils.createByteBuffer(MEMBERS*STORM_DISPLAY_RECORD_BYTES);
            try {
                GL45.glNamedBufferStorage(copy,records.capacity(),0);
                GL45.glCopyNamedBufferSubData(buffer,copy,STORM_DISPLAY_HEADER_BYTES,0,records.capacity());
                GL45.glGetNamedBufferSubData(copy,0,records);
            }finally{GL45.glDeleteBuffers(copy);}
            for(int member=0;member<MEMBERS;member++){
                int key=member*STORM_DISPLAY_RECORD_BYTES+STORM_DISPLAY_KEY_OFFSET;
                check(records.getInt(key+4)==ringId,"Ring assignment uses the independent highest ring slot");
                boolean embedded=(records.getInt(key+12)&4)!=0;
                check(embedded==(i!=2),"Sparse clear/replacement must affect native occupancy with no terrain upload. Step "+i);
            }
            long fence=shared==null?GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0):0;
            try{
                while(!(shared==null?api.recycleStormDisplay(worker,slot,tick.sequence(),fence)
                        :shared.recycle(worker,slot,tick.sequence()))){
                    check(System.nanoTime()<deadline,"Ring display retirement");Thread.sleep(1);
                }
            }finally{if(fence!=0)GL32.glDeleteSync(fence);}
        }
        System.out.println("Ring assignment and paused terrain-upload edit/restore passed");
    }

    private static void verifyHelixGatherReceipt(StormControlNative api, long worker, StormControlJournal journal,
                                                 long deadline) throws InterruptedException {
        int id = 71, slot = STORM_DOME_SLOTS - 1;
        var frame = HelixGatherFrame.initial(new Vec3(0, 64, 0), new Vec3(1, 0, 0), Vec3.ZERO);
        var controls = new ArrayList<StormControlJournal.Command>();
        controls.add(StormControlJournal.Command.helixState(id, slot, 1, true, 1, .25f, 1, 2, 0, 12, 32, 64, true));
        StormControlJournal.Command.helixFrames(controls, id, slot, new HelixGatherFrame[]{frame});
        controls.add(StormControlJournal.Command.helixAssign(id, slot, 0, 0));
        journal.append(5, new StormControlJournal.Parameters(2, true, MEMBERS, 19, 0, 64, 0, 8,
                0, 0, 0, 1, .25f, 6, false), controls);
        var tick = journal.sendable();
        var input = BufferUtils.createByteBuffer(tick.bytes());
        check(api.submitStormTick(worker, input, tick.encode(input), -1, false), "Helix gather submission");
        journal.sent(tick);
        while (api.pollStormTick(worker, tick.sequence()) == 1) {
            check(System.nanoTime() < deadline, "Helix gather receipt timeout");
            Thread.sleep(1);
        }
        check(api.pollStormTick(worker, tick.sequence()) == 2, api.stormControlDiagnostics(worker));
        var batch = BufferUtils.createByteBuffer(STORM_DOME_BATCH_BYTES);
        check(api.copyStormDomeBatch(worker, tick.sequence(), batch), "Helix gather receipt");
        check(api.consumeStormTick(worker, tick.sequence()) == 2, "Helix gather receipt consumed");
        journal.receive(tick.sequence(), true);

        int reference = STORM_HELIX_REFERENCE_OFFSET + slot * STORM_HELIX_REFERENCE_BYTES;
        check(batch.getInt(slot * Integer.BYTES) == id && batch.getInt(reference) == frame.version()
                && batch.getInt(reference + 4) == 1, "Helix gather reference is valid");
        int pauses = STORM_DOME_PAUSED_OFFSET + slot * STORM_DOME_FLOWS * Float.BYTES;
        var pauseTimes = new float[STORM_DOME_FLOWS];
        for (int flow = 0; flow < pauseTimes.length; flow++) {
            float pause = batch.getFloat(pauses + flow * Float.BYTES);
            pauseTimes[flow] = Float.isFinite(pause) ? pause : -1;
        }
        var flowState = new DomeGatherState(pauseTimes);
        check(flowState.revision() == 0, "Helix gather receipt included Dome pauses: " + flowState.revision());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
