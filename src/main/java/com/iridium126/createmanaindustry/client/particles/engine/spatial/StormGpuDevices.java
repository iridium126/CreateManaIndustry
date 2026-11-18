package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationGpu;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

public final class StormGpuDevices {
    public record Device(StormSimulationGpu selection, String pci) {}
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

    public static List<Device> discover() throws IOException {
        SpatialNative.load(ParticleRtModules.prepare());
        return devicesLoaded();
    }

    static StormSimulationGpu cudaDevice(String uuid) {
        for (var device : devicesLoaded()) if (device.selection().uuid().equals(uuid)) return device.selection();
        throw new IllegalStateException("Selected Storm CUDA device is unavailable: " + uuid);
    }

    private static List<Device> devicesLoaded() {
        String[] fields = SpatialNative.stormCudaDevices();
        var devices = new ArrayList<Device>(fields.length / 3);
        for (int i = 0; i < fields.length; i += 3) {
            devices.add(new Device(new StormSimulationGpu(fields[i], shortModel(fields[i + 1])), fields[i + 2]));
        }
        return List.copyOf(devices);
    }
}
