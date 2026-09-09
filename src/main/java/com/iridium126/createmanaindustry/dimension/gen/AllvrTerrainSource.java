package com.iridium126.createmanaindustry.dimension.gen;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.ticks.ProtoChunkTicks;

/**
 * A bounded, isolated vanilla worldgen workspace. Nothing is requested from or
 * written into the real Overworld's chunks. Registry holders are the server's
 * datapack-resolved holders, including Terralith's inline climate parameter list.
 * Published columns contain palettes only, never NoiseChunk's mutable caches.
 */
final class AllvrTerrainSource {
    private static final EnumSet<Heightmap.Types> HEIGHTMAPS = EnumSet.of(
        Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
        Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
        Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    private final ServerLevel level;
    private final NoiseBasedChunkGenerator generator;
    final NoiseGeneratorSettings settings;
    final RandomState random;
    private final Registry<Biome> biomes;
    private final LevelHeightAccessor height;
    private final Map<Long, Column> bases = boundedMap(512);
    private final Map<Long, Stamp> stamps = boundedMap(256);
    private final Map<Long, Column> columns = boundedMap(512);
    private final Map<Long, net.minecraft.world.level.biome.BiomeGenerationSettings> carverBiomes = boundedMap(2048);

    AllvrTerrainSource(ServerLevel allay) {
        level = allay.getServer().overworld();
        var loaded = level.getChunkSource().getGenerator();
        if (allay.getChunkSource().getGenerator() instanceof AllvrChunkGenerator shell && shell.terrain().isPresent()) {
            loaded = shell.terrain().get();
        }
        // A flat/debug Overworld still has a registered noise settings definition.
        Holder<NoiseGeneratorSettings> holder = loaded instanceof NoiseBasedChunkGenerator noise
            ? noise.generatorSettings()
            : level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        settings = holder.value();
        generator = new NoiseBasedChunkGenerator(loaded.getBiomeSource(), holder);
        random = RandomState.create(settings, level.registryAccess().lookupOrThrow(Registries.NOISE), allay.getSeed());
        biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        height = LevelHeightAccessor.create(settings.noiseSettings().minY(), settings.noiseSettings().height());
    }

    BiomeSource biomeSource() { return generator.getBiomeSource(); }

    synchronized Column surfaceColumn(int x, int z) { return base(x, z); }

    Holder<Biome> biome(int x, int y, int z) {
        return biomeSource().getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4), random.sampler());
    }

    /** Serialized cache misses also protect feature implementations with mutable state. */
    synchronized Column column(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        Column cached = columns.get(key);
        if (cached != null) return cached;
        ProtoChunk result = copy(base(x, z));
        // Every source origin owns an independent feature stamp. The same global
        // order is used on BOTH sides of a chunk boundary, even after eviction.
        for (int ox = x - 1; ox <= x + 1; ox++) for (int oz = z - 1; oz <= z + 1; oz++) {
            Stamp stamp = stamp(ox, oz);
            Map<BlockPos, Change> changes = stamp.chunks.get(key);
            if (changes == null) continue;
            changes.forEach((pos, change) -> {
                result.setBlockState(pos, change.state, false);
                result.removeBlockEntity(pos);
                if (change.nbt != null) result.setBlockEntityNbt(change.nbt.copy());
            });
        }
        cached = freeze(result);
        columns.put(key, cached);
        return cached;
    }

    private Column base(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        Column cached = bases.get(key);
        if (cached != null) return cached;
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, height, biomes, null);
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        var fluid = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, random, EmptyBeard.INSTANCE, settings,
            (px, py, pz) -> py < Math.min(-54, settings.seaLevel()) ? lava : fluid, Blender.empty()));
        chunk.fillBiomesFromNoise(biomeSource(), random.sampler());
        // Preinstalled NoiseChunk uses an empty structure beard; no real chunk IO.
        generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
        Map<BlockPos, Holder<Biome>> edgeBiomes = new HashMap<>();
        generator.buildSurface(chunk, new WorldGenerationContext(generator, height), random, level.structureManager(),
            new BiomeManager((qx, qy, qz) -> {
                if ((qx >> 2) == x && (qz >> 2) == z) return chunk.getNoiseBiome(qx, qy, qz);
                return edgeBiomes.computeIfAbsent(new BlockPos(qx, qy, qz),
                    q -> biomeSource().getNoiseBiome(q.getX(), q.getY(), q.getZ(), random.sampler()));
            }, BiomeManager.obfuscateSeed(level.getSeed())), biomes, Blender.empty());
        carve(chunk, noiseChunk);
        cached = freeze(chunk);
        bases.put(key, cached);
        return cached;
    }

    /** Vanilla's radius-eight carver pass, without allocating 289 empty neighbour columns. */
    private void carve(ProtoChunk chunk, NoiseChunk noiseChunk) {
        var context = new net.minecraft.world.level.levelgen.carver.CarvingContext(generator,
            level.registryAccess(), height, noiseChunk, random, settings.surfaceRule());
        var biomeManager = new BiomeManager((x, y, z) -> biomeSource().getNoiseBiome(x, y, z, random.sampler()),
            BiomeManager.obfuscateSeed(level.getSeed()));
        var rng = new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(0));
        for (var step : net.minecraft.world.level.levelgen.GenerationStep.Carving.values()) {
            var mask = chunk.getOrCreateCarvingMask(step);
            for (int x = chunk.getPos().x - 8; x <= chunk.getPos().x + 8; x++) {
                for (int z = chunk.getPos().z - 8; z <= chunk.getPos().z + 8; z++) {
                    var origin = new ChunkPos(x, z);
                    var biomeSettings = carverBiomes.computeIfAbsent(origin.toLong(), key -> generator.getBiomeGenerationSettings(
                        biomeSource().getNoiseBiome(origin.x * 4, 0, origin.z * 4, random.sampler())));
                    int index = 0;
                    for (var carver : biomeSettings.getCarvers(step)) {
                        rng.setLargeFeatureSeed(level.getSeed() + index++, x, z);
                        if (carver.value().isStartChunk(rng)) {
                            carver.value().carve(context, chunk, biomeManager::getBiome, rng, noiseChunk.aquifer(), origin, mask);
                        }
                    }
                }
            }
        }
    }

    private Stamp stamp(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        Stamp cached = stamps.get(key);
        if (cached != null) return cached;
        FeatureRegion region = new FeatureRegion(x, z);
        StructureManager structures = new StructureManager(region, new WorldOptions(level.getSeed(), false, false), null);
        generator.applyBiomeDecoration(region, region.getChunk(x, z), structures);
        Map<Long, Map<BlockPos, Change>> changes = new HashMap<>();
        region.writes.forEach((pos, ignored) -> {
            ChunkAccess chunk = region.getChunk(pos);
            BlockState state = chunk.getBlockState(pos);
            CompoundTag nbt = state.hasBlockEntity() ? chunk.getBlockEntityNbtForSaving(pos, level.registryAccess()) : null;
            changes.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> new HashMap<>())
                .put(pos, new Change(state, nbt));
        });
        cached = new Stamp(changes);
        stamps.put(key, cached);
        return cached;
    }

    private ProtoChunk copy(Column column) {
        LevelChunkSection[] sections = new LevelChunkSection[column.sections.length];
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            for (int i = 0; i < sections.length; i++) {
                var section = column.sections[i];
                // In 1.21.1 PalettedContainer.copy() retains the ORIGINAL palette's
                // resize callback. A mutable copy adding its 17th material would
                // resize/corrupt the cached source and then overflow its own bits.
                // A wire round trip binds every palette to the new container.
                var states = mutableCopy(section.getStates(), buffer);
                sections[i] = new LevelChunkSection(states, section.getBiomes());
            }
        } finally {
            buffer.release();
        }
        ProtoChunk chunk = new ProtoChunk(column.pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(),
            new ProtoChunkTicks<>(), height, biomes, null);
        column.blockEntities.values().forEach(nbt -> chunk.setBlockEntityNbt(nbt.copy()));
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
        // Keep ProtoChunk below INITIALIZE_LIGHT: writes must never call a real light engine.
        return chunk;
    }

    static net.minecraft.world.level.chunk.PalettedContainer<BlockState> mutableCopy(
            net.minecraft.world.level.chunk.PalettedContainer<BlockState> source,
            net.minecraft.network.FriendlyByteBuf buffer) {
        buffer.clear();
        source.write(buffer);
        var copy = source.recreate();
        copy.read(buffer);
        return copy;
    }

    private Column freeze(ProtoChunk chunk) {
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            CompoundTag nbt = chunk.getBlockEntityNbtForSaving(pos, level.registryAccess());
            if (nbt != null) blockEntities.put(pos.immutable(), nbt.copy());
        }
        return new Column(chunk.getPos(), chunk.getSections(), height.getMinBuildHeight(), blockEntities);
    }

    record Column(ChunkPos pos, LevelChunkSection[] sections, int minY, Map<BlockPos, CompoundTag> blockEntities) {
        BlockState block(int x, int y, int z) {
            int section = Math.floorDiv(y - minY, 16);
            return section < 0 || section >= sections.length ? Blocks.AIR.defaultBlockState()
                : sections[section].getBlockState(x & 15, y & 15, z & 15);
        }
    }

    private record Change(BlockState state, CompoundTag nbt) {}
    private record Stamp(Map<Long, Map<BlockPos, Change>> chunks) {}

    private static <K, V> Map<K, V> boundedMap(int capacity) {
        return new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > capacity; }
        };
    }

    private enum EmptyBeard implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;
        @Override public double compute(DensityFunction.FunctionContext context) { return 0; }
        @Override public double minValue() { return 0; }
        @Override public double maxValue() { return 0; }
    }

    /** Vanilla feature API over private protochunks, with no notifications to a live level. */
    private final class FeatureRegion extends WorldGenRegion {
        private final ChunkPos origin;
        private final Map<Long, ProtoChunk> workspace = new HashMap<>();
        private final Map<BlockPos, Boolean> writes = new HashMap<>();
        private final net.minecraft.world.level.lighting.LevelLightEngine unlit = new net.minecraft.world.level.lighting.LevelLightEngine(
            new net.minecraft.world.level.chunk.LightChunkGetter() {
                @Override public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) { return null; }
                @Override public net.minecraft.world.level.BlockGetter getLevel() { return FeatureRegion.this; }
            }, false, false);
        private final net.minecraft.util.RandomSource featureRandom;

        FeatureRegion(int x, int z) {
            super(level, null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), copy(base(x, z)));
            origin = new ChunkPos(x, z);
            featureRandom = random.getOrCreateRandomFactory(net.minecraft.resources.ResourceLocation.withDefaultNamespace("worldgen_region_random"))
                .at(origin.getWorldPosition());
        }

        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (!hasChunk(x, z)) {
                if (!required) return null;
                throw new IllegalArgumentException("Feature read outside its vanilla region: " + x + "," + z + " from " + origin);
            }
            return workspace.computeIfAbsent(ChunkPos.asLong(x, z), key -> copy(base(x, z)));
        }
        @Override public boolean hasChunk(int x, int z) { return origin.getChessboardDistance(x, z) <= 1; }
        @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
            return biomeSource().getNoiseBiome(x, y, z, random.sampler());
        }
        @Override public int getMinBuildHeight() { return height.getMinBuildHeight(); }
        @Override public int getHeight() { return height.getHeight(); }
        @Override public int getSeaLevel() { return settings.seaLevel(); }
        @Override public net.minecraft.util.RandomSource getRandom() { return featureRandom; }
        // FEATURES precedes lighting. Never consult a live Overworld light cache:
        // a player visiting the template coordinates must not change generation.
        @Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() { return unlit; }
        @Override public boolean ensureCanWrite(BlockPos pos) {
            return !height.isOutsideBuildHeight(pos) && hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
        }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
            if (!ensureCanWrite(pos)) return false;
            ChunkAccess chunk = getChunk(pos);
            BlockState old = chunk.setBlockState(pos, state, false);
            chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
            chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG).update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
            if (old != null && old.hasBlockEntity()) chunk.removeBlockEntity(pos);
            if (state.hasBlockEntity()) {
                CompoundTag nbt = new CompoundTag();
                nbt.putString("id", "DUMMY");
                nbt.putInt("x", pos.getX()); nbt.putInt("y", pos.getY()); nbt.putInt("z", pos.getZ());
                chunk.setBlockEntityNbt(nbt);
            }
            writes.put(pos.immutable(), true);
            return true;
        }
        @Override public boolean addFreshEntity(Entity entity) { return false; }
        @Override public boolean isOldChunkAround(ChunkPos pos, int radius) { return false; }
    }
}
