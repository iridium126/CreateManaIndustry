package com.iridium126.createmanaindustry.client.dimension.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashMap;
import java.util.Map;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.client.dimension.AllvrLightSampler;

/** BlockAndTintGetter bridge backed by ALLVR cube data, not empty shell chunks. */
public final class AllvrBlockAccess implements BlockAndTintGetter {

    private final ClientLevel level;
    private final Map<LightKey, Integer> lightCache = new HashMap<>();

    private record LightKey(int x, int y, int z, LightLayer type) {}

    public AllvrBlockAccess(ClientLevel level) {
        this.level = level;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return AllvrClientCubeCache.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return AllvrClientCubeCache.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return AllvrClientCubeCache.getFluidState(pos);
    }

    @Override
    public ModelData getModelData(BlockPos pos) {
        BlockEntity entity = this.getBlockEntity(pos);
        return entity == null ? ModelData.EMPTY : entity.getModelData();
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return this.level.getMinBuildHeight();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return this.level.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.level.getLightEngine();
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos) {
        LightKey key = new LightKey(pos.getX(), pos.getY(), pos.getZ(), type);
        return this.lightCache.computeIfAbsent(key, ignored -> {
            int packed = AllvrLightSampler.sample(this.level, pos);
            return type == LightLayer.SKY ? (packed >>> 20) & 15 : (packed >>> 4) & 15;
        });
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return Math.max(this.getBrightness(LightLayer.BLOCK, pos),
            this.getBrightness(LightLayer.SKY, pos) - amount);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return this.level.getBlockTint(pos, resolver);
    }
}
