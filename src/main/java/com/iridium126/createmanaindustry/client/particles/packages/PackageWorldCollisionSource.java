package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageBlockMaterial;

/** Owner-thread adapter. No world, chunk or BlockState reference escapes into worker snapshots. */
public final class PackageWorldCollisionSource implements PackageCollisionCache.Source {
    public static final int WATER=1, LAVA=2, FIRE=4,MACHINE=16,PORTAL=32;
    private static final PackageCollisionCache.Cell AIR=new PackageCollisionCache.Cell(java.util.List.of(),.6f,0);
    private static final java.util.List<PackageCollisionCache.Box> POWDER_BOXES=java.util.List.of(
            new PackageCollisionCache.Box(0,0,0,1,.9f,1),new PackageCollisionCache.Box(0,0,0,1,1,1));
    private static final java.util.List<PackageCollisionCache.Box> SCAFFOLD_BOXES=java.util.List.of(
            new PackageCollisionCache.Box(0,.875f,0,1,1,1),new PackageCollisionCache.Box(0,0,0,.125f,1,.125f),
            new PackageCollisionCache.Box(0,0,.875f,.125f,1,1),new PackageCollisionCache.Box(.875f,0,0,1,1,.125f),
            new PackageCollisionCache.Box(.875f,0,.875f,1,1,1),new PackageCollisionCache.Box(0,0,0,1,.125f,1));
    private final java.util.IdentityHashMap<net.minecraft.world.phys.shapes.VoxelShape,java.util.List<PackageCollisionCache.Box>> geometry=new java.util.IdentityHashMap<>();
    private final Thread owner=Thread.currentThread();
    private final Level level;
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
    public PackageWorldCollisionSource(Level level){this.level=java.util.Objects.requireNonNull(level);}
    @Override public boolean empty(PackageCollisionCache.Section section) {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("World capture off owner thread");
        // Outside Allvr's native band, column sections cannot describe cube contents.
        // Fall back to the position-aware capture path until a cube snapshot is available.
        if(level.dimension()==com.iridium126.createmanaindustry.dimension.AllvrDimensions.ALLAY_LEVEL
                &&!com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaSection(section.y()))return false;
        var chunk=level.getChunkSource().getChunk(section.x(),section.z(),net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
        if(chunk==null)return false;
        int index=chunk.getSectionIndexFromSectionY(section.y());
        return index<0||index>=chunk.getSections().length||chunk.getSections()[index].hasOnlyAir();
    }
    // Keep the BlockPos overload: Allvr resolves loaded cubes using all three coordinates.
    @SuppressWarnings("deprecation")
    @Override public PackageCollisionCache.Cell capture(PackageCollisionCache.Section section,int index) {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("World capture off owner thread");
        position.set((section.x()<<4)+(index&15),(section.y()<<4)+(index>>>8),(section.z()<<4)+((index>>>4)&15));
        if(!level.hasChunkAt(position))return null;
        // Shapes at a chunk edge can query the neighbour. An unloaded neighbour is
        // unavailable context, rather than the air returned by getBlockState there.
        if(index==0)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            if(!level.hasChunkAt(position.offset(dx*16,0,dz*16)))return null;
        var state=level.getBlockState(position);
        if(state.is(Blocks.AIR)||state.is(Blocks.CAVE_AIR)||state.is(Blocks.VOID_AIR))return AIR;
        // Moving piston shapes and entity-dependent collision need their own admission adapter.
        if(state.is(Blocks.MOVING_PISTON))
            return new PackageCollisionCache.Cell(java.util.List.of(),.6f,PackageCollisionCache.UNSUPPORTED);
        int material=PackageBlockMaterial.encode(state);
        if((material>>>16&255)==PackageBlockMaterial.SCAFFOLDING&&!state.is(net.minecraft.tags.BlockTags.CLIMBABLE))material|=4<<24;
        java.util.List<PackageCollisionCache.Box> boxes;
        if((material>>>16&255)==PackageBlockMaterial.SCAFFOLDING) {
            boxes=SCAFFOLD_BOXES;
        } else if(state.is(Blocks.POWDER_SNOW)) {
            boxes=POWDER_BOXES;
            if(com.simibubi.create.AllEntityTypes.PACKAGE.get().is(net.minecraft.tags.EntityTypeTags.POWDER_SNOW_WALKABLE_MOBS))material|=1<<24;
            if(com.simibubi.create.AllEntityTypes.PACKAGE.get().is(net.minecraft.tags.EntityTypeTags.FREEZE_IMMUNE_ENTITY_TYPES))material|=2<<24;
        } else {
            var shape=state.getCollisionShape(level,position,CollisionContext.empty());
            boxes=geometry.get(shape);
            if(boxes==null){
                var captured=new ArrayList<PackageCollisionCache.Box>();
                shape.forAllBoxes((a,b,c,d,e,f)->captured.add(new PackageCollisionCache.Box((float)a,(float)b,(float)c,(float)d,(float)e,(float)f)));
                boxes=java.util.List.copyOf(captured);if(geometry.size()>=512)geometry.clear();geometry.put(shape,boxes);
            }
        }
        if(state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                &&state.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN)) {
            var below=level.getBlockState(position.below());
            if(below.getBlock().makesOpenTrapdoorAboveClimbable(below,level,position.below(),state))
                material=PackageBlockMaterial.CLIMBABLE<<16;
        }
        if(state.is(Blocks.BUBBLE_COLUMN)&&level.getBlockState(position.above()).isAir())material|=2<<24;
        var fluid=state.getFluidState();
        int flags=(state.is(Blocks.WATER)||state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                &&state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)?WATER:0)
                |(fluid.is(FluidTags.LAVA)?LAVA:0)|(state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock?FIRE:0)|material;
        if(fluid.is(FluidTags.WATER)||fluid.is(FluidTags.LAVA))flags|=Math.clamp((int)Math.ceil(fluid.getHeight(level,position)*255),1,255)<<8;
        var block=state.getBlock();
        if(block instanceof com.simibubi.create.content.logistics.funnel.FunnelBlock
                ||block instanceof com.simibubi.create.content.logistics.chute.AbstractChuteBlock
                ||com.simibubi.create.content.kinetics.belt.BeltBlock.canTransportObjects(state)
                ||com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(level,position,com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour.TYPE)!=null)flags|=MACHINE;
        if(state.is(Blocks.NETHER_PORTAL)||state.is(Blocks.END_PORTAL)||state.is(Blocks.END_GATEWAY))flags|=PORTAL;
        if((flags&MACHINE)!=0&&(flags&0xff0000)==0)flags|=PackageBlockMaterial.MACHINE<<16;
        return new PackageCollisionCache.Cell(boxes,state.getFriction(level,position,null),flags);
    }
}
