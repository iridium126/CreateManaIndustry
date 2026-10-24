package com.iridium126.createmanaindustry.compat.trickster.casting;

import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import com.iridium126.createmanaindustry.compat.hexcasting.CMIHexImageComponents;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.lang.management.ManagementFactory;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

/** Opt-in boundary benchmark. No timing assertions; allocation and timings are reported together. */
@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class CastChainBenchmarkGameTests {
    private static volatile Object sink;
    private static final Iota NOOP = new NullIota() {
        @Override public boolean executable() { return true; }
        @Override public CastResult execute(CastingVM vm, net.minecraft.server.level.ServerLevel level, SpellContinuation next) {
            return new CastResult(this, next, vm.getImage().withUsedOp(), List.of(), ResolvedPatternType.EVALUATED, HexEvalSounds.NOTHING.get());
        }
    };

    @GameTest(template = "cast_chain_test", timeoutTicks = 12000)
    public static void boundaryAllocations(GameTestHelper helper) {
        var previous = ServerConfig.hexJitMode;
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
        try {
            for (int targets : new int[]{0, 32}) measure(helper, targets);
            helper.succeed();
        } finally { ServerConfig.hexJitMode = previous; }
    }

    private static void measure(GameTestHelper helper, int targets) {
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        player.setGameMode(GameType.CREATIVE);
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        Object owner = new Object();
        var chain = new CastChain();
        var cows = new ArrayList<net.minecraft.world.entity.Entity>();
        for (int i = 0; i < targets; i++) {
            var cow = helper.spawn(EntityType.COW, new BlockPos(1, 1, 1));
            cows.add(cow);
            chain.addFateTarget(cow.getUUID());
        }
        chain = CastChains.attach(chain, player, owner);
        final CastChain live = chain;
        var data = chain.snapshot();
        var initial = new CastingImage(TreeList.from(List.<Iota>of(new DoubleIota(1))), 0, TreeList.empty(), false, false, 0, Map.of())
                .withComponent(CMIHexImageComponents.CAST_CHAIN.get(), data);
        var env = new StaffCastEnv(player, InteractionHand.MAIN_HAND) {
            @Override public boolean isEnlightened() { return true; }
            @Override public int maxOpCount() { return Integer.MAX_VALUE; }
        };
        var vm = new CastingVM(initial, env);
        var program = List.of(NOOP);
        try {
            report("snapshot", targets, 10000, () -> sink = snapshot(live));
            report("staff", targets, 1000, () -> sink = vm.queueExecuteAndWrapIotas(program, helper.getLevel()));
            helper.assertTrue(chain.fateTargets().size() == targets, "Benchmark lost a target");
            helper.assertTrue(chain.snapshot() == data && vm.getImage().getComponent(CMIHexImageComponents.CAST_CHAIN.get()) == data,
                    "Unchanged VM entries rebuilt or restored the canonical snapshot");
        } finally {
            CastChains.release(chain, chain);
            CastChains.release(chain, owner);
            cows.forEach(net.minecraft.world.entity.Entity::discard);
        }
    }

    private static Object snapshot(CastChain chain) { return chain.snapshot(); }

    private static void report(String workload, int targets, int iterations, Runnable action) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < iterations * 2; i++) action.run();
        long thread = Thread.currentThread().threadId();
        for (int sample = 0; sample < 7; sample++) {
            long allocated = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            for (int i = 0; i < iterations; i++) action.run();
            long nanos = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(thread) - allocated;
            System.out.printf(Locale.ROOT, "CASTCHAIN_BENCH label=%s workload=%s targets=%d sample=%d iterations=%d nsPerOp=%.3f bytesPerOp=%.3f%n",
                    System.getProperty("cmi.castchain.benchmark"), workload, targets, sample, iterations, (double) nanos / iterations, (double) bytes / iterations);
        }
    }
}
