package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.List;

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
        BlockPos pos = OperatorUtils.getBlockPos(args, 0, getArgc());
        env.assertVecInRange(Vec3.atCenterOf(pos));

        int timesTicked = userData.getCompound(TAG_TIMES_TICKED).getInt(pos.toShortString());
        long cost = ServerConfig.tickConstantCost + ServerConfig.tickCostPerTicked * timesTicked;
        return new Result(
                new TickSpell(pos),
                cost,
                List.of(ParticleSpray.cloud(Vec3.atCenterOf(pos), 1.0, 5)),
                1L);
    }

    private record TickSpell(BlockPos pos) implements RenderedSpell {

        @Override
        public void cast(CastingEnvironment env) {
            throw new IllegalStateException("call cast(env, image) instead.");
        }

        @Override
        @SuppressWarnings("unchecked")
        public CastingImage cast(CastingEnvironment env, CastingImage image) {
            CompoundTag userData = image.getUserData().copy();
            CompoundTag timesTickedMap = userData.getCompound(TAG_TIMES_TICKED);
            String posKey = pos.toShortString();
            timesTickedMap.putInt(posKey, timesTickedMap.getInt(posKey) + 1);
            userData.put(TAG_TIMES_TICKED, timesTickedMap);

            // Preserve every other VM field while returning an image with the updated per-cast counter.
            CastingImage newImage = new CastingImage(
                    image.getStack(),
                    image.getParenCount(),
                    image.getParenthesized(),
                    image.getEscapeNext(),
                    image.getSimulateNext(),
                    image.getOpsConsumed(),
                    userData);

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
