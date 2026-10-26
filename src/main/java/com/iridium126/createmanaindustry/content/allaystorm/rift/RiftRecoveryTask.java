package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.*;

/** Idempotent recovery before accepting new requests. Released records never resurrect ordinary dead entities. */
final class RiftRecoveryTask {
    private final RiftTransferCoordinator owner;
    private final MinecraftServer server;
    private final CompoundTag tag;
    private final UUID id;
    private final ServerLevel source,target,canonical;
    private final ListTag entities;
    private final List<StormTransferGroup.Mount> mounts=new ArrayList<>();
    private final Set<UUID> ids=new LinkedHashSet<>();
    private final Set<UUID> structureIds=new LinkedHashSet<>();
    private final Map<UUID,Entity> restored=new LinkedHashMap<>();
    private final Map<UUID,CompoundTag> savedMembers=new LinkedHashMap<>();
    private final RiftChunkTickets sourceTickets,targetTickets;
    private CompletableFuture<Map<UUID,Set<ChunkPos>>> sourceIndex,targetIndex;
    private final List<CompletableFuture<?>> playerWrites=new ArrayList<>();
    private RiftNativeCheckpoint checkpoint;
    private CompletableFuture<Void> done;
    private int cursor;
    private boolean structures,mounted,quarantined;
    private final boolean committed;
    RiftRecoveryTask(RiftTransferCoordinator owner,MinecraftServer server,CompoundTag tag) {
        this.owner=owner;this.server=server;this.tag=tag;id=tag.getUUID("Transaction");
        source=world(server,tag.getString("Source"));target=world(server,tag.getString("Target"));committed=Set.of("COMMITTED","CHECKPOINTED").contains(tag.getString("State"));canonical=committed?target:source;
        entities=tag.getList(committed?"DestinationEntities":"Entities",Tag.TAG_COMPOUND);
        if(source==null||target==null||entities.isEmpty())throw new IllegalStateException("Journal worlds or complete recovery snapshot unavailable: "+id);
        sourceTickets=new RiftChunkTickets(source,id);targetTickets=new RiftChunkTickets(target,id);
        for(Tag entry:tag.getList("Entities",Tag.TAG_COMPOUND)){CompoundTag e=(CompoundTag)entry;ids.add(e.getUUID("UUID"));sourceTickets.include(new ChunkPos(net.minecraft.core.BlockPos.containing(RiftTransferCoordinator.position(e))));}
        for(Tag entry:entities){CompoundTag e=(CompoundTag)entry;savedMembers.put(e.getUUID("UUID"),e);(committed?targetTickets:sourceTickets).include(new ChunkPos(net.minecraft.core.BlockPos.containing(RiftTransferCoordinator.position(e))));}
        for(Tag entry:tag.getList("Mounts",Tag.TAG_COMPOUND)){CompoundTag e=(CompoundTag)entry;mounts.add(new StormTransferGroup.Mount(e.getUUID("Passenger"),e.getUUID("Vehicle"),e.getInt("Order")));}
        for(Tag entry:tag.getCompound("Structure").getList("SubLevels",Tag.TAG_COMPOUND))structureIds.add(((CompoundTag)entry).getUUID("uuid"));for(UUID structure:structureIds)owner.lock(structure,id);for(UUID entity:ids)owner.lock(entity,id);
    }
    static ServerLevel world(MinecraftServer server,String name){return server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(name)));}
    boolean step(RiftWorkBudget budget) {
        if(quarantined)return true;
        try {
            if(!sourceTickets.step(budget)||!targetTickets.step(budget))return false;
            if(sourceIndex==null){sourceIndex=RiftEntityDiskIndex.scan(source,ids,owner.journal.executor());targetIndex=RiftEntityDiskIndex.scan(target,ids,owner.journal.executor());return false;}
            if(!sourceIndex.isDone()||!targetIndex.isDone())return false;
            for(var ps:sourceIndex.getNow(Map.of()).values())for(ChunkPos p:ps)sourceTickets.include(p);for(var ps:targetIndex.getNow(Map.of()).values())for(ChunkPos p:ps)targetTickets.include(p);
            if(!sourceTickets.step(budget)||!targetTickets.step(budget))return false;
            if(!structures){if(!RiftStructureRecovery.step(id,source,target,canonical,tag,owner.journal,budget.deadline()))return false;structures=true;}
            while(cursor<entities.size()&&budget.available()) {
                CompoundTag saved=entities.getCompound(cursor);UUID uuid=saved.getUUID("UUID");
                Entity existing=null;Set<ChunkPos> locations=new LinkedHashSet<>();locations.addAll(sourceIndex.getNow(Map.of()).getOrDefault(uuid,Set.of()));locations.addAll(targetIndex.getNow(Map.of()).getOrDefault(uuid,Set.of()));locations.add(new ChunkPos(net.minecraft.core.BlockPos.containing(RiftEntitySnapshot.position(saved))));
                for(ServerLevel level:server.getAllLevels()){Entity e=RiftKnownEntities.find(level,uuid,locations);if(e!=null){if(existing!=null&&existing!=e){try(var scope=RiftTransferContext.open(level.dimension(),Set.of(uuid))){e.discard();}}else existing=e;}}
                if(existing!=null)existing.stopRiding();
                if(saved.getBoolean("Player")) {
                    ServerPlayer player=server.getPlayerList().getPlayer(uuid);
                    if(player==null){CompoundTag file=saved.copy();file.putString("Dimension",canonical.dimension().location().toString());file.remove("RootVehicle");RiftMountPersistence.recovery(file,mounts,savedMembers,canonical.dimension().location().toString());RiftStructureRecovery.offlineMount(file,tag);playerWrites.add(owner.journal.player(server.getWorldPath(LevelResource.PLAYER_DATA_DIR),uuid,file));cursor++;continue;}
                    existing=player;
                }
                if(existing!=null&&existing.level()!=canonical) {
                    try(var context=RiftTransferContext.open(canonical.dimension(),Set.of(uuid))){existing=existing.changeDimension(new DimensionTransition(canonical,RiftTransferCoordinator.position(saved),Vec3.ZERO,existing.getYRot(),existing.getXRot(),DimensionTransition.DO_NOTHING));}
                    if(existing==null)throw new IllegalStateException("Recovery native transition refused");
                }
                if(existing==null){existing=EntityType.loadEntityRecursive(saved,canonical,e->e);if(existing==null||!canonical.addFreshEntity(existing))throw new IllegalStateException("Recovery entity cannot be created: "+uuid);}
                else existing.load(saved);
                RiftMountPersistence.mark(existing);if(committed){existing.setDeltaMovement(Vec3.ZERO);existing.fallDistance=0;}restored.put(uuid,existing);cursor++;
            }
            if(cursor<entities.size())return false;
            if(!mounted) {
                // Offline seats remain in the WAL/ride intent. Compress temporary passenger indices until login.
                var online=new ArrayList<StormTransferGroup.Mount>();var orders=new HashMap<UUID,Integer>();for(var edge:mounts.stream().sorted(Comparator.comparingInt(StormTransferGroup.Mount::order)).toList())if(restored.containsKey(edge.passenger())&&restored.containsKey(edge.vehicle()))online.add(new StormTransferGroup.Mount(edge.passenger(),edge.vehicle(),orders.merge(edge.vehicle(),1,Integer::sum)-1));
                RiftTransferCoordinator.mount(online,restored);RiftTransferCoordinator.verifyMounts(online,restored);
                RiftStructureRecovery.mounted(tag,restored);mounted=true;
                checkpoint=new RiftNativeCheckpoint(owner.journal,ids);
                var from=new LinkedHashSet<ChunkPos>();for(var ps:sourceIndex.getNow(Map.of()).values())from.addAll(ps);var to=new LinkedHashSet<ChunkPos>();for(var ps:targetIndex.getNow(Map.of()).values())to.addAll(ps);
                for(Entity e:restored.values())(e.level()==source?from:to).add(e.chunkPosition());checkpoint.include(source,from);checkpoint.include(target,to);for(Entity e:restored.values())if(e instanceof ServerPlayer p)checkpoint.player(p);
            }
            if(!checkpoint.step(budget)||playerWrites.stream().anyMatch(f->!f.isDone()))return false;
            for(var write:playerWrites)write.getNow(null);
            if(!RiftStructureRecovery.checkpoint(id,source,target,canonical,tag,owner.journal,budget.deadline()))return false;
            if(done==null){done=owner.journal.write(id,committed?"RELEASED":"ROLLED_BACK_RELEASED",tag);return false;}
            if(!done.isDone())return false;done.getNow(null);RiftStructureRecovery.release(id);sourceTickets.close();targetTickets.close();owner.recovered(id,ids);return true;
        }catch(Throwable error){quarantined=true;owner.quarantine(id,ids,error);return true;}
    }
}
