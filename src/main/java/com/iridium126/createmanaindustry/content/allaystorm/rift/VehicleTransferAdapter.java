package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Optional-mod classes belong in implementations; the transaction coordinator stays loadable without them. */
public interface VehicleTransferAdapter {
    String id();
    Collection<Entity> collect(Entity member);
    List<AABB> geometry(StormTransferGroup group);
    default Vec3 reference(Entity member){return member.getRootVehicle().position();}
    default Set<UUID> structures(){return Set.of();}
    default boolean flying(){return false;}
    /** A fresh adapter instance rechecks native connections; identity equality alone is insufficient. */
    default String topology(){return id()+structures();}
    default Vec3 destination(Entity entity,Vec3 translation){return entity.position().add(translation);}
    default Vec3 destination(Entity entity,Vec3 originalPosition,Vec3 translation){return originalPosition.add(translation);}
    default CompoundTag targetSnapshot(Entity entity,CompoundTag original,Vec3 translation){return original.copy();}
    default void beforeEntityTransfer(Entity entity){}
    default void beginSnapshot(StormTransferGroup group){}
    default boolean snapshotReady(StormTransferGroup group,long deadline){return true;}
    default boolean commitReady(StormTransferGroup group,ServerLevel target,Vec3 translation,CompoundTag snapshot,long deadline){commit(group,target,translation,snapshot);return true;}
    default boolean verify(StormTransferGroup group,ServerLevel target,CompoundTag snapshot,long deadline){return true;}
    default boolean cleanupSource(StormTransferGroup group,long deadline){return true;}
    default boolean restoreReady(StormTransferGroup group,CompoundTag snapshot,long deadline){restore(group,snapshot);return true;}
    default void zeroMotion(ServerLevel target){}
    default void track(ServerLevel target,List<net.minecraft.server.level.ServerPlayer> players){}
    default boolean checkpoint(StormTransferGroup group,ServerLevel canonical,CompoundTag snapshot,RiftTransferJournal journal,long deadline){return true;}
    default void preflight(StormTransferGroup group, ServerLevel target) {}
    default boolean preflightReady(StormTransferGroup group,ServerLevel target,RiftTransferJournal journal,long deadline){preflight(group,target);return true;}
    default void restored(StormTransferGroup group,Map<UUID,Entity> entities,CompoundTag snapshot){mounted(group,entities);}
    default CompoundTag snapshot(StormTransferGroup group) { return new CompoundTag(); }
    default void prepare(StormTransferGroup group, ServerLevel target) {}
    default void freeze(StormTransferGroup group, boolean frozen) {}
    default void commit(StormTransferGroup group, ServerLevel target, Vec3 translation, CompoundTag snapshot) {}
    default void restore(StormTransferGroup group, CompoundTag snapshot) {}
    default void mounted(StormTransferGroup group, Map<UUID, Entity> entities) {}
    default void release(StormTransferGroup group) {}
}
