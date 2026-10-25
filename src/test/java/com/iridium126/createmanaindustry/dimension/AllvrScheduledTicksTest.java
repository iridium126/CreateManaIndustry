package com.iridium126.createmanaindustry.dimension;

import com.iridium126.createmanaindustry.dimension.cube.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllvrScheduledTicksTest {
    @Test void pausedClockAndNbtRetainRemainingDelayAtHighY() {
        var ticks = new AllvrScheduledTicks<String>(() -> {});
        var pos = new BlockPos(2, 16_777_218, 3);
        ticks.schedule("water", pos, 3, TickPriority.NORMAL, 9);
        ticks.advance();
        var saved = ticks.save(value -> ResourceLocation.withDefaultNamespace(value));
        var restored = new AllvrScheduledTicks<String>(() -> {});
        restored.load(saved, AllvrCubePos.of(pos), id -> id.getPath());
        assertTrue(restored.has(pos, "water"));
        assertNull(restored.peekDue());
        restored.advance(); assertNull(restored.peekDue());
        restored.advance(); assertEquals(pos, restored.pollDue().pos());
        assertFalse(restored.has(pos, "water"));
    }
    @Test void deduplicationPriorityAndTriggerTimeMatchVanilla() {
        var ticks = new AllvrScheduledTicks<String>(() -> {});
        var a = new BlockPos(0, -30_000_000, 0); var b = a.above();
        assertTrue(ticks.schedule("block", a, 2, TickPriority.HIGH, 2));
        assertFalse(ticks.schedule("block", a, 1, TickPriority.EXTREMELY_HIGH, 0));
        ticks.schedule("block", b, 1, TickPriority.LOW, 1);
        ticks.advance(); assertEquals(b, ticks.pollDue().pos());
        assertNull(ticks.pollDue());
        ticks.advance(); assertEquals(a, ticks.pollDue().pos());
    }
}
