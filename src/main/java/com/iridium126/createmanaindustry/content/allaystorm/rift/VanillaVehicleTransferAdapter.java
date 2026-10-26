package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.phys.AABB;
import java.util.Collection;
import java.util.List;

public final class VanillaVehicleTransferAdapter implements VehicleTransferAdapter {
    public static final VanillaVehicleTransferAdapter INSTANCE = new VanillaVehicleTransferAdapter();
    private VanillaVehicleTransferAdapter() {}
    public String id() { return "vanilla"; }
    public Collection<Entity> collect(Entity member) { return List.of(member.getRootVehicle()); }
    public List<AABB> geometry(StormTransferGroup group) { return group.members().stream().map(Entity::getBoundingBox).toList(); }
    @Override public void preflight(StormTransferGroup group, ServerLevel target) {
        for (Entity entity : group.members()) {
            if (!entity.canChangeDimensions(group.source(), target)) throw new IllegalStateException("Cross-dimension travel denied: " + entity.getUUID());
            if (entity instanceof Leashable leashable && leashable.isLeashed()) throw new IllegalStateException("A leashed member needs a leash-preserving adapter");
        }
    }
}
