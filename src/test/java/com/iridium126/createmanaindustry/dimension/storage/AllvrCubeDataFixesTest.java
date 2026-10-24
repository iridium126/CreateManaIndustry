package com.iridium126.createmanaindustry.dimension.storage;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllvrCubeDataFixesTest {
    @Test void onlySchemaThreeIsAcceptedWithoutMigration() throws Exception {
        var tag = new CompoundTag(); tag.putInt("AllvrFormatVersion", 3);
        assertSame(tag, AllvrCubeDataFixes.update(tag));
        for (int version : new int[]{0, 1, 2, 4, Integer.MAX_VALUE}) {
            tag.putInt("AllvrFormatVersion", version);
            assertThrows(AllvrCubeCorruptedException.class, () -> AllvrCubeDataFixes.update(tag));
            assertEquals(version, tag.getInt("AllvrFormatVersion"), "rejected records must remain untouched");
        }
    }
    @Test void missingSchemaFailsClosed() {
        assertThrows(AllvrCubeCorruptedException.class, () -> AllvrCubeDataFixes.update(new CompoundTag()));
    }
}
