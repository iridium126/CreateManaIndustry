package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.concurrent.atomic.AtomicLong;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.network.chat.Component;
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
    private static volatile Runnable cacheInvalidator;
    private HexJitRuntime() {}

    public static void register(IEventBus modBus) {
        modBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == ServerConfig.SPEC) invalidate("server config loaded");
        });
        modBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == ServerConfig.SPEC) invalidate("server config reloaded");
        });
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> { owner = Thread.currentThread(); invalidate("server starting"); });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            if (cache != null) cache.close();
            AddMotionNormalizationCache.releaseThreadState();
            cache = null; owner = null; invalidate("server stopped");
            ActionSites.clear();
        });
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            invalidate("datapack sync or reload");
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(commandTree()));
    }

    static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("cmi")
                .then(Commands.literal("hexjit").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(status()), false); return 1;
                        }))
                        .then(Commands.literal("clear").executes(context -> { invalidate("command"); return 1; })));
    }

    public static boolean enabled() {
        return ServerConfig.hexJitMode != ServerConfig.HexJitMode.OFF && Thread.currentThread() == owner && JitCompatibility.ready();
    }
    /** Fast-path callers have already checked AUTO mode and their feature-specific compatibility gate. */
    public static boolean onServerThread() { return Thread.currentThread() == owner; }
    public static long generation() { return EPOCH.get(); }
    public static long nextSite() { return SITES.incrementAndGet(); }
    /** Optional Hexcasting-specific caches register here without making bootstrap require Hexcasting. */
    public static void registerCacheInvalidator(Runnable invalidator) { cacheInvalidator = invalidator; }
    public static void invalidate(String reason) {
        arithmeticCode = null;
        invalidation = reason;
        EPOCH.incrementAndGet();
        Runnable invalidator = cacheInvalidator;
        if (invalidator != null) invalidator.run();
    }

    public static CompiledCall arithmeticCode() { return arithmeticCode; }
    public static void publishArithmetic(CompiledCall code) { arithmeticCode = code; }

    public static CompiledCall acquire(long site, CallCompiler.Description description) {
        if (!enabled()) return null;
        long epoch = generation();
        if (cache == null || cacheEpoch != epoch) {
            if (cache != null) cache.close();
            cache = new CompilationCache(ServerConfig.hexJitThreshold, ServerConfig.hexJitMaxUnits,
                    ServerConfig.hexJitByteBudget);
            cacheEpoch = epoch;
        }
        return cache.acquire(site, description, ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO);
    }

    public static String status() {
        return "mode=" + ServerConfig.hexJitMode + ", compatibility=" + JitCompatibility.status()
                + ", epoch=" + generation() + ", invalidation=" + invalidation + ", "
                + (cache == null ? "no compiled sites" : cache.stats());
    }
}
