package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Schema-aware plot rebasing. Item counts, IDs, rotations and arbitrary longs are never translated. */
public final class RiftPlotCoordinates {
    public record Move(int minX,int minZ,int size,int dx,int dz) {
        boolean contains(BlockPos p){return p.getX()>=minX&&p.getX()<minX+(long)size&&p.getZ()>=minZ&&p.getZ()<minZ+(long)size;}
    }
    private static final Set<String> POSITIONS=Set.of("OtherConnector","SwivelPlate","ParentPos","parent","Source","Controller","LastKnownPos","Position","pos","BlockPos","Anchor");
    private static final Set<String> PACKED=Set.of("Goal");
    private static final Set<String> INVENTORY=Set.of("Items","Inventory","inventory","Item","item","itemStack","SaddleItem","ArmorItem","HandItems","ArmorItems","Equipment","components");
    private final List<Move> moves;
    public RiftPlotCoordinates(List<Move> moves){this.moves=List.copyOf(moves);}
    public BlockPos map(BlockPos p){for(Move m:moves)if(m.contains(p))return p.offset(m.dx,0,m.dz);return p;}
    public Vec3 map(Vec3 p){BlockPos old=BlockPos.containing(p),next=map(old);return p.add(next.getX()-old.getX(),0,next.getZ()-old.getZ());}
    public void blockEntity(CompoundTag tag) {
        BlockPos old=new BlockPos(tag.getInt("x"),tag.getInt("y"),tag.getInt("z")),next=map(old);
        visit(tag,0);tag.putInt("x",next.getX());tag.putInt("y",next.getY());tag.putInt("z",next.getZ());
    }
    public void entity(CompoundTag tag){visit(tag,0);for(String prefix:List.of("Tile","Sleeping","Bound"))if(tag.contains(prefix+"X")&&tag.contains(prefix+"Y")&&tag.contains(prefix+"Z")){var p=map(new BlockPos(tag.getInt(prefix+"X"),tag.getInt(prefix+"Y"),tag.getInt(prefix+"Z")));tag.putInt(prefix+"X",p.getX());tag.putInt(prefix+"Y",p.getY());tag.putInt(prefix+"Z",p.getZ());}}
    private void visit(CompoundTag tag,int depth) {
        if(depth>64)throw new IllegalArgumentException("Excessive coordinate schema depth");
        for(String key:List.copyOf(tag.getAllKeys())) {
            // Inventory/component payloads are opaque. A compass/custom item may itself have a "pos" or "Goal".
            if(INVENTORY.contains(key))continue;
            Tag value=tag.get(key);
            if(POSITIONS.contains(key)&&value instanceof IntArrayTag array&&array.getAsIntArray().length==3) {
                int[] a=array.getAsIntArray();BlockPos p=map(new BlockPos(a[0],a[1],a[2]));tag.putIntArray(key,new int[]{p.getX(),p.getY(),p.getZ()});
            } else if(POSITIONS.contains(key)&&value instanceof CompoundTag point&&point.contains("X")&&point.contains("Y")&&point.contains("Z")) {
                BlockPos p=map(new BlockPos(point.getInt("X"),point.getInt("Y"),point.getInt("Z")));point.putInt("X",p.getX());point.putInt("Y",p.getY());point.putInt("Z",p.getZ());
            } else if(PACKED.contains(key)&&value instanceof LongTag number)tag.putLong(key,map(BlockPos.of(number.getAsLong())).asLong());
            else if(value instanceof CompoundTag child) {
                if(key.equals("Network")&&child.contains("Id",Tag.TAG_LONG))child.putLong("Id",map(BlockPos.of(child.getLong("Id"))).asLong());
                visit(child,depth+1);
            } else if(value instanceof ListTag list)for(Tag entry:list)if(entry instanceof CompoundTag child)visit(child,depth+1);
        }
    }
    public void ticks(ListTag ticks){for(Tag entry:ticks){CompoundTag t=(CompoundTag)entry;BlockPos p=map(new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z")));t.putInt("x",p.getX());t.putInt("y",p.getY());t.putInt("z",p.getZ());}}
    /** Native plot section keys are array indices, not absolute section Y. Preserve block Y across dimension layouts. */
    public static void sections(CompoundTag plot,int sourceMinY,int targetMinY,int targetHeight){int shift=(sourceMinY-targetMinY)>>4;for(String key:plot.getCompound("chunks").getAllKeys()){CompoundTag chunk=plot.getCompound("chunks").getCompound(key),sections=chunk.getCompound("sections"),rebased=new CompoundTag();for(String section:sections.getAllKeys()){int index=Integer.parseInt(section)+shift;if(index<0||index>=targetHeight/16)throw new IllegalStateException("Source plot section is outside the target native height layout");rebased.put(Integer.toString(index),sections.get(section));}chunk.put("sections",rebased);chunk.remove("heightmaps");}}
}
