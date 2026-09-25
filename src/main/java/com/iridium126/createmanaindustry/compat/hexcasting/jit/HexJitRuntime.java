package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class HexJitRuntime {
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final AtomicLong SITES = new AtomicLong();
    private static Thread owner;
    private static CompilationCache cache;
    private static long cacheEpoch = -1;
    private static volatile CompiledCall arithmeticCode;
    private static volatile String invalidation = "startup";
    private HexJitRuntime() {}

    public static void register(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, HexJitConfig.SPEC, "createmanaindustry-hex-jit.toml");
        modBus.addListener((ModConfigEvent.Loading event) -> { if (event.getConfig().getSpec() == HexJitConfig.SPEC) HexJitConfig.reload(); });
        modBus.addListener((ModConfigEvent.Reloading event) -> { if (event.getConfig().getSpec() == HexJitConfig.SPEC) HexJitConfig.reload(); });
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> { owner = Thread.currentThread(); invalidate("server starting"); });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            if (cache != null) cache.close();
            cache = null; owner = null; invalidate("server stopped");
            ActionSites.clear();
        });
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            invalidate("datapack sync or reload");
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(
                Commands.literal("cmi_hexjit").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(status()), false); return 1;
                        }))
                        .then(Commands.literal("clear").executes(context -> { invalidate("command"); return 1; }))));
    }

    public static boolean enabled() {
        return HexJitConfig.mode != HexJitConfig.Mode.OFF && Thread.currentThread() == owner && JitCompatibility.ready();
    }
    public static long generation() { return EPOCH.get(); }
    public static long nextSite() { return SITES.incrementAndGet(); }
    public static void invalidate(String reason) {
        arithmeticCode = null;
        invalidation = reason;
        EPOCH.incrementAndGet();
    }

    public static CompiledCall arithmeticCode() { return arithmeticCode; }
    public static void publishArithmetic(CompiledCall code) { arithmeticCode = code; }

    public static CompiledCall acquire(long site, CallCompiler.Description description) {
        if (!enabled()) return null;
        long epoch = generation();
        if (cache == null || cacheEpoch != epoch) {
            if (cache != null) cache.close();
            cache = new CompilationCache(HexJitConfig.threshold, HexJitConfig.maxUnits, HexJitConfig.byteBudget);
            cacheEpoch = epoch;
        }
        return cache.acquire(site, description, HexJitConfig.mode == HexJitConfig.Mode.AUTO);
    }

    public static String status() {
        return "mode=" + HexJitConfig.mode + ", compatibility=" + JitCompatibility.status()
                + ", epoch=" + generation() + ", invalidation=" + invalidation + ", "
                + (cache == null ? "no compiled sites" : cache.stats());
    }
}
