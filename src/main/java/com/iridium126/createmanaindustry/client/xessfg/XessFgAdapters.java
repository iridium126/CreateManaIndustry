package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.client.particles.engine.spatial.StormGpuDevices;
import com.iridium126.createmanaindustry.infrastructure.config.XessFgGpu;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class XessFgAdapters {
    public record Adapter(XessFgGpu selection, long luid, int vendor, int device) {
        public boolean arcA750() {
            return vendor == 0x8086 && device == 0x56a1;
        }

        public boolean xmxArc() {
            return vendor == 0x8086 && (device >> 8 == 0x56 || device >> 8 == 0xe2);
        }
    }

    private static List<Adapter> adapters;

    private XessFgAdapters() {}

    public static synchronized List<Adapter> discover(Path gameDirectory) throws IOException {
        if (adapters != null) return adapters;
        XessFgNative.load(gameDirectory);
        var found = new ArrayList<Adapter>();
        for (var device : StormGpuDevices.discoverWindowsAdapters()) {
            int pci = XessFgNative.adapter(device.luid());
            if (pci != 0) {
                var selection = new XessFgGpu(device.selection().uuid(), device.selection().label());
                found.add(new Adapter(selection, device.luid(), pci >>> 16, pci & 0xffff));
            }
        }
        adapters = found.stream().sorted(Comparator.comparingInt(XessFgAdapters::autoRank)
                .thenComparing(adapter -> adapter.selection().id())).toList();
        return adapters;
    }

    static Adapter select(List<Adapter> devices, XessFgGpu selection) {
        return devices.stream().filter(device -> XessFgGpu.AUTO.equals(selection.id())
                        || device.selection().id().equals(selection.id()))
                .min(Comparator.comparingInt(XessFgAdapters::autoRank).thenComparing(device -> device.selection().id()))
                .orElseThrow(() -> new IllegalStateException("XeSS-FG adapter unavailable: " + selection.value()));
    }

    private static int autoRank(Adapter adapter) {
        if (adapter.arcA750()) return 0;
        return adapter.xmxArc() ? 1 : 2;
    }
}
