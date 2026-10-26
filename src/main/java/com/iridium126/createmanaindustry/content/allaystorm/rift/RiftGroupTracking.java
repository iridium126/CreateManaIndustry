package com.iridium126.createmanaindustry.content.allaystorm.rift;
import com.iridium126.createmanaindustry.mixin.allaystorm.RiftTrackingAccessor;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import java.util.*;
public final class RiftGroupTracking {
    public interface Tracked{void cmi$update(ServerPlayer player);}
    public static void publish(ServerLevel target,Collection<Entity> entities,List<ServerPlayer> players){var tracked=((RiftTrackingAccessor)target.getChunkSource().chunkMap).cmi$tracked();for(Entity entity:entities){Object entry=tracked.get(entity.getId());if(!(entry instanceof Tracked access))throw new IllegalStateException("Target entity tracking unavailable");for(ServerPlayer player:players)access.cmi$update(player);}}
    private RiftGroupTracking(){}
}
