package com.iridium126.createmanaindustry.infrastructure.config;

public enum XessFgTransportMode {
    RGBA8("rgba8", 0), RGB24("rgb24", 1), YCBCR422("ycbcr422", 2);

    private final String value;
    private final int nativeId;

    XessFgTransportMode(String value, int nativeId) {
        this.value = value;
        this.nativeId = nativeId;
    }

    public String value() { return value; }
    public int nativeId() { return nativeId; }

    public static boolean valid(Object value) {
        for (var mode : values()) if (mode.value.equals(value)) return true;
        return false;
    }

    public static XessFgTransportMode parse(String value) {
        for (var mode : values()) if (mode.value.equals(value)) return mode;
        return RGB24;
    }
}
