package com.iridium126.createmanaindustry.dimension;

import com.iridium126.createmanaindustry.dimension.cube.*;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllvrEntitySectionStorageTest {
    private record Fake(int getId, BlockPos blockPosition) implements EntityAccess {
        public UUID getUUID() { return new UUID(0, getId); }
        public AABB getBoundingBox() { return new AABB(blockPosition); }
        public void setLevelCallback(EntityInLevelCallback callback) {}
        public Stream<Fake> getSelfAndPassengers() { return Stream.of(this); }
        public Stream<Fake> getPassengersAndSelf() { return Stream.of(this); }
        public void setRemoved(Entity.RemovalReason reason) {}
        public boolean shouldBeSaved() { return true; }
        public boolean isAlwaysTicking() { return false; }
    }
    @Test void sameColumnAtDistantHeightsDoesNotAliasAndQueriesAreLocal() {
        var storage = new AllvrEntitySectionStorage<Fake>(Fake.class, key -> Visibility.TRACKED);
        storage.enable(coord -> Visibility.TICKING);
        int[] heights = {-30_000_000, -16_777_216, -8_388_608, 0, 8_388_608, 16_777_216, 30_000_000};
        var handles = new HashSet<Long>();
        for (int y : heights) {
            var fake = new Fake(y, new BlockPos(0, y, 0));
            long handle = storage.key(fake.blockPosition()); assertTrue(handles.add(handle));
            storage.getOrCreateSection(handle).add(fake);
        }
        assertEquals(heights.length, storage.count());
        assertEquals(heights.length, storage.getExistingSectionPositionsInChunk(0).count());
        var found = new ArrayList<Fake>();
        storage.getEntities(new AABB(-1, 16_777_215, -1, 2, 16_777_219, 2), entity -> {
            found.add(entity); return AbortableIterationConsumer.Continuation.CONTINUE;
        });
        assertEquals(1, found.size()); assertEquals(16_777_216, found.getFirst().getId());
        for (long handle : handles) storage.remove(handle);
        assertEquals(0, storage.identityCount()); assertEquals(0, storage.count());
        assertTrue(storage.getAllChunksWithExistingSections().isEmpty());
        assertTrue(storage.key(new BlockPos(0, 0, 0)) > Collections.max(handles));
    }
}
