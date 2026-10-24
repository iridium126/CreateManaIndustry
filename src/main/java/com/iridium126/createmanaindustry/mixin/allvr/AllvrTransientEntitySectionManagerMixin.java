package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.cube.*;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.*;

@Mixin(TransientEntitySectionManager.class)
public abstract class AllvrTransientEntitySectionManagerMixin implements AllvrTransientEntityManagerAccess {
    @Shadow @Final private EntitySectionStorage<Entity> sectionStorage;
    @Shadow @Final private LevelCallback<Entity> callbacks;
    @Override public void allvr$syncSections() {
        var storage = (AllvrEntitySectionStorage<?>) sectionStorage;
        for (long column : storage.getAllChunksWithExistingSections()) storage.getExistingSectionPositionsInChunk(column).forEach(key -> {
            var coord = storage.coordinate(key);
            if (AllvrDimensionLimits.isVanillaSection(coord.y())) return;
            var section = sectionStorage.getSection(key);
            var desired = AllvrClientCubeCache.isLoaded(coord.origin()) ? Visibility.TICKING : Visibility.TRACKED;
            var previous = section.updateChunkStatus(desired);
            if (previous.isTicking() == desired.isTicking()) return;
            section.getEntities().filter(entity -> !entity.isAlwaysTicking()).forEach(desired.isTicking() ? callbacks::onTickingStart : callbacks::onTickingEnd);
        });
    }
}
