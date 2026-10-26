package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import dev.ryanhcode.sable.sublevel.storage.region.*;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.io.IOException;
@Mixin(SubLevelStorage.class)
public interface RiftStorageAccessor {
    @Invoker("getRegionFile") SubLevelRegionFile cmi$region(ChunkPos position)throws IOException;
    @Invoker("getRegionStorageFile") SubLevelStorageFile cmi$data(ChunkPos position,int index)throws IOException;
}
