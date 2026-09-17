package com.iridium126.createmanaindustry.dimension.gen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.CaveFeatures;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;

/** Executes the registered vanilla configured feature once in an isolated, deterministic workspace.
 * Its immutable writes are replayed per chunk, avoiding cross-chunk feature-order truncation. */
final class SanctuaryGeodes {
    private final AllvrSanctuaryGenerator owner;
    private volatile Map<BlockPos,BlockState> stamp;
    SanctuaryGeodes(AllvrSanctuaryGenerator owner) { this.owner=owner; }

    private synchronized Map<BlockPos,BlockState> generate(WorldGenLevel level) {
        if(stamp!=null) return stamp;
        var feature=level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getOrThrow(CaveFeatures.AMETHYST_GEODE);
        if(feature.feature()!=net.minecraft.world.level.levelgen.feature.Feature.GEODE)
            throw new IllegalStateException("minecraft:amethyst_geode must use the vanilla geode feature");
        var result=new HashMap<BlockPos,BlockState>();
        for(var target:owner.network.geodes()) {
            BlockPos origin=BlockPos.containing(target.x()-5,target.y()-5,target.z()-5);
            var region=new Workspace(level,origin);
            if(!feature.place(region,level.getLevel().getChunkSource().getGenerator(),
                    RandomSource.create(owner.field.hash(origin.getX(),origin.getY(),origin.getZ())),origin))
                throw new IllegalStateException("Vanilla amethyst_geode placement failed at "+origin);
            result.putAll(region.writes);
        }
        return stamp=Map.copyOf(result);
    }

    boolean near(int x,int y,int z) {
        for(var p:owner.network.geodes())
            if(Math.abs(x-p.x())<=22 && Math.abs(y-p.y())<=22 && Math.abs(z-p.z())<=22) return true;
        return false;
    }
    void apply(WorldGenLevel level,ChunkAccess chunk) {
        int x=chunk.getPos().getMinBlockX(),z=chunk.getPos().getMinBlockZ();
        boolean intersects=false;
        for(var p:owner.network.geodes())
            if(p.x()+22>=x && p.x()-22<x+16 && p.z()+22>=z && p.z()-22<z+16) intersects=true;
        if(!intersects) return;
        var generated=generate(level);
        generated.forEach((pos,state)->{
            if((pos.getX()>>4)!=chunk.getPos().x || (pos.getZ()>>4)!=chunk.getPos().z) return;
            // A passage can open the shell after vanilla placement; remove buds whose parent it cuts away.
            if(state.getBlock() instanceof net.minecraft.world.level.block.AmethystClusterBlock) {
                var parent=pos.relative(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING).getOpposite());
                var support=owner.material(owner.network.get(parent.getX(),parent.getY(),parent.getZ()));
                if(support==null) support=generated.getOrDefault(parent,owner.naturalState(owner.field.column(parent.getX(),parent.getZ()),parent.getY()));
                if(!support.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,parent)) state=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
            chunk.setBlockState(pos,state,false);
        });
    }

    private final class Workspace extends WorldGenRegion {
        final Map<BlockPos,BlockState> writes=new HashMap<>();
        final long seed;
        final BlockPos origin;
        Workspace(WorldGenLevel source,BlockPos origin) {
            super(source.getLevel(),null,ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES),
                new ProtoChunk(new ChunkPos(origin),UpgradeData.EMPTY,LevelHeightAccessor.create(-128,512),
                    source.registryAccess().registryOrThrow(Registries.BIOME),null));
            this.origin=origin; seed=source.getSeed();
        }
        @Override public long getSeed() { return seed; }
        @Override public BlockState getBlockState(BlockPos pos) {
            var state=writes.get(pos);
            return state!=null?state:owner.naturalState(owner.field.column(pos.getX(),pos.getZ()),pos.getY());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public boolean ensureCanWrite(BlockPos pos) { return Math.abs(pos.getX()-origin.getX())<=20
            && Math.abs(pos.getY()-origin.getY())<=20 && Math.abs(pos.getZ()-origin.getZ())<=20; }
        @Override public boolean setBlock(BlockPos pos,BlockState state,int flags,int recursion) {
            if(!ensureCanWrite(pos)) throw new IllegalStateException("Geode write outside bounded workspace: "+pos);
            writes.put(pos.immutable(),state);return true;
        }
    }
}
