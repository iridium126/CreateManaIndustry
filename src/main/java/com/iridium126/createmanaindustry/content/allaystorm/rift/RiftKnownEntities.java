package com.iridium126.createmanaindustry.content.allaystorm.rift;
import com.iridium126.createmanaindustry.mixin.allaystorm.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
/** Plot entity sections may be hidden from the ordinary visible-entity lookup during cold recovery. */
public final class RiftKnownEntities {
    public static List<Entity> column(ServerLevel level,ChunkPos position){return ((RiftEntityManagerAccessor)((RiftLevelEntitiesAccessor)level).cmi$entities()).cmi$sections().getExistingSectionsInChunk(position.toLong()).flatMap(section->section.getEntities()).toList();}
    public static Entity find(ServerLevel level,UUID id,Collection<ChunkPos> positions){Entity visible=level.getEntity(id);if(visible!=null)return visible;for(ChunkPos p:positions)for(Entity e:column(level,p))if(e.getUUID().equals(id)&&!e.isRemoved())return e;return null;}
    public static boolean request(ServerLevel level,ChunkPos pos,RiftWorkBudget budget){if(level.areEntitiesLoaded(pos.toLong()))return true;var access=(RiftEntityManagerAccessor)((RiftLevelEntitiesAccessor)level).cmi$entities();if("PENDING".equals(String.valueOf(access.cmi$loadStates().get(pos.toLong()))))return false;if(budget!=null&&!budget.load())return false;access.cmi$request(pos.toLong());return false;}
    private RiftKnownEntities(){}
}
