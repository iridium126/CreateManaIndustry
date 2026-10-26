package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** An ordered, immutable membership snapshot. Geometry and edges are rechecked before taking the lock. */
public record StormTransferGroup(ServerLevel source, UUID key, Vec3 reference, List<Entity> members,
                                 List<Mount> mounts, VehicleTransferAdapter adapter) {
    public record Mount(UUID passenger, UUID vehicle, int order) {}
    public StormTransferGroup {
        members = List.copyOf(members);
        mounts = List.copyOf(mounts);
        if (members.isEmpty()) throw new IllegalArgumentException("Empty transport group");
    }
    public static StormTransferGroup collect(Entity player, VehicleTransferAdapter adapter) {
        LinkedHashMap<UUID, Entity> members = new LinkedHashMap<>();
        var pending = new ArrayDeque<>(adapter.collect(player));
        while (!pending.isEmpty()) {
            Entity entity = pending.removeFirst();
            if (members.putIfAbsent(entity.getUUID(), entity) != null) continue;
            if (entity.level() != player.level() || entity.isRemoved()) throw new IllegalStateException("Transport membership crosses worlds or contains a removed entity");
            pending.addAll(entity.getPassengers());
            if (entity.getVehicle() != null) pending.add(entity.getVehicle());
        }
        var mounts = new ArrayList<Mount>();
        for (Entity entity : members.values()) for (int i = 0; i < entity.getPassengers().size(); i++)
            mounts.add(new Mount(entity.getPassengers().get(i).getUUID(), entity.getUUID(), i));
        var keys=new HashSet<>(members.keySet());keys.addAll(adapter.structures());
        UUID key = keys.stream().min(UUID::compareTo).orElseThrow();
        return new StormTransferGroup((ServerLevel) player.level(), key, adapter.reference(player), new ArrayList<>(members.values()), mounts, adapter);
    }
    public boolean sameMembership(StormTransferGroup other) {
        return key.equals(other.key) && members.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet())
                .equals(other.members.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet())) && mounts.equals(other.mounts)
                && adapter.topology().equals(other.adapter.topology());
    }
    public List<AABB> geometry() { return adapter.geometry(this); }
    public boolean contains(UUID id) { return members.stream().anyMatch(entity -> entity.getUUID().equals(id)); }
    public CompoundTag snapshot() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Group", key);
        tag.putString("Source", source.dimension().location().toString());
        tag.putString("Adapter", adapter.id());
        var entities = new net.minecraft.nbt.ListTag();
        for (Entity entity : members) {
            entities.add(RiftEntitySnapshot.capture(entity));
        }
        tag.put("Entities", entities);
        var edges = new net.minecraft.nbt.ListTag();
        for (Mount mount : mounts) {
            CompoundTag edge = new CompoundTag();
            edge.putUUID("Passenger", mount.passenger);
            edge.putUUID("Vehicle", mount.vehicle);
            edge.putInt("Order", mount.order);
            edges.add(edge);
        }
        tag.put("Mounts", edges);
        tag.put("Structure", adapter.snapshot(this));
        return tag;
    }
}
