package com.iridium126.createmanaindustry.content.allaystorm.lod;

/** Shared distance policy; distances refer to member centres, never individual vertices. */
public final class StormLodRules {
    public static final int MAX_DISTANCE = 8192;
    private StormLodRules() {}
    public static int limit(int server, int client) { return Math.clamp(Math.min(server, client), 0, MAX_DISTANCE); }
    public static float near(int fade) { return fade + 24f; }
    public static float middle(int configured, float near, float far) { return Math.min(far, Math.max(near, configured)); }
    public static boolean subscribed(boolean previous, double distance, double envelope, int limit) {
        return limit > 0 && distance < limit + envelope + (previous ? 64 : 0);
    }
}
