package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.cube.*;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TransientEntitySectionManager.class)
public abstract class AllvrTransientEntitySectionManagerMixin implements AllvrTransientEntityManagerAccess, AllvrEntityManagerAccess {
    @Shadow @Final private EntitySectionStorage<Entity> sectionStorage;
    @Shadow @Final private LevelCallback<Entity> callbacks;

    @Override public AllvrEntitySectionStorage<Entity> allvr$sections() {
        return (AllvrEntitySectionStorage<Entity>) sectionStorage;
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/world/level/entity/EntitySectionStorage"))
    private <T extends EntityAccess> EntitySectionStorage<T> allvr$storage(Class<T> type,
            Long2ObjectFunction<Visibility> visibility, Operation<EntitySectionStorage<T>> original) {
        return new AllvrEntitySectionStorage<>(type, visibility);
    }

    @WrapOperation(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;asLong(Lnet/minecraft/core/BlockPos;)J"))
    private long allvr$sectionKey(BlockPos pos, Operation<Long> original) {
        var sections = allvr$sections();
        return sections.enabled() ? sections.key(pos) : original.call(pos);
    }

    @WrapOperation(method = {"startTicking(Lnet/minecraft/world/level/ChunkPos;)V", "stopTicking(Lnet/minecraft/world/level/ChunkPos;)V"},
            require = 2, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntitySectionStorage;getExistingSectionsInChunk(J)Ljava/util/stream/Stream;"))
    private Stream<EntitySection<Entity>> allvr$nativeSections(EntitySectionStorage<Entity> storage, long column,
            Operation<Stream<EntitySection<Entity>>> original) {
        var sections = allvr$sections();
        return sections.enabled() ? sections.nativeSectionsInChunk(column) : original.call(storage, column);
    }

    @Override public void allvr$syncSections() {
        var storage = allvr$sections();
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
