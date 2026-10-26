package com.iridium126.createmanaindustry.content.allaystorm.rift;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftPlotVerification;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RiftPlotVerificationTest {
    CompoundTag chunk(){var chunk=new CompoundTag();var sections=new CompoundTag();var section=new CompoundTag();var blocks=new CompoundTag();blocks.putString("Fixture","states");section.put("block_states",blocks);sections.put("12",section);chunk.put("sections",sections);var be=new CompoundTag();be.putInt("x",12);be.putInt("y",64);be.putInt("z",4);be.putString("id","minecraft:chest");var inventory=new ListTag();var stack=new CompoundTag();stack.putString("id","minecraft:diamond");stack.putInt("count",17);inventory.add(stack);be.put("Items",inventory);var entities=new ListTag();entities.add(be);chunk.put("block_entities",entities);return chunk;}
    @Test void regeneratedLightAndHeightmapsDoNotInvalidateAnExactStorageCopy(){var expected=chunk();var actual=expected.copy();actual.put("heightmaps",new CompoundTag());actual.getCompound("sections").getCompound("12").putByteArray("BlockLight",new byte[]{1,2});RiftPlotVerification.chunk(expected,actual);}
    @Test void inventoryOrSectionCorruptionPreventsTheCommitDecision(){var expected=chunk();var actual=expected.copy();actual.getList("block_entities",10).getCompound(0).getList("Items",10).getCompound(0).putInt("count",16);assertThrows(IllegalStateException.class,()->RiftPlotVerification.chunk(expected,actual));var wrongHeight=expected.copy();var sections=wrongHeight.getCompound("sections");sections.put("8",sections.get("12"));sections.remove("12");assertThrows(IllegalStateException.class,()->RiftPlotVerification.chunk(expected,wrongHeight));}
}
