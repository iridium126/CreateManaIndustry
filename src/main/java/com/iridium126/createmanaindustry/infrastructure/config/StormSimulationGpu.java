package com.iridium126.createmanaindustry.infrastructure.config;

import java.util.Locale;
import java.util.regex.Pattern;

/** The label is retained for display when the UUID can no longer be resolved. */
public record StormSimulationGpu(String uuid, String label) {
    public static final String DEFAULT_VALUE = "minecraft";
    public static final StormSimulationGpu FOLLOW_MINECRAFT = new StormSimulationGpu("", "");
    private static final Pattern UUID = Pattern.compile("GPU-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);

    public static boolean valid(Object value) {
        return value instanceof String text && (text.equals(DEFAULT_VALUE)
                || text.length() <= 192 && UUID.matcher(text.split("\\|", 2)[0]).matches());
    }

    public static StormSimulationGpu parse(String value) {
        if (value.equals(DEFAULT_VALUE)) return FOLLOW_MINECRAFT;
        if (!valid(value)) throw new IllegalArgumentException("Invalid Storm CUDA device: " + value);
        String[] fields = value.split("\\|", 2);
        return new StormSimulationGpu("GPU-" + fields[0].substring(4).toLowerCase(Locale.ROOT),
                fields.length == 2 ? fields[1].strip() : "");
    }

    public boolean followsMinecraft() { return uuid.isEmpty(); }

    public String value() {
        if (followsMinecraft()) return DEFAULT_VALUE;
        return label.isEmpty() ? uuid : uuid + "|" + label;
    }
}
