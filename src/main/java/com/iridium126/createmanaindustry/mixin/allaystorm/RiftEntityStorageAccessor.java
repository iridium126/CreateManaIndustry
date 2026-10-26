package com.iridium126.createmanaindustry.mixin.allaystorm;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(EntityStorage.class)
public interface RiftEntityStorageAccessor {
    @Accessor("simpleRegionStorage") SimpleRegionStorage cmi$region();
}
