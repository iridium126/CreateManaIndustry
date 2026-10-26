package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import dev.ryanhcode.sable.sublevel.storage.holding.*;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.UUID;
@Mixin(SubLevelHoldingChunkMap.class)
public interface RiftHoldingAccessor {
    @Accessor("loadedHoldingChunks") Long2ObjectMap<SubLevelHoldingChunk> cmi$chunks();
    @Accessor("queuedDeletion") ObjectSet<GlobalSavedSubLevelPointer> cmi$deleted();
    @Accessor("allHoldingSubLevels") Object2ObjectMap<UUID,HoldingSubLevel> cmi$holding();
}
