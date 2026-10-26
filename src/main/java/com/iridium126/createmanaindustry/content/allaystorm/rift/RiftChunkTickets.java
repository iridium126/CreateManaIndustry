package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import java.util.*;

/** Tickets are polled through nonblocking cache queries. Both terrain and entity storage must be ready. */
public final class RiftChunkTickets implements AutoCloseable {
    private static final TicketType<UUID> TYPE=TicketType.create("cmi_rift_group",UUID::compareTo);
    private final ServerLevel level;
    private final UUID transaction;
    private final LinkedHashSet<ChunkPos> desired=new LinkedHashSet<>(),held=new LinkedHashSet<>();
    public RiftChunkTickets(ServerLevel level,UUID transaction){this.level=level;this.transaction=transaction;}
    public void include(ChunkPos position){desired.add(position);}
    public void include(AABB box){
        if(!Double.isFinite(box.minX)||!Double.isFinite(box.maxX)||!Double.isFinite(box.minZ)||!Double.isFinite(box.maxZ))throw new IllegalArgumentException("Non-finite transport geometry");
        int x0=Mth.floor(box.minX)>>4,x1=Mth.floor(box.maxX)>>4,z0=Mth.floor(box.minZ)>>4,z1=Mth.floor(box.maxZ)>>4;
        if(((long)x1-x0+1)*((long)z1-z0+1)>2400)throw new IllegalStateException("Footprint cannot cold-load inside the sixty-second request budget");
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)desired.add(new ChunkPos(x,z));
    }
    public boolean step(RiftWorkBudget budget){
        for(ChunkPos position:desired) {
            // Sable's synthetic PlotChunkHolder must never enter vanilla DistanceManager unload scheduling.
            if(VehicleTransferAdapters.plot(level,position)) {
                if(!RiftKnownEntities.request(level,position,budget))return false;
                continue;
            }
            if(held.contains(position))continue;
            boolean cold=level.getChunkSource().getChunkNow(position.x,position.z)==null;
            if(cold&&!budget.load())return false;
            if(!budget.available())return false;
            level.getChunkSource().addRegionTicket(TYPE,position,1,transaction);held.add(position);
        }
        for(ChunkPos position:desired)if((!VehicleTransferAdapters.plot(level,position)&&level.getChunkSource().getChunkNow(position.x,position.z)==null)||!level.areEntitiesLoaded(position.toLong()))return false;
        return true;
    }
    public Set<ChunkPos> positions(){return Set.copyOf(desired);}
    public boolean resident(){for(ChunkPos pos:desired)if((!VehicleTransferAdapters.plot(level,pos)&&level.getChunkSource().getChunkNow(pos.x,pos.z)==null)||!level.areEntitiesLoaded(pos.toLong()))return false;return true;}
    @Override public void close(){for(ChunkPos position:held)level.getChunkSource().removeRegionTicket(TYPE,position,1,transaction);held.clear();}
}
