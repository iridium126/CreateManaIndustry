package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

/** Lazy optional boundaries: absent Sable/companion classes never enter common initialization. */
public final class VehicleTransferAdapters {
    private VehicleTransferAdapters(){}
    public record Association(java.util.UUID root,java.util.UUID structure){}
    public static Association association(Entity member){return new Association(member.getRootVehicle().getUUID(),ModList.get().isLoaded("sable")?SableBoundary.association(member):null);}
    public static void maintenance(){if(ModList.get().isLoaded("sable"))SableBoundary.poll();}
    public static void stopping(net.minecraft.server.MinecraftServer server){if(ModList.get().isLoaded("sable"))SableBoundary.stopping(server);}
    public static void clear(){if(ModList.get().isLoaded("sable"))SableBoundary.clear();}
    public static void freezeRecovery(net.minecraft.server.MinecraftServer server){if(ModList.get().isLoaded("sable"))SableBoundary.freezeRecovery(server);}
    public static boolean plot(net.minecraft.server.level.ServerLevel level,net.minecraft.world.level.ChunkPos pos){return ModList.get().isLoaded("sable")&&SableBoundary.plot(level,pos);}
    public static boolean occupied(net.minecraft.server.level.ServerLevel level,net.minecraft.world.phys.AABB box,java.util.Set<java.util.UUID> ignored){return ModList.get().isLoaded("sable")&&SableBoundary.occupied(level,box,ignored);}
    public static VehicleTransferAdapter select(Entity member) {
        if(ModList.get().isLoaded("sable")) {
            VehicleTransferAdapter adapter=SableBoundary.select(member);if(adapter!=null)return adapter;
        }
        if(ModList.get().isLoaded("create"))return new CreateVehicleTransferAdapter();
        return VanillaVehicleTransferAdapter.INSTANCE;
    }
    private static final class SableBoundary {
        static java.util.UUID association(Entity member){var sub=com.iridium126.createmanaindustry.content.allaystorm.rift.sable.SableVehicleTransferAdapter.owner(member);return sub==null?null:sub.getUniqueId();}
        static void freezeRecovery(net.minecraft.server.MinecraftServer server){for(var world:server.getAllLevels()){var container=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(world);if(container!=null){for(var sub:container.getAllSubLevels())if(StormRiftTransfers.frozen(sub.getUniqueId()))com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableFreeze.lock(sub);com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSimulatedBridge.freezeRecovery(world);}}}
        static boolean occupied(net.minecraft.server.level.ServerLevel level,net.minecraft.world.phys.AABB box,java.util.Set<java.util.UUID> ignored){return com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableSpace.intersects(level,box,ignored);}
        static boolean plot(net.minecraft.server.level.ServerLevel level,net.minecraft.world.level.ChunkPos pos){var container=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);return container!=null&&container.inBounds(pos);}
        static void poll(){com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableStore.poll();}
        static void stopping(net.minecraft.server.MinecraftServer server){com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableStore.stopping(server);}
        static void clear(){com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableStore.clear();}
        static VehicleTransferAdapter select(Entity member){return com.iridium126.createmanaindustry.content.allaystorm.rift.sable.SableVehicleTransferAdapter.select(member);}
    }
}
