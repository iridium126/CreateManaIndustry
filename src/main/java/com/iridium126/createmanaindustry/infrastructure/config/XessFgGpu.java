package com.iridium126.createmanaindustry.infrastructure.config;

/** Uses the same physical adapter IDs as the Storm GPU selector. */
public record XessFgGpu(String id, String label) {
    public static final String AUTO = "auto";

    public static boolean valid(Object value) {
        return AUTO.equals(value) || StormSimulationGpu.valid(value) && !"minecraft".equals(value);
    }

    public static XessFgGpu parse(String value) {
        if (AUTO.equals(value)) return new XessFgGpu(AUTO, "");
        StormSimulationGpu adapter = StormSimulationGpu.parse(value);
        if (adapter.followsMinecraft()) throw new IllegalArgumentException("Invalid XeSS-FG adapter");
        return new XessFgGpu(adapter.uuid(), adapter.label());
    }

    public String value() {
        return label.isEmpty() ? id : id + "|" + label;
    }
}
