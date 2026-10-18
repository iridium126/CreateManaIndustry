package com.iridium126.createmanaindustry.compat.hextrick.conversion;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Accessed on the server thread; values always describe a completed tick. */
public final class ConversionTickTime {
    private static final Map<MinecraftServer, Double> LAST_TICK = new IdentityHashMap<>();
    private static boolean registered;

    private ConversionTickTime() {}

    public static void register() {
        if (registered) return;
        NeoForge.EVENT_BUS.addListener(ConversionTickTime::onTick);
        NeoForge.EVENT_BUS.addListener(ConversionTickTime::onStopped);
        registered = true;
    }

    private static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long[] times = server.getTickTimesNanos();
        LAST_TICK.put(server, times[Math.floorMod(server.getTickCount(), times.length)] / 1_000_000.0);
    }

    private static void onStopped(ServerStoppedEvent event) {
        LAST_TICK.remove(event.getServer());
    }

    public static double milliseconds(MinecraftServer server) {
        return LAST_TICK.getOrDefault(server, 0.0);
    }
}
