package com.iridium126.createmanaindustry.content.allaystorm.rift;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftPlotCoordinates;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RiftPlotCoordinatesTest {
    @Test void rebaseUsesEachConnectedPlotsOwnOffsetAndPreservesInventoryNumbers() {
        var map=new RiftPlotCoordinates(List.of(new RiftPlotCoordinates.Move(1000,2000,512,4096,-512),new RiftPlotCoordinates.Move(1512,2000,512,-512,1024)));
        CompoundTag be=new CompoundTag();be.putInt("x",1010);be.putInt("y",70);be.putInt("z",2010);be.putInt("Count",64);be.putLong("UnrelatedLong",BlockPos.asLong(1001,3,2001));be.putLong("Goal",BlockPos.asLong(1520,70,2001));be.putIntArray("SwivelPlate",new int[]{1520,70,2001});CompoundTag network=new CompoundTag();network.putLong("Id",BlockPos.asLong(1002,70,2002));be.put("Network",network);map.blockEntity(be);
        assertEquals(5106,be.getInt("x"));assertEquals(1498,be.getInt("z"));assertEquals(new BlockPos(1008,70,3025),BlockPos.of(be.getLong("Goal")));assertArrayEquals(new int[]{1008,70,3025},be.getIntArray("SwivelPlate"));assertEquals(64,be.getInt("Count"));assertEquals(BlockPos.asLong(1001,3,2001),be.getLong("UnrelatedLong"));assertEquals(BlockPos.asLong(5098,70,1490),be.getCompound("Network").getLong("Id"));
    }
    @Test void scheduledTicksMoveButTheirTimeAndPriorityDoNot(){var map=new RiftPlotCoordinates(List.of(new RiftPlotCoordinates.Move(0,0,512,512,1024)));CompoundTag tick=new CompoundTag();tick.putInt("x",3);tick.putInt("y",4);tick.putInt("z",5);tick.putInt("t",99);tick.putInt("p",2);ListTag ticks=new ListTag();ticks.add(tick);map.ticks(ticks);assertEquals(515,tick.getInt("x"));assertEquals(1029,tick.getInt("z"));assertEquals(99,tick.getInt("t"));assertEquals(2,tick.getInt("p"));}
    @Test void overworldSectionIndicesBecomeAllayIndicesWithoutMovingBlockY(){CompoundTag plot=new CompoundTag(),chunks=new CompoundTag(),chunk=new CompoundTag(),sections=new CompoundTag(),stone=new CompoundTag();stone.putString("Fixture","Y64");sections.put("8",stone);chunk.put("sections",sections);chunk.put("heightmaps",new CompoundTag());chunks.put("0",chunk);plot.put("chunks",chunks);RiftPlotCoordinates.sections(plot,-64,-128,512);assertEquals("Y64",chunk.getCompound("sections").getCompound("12").getString("Fixture"));assertFalse(chunk.contains("heightmaps"));assertFalse(chunk.getCompound("sections").contains("8"));}
    @Test void itemComponentCoordinatesRemainByteForByteUnchanged(){var coordinates=new RiftPlotCoordinates(List.of(new RiftPlotCoordinates.Move(0,0,512,512,1024)));var be=new CompoundTag();be.putInt("x",2);be.putInt("y",64);be.putInt("z",3);var items=new ListTag();var stack=new CompoundTag();stack.putString("id","minecraft:compass");stack.putInt("count",1);var custom=new CompoundTag();custom.putIntArray("pos",new int[]{2,64,3});custom.putLong("Goal",BlockPos.asLong(2,64,3));var components=new CompoundTag();components.put("example:coordinates",custom);stack.put("components",components);items.add(stack);be.put("Items",items);var expected=items.copy();coordinates.blockEntity(be);assertEquals(514,be.getInt("x"));assertEquals(expected,be.get("Items"));}
}
