package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableStore;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.sublevel.storage.holding.*;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(SubLevelHoldingChunkMap.class)
public abstract class RiftHoldingIoMixin {
    @WrapMethod(method="processChanges")
    private void cmi$poll(Operation<Void> original){if(!RiftSableStore.busy((SubLevelHoldingChunkMap)(Object)this))original.call();}
    @WrapMethod(method="saveAll")
    private void cmi$save(Operation<Void> original){if(!RiftSableStore.defer((SubLevelHoldingChunkMap)(Object)this,()->original.call()))original.call();}
    @WrapMethod(method="queueDeletion")
    private void cmi$delete(ServerSubLevel sub,Operation<Void> original){if(!RiftSableStore.defer((SubLevelHoldingChunkMap)(Object)this,()->original.call(sub)))original.call(sub);}
    @WrapMethod(method="moveToUnloaded")
    private void cmi$unload(ServerSubLevel sub,ChunkPos pos,Operation<Void> original){if(!RiftSableStore.defer((SubLevelHoldingChunkMap)(Object)this,()->original.call(sub,pos)))original.call(sub,pos);}
    @WrapMethod(method="snatchAndLoad")
    private void cmi$load(GlobalSavedSubLevelPointer p,UUID id,Operation<Void> original){if(!RiftSableStore.defer((SubLevelHoldingChunkMap)(Object)this,()->original.call(p,id)))original.call(p,id);}
}
