package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.mixin.allaystorm.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;
import java.util.concurrent.*;

/** Retire the WAL only after native entity regions and detached player files are durable. */
public final class RiftNativeCheckpoint {
    private final RiftTransferJournal journal;
    private final Set<UUID> ids;
    private final Map<ServerLevel,LinkedHashSet<ChunkPos>> pending=new LinkedHashMap<>();
    private final List<CompletableFuture<?>> writes=new ArrayList<>();
    private CompletableFuture<Void> flushed;
    public RiftNativeCheckpoint(RiftTransferJournal journal,Set<UUID> ids){this.journal=journal;this.ids=Set.copyOf(ids);}
    public void include(ServerLevel level,Collection<ChunkPos> positions){pending.computeIfAbsent(level,ignored->new LinkedHashSet<>()).addAll(positions);}
    public void player(ServerPlayer player){writes.add(journal.player(player.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR),player.getUUID(),player.saveWithoutId(new CompoundTag())));}
    public boolean step(RiftWorkBudget budget) {
        for(var entry:pending.entrySet()) {
            ServerLevel level=entry.getKey();var cursor=entry.getValue().iterator();
            while(cursor.hasNext()) {
                if(!budget.available())return false;
                ChunkPos pos=cursor.next();
                // Never rewrite a stale disk copy while native entity sections can load concurrently.
                // Load the section asynchronously, then let its single server-thread owner serialize it.
                if(!RiftKnownEntities.request(level,pos,budget))return false;
                if(!((RiftEntityManagerAccessor)((RiftLevelEntitiesAccessor)level).cmi$entities()).cmi$store(pos.toLong(),ignored->{}))return false;
                cursor.remove();
            }
        }
        if(flushed==null)flushed=CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).thenCompose(ignored->CompletableFuture.allOf(pending.keySet().stream().map(l->RiftEntityDiskIndex.storage(l).synchronize(true)).toArray(CompletableFuture[]::new)));
        if(!flushed.isDone())return false;flushed.getNow(null);return true;
    }
}
