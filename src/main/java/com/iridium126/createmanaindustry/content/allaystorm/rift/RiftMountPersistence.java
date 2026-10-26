package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.*;

/** Managed mounts persist in entity regions once, rather than being copied into each player's RootVehicle. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID)
public final class RiftMountPersistence {
    public static final String MANAGED="CMIRiftManaged",RIDE="CMIRiftRide";
    private record Pending(ServerPlayer player,CompoundTag ride,RiftChunkTickets tickets,long until,Vec3 initial){}
    private static final Map<UUID,Pending> PENDING=new LinkedHashMap<>();
    private RiftMountPersistence(){}
    public static boolean managed(Entity entity){return entity.getPersistentData().getBoolean(MANAGED);}
    public static void mark(Entity entity){entity.getPersistentData().putBoolean(MANAGED,true);}
    public static boolean separate(ServerPlayer player){return managed(player.getRootVehicle());}
    public static void write(ServerPlayer player,CompoundTag output) {
        if(!separate(player)||player.getVehicle()==null)return;
        Entity root=player.getRootVehicle(),vehicle=player.getVehicle();CompoundTag ride=new CompoundTag();ride.putUUID("Root",root.getUUID());ride.putUUID("Vehicle",vehicle.getUUID());ride.putInt("Order",vehicle.getPassengers().indexOf(player));ride.putString("Dimension",player.level().dimension().location().toString());ride.putInt("ChunkX",root.chunkPosition().x);ride.putInt("ChunkZ",root.chunkPosition().z);
        if(net.neoforged.fml.ModList.get().isLoaded("sable"))Sable.structure(player,root,ride);
        if(net.neoforged.fml.ModList.get().isLoaded("create"))Create.write(player,vehicle,ride);
        output.put(RIDE,ride);
    }
    public static void read(ServerPlayer player,CompoundTag input){if(input.contains(RIDE,Tag.TAG_COMPOUND))player.getPersistentData().put(RIDE,input.getCompound(RIDE).copy());else player.getPersistentData().remove(RIDE);}
    /** Offline recovery writes the same small attachment intent, never another copy of the root inventory. */
    public static void recovery(CompoundTag player,List<StormTransferGroup.Mount> edges,Map<UUID,CompoundTag> members,String dimension) {
        UUID id=player.getUUID("UUID");var edge=edges.stream().filter(m->m.passenger().equals(id)).findFirst();if(edge.isEmpty())return;
        UUID root=edge.get().vehicle();Set<UUID> visited=new HashSet<>();while(visited.add(root)){UUID current=root;var parent=edges.stream().filter(m->m.passenger().equals(current)).findFirst();if(parent.isEmpty())break;root=parent.get().vehicle();}
        CompoundTag rootTag=members.get(root);if(rootTag==null)throw new IllegalStateException("Recovery root is missing");var pos=RiftEntitySnapshot.position(rootTag);CompoundTag ride=new CompoundTag();ride.putUUID("Root",root);ride.putUUID("Vehicle",edge.get().vehicle());ride.putInt("Order",edge.get().order());ride.putString("Dimension",dimension);ride.putInt("ChunkX",new ChunkPos(net.minecraft.core.BlockPos.containing(pos)).x);ride.putInt("ChunkZ",new ChunkPos(net.minecraft.core.BlockPos.containing(pos)).z);player.put(RIDE,ride);
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;CompoundTag ride=player.getPersistentData().getCompound(RIDE);if(!ride.hasUUID("Vehicle")||!ride.hasUUID("Root"))return;
        if(!ride.getString("Dimension").equals(player.level().dimension().location().toString()))return;
        var tickets=new RiftChunkTickets(player.serverLevel(),UUID.randomUUID());if(!ride.hasUUID("Structure"))tickets.include(new ChunkPos(ride.getInt("ChunkX"),ride.getInt("ChunkZ")));
        PENDING.put(player.getUUID(),new Pending(player,ride.copy(),tickets,player.getServer().getTickCount()+1200L,player.position()));
    }
    public static void tick(MinecraftServer server,RiftWorkBudget budget) {
        var cursor=PENDING.values().iterator();while(cursor.hasNext()&&budget.available()) {
            Pending pending=cursor.next();ServerPlayer p=pending.player;
            if(p.isRemoved()||server.getTickCount()>pending.until||!pending.ride.getString("Dimension").equals(p.level().dimension().location().toString())||p.getVehicle()!=null||p.position().distanceToSqr(pending.initial)>16){pending.tickets.close();cursor.remove();continue;}
            if(!pending.tickets.step(budget))continue;
            var positions=List.of(new ChunkPos(pending.ride.getInt("ChunkX"),pending.ride.getInt("ChunkZ")));Entity root=RiftKnownEntities.find(p.serverLevel(),pending.ride.getUUID("Root"),positions),vehicle=RiftKnownEntities.find(p.serverLevel(),pending.ride.getUUID("Vehicle"),positions);if(root==null||vehicle==null)continue;
            if(!managed(root)||vehicle.getRootVehicle()!=root)throw new IllegalStateException("Persisted rift mount identity mismatch");
            try(var access=RiftWorldAccess.open()){if(!p.startRiding(vehicle,true))throw new IllegalStateException("Persisted passenger could not remount");var riders=new ArrayList<>(vehicle.getPassengers());riders.remove(p);riders.add(Math.min(Math.max(0,pending.ride.getInt("Order")),riders.size()),p);((com.iridium126.createmanaindustry.mixin.allaystorm.RiftPassengersAccessor)vehicle).cmi$orderedPassengers(com.google.common.collect.ImmutableList.copyOf(riders));if(net.neoforged.fml.ModList.get().isLoaded("create"))Create.seat(p,vehicle,pending.ride);vehicle.positionRider(p);}
            pending.tickets.close();cursor.remove();
        }
    }
    public static void clear(){for(var p:PENDING.values())p.tickets.close();PENDING.clear();}
    private static final class Create {
        static void write(ServerPlayer player,Entity vehicle,CompoundTag ride){if(vehicle instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity contraption){Integer seat=contraption.getContraption().getSeatMapping().get(player.getUUID());if(seat!=null)ride.putInt("CreateSeat",seat);}}
        static void seat(ServerPlayer player,Entity vehicle,CompoundTag ride){if(vehicle instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity contraption){Integer seat=ride.contains("CreateSeat")?ride.getInt("CreateSeat"):contraption.getContraption().getSeatMapping().get(player.getUUID());if(seat!=null)contraption.addSittingPassenger(player,seat);}}
    }
    private static final class Sable {
        static void structure(ServerPlayer player,Entity root,CompoundTag ride){var sub=dev.ryanhcode.sable.Sable.HELPER.getContaining(root);if(sub==null&&root instanceof dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension ext)sub=ext.sable$getTrackingSubLevel();if(sub!=null)ride.putUUID("Structure",sub.getUniqueId());}
    }
}
