package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftEntityDiskIndex;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(EntityStorage.class)
public abstract class RiftEntityStoreObservationMixin {
    @Inject(method="storeEntities",at=@At("TAIL"))
    private void cmi$indexed(ChunkEntities<Entity> entities,CallbackInfo ci){RiftEntityDiskIndex.changed(((RiftEntityStorageAccessor)this).cmi$region(),entities.getPos(),entities.getEntities());}
}
