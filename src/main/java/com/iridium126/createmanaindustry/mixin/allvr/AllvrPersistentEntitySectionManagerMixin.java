package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrEntitySectionManagerDuck;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Adds the missing Y-aware visibility transition for cube entity sections.
 * The vanilla manager keys visibility by X/Z {@code ChunkPos}; that is enough
 * for columns, but it makes a cube entity either hidden forever or tick even
 * after its independent 32-block cube has been unloaded.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class AllvrPersistentEntitySectionManagerMixin
    implements AllvrEntitySectionManagerDuck {

    @Shadow @Final private EntitySectionStorage<Entity> sectionStorage;
    @Shadow @Final LevelCallback<Entity> callbacks;
    @Shadow @Final private EntityLookup<Entity> visibleEntityStorage;
    @Shadow @Final private Long2ObjectMap<Visibility> chunkVisibility;
    @Shadow @Final private LongSet chunksToUnload;

    @Override
    public void allvr$syncCubeEntityTicking(AllvrCubeMap map) {
        LongSet cubeChunks = new LongOpenHashSet();
        LongSet loadedCubeChunks = new LongOpenHashSet();
        LongSet chunks = this.sectionStorage.getAllChunksWithExistingSections();
        for (long chunkKey : chunks) {
            this.sectionStorage.getExistingSectionPositionsInChunk(chunkKey)
                .forEach(sectionKey -> {
                    if (this.allvr$syncSection(sectionKey, map)) {
                        long cubeChunk = ChunkPos.asLong(SectionPos.x(sectionKey), SectionPos.z(sectionKey));
                        cubeChunks.add(cubeChunk);
                        loadedCubeChunks.add(cubeChunk);
                    } else if (this.allvr$isCubeSection(sectionKey)) {
                        cubeChunks.add(ChunkPos.asLong(SectionPos.x(sectionKey), SectionPos.z(sectionKey)));
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
        int sectionY = SectionPos.y(sectionKey);
        return !AllvrDimensionLimits.isVanillaSection(sectionY);
    }

    private boolean allvr$syncSection(long sectionKey, AllvrCubeMap map) {
        int sectionY = SectionPos.y(sectionKey);
        if (!this.allvr$isCubeSection(sectionKey)) {
            return false;
        }
        EntitySection<Entity> section = this.sectionStorage.getSection(sectionKey);
        if (section == null || section.isEmpty()) {
            return false;
        }

        BlockPos sample = new BlockPos(
            SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey)),
            SectionPos.sectionToBlockCoord(sectionY),
            SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey)));
        boolean loaded = map.isLoaded(sample);
        Visibility desired = loaded ? Visibility.TICKING : Visibility.HIDDEN;
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
