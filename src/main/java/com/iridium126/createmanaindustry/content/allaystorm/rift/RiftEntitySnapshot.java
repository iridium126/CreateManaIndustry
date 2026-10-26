package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Serializes a member once without altering its live riding edges or recursively saving the tree again. */
public final class RiftEntitySnapshot {
    private static final ThreadLocal<Entity> WRITING = new ThreadLocal<>();
    private RiftEntitySnapshot() {}
    public static boolean singleMember(Entity entity) { return WRITING.get()==entity; }
    public static Vec3 position(CompoundTag tag){var pos=tag.getList("Pos",net.minecraft.nbt.Tag.TAG_DOUBLE);if(pos.size()!=3)throw new IllegalStateException("Invalid member pose");return new Vec3(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2));}
    public static void at(CompoundTag tag,Vec3 pos,net.minecraft.resources.ResourceLocation dimension){ListTag p=new ListTag(),motion=new ListTag();for(double d:new double[]{pos.x,pos.y,pos.z})p.add(DoubleTag.valueOf(d));for(int i=0;i<3;i++)motion.add(DoubleTag.valueOf(0));tag.put("Pos",p);tag.put("Motion",motion);tag.putFloat("FallDistance",0);if(tag.getBoolean("Player"))tag.putString("Dimension",dimension.toString());}
    public static CompoundTag capture(Entity entity) {
        if (WRITING.get()!=null) throw new IllegalStateException("Nested transport snapshot");
        WRITING.set(entity);
        try {
            CompoundTag result=new CompoundTag();
            if(!entity.saveAsPassenger(result)) {
                if(!(entity instanceof ServerPlayer))throw new IllegalStateException("Entity cannot be serialized: "+entity.getUUID());
                entity.saveWithoutId(result); result.putBoolean("Player",true);
            }
            if(result.contains("Passengers"))throw new IllegalStateException("Transport serializer did not suppress recursive passengers");
            // Vanilla normally substitutes vehicle XZ when saving a rider. Preserve the exact feet pose here.
            ListTag position=new ListTag(); position.add(DoubleTag.valueOf(entity.getX())); position.add(DoubleTag.valueOf(entity.getY())); position.add(DoubleTag.valueOf(entity.getZ()));
            result.put("Pos",position);
            return result;
        } finally { WRITING.remove(); }
    }
}
