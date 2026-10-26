package com.iridium126.createmanaindustry.content.allaystorm.rift;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import java.util.*;
/** Optional bridge is linked only for journals that actually contain native structures. */
final class RiftStructureRecovery {
    static boolean step(UUID id,ServerLevel source,ServerLevel target,ServerLevel canonical,CompoundTag tag,RiftTransferJournal journal,long deadline){if(tag.getCompound("Structure").getList("SubLevels",10).isEmpty())return true;requireSable();return Native.step(id,source,target,canonical,tag,journal,deadline);}
    static boolean checkpoint(UUID id,ServerLevel source,ServerLevel target,ServerLevel canonical,CompoundTag tag,RiftTransferJournal journal,long deadline){if(tag.getCompound("Structure").getList("SubLevels",10).isEmpty())return true;return Native.checkpoint(id,source,target,canonical,tag,journal,deadline);}
    static void mounted(CompoundTag tag,Map<UUID,Entity> entities){if(tag.getString("Adapter").contains("create")||tag.getString("Adapter").contains("sable")){var adapter=new CreateVehicleTransferAdapter();adapter.restoreSeats(tag.getCompound("Structure"));adapter.mounted(null,entities);}if(!tag.getCompound("Structure").getList("SubLevels",10).isEmpty()){requireSable();Native.mounted(tag,entities);}}
    static void offlineMount(CompoundTag player,CompoundTag tag){var ride=player.getCompound(RiftMountPersistence.RIDE);if(!ride.hasUUID("Root"))return;for(var item:tag.getCompound("Structure").getList("EntityOwners",10)){var owner=(CompoundTag)item;if(owner.getUUID("Entity").equals(ride.getUUID("Root")))ride.putUUID("Structure",owner.getUUID("Structure"));}for(var entry:tag.getCompound("Structure").getList("CreateSeats",10)){var vehicle=(CompoundTag)entry;if(vehicle.getUUID("Entity").equals(ride.getUUID("Vehicle")))for(var seat:vehicle.getList("Seats",10)){var s=(CompoundTag)seat;if(s.getUUID("Passenger").equals(player.getUUID("UUID")))ride.putInt("CreateSeat",s.getInt("Seat"));}}}
    static void release(UUID id){if(net.neoforged.fml.ModList.get().isLoaded("sable"))Native.release(id);}
    private static void requireSable(){if(!net.neoforged.fml.ModList.get().isLoaded("sable"))throw new IllegalStateException("Sable is required to recover this group");}
    private static final class Native {
        static void mounted(CompoundTag tag,Map<UUID,Entity> entities){for(var item:tag.getCompound("Structure").getList("EntityOwners",10)){var owner=(CompoundTag)item;Entity e=entities.get(owner.getUUID("Entity"));if(e instanceof dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension movement){var sub=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(e.level()).getSubLevel(owner.getUUID("Structure"));if(sub==null)throw new IllegalStateException("Recovered entity structure owner missing");movement.sable$setTrackingSubLevel(sub);movement.sable$setLastTrackingSubLevelID(sub.getUniqueId());}}}
        static boolean step(UUID id,ServerLevel source,ServerLevel target,ServerLevel canonical,CompoundTag tag,RiftTransferJournal journal,long deadline){return com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableRecovery.step(id,source,target,canonical,tag,journal,deadline);}
        static boolean checkpoint(UUID id,ServerLevel source,ServerLevel target,ServerLevel canonical,CompoundTag tag,RiftTransferJournal journal,long deadline){return com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableRecovery.checkpoint(id,source,target,canonical,tag,journal,deadline);}
        static void release(UUID id){com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableRecovery.release(id);}
    }
}
