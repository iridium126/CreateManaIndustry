package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.cube.*;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({PersistentEntitySectionManager.class, TransientEntitySectionManager.class})
public abstract class AllvrEntitySectionStorageMixin implements AllvrEntityManagerAccess {
    @Shadow(remap = false) @Final private EntitySectionStorage<?> sectionStorage;
    @Override public AllvrEntitySectionStorage<?> allvr$sections() { return (AllvrEntitySectionStorage<?>) sectionStorage; }
    @WrapOperation(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/world/level/entity/EntitySectionStorage"))
    private <T extends EntityAccess> EntitySectionStorage<T> allvr$storage(Class<T> type, Long2ObjectFunction<Visibility> visibility, Operation<EntitySectionStorage<T>> original) {
        return new AllvrEntitySectionStorage<>(type, visibility);
    }
    @WrapOperation(method = {"addEntity", "addEntityWithoutEvent"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;asLong(Lnet/minecraft/core/BlockPos;)J"))
    private long allvr$key(BlockPos pos, Operation<Long> original) { return allvr$sections().key(pos); }
    @WrapOperation(method = {"updateChunkStatus", "startTicking", "stopTicking"}, require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntitySectionStorage;getExistingSectionsInChunk(J)Ljava/util/stream/Stream;"))
    private Stream<?> allvr$nativeSections(EntitySectionStorage<?> storage, long column, Operation<Stream<?>> original) {
        if (!allvr$sections().enabled()) return original.call(storage, column);
        return storage.getExistingSectionPositionsInChunk(column)
            .filter(key -> com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaSection(allvr$sections().coordinate(key).y()))
            .mapToObj(storage::getSection);
    }
    @WrapOperation(method = "*", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;x(J)I"))
    private int allvr$debugX(long key, Operation<Integer> original) { return allvr$sections().enabled() ? allvr$sections().coordinate(key).x() : original.call(key); }
    @WrapOperation(method = "*", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;y(J)I"))
    private int allvr$debugY(long key, Operation<Integer> original) { return allvr$sections().enabled() ? allvr$sections().coordinate(key).y() : original.call(key); }
    @WrapOperation(method = "*", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;z(J)I"))
    private int allvr$debugZ(long key, Operation<Integer> original) { return allvr$sections().enabled() ? allvr$sections().coordinate(key).z() : original.call(key); }

}
