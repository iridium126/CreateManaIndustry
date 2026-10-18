package com.iridium126.createmanaindustry.compat;

import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.compat.trickster.casting.*;
import com.iridium126.createmanaindustry.compat.trickster.partition.*;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import com.mojang.authlib.GameProfile;
import dev.enjarai.trickster.block.ModBlocks;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.execution.SpellExecutionManager;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.BlockSpellSource;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexInteropSceneBenchmark {
    private static final int ITERATIONS = Integer.getInteger("cmi.benchmark.iterations", 1000);
    @FunctionalInterface private interface Probe { void run() throws Exception; }
    private static final List<Map<String, Object>> SAMPLES = new ArrayList<>();
    private static void measure(String name, Probe probe) throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < ITERATIONS; i++) probe.run();
        for (int round = 0; round < 7; round++) {
            long allocation = bean.getThreadAllocatedBytes(thread), time = System.nanoTime(), cpu = bean.getCurrentThreadCpuTime();
            for (int i = 0; i < ITERATIONS; i++) probe.run();
            SAMPLES.add(Map.of("probe", name, "round", round, "iterations", ITERATIONS,
                    "cpuNsPerOp", (bean.getCurrentThreadCpuTime() - cpu) / (double) ITERATIONS, "nsPerOp", (System.nanoTime() - time) / (double) ITERATIONS, "bytesPerOp", (bean.getThreadAllocatedBytes(thread) - allocation) / (double) ITERATIONS));
        }
    }
    @GameTest(template = "hexinterop_benchmark", timeoutTicks = 1200)
    public static void scenes(GameTestHelper helper) throws Exception {
        var cow = helper.spawn(EntityType.COW, new BlockPos(1, 1, 1));
        var chunk = cow.chunkPosition();
        boolean forced = helper.getLevel().getForcedChunks().contains(chunk.toLong());
        helper.getLevel().setChunkForced(chunk.x, chunk.z, true);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                dev.enjarai.trickster.spell.fragment.EntityFragment.isValidEntity(cow), "Waiting for benchmark entity ticking"))
                .thenExecute(() -> {
                    try { run(helper, cow); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                    finally { helper.getLevel().setChunkForced(chunk.x, chunk.z, forced); }
                }).thenSucceed();
    }
    private static void run(GameTestHelper helper, net.minecraft.world.entity.Entity cow) throws Exception {
        var server = helper.getLevel().getServer();
        var managers = new ArrayList<SpellExecutionManager>();
        var players = new ArrayList<ServerPlayer>();
        var chains = new ArrayList<CastChain>();
        Object owner = new Object();
        long originalTime = server.overworld().getGameTime();
        var playerField = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
        playerField.setAccessible(true);
        @SuppressWarnings("unchecked") var observers = (List<ServerPlayer>) playerField.get(server.getPlayerList());
        try {
            Class<?> queueType;
            try { queueType = Class.forName("com.iridium126.createmanaindustry.compat.hextrick.execution.BlockSpellExecutionManager"); }
            catch (ClassNotFoundException baseline) { queueType = Class.forName("com.iridium126.createmanaindustry.compat.hexcasting.BlockSpellExecutionManager"); }
            Method tick = queueType.getDeclaredMethod("tick", LevelTickEvent.Post.class); tick.setAccessible(true);
            var worlds = List.of(server.overworld(), server.getLevel(Level.NETHER), server.getLevel(Level.END));
            for (ServerLevel world : worlds) for (int block = 0; block < 4; block++) {
                BlockPos position = new BlockPos(32 + block, 80, 32);
                world.setBlockAndUpdate(position, ModBlocks.SPELL_CONSTRUCT.defaultBlockState());
                var entity = (SpellConstructBlockEntity) world.getBlockEntity(position);
                var source = new BlockSpellSource<>(world, position, entity);
                var manager = (SpellExecutionManager) queueType.getConstructor(net.minecraft.world.level.block.entity.BlockEntity.class,
                        dev.enjarai.trickster.spell.execution.source.SpellSource.class).newInstance(entity, source);
                managers.add(manager);
                for (int slot = 0; slot < 5; slot++) {
                    var executor = new DefaultSpellExecutor(new SpellPart(), List.of());
                    executor.getDeepestState().addDelay(1000000);
                    if (manager.queue(executor).isEmpty()) throw new AssertionError("Benchmark queue capacity mismatch");
                }
            }
            ((ServerLevelData) server.overworld().getLevelData()).setGameTime(originalTime + 1);
            measure("background_one_world_of_three_20_jobs", () -> tick.invoke(null, new LevelTickEvent.Post(() -> true, worlds.getFirst())));
            measure("background_three_worlds_60_jobs", () -> { for (var world : worlds) tick.invoke(null, new LevelTickEvent.Post(() -> true, world)); });
            for (int i = 0; i < 32; i++) {
                var profile = new GameProfile(UUID.nameUUIDFromBytes(("interop-benchmark-" + i).getBytes(StandardCharsets.UTF_8)), "Bench" + i);
                var player = FakePlayerFactory.get(helper.getLevel(), profile);
                player.setPos(helper.absolutePos(BlockPos.ZERO).getCenter());
                var connection = player.connection.getConnection();
                if (connection.channel() == null) {
                    new io.netty.channel.embedded.EmbeddedChannel(connection);
                    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
                }
                var battery = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("hexcasting:battery")));
                battery.set(at.petrak.hexcasting.common.lib.HexDataComponents.MEDIA_MAX, 640000000L);
                battery.set(CMIComponents.ANIMA.get(), 128000000L); player.getInventory().setItem(0, battery);
                var chain = CastChains.attach(new CastChain(), player, owner);
                players.add(player); chains.add(chain);
                SharedFateManager.bind(player, cow, chain, 32000);
            }
            observers.addAll(players.subList(0, 8));
            if (SharedFateManager.bindingCount(server) != 32) throw new AssertionError("Benchmark lost its 32 relation fixture");
            var runtimes = SharedFateManager.class.getDeclaredField("RUNTIMES"); runtimes.setAccessible(true);
            Object runtime = ((Map<?, ?>) runtimes.get(null)).get(server);
            Method fateSync = runtime.getClass().getDeclaredMethod("sync"); fateSync.setAccessible(true);
            measure("fate_refresh_32_links", () -> SharedFateManager.refresh(server));
            measure("fate_sync_32_links_8_observers", () -> fateSync.invoke(runtime));
            for (int i = 0; i < 32; i++) {
                BlockPos p = helper.absolutePos(BlockPos.ZERO).offset(100 + i * 4, 0, 100);
                chains.get(i).partition(new SavedPartition(helper.getLevel().dimension().location(), new PartitionBounds(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1)));
                PartitionManager.resume(chains.get(i), players.get(i));
            }
            Method partitionSync = PartitionManager.class.getDeclaredMethod("sync", net.minecraft.server.MinecraftServer.class); partitionSync.setAccessible(true);
            measure("partition_sync_32_domains_8_observers", () -> partitionSync.invoke(null, server));
            for (int i = 0; i < 256; i++) chains.add(CastChains.attach(new CastChain(), players.getFirst(), owner));
            measure("chain_attach_release_with_288_live", () -> {
                var chain = CastChains.attach(new CastChain(), players.getFirst(), owner); CastChains.release(chain, owner);
            });
            var output = Path.of(System.getProperty("cmi.benchmark.output")); Files.createDirectories(output);
            Files.writeString(output.resolve("scenes.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                    "label", System.getProperty("cmi.benchmark.label"), "java", System.getProperty("java.runtime.version"), "heap", Runtime.getRuntime().maxMemory(), "samples", SAMPLES)));
        } finally {
            managers.forEach(SpellExecutionManager::killAll);
            observers.removeAll(players);
            chains.forEach(chain -> CastChains.release(chain, owner));
            ((ServerLevelData) server.overworld().getLevelData()).setGameTime(originalTime);
        }
    }
}
