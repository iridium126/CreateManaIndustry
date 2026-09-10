package com.iridium126.createmanaindustry.dimension.storage;



import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Cube ↔ NBT on the service/decode threads (plan §7.1) — the ALLVR counterpart of
 * {@code ChunkSerializer}, minus everything the cube layer does not own
 * (ticks, entities, heightmaps, light data). The region layer never touches
 * this schema; the worker never touches a live cube: both only see the
 * immutable {@link AllvrCubeSnapshot}. Loads run on the bounded persistence
 * decode pool, while the cube map installs the result on the server thread.
 * <p>
 * Strictness (plan §6): section order is not trusted (reordered by
 * {@code Index}, duplicates/missing/out-of-range validated), block entities
 * must belong to the target cube and be supported by the recorded block
 * state (invalid entries are logged and skipped), and any structural damage
 * throws {@link AllvrCubeCorruptedException} — the caller then keeps the cube
 * unloaded and must never regenerate over the record.
 */
public final class AllvrCubeSerializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(CreateManaIndustry.MODID + "/AllvrCubeSerializer");

    /** Bumped by schema changes; see {@link AllvrCubeDataFixes}. */
    public static final int FORMAT_VERSION = AllvrCubeDataFixes.CURRENT_VERSION;

    /**
     * Bumped when the deterministic island generator changes shape — edited
     * cubes keep full snapshots and so are immune, but the version lets future
     * migrations reason about unedited regions.
     */
    public static final int GENERATOR_VERSION = 1;

    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
        Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
        Blocks.AIR.defaultBlockState());

    private AllvrCubeSerializer() {}

    // ------------------------------------------------------------------
    // save
    // ------------------------------------------------------------------

    /**
     * Serializes a cube into its self-contained NBT record. Server thread
     * only — reads live sections and block entities. The returned snapshot
     * carries the captured mutation version so the worker can do
     * latest-wins bookkeeping.
     */
    @SuppressWarnings("unchecked")
    public static AllvrCubeSnapshot snapshot(AllvrCube cube, ServerLevel level) {        CompoundTag root = new CompoundTag();
        NbtUtils.addCurrentDataVersion(root);
        root.putInt("AllvrFormatVersion", FORMAT_VERSION);
        root.putInt("GeneratorVersion", GENERATOR_VERSION);
        AllvrCubePos pos = cube.getPos();
        root.putInt("xPos", pos.getX());
        root.putInt("yPos", pos.getY());
        root.putInt("zPos", pos.getZ());
        root.putLong("LastUpdate", level.getGameTime());

        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Codec<PalettedContainer<Holder<Biome>>> biomeCodec = biomeCodec(biomes);
        ListTag sections = new ListTag();
        LevelChunkSection[] cubeSections = cube.getSections();
        for (int i = 0; i < AllvrCube.SECTIONS_PER_CUBE; i++) {
            LevelChunkSection section = cubeSections[i];
            CompoundTag sectionTag = new CompoundTag();
            sectionTag.putByte("Index", (byte) i);
            sectionTag.put("block_states", encodeOrThrow(
                BLOCK_STATE_CODEC.encodeStart(NbtOps.INSTANCE, section.getStates()), pos, "block_states[" + i + "]"));
            sectionTag.put("biomes", encodeOrThrow(
                biomeCodec.encodeStart(NbtOps.INSTANCE, (PalettedContainer<Holder<Biome>>) section.getBiomes()),
                pos, "biomes[" + i + "]"));
            sections.add(sectionTag);
        }
        root.put("sections", sections);

        ListTag blockEntities = new ListTag();
        for (BlockEntity be : cube.getBlockEntities().values()) {
            if (be.isRemoved()) {
                continue;
            }
            try {
                CompoundTag beTag = be.saveWithFullMetadata(level.registryAccess());
                if (beTag != null) {
                    blockEntities.add(beTag);
                }
            } catch (Exception e) {
                throw new IllegalStateException("failed to save block entity " + be.getType() + " at "
                    + be.getBlockPos() + " in cube " + pos, e);
            }
        }
        root.put("block_entities", blockEntities);
        return new AllvrCubeSnapshot(pos, cube.mutationVersion(), root);
    }

    private static Tag encodeOrThrow(DataResult<Tag> result, AllvrCubePos pos, String what) {
        // encode failures abort the snapshot (the cube stays pinned in memory,
        // plan §7.3) — never a partial record
        return result.getOrThrow(message -> new IllegalStateException(
            "cube " + pos + ": encoding " + what + " failed: " + message));
    }

    private static final java.util.concurrent.ConcurrentHashMap<Registry<Biome>, Codec<PalettedContainer<Holder<Biome>>>>
        BIOME_CODECS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Codec<PalettedContainer<Holder<Biome>>> biomeCodec(Registry<Biome> biomes) {
        return BIOME_CODECS.computeIfAbsent(biomes, registry -> PalettedContainer.codecRW(
            registry.asHolderIdMap(), registry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES,
            registry.getHolderOrThrow(Biomes.PLAINS)));
    }

    // ------------------------------------------------------------------
    // load
    // ------------------------------------------------------------------

    /**
     * Validates a record and restores it into a fresh {@link AllvrCube} —
     * persistence decode worker only. Sections and BEs are installed via the loader-only
     * paths (no dirty marking, no neighbour updates); the caller rebuilds the
     * level-independent emitter index off-thread, then finishes with
     * {@code onLoad} on the server thread and owns lifecycle. Cubes with
     * block entities may refresh level-dependent emissions after binding.
     *
     * @throws AllvrCubeCorruptedException on any structural damage — the cube
     *                                     must stay unloaded and the record must never be regenerated over
     */
    public static AllvrCube load(AllvrCubePos pos, CompoundTag raw, ServerLevel level)
        throws AllvrCubeCorruptedException {
        CompoundTag root = AllvrCubeDataFixes.update(raw);
        NbtUtils.getDataVersion(root, -1); // recorded for future migrations; V1 has none

        checkCoord(root, "xPos", pos.getX(), pos);
        checkCoord(root, "yPos", pos.getY(), pos);
        checkCoord(root, "zPos", pos.getZ(), pos);

        AllvrCube cube = new AllvrCube(pos, level.registryAccess().registryOrThrow(Registries.BIOME));
        Codec<PalettedContainer<Holder<Biome>>> biomeCodec =
            biomeCodec(level.registryAccess().registryOrThrow(Registries.BIOME));

        ListTag sections = root.getList("sections", Tag.TAG_COMPOUND);
        boolean[] seen = new boolean[AllvrCube.SECTIONS_PER_CUBE];
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag sectionTag = sections.getCompound(i);
            int index = sectionTag.getByte("Index");
            if (index < 0 || index >= AllvrCube.SECTIONS_PER_CUBE) {
                throw new AllvrCubeCorruptedException("cube " + pos + ": section index " + index + " out of range");
            }
            if (seen[index]) {
                throw new AllvrCubeCorruptedException("cube " + pos + ": duplicate section index " + index);
            }
            seen[index] = true;
            PalettedContainer<BlockState> states = parse(
                BLOCK_STATE_CODEC, sectionTag.getCompound("block_states"), pos, "block_states[" + index + "]");
            PalettedContainer<Holder<Biome>> biomes = parse(
                biomeCodec, sectionTag.getCompound("biomes"), pos, "biomes[" + index + "]");
            cube.installSection(index, states, biomes);
        }
        // sections absent from the list keep the fresh-cube default (air + plains) — plan §6

        ListTag blockEntities = root.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag beTag = blockEntities.getCompound(i);
            BlockPos at = new BlockPos(beTag.getInt("x"), beTag.getInt("y"), beTag.getInt("z"));
            if (!AllvrCubePos.of(at).equals(pos)) {
                LOGGER.error("[Allvr] cube {}: block entity record at {} does not belong to this cube — skipped", pos, at);
                continue;
            }
            BlockState state = cube.getBlockState(at);
            BlockEntity be;
            try {
                be = BlockEntity.loadStatic(at, state, beTag, level.registryAccess());
            } catch (Exception e) {
                LOGGER.error("[Allvr] cube {}: failed to load block entity at {} — skipped", pos, at, e);
                continue;
            }
            if (be == null || !state.hasBlockEntity() || !be.getType().isValid(state)) {
                LOGGER.error("[Allvr] cube {}: block entity {} at {} is not supported by the recorded block state — skipped",
                    pos, beTag.getString("id"), at);
                continue;
            }
            cube.installBlockEntity(be);
        }
        return cube;
    }

    private static void checkCoord(CompoundTag root, String key, int expected, AllvrCubePos pos)
        throws AllvrCubeCorruptedException {
        int actual = root.getInt(key);
        if (actual != expected) {
            throw new AllvrCubeCorruptedException(
                "cube record " + key + "=" + actual + " does not match requested " + expected + " (" + pos + ")");
        }
    }

    private static <T> T parse(Codec<T> codec, CompoundTag tag, AllvrCubePos pos, String what)
        throws AllvrCubeCorruptedException {
        return codec.parse(NbtOps.INSTANCE, tag)
            .promotePartial(message -> LOGGER.error("[Allvr] cube {}: partial error decoding {}: {}", pos, what, message))
            .getOrThrow(message -> new AllvrCubeCorruptedException(
                "cube " + pos + ": decoding " + what + " failed: " + message));
    }

}
