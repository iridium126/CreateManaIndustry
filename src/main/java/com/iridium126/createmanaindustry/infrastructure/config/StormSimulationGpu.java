package com.iridium126.createmanaindustry.infrastructure.config;

import java.util.Locale;
import java.util.regex.Pattern;

/** The label is retained for display when the device can no longer be resolved. */
public record StormSimulationGpu(String uuid, String label) {
    public static final String DEFAULT_VALUE = "minecraft";
    public static final StormSimulationGpu FOLLOW_MINECRAFT = new StormSimulationGpu("", "");
    private static final Pattern PNP_ID = Pattern.compile("PNP-[0-9a-f]{64}", Pattern.CASE_INSENSITIVE);

    public static boolean valid(Object value) {
        if (!(value instanceof String text) || text.length() > 192) return false;
        if (text.equals(DEFAULT_VALUE)) return true;
        String id = text.split("\\|", 2)[0];
        return PNP_ID.matcher(id).matches();
    }

    public static StormSimulationGpu parse(String value) {
        if (value.equals(DEFAULT_VALUE)) return FOLLOW_MINECRAFT;
        if (!valid(value)) throw new IllegalArgumentException("Invalid Storm simulation device: " + value);
        String[] fields = value.split("\\|", 2);
        String id = fields[0];
        return new StormSimulationGpu(id.substring(0, 4).toUpperCase(Locale.ROOT) + id.substring(4).toLowerCase(Locale.ROOT),
                fields.length == 2 ? fields[1].strip() : "");
    }

    public boolean followsMinecraft() { return uuid.isEmpty(); }

    public String value() {
        if (followsMinecraft()) return DEFAULT_VALUE;
        return label.isEmpty() ? uuid : uuid + "|" + label;
    }
}
