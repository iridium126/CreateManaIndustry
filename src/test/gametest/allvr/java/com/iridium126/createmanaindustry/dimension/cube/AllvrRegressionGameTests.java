package com.iridium126.createmanaindustry.dimension.cube;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.Visibility;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class AllvrRegressionGameTests {
    @GameTest(template = "allvr_regression", timeoutTicks = 200)
    public static void fullHeightEntityManagersAndUpdateRouting(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        helper.assertTrue(level != null, "Allay dimension must exist in isolated preset");
        var map = ((AllvrServerLevelDuck) level).allvr$getCubeMap();
        // Lower band is inexpensive deterministic terrain; no floating-island generation dependencies.
        BlockPos pos = new BlockPos(1024, -160, 1024);
        map.getOrGenerate(AllvrCubePos.of(pos));
        level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState(), 3);
        var sign = (net.minecraft.world.level.block.entity.SignBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(sign != null, "Cube sign must be created");
        sign.setWaxed(true); // Vanilla sendBlockUpdated must bypass ChunkHolder's narrow arrays.
        level.getChunkSource().blockChanged(pos); // Create sendData entry point.
        var full = com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrCubePacket.of(map.peek(pos), level.registryAccess());
        var decoded = full.decodeCube(level.registryAccess());
        helper.assertTrue(decoded.getBlockEntity(pos) == null, "Packet decode invoked mod BE callbacks before publication");
        decoded.onLoad(level);
        helper.assertTrue(((net.minecraft.world.level.block.entity.SignBlockEntity) decoded.getBlockEntity(pos)).isWaxed(), "Full update tag was not restored at publication");
        decoded.onUnload();
        var storage = ((AllvrEntityLevelAccess) level).allvr$entitySections();
        helper.assertTrue(storage.enabled(), "Full-height entity storage must be enabled");
        for (int y : new int[]{8_388_608, 16_777_216, -16_777_216, 29_999_990}) {
            var entity = EntityType.ARMOR_STAND.create(level);
            entity.setPos(1000, y, 1000);
            helper.assertTrue(level.addFreshEntity(entity), "Entity insertion failed");
            long key = storage.key(entity.blockPosition());
            helper.assertTrue(storage.coordinate(key).y() == (y >> 4), "Entity section Y was truncated");
            entity.setPos(1000, y + 32, 1000);
            key = storage.key(entity.blockPosition());
            helper.assertTrue(storage.coordinate(key).y() == ((y + 32) >> 4), "Entity movement Y was truncated");
            entity.discard();
        }
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(), "save-off");
        helper.assertTrue(level.noSave, "save-off command did not disable saving");
        level.setBlock(pos.above(), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(!map.saveAll(false), "noSave must reject snapshot publication");
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(), "save-on");
        helper.assertTrue(!level.noSave, "save-on command did not resume saving");
        helper.assertTrue(map.saveAll(true), "Cube flush must succeed");
        helper.succeed();
    }
    @GameTest(template = "allvr_regression", timeoutTicks = 600)
    public static void blockLightCrossesWindowsAndSkyBandsStayIndependent(GameTestHelper helper) {
        var level = helper.getLevel();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        for (int boundary : new int[]{2048, 4096, -2048, 16_777_216, -16_777_216}) {
            var cubes = new java.util.HashMap<Long, AllvrCube>();
            var engine = new com.iridium126.createmanaindustry.dimension.light.AllvrLightEngine(level,
                new com.iridium126.createmanaindustry.dimension.light.AllvrLightEngine.Access() {
                    public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                        var cube = cubes.get(AllvrCubePos.asLong(pos)); return cube == null ? Blocks.AIR.defaultBlockState() : cube.getBlockState(pos);
                    }
                    public boolean isLoaded(BlockPos pos) { return cubes.containsKey(AllvrCubePos.asLong(pos)); }
                });
            var source = new BlockPos(4, boundary - 1, 4);
            var below = new AllvrCube(AllvrCubePos.of(source), biomes);
            var above = new AllvrCube(AllvrCubePos.of(source.above()), biomes);
            below.setBlockState(source, Blocks.GLOWSTONE.defaultBlockState(), false);
            cubes.put(below.getPos().asLong(), below); cubes.put(above.getPos().asLong(), above);
            engine.onCubeLoaded(below); engine.onCubeLoaded(above);
            for (int n = 0; n < 12; n++) engine.tick(65_536);
            helper.assertTrue(engine.blockLight(source) == 15, "Missing source light at " + source);
            helper.assertTrue(engine.blockLight(source.above()) == 14, "Block light seam at " + boundary + " value=" + engine.blockLight(source.above()));
            below.setBlockState(source, Blocks.AIR.defaultBlockState(), false); engine.onBlockChanged(source);
            for (int n = 0; n < 12; n++) engine.tick(65_536);
            helper.assertTrue(engine.blockLight(source.above()) == 0, "Removed source left stale light at " + boundary);
            // A roof in the upper sky band must not shadow the independently lit lower band.
            for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
                var pos = new BlockPos(x, boundary, z); above.setBlockState(pos, Blocks.STONE.defaultBlockState(), false); engine.onBlockChanged(pos);
            }
            for (int n = 0; n < 12; n++) engine.tick(65_536);
            helper.assertTrue(engine.skyLight(source) == 15, "Independent sky band was shadowed at " + boundary);
            below.setBlockState(source, Blocks.GLOWSTONE.defaultBlockState(), false); engine.onBlockChanged(source);
            above.setBlockState(source.above(), Blocks.AIR.defaultBlockState(), false); engine.onBlockChanged(source.above());
            for (int n = 0; n < 12; n++) engine.tick(65_536);
            helper.assertTrue(engine.blockLight(source.above()) == 14, "Reloaded source did not reach neighbour");
            cubes.remove(below.getPos().asLong()); engine.onCubeUnloaded(below);
            for (int n = 0; n < 12; n++) engine.tick(65_536);
            helper.assertTrue(engine.blockLight(source.above()) == 0, "Unloaded source cube left stale neighbour light");
            engine.clear();
        }
        helper.succeed();
    }

    @GameTest(template = "allvr_regression", timeoutTicks = 800)
    public static void nativeAndCubeBlockLightSeamsInBothDirections(GameTestHelper helper) {
        checkNativeSeam(helper, 0);
    }
    private static void checkNativeSeam(GameTestHelper helper, int test) {
        if (test == 2) { helper.succeed(); return; }
        var level = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        int x = 3000 + test * 64;
        var nativePos = new BlockPos(x, test == 0 ? 383 : -128, 3000);
        var cubePos = nativePos.offset(0, test == 0 ? 1 : -1, 0);
        level.getChunk(x >> 4, 3000 >> 4);
        var cube = new AllvrCube(AllvrCubePos.of(cubePos), level.registryAccess().registryOrThrow(Registries.BIOME));
        var engine = new com.iridium126.createmanaindustry.dimension.light.AllvrLightEngine(level,
            new com.iridium126.createmanaindustry.dimension.light.AllvrLightEngine.Access() {
                public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                    return com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaY(pos.getY()) ? level.getBlockState(pos)
                        : AllvrCubePos.of(pos).equals(cube.getPos()) ? cube.getBlockState(pos) : Blocks.AIR.defaultBlockState();
                }
                public boolean isLoaded(BlockPos pos) {
                    return com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaY(pos.getY())
                        ? level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4) : AllvrCubePos.of(pos).equals(cube.getPos());
                }
            });
        level.setBlock(nativePos, Blocks.GLOWSTONE.defaultBlockState(), 3);
        engine.onCubeLoaded(cube);
        for (int n = 0; n < 12; n++) engine.tick(65_536);
        helper.assertTrue(engine.blockLight(cubePos) == 14, "Native -> cube light seam at " + nativePos + " value=" + engine.blockLight(cubePos));
        engine.onNativeColumnUnloaded(x >> 4, 3000 >> 4);
        for (int n = 0; n < 12; n++) engine.tick(65_536);
        helper.assertTrue(engine.blockLight(cubePos) == 0, "Native chunk unload left stale halo light");
        engine.onNativeColumnChanged(x >> 4, 3000 >> 4);
        for (int n = 0; n < 12; n++) engine.tick(65_536);
        helper.assertTrue(engine.blockLight(cubePos) == 14, "Native chunk reload did not restore halo light");
        level.setBlock(nativePos, Blocks.AIR.defaultBlockState(), 3);
        cube.setBlockState(cubePos, Blocks.GLOWSTONE.defaultBlockState(), false);
        engine.onBlockChanged(nativePos); engine.onBlockChanged(cubePos);
        for (int n = 0; n < 12; n++) engine.tick(65_536);
        drainNativeLight(level, nativePos);
        int light = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, nativePos);
        helper.assertTrue(light == 14, "Cube -> native light seam at " + nativePos + " value=" + light);
        cube.setBlockState(cubePos, Blocks.AIR.defaultBlockState(), false); engine.onBlockChanged(cubePos);
        for (int n = 0; n < 12; n++) engine.tick(65_536);
        drainNativeLight(level, nativePos);
        helper.assertTrue(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, nativePos) == 0, "Native light survived cube source removal");
        engine.onCubeUnloaded(cube); drainNativeLight(level, nativePos);
        engine.clear(); checkNativeSeam(helper, test + 1);
    }
    private static void drainNativeLight(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        var light = level.getChunkSource().getLightEngine();
        var done = light.waitForPendingTasks(pos.getX() >> 4, pos.getZ() >> 4);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        level.getServer().managedBlock(() -> {
            light.tryScheduleUpdate();
            if (System.nanoTime() > deadline) throw new IllegalStateException("Native light mailbox did not drain");
            return done.isDone();
        });
        done.join();
    }
    @GameTest(template = "allvr_regression", timeoutTicks = 300)
    public static void cubeTicksPersistenceAndRandomGrowth(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        var map = ((AllvrServerLevelDuck) level).allvr$getCubeMap();
        var pos = new BlockPos(4000, 16_777_224, 4000);
        var cube = new AllvrCube(AllvrCubePos.of(pos), level.registryAccess().registryOrThrow(Registries.BIOME));
        map.publishCube(cube);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos.north(), Blocks.STONE.defaultBlockState(), 3); level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, Blocks.STONE_BUTTON.defaultBlockState().setValue(net.minecraft.world.level.block.ButtonBlock.POWERED, true), 3);
        level.scheduleTick(pos, Blocks.STONE_BUTTON, 2);
        helper.assertTrue(level.getBlockTicks().hasScheduledTick(pos, Blocks.STONE_BUTTON), "Native query did not route to cube ticks");
        map.ticking().tick(new long[]{cube.getPos().asLong()});
        helper.assertTrue(level.getBlockState(pos).getValue(net.minecraft.world.level.block.ButtonBlock.POWERED), "Delayed block tick fired early");
        var nbt = com.iridium126.createmanaindustry.dimension.storage.AllvrCubeSerializer.snapshot(cube, level, map.lightEngine()).tag();
        try {
            var restored = com.iridium126.createmanaindustry.dimension.storage.AllvrCubeSerializer.load(cube.getPos(), nbt, level);
            helper.assertTrue(restored.blockTicks().has(pos, Blocks.STONE_BUTTON), "Scheduled tick was not persisted");
            helper.assertTrue(restored.blockTicks().remaining(restored.blockTicks().snapshot().getFirst()) == 1, "Remaining tick delay changed during snapshot");
        } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        var rate = level.tickRateManager();
        rate.setFrozen(true); rate.tick();
        try {
            map.ticking().tick(new long[]{cube.getPos().asLong()});
            helper.assertTrue(cube.blockTicks().remaining(cube.blockTicks().snapshot().getFirst()) == 1, "Frozen cube consumed a scheduled delay");
        } finally { rate.setFrozen(false); rate.tick(); }
        map.ticking().tick(new long[]{cube.getPos().asLong()});
        helper.assertTrue(!level.getBlockState(pos).getValue(net.minecraft.world.level.block.ButtonBlock.POWERED), "Delayed block tick did not execute");
        var fluidPos = pos.offset(8, 0, 8);
        level.setBlock(fluidPos.below(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(fluidPos, Blocks.LAVA.defaultBlockState(), 3);
        level.getFluidTicks().clearArea(new net.minecraft.world.level.levelgen.structure.BoundingBox(fluidPos));
        level.scheduleTick(fluidPos, net.minecraft.world.level.material.Fluids.LAVA, 1);
        map.ticking().tick(new long[]{cube.getPos().asLong()});
        helper.assertTrue(!level.getFluidState(fluidPos.below()).isEmpty(), "High-Y fluid scheduled tick did not spread");
        // A full wheat section gives a deterministic opportunity count without relying on a single random position.
        var states = new net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState>(
            net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, Blocks.WHEAT.defaultBlockState(), net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
        cube.getSections()[0] = new net.minecraft.world.level.chunk.LevelChunkSection(states, cube.getSections()[0].getBiomes());
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            cube.getSections()[0].setBlockState(x, y, z, (y % 2 == 0 ? Blocks.FARMLAND : Blocks.WHEAT).defaultBlockState());
        int oldSpeed = level.getGameRules().getInt(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING).set(4096, level.getServer());
        map.lightEngine().onCubeLoaded(cube); for (int n = 0; n < 12; n++) map.lightEngine().tick(65_536);
        try { for (int n = 0; n < 5; n++) map.ticking().tick(new long[]{cube.getPos().asLong()}); }
        finally { level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING).set(oldSpeed, level.getServer()); }
        int grown = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var state = cube.getSections()[0].getBlockState(x, 15, z);
            if (state.is(Blocks.WHEAT) && state.getValue(net.minecraft.world.level.block.CropBlock.AGE) > 0) grown++;
        }
        helper.assertTrue(grown > 0, "Random ticking did not grow supported crops");
        helper.succeed();
    }

    @GameTest(template = "allvr_regression", timeoutTicks = 600)
    public static void noPlayersUnloadCleanCubesAndRetainOnlyUnsavedState(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        var map = ((AllvrServerLevelDuck) level).allvr$getCubeMap();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var clean = new AllvrCube(AllvrCubePos.of(new BlockPos(8000, 500, 8000)), biomes);
        var dirty = new AllvrCube(AllvrCubePos.of(new BlockPos(8064, 500, 8000)), biomes);
        map.publishCube(clean); map.publishCube(dirty);
        map.lightEngine().tick(65_536);
        clean.markQueued(clean.mutationVersion(), clean.lightVersion()); dirty.markDirty();
        level.noSave = true; map.syncSavingState();
        try {
            for (int n = 0; n < 20; n++) map.tick();
            helper.assertTrue(map.getLoadedCube(clean.getPos().asLong()) == null, "Clean cube stayed resident without players");
            helper.assertTrue(map.getLoadedCube(dirty.getPos().asLong()) == dirty, "save-off discarded dirty cube");
            helper.assertTrue(map.diagnostics().pinnedSaveDisabled > 0, "Missing save-off retention diagnostic");
        } finally { level.noSave = false; map.syncSavingState(); }
        map.saveAll(true);
        helper.succeedWhen(() -> {
            helper.assertTrue(map.getLoadedCube(dirty.getPos().asLong()) == null, "Saved cube did not unload without players");
            helper.assertTrue(map.getLoadedCubeCount() == 0, "Idle map did not drain all releasable cubes");
        });
    }

    @GameTest(template = "allvr_regression", timeoutTicks = 100)
    public static void sameColumnPlayersHaveIndependentSimulationAndBoundedEntityLoads(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        var first = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "allvr-upper"), net.minecraft.server.level.ClientInformation.createDefault());
        var second = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "allvr-lower"), net.minecraft.server.level.ClientInformation.createDefault());
        first.setPos(0, 16_777_216, 0); second.setPos(0, -16_777_216, 0);
        var loaded = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<AllvrCube>();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        for (var player : java.util.List.of(first, second)) {
            var center = AllvrCubePos.of(player.blockPosition());
            var near = new AllvrCube(center, biomes); loaded.put(center.asLong(), near);
            var edge = new AllvrCube(AllvrCubePos.of(center.getX(), center.getY() + 12, center.getZ()), biomes);
            loaded.put(edge.getPos().asLong(), edge);
        }
        var simulation = new AllvrSimulationTickets(); simulation.rebuild(java.util.List.of(first, second), 32, loaded);
        helper.assertTrue(simulation.keys().length == 4, "Simulation distance did not include both full-height ranges");
        simulation.rebuild(java.util.List.of(first), 2, loaded);
        helper.assertTrue(simulation.keys().length == 1, "One player's ticket kept another height ticking");
        var columns = new AllvrEntityColumnTickets(); columns.rebuild(java.util.List.of(first, second), 2);
        helper.assertTrue(columns.pending() == 25, "Same XZ players duplicated entity file requests");
        var requests = new java.util.HashSet<Long>(); columns.tick(requests::add);
        helper.assertTrue(requests.size() == 16 && columns.pending() == 9, "Entity column request budget was exceeded");
        columns.tick(requests::add); columns.rebuild(java.util.List.of(first, second), 2);
        helper.assertTrue(columns.pending() == 0, "Stable players reloaded entity columns");
        columns.clear(); helper.assertTrue(columns.pending() == 0, "Entity column queue was not reclaimed");
        helper.succeed();
    }

    @GameTest(template = "allvr_regression", timeoutTicks = 200)
    public static void scheduledQueuesSurviveDedicatedServerRestart(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        var map = ((AllvrServerLevelDuck) level).allvr$getCubeMap();
        var marker = new BlockPos(9000, 20_000_008, 9000);
        var cube = map.getOrGenerate(AllvrCubePos.of(marker));
        boolean restart = cube.getBlockState(marker).is(Blocks.LAPIS_BLOCK);
        if (restart) {
            helper.assertTrue(cube.blockTicks().has(marker, Blocks.LAPIS_BLOCK), "Restart lost block scheduled tick");
            helper.assertTrue(cube.fluidTicks().has(marker.above(), net.minecraft.world.level.material.Fluids.WATER), "Restart lost fluid scheduled tick");
            helper.assertTrue(cube.blockTicks().remaining(cube.blockTicks().snapshot().getFirst()) == 37, "Offline time consumed block delay");
            helper.assertTrue(cube.fluidTicks().remaining(cube.fluidTicks().snapshot().getFirst()) == 53, "Offline time consumed fluid delay");
        }
        cube.setBlockState(marker, Blocks.LAPIS_BLOCK.defaultBlockState(), false);
        cube.setBlockState(marker.above(), Blocks.WATER.defaultBlockState(), false);
        var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(cube.getPos().minBlockX(), cube.getPos().minBlockY(), cube.getPos().minBlockZ(),
            cube.getPos().minBlockX() + 31, cube.getPos().minBlockY() + 31, cube.getPos().minBlockZ() + 31);
        cube.blockTicks().clear(area); cube.fluidTicks().clear(area);
        map.scheduleBlockTick(marker, Blocks.LAPIS_BLOCK, 37, net.minecraft.world.ticks.TickPriority.HIGH);
        map.scheduleFluidTick(marker.above(), net.minecraft.world.level.material.Fluids.WATER, 53, net.minecraft.world.ticks.TickPriority.LOW);
        map.saveAll(true);
        com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info("[Allvr regression] scheduled queues restart {}",
            restart ? "VERIFIED (block=37, fluid=53)" : "SEEDED (run suite again to verify restart)");
        helper.succeed();
    }

    private static final class CountingChest extends net.minecraft.world.level.block.entity.ChestBlockEntity {
        int loads, unloads;
        CountingChest(BlockPos pos) { super(pos, Blocks.CHEST.defaultBlockState()); }
        @Override public void onLoad() { super.onLoad(); loads++; }
        @Override public void onChunkUnloaded() { super.onChunkUnloaded(); unloads++; }
    }
    @GameTest(template = "allvr_regression", timeoutTicks = 100)
    public static void blockEntityFreshLifecycleIsExactlyOnce(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        var pos = new BlockPos(4500, 500, 4500);
        var cube = new AllvrCube(AllvrCubePos.of(pos), level.registryAccess().registryOrThrow(Registries.BIOME));
        cube.setBlockState(pos, Blocks.CHEST.defaultBlockState(), false);
        var be = new CountingChest(pos); cube.putBlockEntity(pos, be);
        cube.onLoad(level); cube.onLoad(level);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(be.loads == 1, "BE onLoad count=" + be.loads);
            cube.onUnload(); cube.onUnload();
            helper.assertTrue(be.unloads == 1 && be.isRemoved(), "BE unload was duplicated or incomplete");
            helper.succeed();
        });
    }

}
