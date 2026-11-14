package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrEntitySectionManagerDuck;
import com.iridium126.createmanaindustry.dimension.cube.AllvrEntityManagerAccess;
import com.iridium126.createmanaindustry.dimension.cube.AllvrEntitySectionStorage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import java.util.stream.Stream;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds the missing Y-aware visibility transition for cube entity sections.
 * The vanilla manager keys visibility by X/Z {@code ChunkPos}; that is enough
 * for columns, but it makes a cube entity either hidden forever or tick even
 * after its independent 32-block cube has been unloaded.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class AllvrPersistentEntitySectionManagerMixin
    implements AllvrEntitySectionManagerDuck, AllvrEntityManagerAccess {

    @Shadow @Final private EntitySectionStorage<Entity> sectionStorage;
    @Shadow @Final LevelCallback<Entity> callbacks;
    @Shadow @Final private EntityLookup<Entity> visibleEntityStorage;
    @Shadow @Final private Long2ObjectMap<Visibility> chunkVisibility;
    @Shadow @Final private LongSet chunksToUnload;

    @org.spongepowered.asm.mixin.Unique private long allvr$lastRevision = Long.MIN_VALUE;
    @Shadow protected abstract void ensureChunkQueuedForLoad(long key);
    @Shadow protected abstract boolean storeChunkSections(long key, java.util.function.Consumer<Entity> consumer);

    @Override public AllvrEntitySectionStorage<Entity> allvr$sections() {
        return (AllvrEntitySectionStorage<Entity>) sectionStorage;
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/world/level/entity/EntitySectionStorage"))
    private <T extends EntityAccess> EntitySectionStorage<T> allvr$storage(Class<T> type,
            Long2ObjectFunction<Visibility> visibility, Operation<EntitySectionStorage<T>> original) {
        return new AllvrEntitySectionStorage<>(type, visibility);
    }

    @WrapOperation(method = "addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;asLong(Lnet/minecraft/core/BlockPos;)J"))
    private long allvr$sectionKey(BlockPos pos, Operation<Long> original) {
        var sections = allvr$sections();
        return sections.enabled() ? sections.key(pos) : original.call(pos);
    }

    @WrapOperation(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/entity/Visibility;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntitySectionStorage;getExistingSectionsInChunk(J)Ljava/util/stream/Stream;"))
    private Stream<EntitySection<Entity>> allvr$nativeSections(EntitySectionStorage<Entity> storage, long column,
            Operation<Stream<EntitySection<Entity>>> original) {
        var sections = allvr$sections();
        return sections.enabled() ? sections.nativeSectionsInChunk(column) : original.call(storage, column);
    }

    @WrapOperation(method = "lambda$dumpSections$16(Lnet/minecraft/util/CsvOutput;Lnet/minecraft/world/level/entity/PersistentEntitySectionManager$ChunkLoadStatus;J)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;x(J)I"))
    private int allvr$dumpX(long key, Operation<Integer> original) {
        return allvr$sections().enabled() ? allvr$coordinate(key).x() : original.call(key);
    }

    @WrapOperation(method = "lambda$dumpSections$16(Lnet/minecraft/util/CsvOutput;Lnet/minecraft/world/level/entity/PersistentEntitySectionManager$ChunkLoadStatus;J)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;y(J)I"))
    private int allvr$dumpY(long key, Operation<Integer> original) {
        return allvr$sections().enabled() ? allvr$coordinate(key).y() : original.call(key);
    }

    @WrapOperation(method = "lambda$dumpSections$16(Lnet/minecraft/util/CsvOutput;Lnet/minecraft/world/level/entity/PersistentEntitySectionManager$ChunkLoadStatus;J)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;z(J)I"))
    private int allvr$dumpZ(long key, Operation<Integer> original) {
        return allvr$sections().enabled() ? allvr$coordinate(key).z() : original.call(key);
    }

    @Override public void allvr$requestEntityColumn(long key) { ensureChunkQueuedForLoad(key); }
    @org.spongepowered.asm.mixin.Unique
    private com.iridium126.createmanaindustry.dimension.cube.AllvrSectionCoord allvr$coordinate(long key) {
        return allvr$sections().coordinate(key);
    }
    @org.spongepowered.asm.mixin.injection.Inject(method = "processChunkUnload", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void allvr$keepResidentEntities(long key, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (!(sectionStorage instanceof com.iridium126.createmanaindustry.dimension.cube.AllvrEntitySectionStorage<?> storage) || !storage.enabled()) return;
        boolean resident = sectionStorage.getExistingSectionsInChunk(key).flatMap(EntitySection::getEntities).anyMatch(entity -> {
            if (!(entity.level() instanceof com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck duck)) return false;
            var map = duck.allvr$peekCubeMap();
            return map != null && !map.isClosed() && !AllvrDimensionLimits.isVanillaY(entity.blockPosition().getY()) && map.isLoaded(entity.blockPosition());
        });
        if (resident) cir.setReturnValue(storeChunkSections(key, entity -> {}));
    }
    @Override
    public void allvr$syncCubeEntityTicking(AllvrCubeMap map) {
        long revision = map.entityRevision() + ((com.iridium126.createmanaindustry.dimension.cube.AllvrEntitySectionStorage<?>) sectionStorage).revision();
        if (allvr$lastRevision == revision) return;
        allvr$lastRevision = revision;
        LongSet cubeChunks = new LongOpenHashSet();
        LongSet loadedCubeChunks = new LongOpenHashSet();
        LongSet chunks = this.sectionStorage.getAllChunksWithExistingSections();
        for (long chunkKey : chunks) {
            this.sectionStorage.getExistingSectionPositionsInChunk(chunkKey)
                .forEach(sectionKey -> {
                    if (this.allvr$syncSection(sectionKey, map)) {
                        long cubeChunk = ChunkPos.asLong(allvr$coordinate(sectionKey).x(), allvr$coordinate(sectionKey).z());
                        cubeChunks.add(cubeChunk);
                        loadedCubeChunks.add(cubeChunk);
                    } else if (this.allvr$isCubeSection(sectionKey)) {
                        cubeChunks.add(ChunkPos.asLong(allvr$coordinate(sectionKey).x(), allvr$coordinate(sectionKey).z()));
                    }
                });
        }

        /*
         * Vanilla's visibility map is column-only.  A hidden native column
         * therefore leaves its key in chunksToUnload even when a loaded cube
         * section in that column must remain resident.  Keep the column alive
         * while any cube section is loaded; when the last such cube unloads,
         * put the key back so vanilla can persist and remove the section.
         */
        for (long cubeChunk : loadedCubeChunks) {
            if (!this.chunkVisibility.containsKey(cubeChunk)) {
                this.chunksToUnload.remove(cubeChunk);
            }
        }
        for (long cubeChunk : cubeChunks) {
            if (!loadedCubeChunks.contains(cubeChunk)
                && !this.chunkVisibility.containsKey(cubeChunk)) {
                this.chunksToUnload.add(cubeChunk);
            }
        }
    }

    private boolean allvr$isCubeSection(long sectionKey) {
        int sectionY = allvr$coordinate(sectionKey).y();
        return !AllvrDimensionLimits.isVanillaSection(sectionY);
    }

    private boolean allvr$syncSection(long sectionKey, AllvrCubeMap map) {
        int sectionY = allvr$coordinate(sectionKey).y();
        if (!this.allvr$isCubeSection(sectionKey)) {
            return false;
        }
        EntitySection<Entity> section = this.sectionStorage.getSection(sectionKey);
        if (section == null || section.isEmpty()) {
            return false;
        }

        BlockPos sample = new BlockPos(
            SectionPos.sectionToBlockCoord(allvr$coordinate(sectionKey).x()),
            SectionPos.sectionToBlockCoord(sectionY),
            SectionPos.sectionToBlockCoord(allvr$coordinate(sectionKey).z()));
        boolean loaded = map.isLoaded(sample);
        Visibility desired = !loaded ? Visibility.HIDDEN : map.isSimulating(sample) ? Visibility.TICKING : Visibility.TRACKED;
        Visibility previous = section.updateChunkStatus(desired);
        if (previous == desired) {
            return loaded;
        }

        boolean wasAccessible = previous.isAccessible();
        boolean isAccessible = desired.isAccessible();
        boolean wasTicking = previous.isTicking();
        boolean isTicking = desired.isTicking();
        if (wasTicking && !isTicking) {
            section.getEntities()
                .filter(entity -> !entity.isAlwaysTicking())
                .forEach(this.callbacks::onTickingEnd);
        }
        if (wasAccessible && !isAccessible) {
            section.getEntities()
                .filter(entity -> !entity.isAlwaysTicking())
                .forEach(entity -> {
                    this.callbacks.onTrackingEnd(entity);
                    this.visibleEntityStorage.remove(entity);
                });
        } else if (!wasAccessible && isAccessible) {
            section.getEntities()
                .filter(entity -> !entity.isAlwaysTicking())
                .forEach(entity -> {
                    this.visibleEntityStorage.add(entity);
                    this.callbacks.onTrackingStart(entity);
                });
        }
        if (!wasTicking && isTicking) {
            section.getEntities()
                .filter(entity -> !entity.isAlwaysTicking())
                .forEach(this.callbacks::onTickingStart);
        }
        return loaded;
    }

}
