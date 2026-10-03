package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PackageSableCollisionCoordinatesTest {
    @Test void embeddedReaderReceivesCoordinatesRelativeToThePlotCenter() {
        var storage = new BlockPos(30_000_123, 128, -29_999_877);
        var center = new BlockPos(30_000_008, 96, -29_999_992);
        var target = new BlockPos.MutableBlockPos();

        assertSame(target, PackageSableCollisionCoordinates.contextPosition(storage, center, target));
        assertEquals(new BlockPos(115, 32, 115), target);
        assertEquals(storage, target.offset(center));
    }
}
