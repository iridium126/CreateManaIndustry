package com.iridium126.createmanaindustry.worldgen.markov;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Worldgen adapter: generate, validate the entire footprint, then place without clipping. */
public final class MarkovTreeFeature extends Feature<MarkovTreeConfiguration> {
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, CreateManaIndustry.MODID);
    static { FEATURES.register("markov_tree", MarkovTreeFeature::new); }

    public static void register(IEventBus bus) { FEATURES.register(bus); }
    private MarkovTreeFeature() { super(MarkovTreeConfiguration.CODEC); }

    @Override public boolean place(FeaturePlaceContext<MarkovTreeConfiguration> context) {
        var world = context.level();
        var config = context.config();
        BlockPos origin = context.origin();
        BlockState soil = world.getBlockState(origin.below());
        if (!soil.is(BlockTags.DIRT) && !soil.is(Blocks.FARMLAND)) return false;
        if (!world.getBlockState(origin).getFluidState().isEmpty()) return false;

        MarkovModel model = config.compiledModel();
        BlockState[] palette = new BlockState[model.values().length()];
        config.palette().forEach((symbol, state) -> {
            int index = model.values().indexOf(symbol);
            if (index < 0 || index == 0 || state.isAir() || !state.getFluidState().isEmpty())
                throw new IllegalArgumentException("Invalid tree palette mapping: " + symbol);
            palette[index] = state;
        });
        byte[] voxels = model.generate(context.random().nextInt(), config.maxSteps());
        BlockState[] states = new BlockState[voxels.length];
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < voxels.length; i++) {
            if (voxels[i] == 0) continue;
            BlockState state = palette[voxels[i]];
            if (state == null) throw new IllegalArgumentException("Unmapped final symbol: " + model.values().charAt(voxels[i]));
            states[i] = state;
            position(pos, origin, i, config);
            if (world.isOutsideBuildHeight(pos) || !world.ensureCanWrite(pos)) return false;
            BlockState existing = world.getBlockState(pos);
            if (!existing.getFluidState().isEmpty() || (!existing.isAir() && !existing.is(BlockTags.REPLACEABLE_BY_TREES)))
                return false;
            count++;
        }
        if (count == 0 || states[config.width() / 2 + config.depth() / 2 * config.width()] == null) return false;
        updateLeafDistances(states, config.width(), config.depth(), config.height());
        // The preflight above prevents a blocked trunk/crown from leaving half a tree behind.
        if (soil.is(Blocks.GRASS_BLOCK) || soil.is(Blocks.MYCELIUM) || soil.is(Blocks.FARMLAND))
            world.setBlock(origin.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int i = 0; i < states.length; i++) if (states[i] != null) {
            position(pos, origin, i, config);
            world.setBlock(pos, states[i], Block.UPDATE_CLIENTS);
        }
        return true;
    }

    private static void position(BlockPos.MutableBlockPos pos, BlockPos origin, int i, MarkovTreeConfiguration config) {
        // Markov (x,y,z) -> Minecraft (x,z,y), with the horizontal grid centre at the placement origin.
        pos.set(origin.getX() + i % config.width() - config.width() / 2,
                origin.getY() + i / (config.width() * config.depth()),
                origin.getZ() + i / config.width() % config.depth() - config.depth() / 2);
    }

    /** Vanilla leaf support distances prevent newly generated, nonpersistent foliage from decaying. */
    static void updateLeafDistances(BlockState[] states, int width, int depth, int height) {
        int[] queue = new int[states.length];
        byte[] distance = new byte[states.length];
        java.util.Arrays.fill(distance, (byte)7);
        int end = 0;
        for (int i = 0; i < states.length; i++) if (states[i] != null && states[i].is(BlockTags.LOGS)) {
            distance[i] = 0;
            queue[end++] = i;
        }
        for (int head = 0; head < end; head++) {
            int i = queue[head], nextDistance = distance[i] + 1;
            if (nextDistance > 6) continue;
            int x = i % width, y = i / width % depth, z = i / (width * depth);
            int[] neighbors = {x > 0 ? i - 1 : -1, x + 1 < width ? i + 1 : -1,
                    y > 0 ? i - width : -1, y + 1 < depth ? i + width : -1,
                    z > 0 ? i - width * depth : -1, z + 1 < height ? i + width * depth : -1};
            for (int n : neighbors) if (n >= 0 && distance[n] > nextDistance && states[n] != null
                    && states[n].hasProperty(LeavesBlock.DISTANCE)) {
                distance[n] = (byte)nextDistance;
                queue[end++] = n;
            }
        }
        for (int i = 0; i < states.length; i++) if (states[i] != null && states[i].hasProperty(LeavesBlock.DISTANCE))
            states[i] = states[i].setValue(LeavesBlock.DISTANCE, (int)distance[i]);
    }
}
