package com.iridium126.createmanaindustry.infrastructure.config;

public enum XessFgSurfaceMode {
    CHILD("child"),
    BORDERLESS_CHILD("borderless_child"),
    BORDERLESS_POPUP("borderless_popup");

    private final String value;

    XessFgSurfaceMode(String value) { this.value = value; }

    public String value() { return value; }

    public boolean borderless() { return this != CHILD; }

    public static boolean valid(Object value) {
        for (var mode : values()) if (mode.value.equals(value)) return true;
        return false;
    }

    public static XessFgSurfaceMode parse(String value) {
        for (var mode : values()) if (mode.value.equals(value)) return mode;
        return CHILD;
    }
}
