package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastCompoundTagCopy;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;

import at.petrak.hexcasting.api.casting.OperatorUtils;
import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.RenderedSpell;
import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.Iota;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Hexal's Tick Acceleration great spell. */
public final class OpTick implements SpellAction {

    public static final OpTick INSTANCE = new OpTick();
    public static final String TAG_TIMES_TICKED = "hexal:times_ticked";
    private static final Map<BlockPos, FastTickAssets[]> FAST_TICK_ASSETS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, FastTickAssets[]> eldest) {
            return size() > 128;
        }
    };
    private static BlockPos lastFastAssetPos;
    private static FastTickAssets[] lastFastAssets;

    private OpTick() {}

    @Override
    public int getArgc() {
        return 1;
    }

    @Override
    public Result execute(List<? extends Iota> args, CastingEnvironment env) {
        throw new IllegalStateException("call executeWithUserdata instead.");
    }

    @Override
    public Result executeWithUserdata(List<? extends Iota> args, CastingEnvironment env, CompoundTag userData) {
        return executeWithPosition(OperatorUtils.getBlockPos(args, 0, getArgc()), env, userData, false, false);
    }

    public Result executeForFastPath(BlockPos pos, CastingEnvironment env, CompoundTag userData,
                                     boolean mutateUserDataInPlace) {
        return executeWithPosition(pos, env, userData, true, mutateUserDataInPlace);
    }

    private Result executeWithPosition(BlockPos pos, CastingEnvironment env, CompoundTag userData,
                                       boolean fastUserDataCopy, boolean mutateUserDataInPlace) {
        env.assertVecInRange(Vec3.atCenterOf(pos));

        FastTickAssets fastAssets = fastUserDataCopy ? fastAssets(pos, mutateUserDataInPlace) : null;
        String posKey = fastAssets == null ? pos.toShortString() : fastAssets.posKey();
        int timesTicked = userData.getCompound(TAG_TIMES_TICKED).getInt(posKey);
        long cost = ServerConfig.tickConstantCost + ServerConfig.tickCostPerTicked * timesTicked;
        TickSpell effect = fastAssets == null ? new TickSpell(pos, null, false, false) : fastAssets.effect();
        List<ParticleSpray> particles = fastAssets == null
                ? List.of(ParticleSpray.cloud(Vec3.atCenterOf(pos), 1.0, 5)) : fastAssets.particles();
        return new Result(
                effect,
                cost,
                particles,
                1L);
    }

    private static FastTickAssets fastAssets(BlockPos pos, boolean mutateUserDataInPlace) {
        if (lastFastAssetPos != null && pos.equals(lastFastAssetPos)) {
            FastTickAssets cached = lastFastAssets[mutateUserDataInPlace ? 1 : 0];
            if (cached != null) return cached;
        }
        BlockPos key = pos.immutable();
        FastTickAssets[] variants = FAST_TICK_ASSETS.computeIfAbsent(key, ignored -> new FastTickAssets[2]);
        lastFastAssetPos = key;
        lastFastAssets = variants;
        int variant = mutateUserDataInPlace ? 1 : 0;
        if (variants[variant] == null) {
            BlockPos stablePos = key;
            String posKey = stablePos.toShortString();
            variants[variant] = new FastTickAssets(
                    new TickSpell(stablePos, posKey, true, mutateUserDataInPlace),
                    posKey,
                    List.of(ParticleSpray.cloud(Vec3.atCenterOf(stablePos), 1.0, 5)));
        }
        return variants[variant];
    }

    private record FastTickAssets(TickSpell effect, String posKey, List<ParticleSpray> particles) {}

    private record TickSpell(BlockPos pos, String posKey, boolean fastUserDataCopy,
                             boolean mutateUserDataInPlace) implements RenderedSpell {

        @Override
        public void cast(CastingEnvironment env) {
            throw new IllegalStateException("call cast(env, image) instead.");
        }

        @Override
        @SuppressWarnings("unchecked")
        public CastingImage cast(CastingEnvironment env, CastingImage image) {
            CompoundTag userData = mutateUserDataInPlace
                    ? image.getUserData()
                    : fastUserDataCopy ? FastCompoundTagCopy.copy(image.getUserData()) : image.getUserData().copy();
            CompoundTag timesTickedMap = userData.getCompound(TAG_TIMES_TICKED);
            String key = posKey == null ? pos.toShortString() : posKey;
            timesTickedMap.putInt(key, timesTickedMap.getInt(key) + 1);
            userData.put(TAG_TIMES_TICKED, timesTickedMap);

            // Preserve every other VM field while returning an image with the updated per-cast counter.
            CastingImage newImage = mutateUserDataInPlace ? image : new CastingImage(
                    image.getStack(), image.getParenCount(), image.getParenthesized(),
                    image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed(), userData);

            ServerLevel level = env.getWorld();
            BlockState blockState = level.getBlockState(pos);
            if (!ServerConfig.isHexTickAccelerateAllowed(BuiltInRegistries.BLOCK.getKey(blockState.getBlock())))
                return newImage;

            BlockEntity targetBE = level.getBlockEntity(pos);
            if (targetBE != null) {
                BlockEntityType<BlockEntity> type = (BlockEntityType<BlockEntity>) targetBE.getType();
                BlockEntityTicker<BlockEntity> ticker = targetBE.getBlockState().getTicker(level, type);
                if (ticker != null)
                    ticker.tick(level, pos, targetBE.getBlockState(), targetBE);
            } else if (blockState.isRandomlyTicking()
                    && level.getRandom().nextInt(ServerConfig.tickRandomTickIProb) == 0) {
                blockState.randomTick(level, pos, level.getRandom());
            }

            return newImage;
        }
    }
}
