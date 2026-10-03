package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Owner-thread adapter. No world, chunk or BlockState reference escapes into worker snapshots. */
public final class PackageWorldCollisionSource implements PackageCollisionCache.Source {
    public static final int WATER=1, LAVA=2, FIRE=4,MACHINE=16,PORTAL=32;
    private final Thread owner=Thread.currentThread();
    private final Level level;
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
    public PackageWorldCollisionSource(Level level){this.level=java.util.Objects.requireNonNull(level);}
    /** Create's empty-context belt collider, friction, fluid and machine flags depend only
     * on BlockState. Inventory/passenger/speed update tags do not change this GPU input.
     * Exact class match keeps overrides in other mods on the conservative invalidation path. */
    public static boolean blockEntityAffectsCollision(net.minecraft.world.level.block.state.BlockState state){
        return state.getBlock().getClass()!=com.simibubi.create.content.kinetics.belt.BeltBlock.class;
    }
    @Override public PackageCollisionCache.Cell capture(PackageCollisionCache.Section section,int index) {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("World capture off owner thread");
        position.set((section.x()<<4)+(index&15),(section.y()<<4)+(index>>>8),(section.z()<<4)+((index>>>4)&15));
        if(!level.hasChunkAt(position))return null;
        // Shapes at a chunk edge can query the neighbour. An unloaded neighbour is
        // unavailable context, rather than the air returned by getBlockState there.
        if(index==0)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            if(!level.hasChunkAt(position.offset(dx*16,0,dz*16)))return null;
        var state=level.getBlockState(position);
        // Moving piston shapes and entity-dependent collision need their own admission adapter.
        if(state.is(Blocks.MOVING_PISTON) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SCAFFOLDING))
            return new PackageCollisionCache.Cell(java.util.List.of(),.6f,PackageCollisionCache.UNSUPPORTED);
        var shape=state.getCollisionShape(level,position,CollisionContext.empty());
        var boxes=new ArrayList<PackageCollisionCache.Box>();
        for(var box:shape.toAabbs())boxes.add(new PackageCollisionCache.Box((float)box.minX,(float)box.minY,
                (float)box.minZ,(float)box.maxX,(float)box.maxY,(float)box.maxZ));
        var fluid=state.getFluidState();
        int flags=(fluid.is(FluidTags.WATER)?WATER:0)|(fluid.is(FluidTags.LAVA)?LAVA:0)|(state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock?FIRE:0);
        if((flags&3)!=0)flags|=Math.clamp((int)Math.ceil(fluid.getHeight(level,position)*255),1,255)<<8;
        var block=state.getBlock();
        if(block instanceof com.simibubi.create.content.logistics.funnel.FunnelBlock
                ||block instanceof com.simibubi.create.content.logistics.chute.AbstractChuteBlock
                ||com.simibubi.create.content.kinetics.belt.BeltBlock.canTransportObjects(state)
                ||com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(level,position,com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour.TYPE)!=null)flags|=MACHINE;
        if(state.is(Blocks.NETHER_PORTAL)||state.is(Blocks.END_PORTAL)||state.is(Blocks.END_GATEWAY))flags|=PORTAL;
        return new PackageCollisionCache.Cell(boxes,state.getFriction(level,position,null),flags);
    }
}
