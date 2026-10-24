package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import com.iridium126.createmanaindustry.compat.hexcasting.*;
import com.iridium126.createmanaindustry.compat.hextrick.execution.*;
import com.iridium126.createmanaindustry.compat.trickster.casting.*;
import com.iridium126.createmanaindustry.compat.trickster.partition.*;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import io.wispforest.endec.SerializationContext;
import io.wispforest.endec.format.edm.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class CastChainStateGameTests {
    private static class Env extends StaffCastEnv {
        CastingVM vm;
        int calls;
        Consumer<CastingImage> observer = image -> {};
        Env(ServerPlayer player) { super(player, InteractionHand.MAIN_HAND); }
        @Override public int maxOpCount() { return Integer.MAX_VALUE; }
        @Override public void postCast(CastingImage image) {
            if (vm.getImage() != image) throw new AssertionError("postCast and VM received different images");
            calls++;
            observer.accept(image);
            super.postCast(image);
        }
    }
    private static Iota step(Runnable operation, boolean clear, boolean fail) {
        return new NullIota() {
            @Override public boolean executable() { return true; }
            @Override public CastResult execute(CastingVM vm, net.minecraft.server.level.ServerLevel world, SpellContinuation next) {
                operation.run();
                var image = vm.getImage();
                if (clear) image = image.copy(TreeList.empty(), 0, TreeList.empty(), false, false, image.getOpsConsumed(), image.getComponents());
                return new CastResult(this, next, image.withUsedOp(), List.of(),
                        fail ? ResolvedPatternType.ERRORED : ResolvedPatternType.EVALUATED, HexEvalSounds.NOTHING.get());
            }
        };
    }
    private static ServerPlayer player(GameTestHelper helper) {
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        player.setGameMode(GameType.CREATIVE);
        return player;
    }
    private static CastingVM vm(Env env) {
        env.vm = new CastingVM(new CastingImage(TreeList.from(List.<Iota>of(new DoubleIota(1))), 0, TreeList.empty(), false, false, 0, Map.of()), env);
        return env.vm;
    }
    private static void cleanup(ServerPlayer player, CastingVM vm) {
        var snapshot = HexCastChainState.get(vm.getImage());
        if (snapshot != null) CastChains.resetStaff(player, snapshot.id());
    }

    @GameTest(template = "trickster_stock_test")
    public static void postCastSeesFinalStateInOffAndAuto(GameTestHelper helper) {
        var previous = ServerConfig.hexJitMode;
        try {
            for (var mode : List.of(ServerConfig.HexJitMode.OFF, ServerConfig.HexJitMode.AUTO)) {
                ServerConfig.hexJitMode = mode;
                var player = player(helper); var env = new Env(player); var vm = vm(env);
                UUID target = UUID.randomUUID();
                var partition = new SavedPartition(ResourceLocation.parse("minecraft:overworld"), new PartitionBounds(0, 0, 0, 2, 2, 2));
                env.observer = image -> {
                    var snapshot = HexCastChainState.get(image);
                    helper.assertTrue(snapshot != null && snapshot.inverted() && snapshot.targets().getFirst().id().equals(target)
                            && snapshot.partition().orElseThrow().equals(partition), "postCast saw stale chain state: " + mode);
                };
                try {
                    vm.queueExecuteAndWrapIotas(List.of(step(() -> {
                        var chain = CastChains.current(); chain.toggle(); chain.addFateTarget(target); chain.partition(partition);
                    }, false, false)), helper.getLevel());
                    helper.assertTrue(env.calls == 1, "postCast was duplicated");
                } finally { cleanup(player, vm); }
            }
            helper.succeed();
        } finally { ServerConfig.hexJitMode = previous; }
    }

    @GameTest(template = "trickster_stock_test")
    public static void terminalCastRemovesComponentBeforeCallbackAndReleasesOwners(GameTestHelper helper) {
        for (boolean fail : new boolean[]{false, true}) {
            var player = player(helper); var env = new Env(player); var vm = vm(env);
            var chains = new ArrayList<CastChain>();
            env.observer = image -> helper.assertTrue(HexCastChainState.get(image) == null && CastChains.current().live(),
                    "Terminal snapshot or callback lifetime was wrong");
            vm.queueExecuteAndWrapIotas(List.of(step(() -> chains.add(CastChains.current()), !fail, fail)), helper.getLevel());
            helper.assertTrue(!chains.getFirst().live() && CastChains.find(player, chains.getFirst().id()) == null,
                    "Terminal cast leaked an owner");
        }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void callbackMutationAndReentryPublishWithoutMutatingOldSnapshots(GameTestHelper helper) {
        var player = player(helper); var env = new Env(player); var vm = vm(env);
        var seen = new ArrayList<CastChainSnapshot>();
        env.observer = image -> {
            var snapshot = HexCastChainState.get(image); seen.add(snapshot);
            if (env.calls == 1) {
                vm.queueExecuteAndWrapIotas(List.of(step(() -> CastChains.current().toggle(), false, false)), helper.getLevel());
                CastChains.current().addFateTarget(UUID.randomUUID());
            }
            else helper.assertTrue(snapshot.inverted(), "Nested callback did not see the inherited chain mutation");
        };
        try {
            vm.queueExecuteAndWrapIotas(List.of(step(() -> {}, false, false)), helper.getLevel());
            helper.assertTrue(env.calls == 2 && !seen.getFirst().inverted() && seen.getLast().inverted()
                    && seen.getFirst().id().equals(seen.getLast().id()) && HexCastChainState.get(vm.getImage()).inverted()
                    && HexCastChainState.get(vm.getImage()).targets().size() == 1 && seen.getLast().targets().isEmpty(),
                    "Reentry changed identity, old snapshots or final publication");
        } finally { cleanup(player, vm); }
        helper.assertTrue(CastChains.current() == null, "Reentry leaked thread context");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void callbackExceptionDoesNotLeakSession(GameTestHelper helper) {
        var player = player(helper); var env = new Env(player); var vm = vm(env);
        var failure = new IllegalStateException("postCast fixture");
        var chains = new ArrayList<CastChain>();
        env.observer = image -> {
            player.getPersistentData().put(at.petrak.hexcasting.forge.xplat.ForgeXplatImpl.TAG_VM,
                    CastingImage.getCODEC().encodeStart(NbtOps.INSTANCE, image).getOrThrow());
            throw failure;
        };
        boolean propagated = false;
        try { vm.queueExecuteAndWrapIotas(List.of(step(() -> chains.add(CastChains.current()), false, false)), helper.getLevel()); }
        catch (IllegalStateException caught) { propagated = caught == failure; }
        helper.assertTrue(propagated && HexCastChainState.get(vm.getImage()) == null && !chains.getFirst().live()
                && CastChains.find(player, chains.getFirst().id()) == null && CastChains.current() == null
                && HexCastChainState.savedId(player) == null, "Callback failure leaked a chain or changed the exception");
        player.getPersistentData().remove(at.petrak.hexcasting.forge.xplat.ForgeXplatImpl.TAG_VM);
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void canonicalChainWinsOverAnOlderImageAndRejectsAnotherPlayer(GameTestHelper helper) {
        var player = player(helper); Object first = new Object(), second = new Object();
        var chain = CastChains.attach(new CastChain(), player, first);
        var old = chain.snapshot(); chain.toggle();
        try {
            helper.assertTrue(CastChains.attach(old, player, second) == chain && chain.inverted(), "Old image rewound the live chain");
            var alien = new CastChainSnapshot(chain.id(), Optional.of(UUID.randomUUID()), false, List.of(), Optional.empty(), Optional.empty());
            boolean rejected = false;
            try { CastChains.attach(alien, player, new Object()); } catch (IllegalStateException expected) { rejected = true; }
            helper.assertTrue(rejected, "Cross-player snapshot was accepted");
            var otherPlayer = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(alien.player().orElseThrow(), "cast_chain_other"));
            rejected = false;
            try { CastChains.attach(alien, otherPlayer, new Object()); } catch (IllegalStateException expected) { rejected = true; }
            CastChains.resetStaff(otherPlayer, chain.id());
            helper.assertTrue(rejected && chain.live() && CastChains.find(player, chain.id()) == chain,
                    "Canonical ownership validation or reset accepted another player");
            helper.assertTrue(HexCastChainState.publish(HexCastChainState.publish(new CastingImage(), chain.snapshot()), chain.snapshot())
                    .getComponent(CMIHexImageComponents.CAST_CHAIN.get()) == chain.snapshot(), "Unchanged publication rebuilt its snapshot");
        } finally { CastChains.release(chain, second); CastChains.release(chain, first); }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void structuredEndecRoundTripAndSavedUpdatesPreserveSiblings(GameTestHelper helper) {
        var player = player(helper); Object owner = new Object();
        var chain = CastChains.attach(new CastChain(), player, owner);
        var block = new CircleSourceDescriptor.BlockReference(1, 2, 3, ResourceLocation.parse("hexcasting:slate"), UUID.randomUUID());
        var origin = new CastingOrigin(new CircleSourceDescriptor(ResourceLocation.parse("minecraft:overworld"), block, block, List.of(block)),
                Direction.NORTH, 0, 0, 0, 3, 3, 3, player.getUUID());
        chain.addFateTarget(UUID.randomUUID(), origin); chain.addFateTarget(UUID.randomUUID());
        chain.partition(new SavedPartition(ResourceLocation.parse("minecraft:overworld"), new PartitionBounds(0, 0, 0, 3, 3, 3)));
        chain.partitionOrigin(origin);
        var snapshot = chain.snapshot();
        var serializer = EdmSerializer.of();
        CastChainSnapshotEndec.ENDEC.encode(SerializationContext.empty(), serializer, snapshot);
        helper.assertTrue(snapshot.equals(CastChainSnapshotEndec.ENDEC.decode(SerializationContext.empty(), EdmDeserializer.of(serializer.result()))),
                "Endec lost nested origins, target order or partition");
        try {
            var image = HexCastChainState.publish(new CastingImage(), snapshot);
            var encoded = (CompoundTag) CastingImage.getCODEC().encodeStart(NbtOps.INSTANCE, image).getOrThrow();
            encoded.putString("fixture_sibling", "unchanged");
            encoded.getCompound("components").putString("fixture:other_component", "untouched");
            player.getPersistentData().put(at.petrak.hexcasting.forge.xplat.ForgeXplatImpl.TAG_VM, encoded);
            chain.toggle(); chain.removeFateTarget(snapshot.targets().getFirst().id());
            HexCastChainState.persist(player, chain);
            helper.assertTrue(encoded.getString("fixture_sibling").equals("unchanged")
                    && encoded.getCompound("components").getString("fixture:other_component").equals("untouched")
                    && HexCastChainState.saved(player).equals(chain.snapshot()) && snapshot.targets().size() == 2,
                    "Saved update mutated another component or an earlier snapshot");
            helper.assertTrue(HexCastChainState.publish(image, snapshot) == image, "Unchanged image was replaced");
        } finally {
            player.getPersistentData().remove(at.petrak.hexcasting.forge.xplat.ForgeXplatImpl.TAG_VM);
            CastChains.release(chain, owner);
        }
        helper.succeed();
    }
}
