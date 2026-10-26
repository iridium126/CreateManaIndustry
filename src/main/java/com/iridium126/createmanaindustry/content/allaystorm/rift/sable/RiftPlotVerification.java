package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import net.minecraft.nbt.*;
import java.util.*;

/** Compare the storage contract, excluding regenerated light and height maps. */
public final class RiftPlotVerification {
    public static void chunk(CompoundTag expected, CompoundTag actual) {
        CompoundTag sections=expected.getCompound("sections"), found=actual.getCompound("sections");
        if(!sections.getAllKeys().equals(found.getAllKeys()))throw new IllegalStateException("Target section layout changed");
        for(String key:sections.getAllKeys())if(!sections.getCompound(key).getCompound("block_states").equals(found.getCompound(key).getCompound("block_states")))
            throw new IllegalStateException("Target block states changed in section "+key);
        if(!entities(expected).equals(entities(actual)))throw new IllegalStateException("Target block entity data, inventory or connection changed");
        for(String ticks:List.of("block_ticks","fluid_ticks"))if(!new HashSet<>(expected.getList(ticks,Tag.TAG_COMPOUND)).equals(new HashSet<>(actual.getList(ticks,Tag.TAG_COMPOUND))))
            throw new IllegalStateException("Target scheduled ticks changed: "+ticks);
    }
    private static Map<String,CompoundTag> entities(CompoundTag chunk) {
        var result=new HashMap<String,CompoundTag>();
        for(Tag entry:chunk.getList("block_entities",Tag.TAG_COMPOUND)) {
            var be=((CompoundTag)entry).copy();String key=be.getInt("x")+","+be.getInt("y")+","+be.getInt("z");
            if(result.put(key,be)!=null)throw new IllegalStateException("Duplicate block entity position");
        }
        return result;
    }
    private RiftPlotVerification(){}
}
