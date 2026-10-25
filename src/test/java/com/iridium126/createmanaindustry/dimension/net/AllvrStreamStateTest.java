package com.iridium126.createmanaindustry.dimension.net;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllvrStreamStateTest {
    @Test void firstSnapshotReplaysChangesReceivedWhileDecoding() {
        var state = new AllvrStreamState<String>();
        var values = new ArrayList<String>();
        assertTrue(state.begin(1, 10));
        state.update(1, 11, "placed", values::add);
        state.update(1, 12, "edited NBT", values::add);
        assertTrue(values.isEmpty());
        assertTrue(state.complete(1, 10, values::add));
        assertEquals(List.of("placed", "edited NBT"), values);
        assertFalse(state.update(1, 12, "duplicate", values::add));
    }
    @Test void forgetAndReentryRejectLateSnapshotAndOldDelta() {
        var state = new AllvrStreamState<String>();
        state.begin(1, 0);
        state.forget(1);
        assertFalse(state.begin(1, 0));
        state.begin(2, 0);
        assertFalse(state.complete(1, 0, value -> fail()));
        assertFalse(state.update(1, 1, "old", value -> fail()));
        assertTrue(state.complete(2, 0, value -> fail()));
    }
    @Test void supersededFullCannotPublishAfterNewerFullStarted() {
        var state = new AllvrStreamState<String>();
        state.begin(1, 10); state.begin(1, 12);
        assertFalse(state.canComplete(1, 10));
        assertTrue(state.canComplete(1, 12));
        assertFalse(state.complete(1, 10, value -> fail()));
        assertTrue(state.complete(1, 12, value -> fail()));
    }
    @Test void gapAndOverflowRequestResyncInsteadOfApplyingPartialState() {
        var state = new AllvrStreamState<Integer>();
        state.begin(1, 0);
        state.update(1, 2, 2, value -> fail());
        assertFalse(state.complete(1, 0, value -> fail()));
        assertTrue(state.needsResync());
        state.begin(2, 0);
        for (int i = 1; i <= AllvrStreamState.MAX_BUFFERED_UPDATES + 1; i++) state.update(2, i, i, value -> fail());
        assertTrue(state.needsResync());
    }
}
