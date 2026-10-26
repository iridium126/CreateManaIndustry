package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import java.util.Set;
@Mixin(targets="net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class RiftTrackedEntityMixin implements RiftGroupTracking.Tracked {
    @Shadow @Final Entity entity;
    @Shadow @Final ServerEntity serverEntity;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;
    @Shadow public abstract void updatePlayer(ServerPlayer player);
    public void cmi$update(ServerPlayer player){updatePlayer(player);}
    @WrapMethod(method="updatePlayer")
    private void cmi$group(ServerPlayer player,Operation<Void> original) {
        if(player!=entity&&StormRiftTransfers.together(entity.getUUID(),player.getUUID())&&entity.broadcastToPlayer(player)){if(seenBy.add(player.connection))serverEntity.addPairing(player);}
        else original.call(player);
    }
}
