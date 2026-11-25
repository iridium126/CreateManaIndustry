package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationBackend;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationGpu;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

public final class StormGpuDevices {
    public record Device(StormSimulationGpu selection, String pci, long luid, boolean dxr, String cudaUuid) {
        public boolean visible(long renderLuid, String savedId) {
            return renderLuid == 0 || luid != renderLuid || selection.uuid().equals(savedId);
        }

        public String backends() {
            if (cudaUuid.isEmpty()) return "DXR";
            return dxr ? "OptiX, DXR" : "OptiX";
        }
    }
    record CudaDevice(String uuid, String model, long luid) {}
    public record RenderAdapter(long luid, int nodes) {
        private static final RenderAdapter UNKNOWN = new RenderAdapter(0, 0);
    }
    record Route(boolean dxr, String id, String model, boolean shared, RenderAdapter renderer) {}
    private static String renderer;

    private StormGpuDevices() {}

    public static String renderer() {
        if (renderer == null && GLFW.glfwGetCurrentContext() != 0) {
            String value = GL11.glGetString(GL11.GL_RENDERER);
            if (value != null) renderer = shortModel(value);
        }
        return renderer == null ? "" : renderer;
    }

    public static String shortModel(String model) {
        return model.replaceFirst("(?i)/(?:PCIe|SSE2).*", "")
                .replaceFirst("(?i)^NVIDIA\\s+(?:GeForce\\s+)?", "")
                .replaceFirst("(?i)^Intel(?:\\(R\\))?\\s+", "")
                .replaceFirst("(?i)^AMD\\s+", "")
                .replaceAll("(?i)\\((?:R|TM)\\)", "")
                .replaceFirst("(?i)\\s+(?:GPU|Graphics)$", "").strip();
    }

    public static List<Device> discover() {
        var cuda = cudaByLuid();
        try {
            var devices = new ArrayList<Device>();
            for (var device : windowsDevices(true)) {
                var optix = cuda.get(device.luid());
                if (device.dxr() || optix != null)
                    devices.add(new Device(device.selection(), device.pci(), device.luid(), device.dxr(),
                            optix == null ? "" : optix.uuid()));
            }
            return List.copyOf(devices);
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            // The saved model can still be displayed if enumeration fails.
            return List.of();
        }
    }

    public static RenderAdapter renderAdapter() {
        if (GLFW.glfwGetCurrentContext() == 0) return RenderAdapter.UNKNOWN;
        var capabilities = GL.getCapabilities();
        if (!capabilities.GL_EXT_memory_object_win32 && !capabilities.GL_EXT_semaphore_win32)
            return RenderAdapter.UNKNOWN;
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(EXTMemoryObjectWin32.GL_LUID_SIZE_EXT);
            if (capabilities.GL_EXT_memory_object_win32)
                EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, data);
            else EXTSemaphore.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, data);
            return new RenderAdapter(data.getLong(0), GL11.glGetInteger(EXTMemoryObjectWin32.GL_DEVICE_NODE_MASK_EXT));
        }
    }

    static Route resolve(ClientConfig.StormSimulation selection) throws IOException {
        var backend = selection.backend();
        var gpu = selection.gpu();
        if (backend == StormSimulationBackend.OPENGL)
            throw new IllegalArgumentException("OpenGL simulation does not create a native Storm");
        boolean allowOptix = backend != StormSimulationBackend.DXR;
        var render = renderAdapter();
        if (allowOptix && gpu.followsMinecraft()) {
            String cuda = renderCudaUuid();
            if (cuda != null) return new Route(false, cuda, renderer(), true, render);
            if (backend == StormSimulationBackend.OPTIX && render.luid() == 0)
                throw new IllegalStateException("Minecraft's GPU is unavailable to OptiX");
        }
        if (gpu.followsMinecraft() && render.luid() == 0)
            throw new IllegalStateException("Cannot identify Minecraft's DXR adapter (OpenGL LUID unavailable)");
        var device = windowsDevices(false).stream()
                .filter(candidate -> gpu.followsMinecraft() ? candidate.luid() == render.luid()
                        : candidate.selection().uuid().equals(gpu.uuid()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Selected Storm GPU is unavailable: " + gpu.value()));
        if (allowOptix) {
            var cuda = cudaByLuid().get(device.luid());
            if (cuda != null) {
                boolean same = render.luid() == 0 ? cuda.uuid().equals(renderCudaUuid()) : device.luid() == render.luid();
                return new Route(false, cuda.uuid(), cuda.model(), same, render);
            }
            if (backend == StormSimulationBackend.OPTIX)
                throw new IllegalStateException("Selected Storm GPU is unavailable to OptiX: " + device.selection().label());
        }
        if (render.luid() == 0)
            throw new IllegalStateException("Cannot select a DXR display transport without Minecraft's OpenGL LUID");
        boolean shared = render.luid() == device.luid();
        if (shared) StormDxrSharedDisplay.requireInterop(render);
        return new Route(true, device.selection().uuid(), device.selection().label(), shared, render);
    }

    private static String renderCudaUuid() {
        try {
            SpatialNative.load(ParticleNativeModules.prepareOptix());
            return SpatialNative.stormOpenGlCudaUuid();
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static HashMap<Long, CudaDevice> cudaByLuid() {
        var devices = new HashMap<Long, CudaDevice>();
        try {
            for (var cuda : discoverCuda()) if (cuda.luid() != 0) devices.putIfAbsent(cuda.luid(), cuda);
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            // DXR can run without NVIDIA's driver.
        }
        return devices;
    }

    static List<CudaDevice> discoverCuda() throws IOException {
        SpatialNative.load(ParticleNativeModules.prepareOptix());
        String[] fields = SpatialNative.stormCudaDevices();
        var devices = new ArrayList<CudaDevice>(fields.length / 4);
        for (int i = 0; i < fields.length; i += 4)
            devices.add(new CudaDevice(fields[i], shortModel(fields[i + 1]), luid(fields[i + 3])));
        return devices;
    }

    static List<Device> discoverDxr() throws IOException {
        return windowsDevices(true).stream().filter(Device::dxr).toList();
    }

    public static List<Device> discoverWindowsAdapters() throws IOException {
        return windowsDevices(false);
    }

    private static List<Device> windowsDevices(boolean probe) throws IOException {
        StormDxrNative.load();
        String[] fields = StormDxrNative.devices(probe);
        var devices = new ArrayList<Device>(fields.length / 5);
        for (int i = 0; i < fields.length; i += 5)
            devices.add(new Device(new StormSimulationGpu(fields[i], shortModel(fields[i + 1])), fields[i + 2],
                    luid(fields[i + 3]), Boolean.parseBoolean(fields[i + 4]), ""));
        return devices;
    }

    private static long luid(String value) {
        return value.isEmpty() ? 0 : Long.parseUnsignedLong(value, 16);
    }
}
