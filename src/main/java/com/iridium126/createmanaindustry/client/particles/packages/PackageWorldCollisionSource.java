package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Owner-thread adapter. No world, chunk or BlockState reference escapes into worker snapshots. */
public final class PackageWorldCollisionSource implements PackageCollisionCache.Source {
    public static final int WATER=1, LAVA=2, FIRE=4;
    private final Thread owner=Thread.currentThread();
    private final Level level;
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
    public PackageWorldCollisionSource(Level level){this.level=java.util.Objects.requireNonNull(level);}
    @Override public PackageCollisionCache.Cell capture(PackageCollisionCache.Section section,int index) {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("World capture off owner thread");
        position.set((section.x()<<4)+(index&15),(section.y()<<4)+(index>>>8),(section.z()<<4)+((index>>>4)&15));
        if(!level.hasChunkAt(position))return null;
        var state=level.getBlockState(position);
        // Moving piston shapes and entity-dependent collision need their own admission adapter.
        if(state.is(Blocks.MOVING_PISTON) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SCAFFOLDING))return null;
        var shape=state.getCollisionShape(level,position,CollisionContext.empty());
        var boxes=new ArrayList<PackageCollisionCache.Box>();
        for(var box:shape.toAabbs())boxes.add(new PackageCollisionCache.Box((float)box.minX,(float)box.minY,
                (float)box.minZ,(float)box.maxX,(float)box.maxY,(float)box.maxZ));
        var fluid=state.getFluidState();
        int flags=(fluid.is(FluidTags.WATER)?WATER:0)|(fluid.is(FluidTags.LAVA)?LAVA:0)|(state.is(Blocks.FIRE)?FIRE:0);
        return new PackageCollisionCache.Cell(boxes,state.getFriction(level,position,null),flags);
    }
}
