package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarfallSnapshotSyncTest {
    private final UUID first = new UUID(0, 1), second = new UUID(0, 2), observer = new UUID(0, 3), other = new UUID(0, 4);

    @Test void observersShareOneRequestAndOnlyAssignedSourceCanReply() {
        var sync = new StarfallSnapshotSync(); sync.created(1, List.of(first, second)); sync.observe(1, observer); sync.observe(1, other);
        var request = sync.poll(100, first).getFirst();
        assertEquals(first, request.source()); assertTrue(sync.poll(101, first).isEmpty());
        assertTrue(sync.accept(1, request.token(), second, 101).isEmpty());
        assertTrue(sync.accept(1, request.token() + 1, first, 101).isEmpty());
        assertEquals(Set.of(observer, other), sync.accept(1, request.token(), first, 101));
        assertTrue(sync.accept(1, request.token(), first, 101).isEmpty());
        sync.observe(1, observer); assertTrue(sync.poll(102, first).isEmpty());
    }

    @Test void timeoutRotatesSourceAndDisconnectInvalidatesOldReply() {
        var sync = new StarfallSnapshotSync(); sync.created(1, List.of(first, second)); sync.observe(1, observer);
        var old = sync.poll(100, first).getFirst(); var replacement = sync.poll(120, first).getFirst();
        assertEquals(second, replacement.source()); assertTrue(sync.accept(1, old.token(), first, 120).isEmpty());
        sync.retain(Set.of(1), Set.of(first, observer));
        assertTrue(sync.accept(1, replacement.token(), second, 121).isEmpty());
        assertEquals(first, sync.poll(121, first).getFirst().source());
        sync.retain(Set.of(1), Set.of(observer)); assertEquals(Set.of(1), sync.unavailable());
        sync.clear(); assertTrue(sync.poll(200, first).isEmpty());
    }

    @Test void retiredColumnsAndDepartedObserversCannotReceiveSnapshots() {
        var sync = new StarfallSnapshotSync(); sync.created(1, List.of(first)); sync.observe(1, observer);
        var request = sync.poll(1, first).getFirst(); sync.retain(Set.of(), Set.of(first, observer));
        assertTrue(sync.accept(1, request.token(), first, 2).isEmpty());
        sync.created(2, List.of(first)); sync.observe(2, observer); request = sync.poll(2, first).getFirst();
        sync.retain(Set.of(2), Set.of(first)); assertTrue(sync.accept(2, request.token(), first, 3).isEmpty());
    }
}
