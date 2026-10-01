package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageFreePickQueueTest {
    private static final PackagePoseQueryGpu.Ray RAY=new PackagePoseQueryGpu.Ray(0,0,-2,0,0,4);
    private static PackagePoseQueryGpu.Result hit(int flags,float state,float fraction) {
        return new PackagePoseQueryGpu.Result(0x100000005L,0x200000007L,17,9,flags,-1,
                1,2,3,4,0,0,0,state,0,0,0,0,fraction,.5f,1,.5f,0,0,0,0,1,2,3,4);
    }
    private static PackagePoseQueryGpu.Completed completed(Object tag,PackagePoseQueryGpu.Result result) {
        return new PackagePoseQueryGpu.Completed(0,4,0,PackagePoseQueryGpu.Kind.PICK,tag,List.of(result));
    }
    @Test void busyQueryKeepsOneInputAndNativeReplayCanOnlyTakeItOnce() {
        var queue=new PackageFreePickQueue(7,()->0);
        assertTrue(queue.enqueue(PackageFreePickQueue.Action.USE,RAY,"first"));var input=queue.queued();
        assertFalse(queue.enqueue(PackageFreePickQueue.Action.ATTACK,RAY,"second"));assertSame(input,queue.queued());
        assertTrue(queue.submitted(input));assertFalse(queue.submitted(input));assertNull(queue.queued());
        assertTrue(queue.completed(completed(input,hit(0,0,.25f))));assertEquals(PackageFreePickQueue.Phase.READY,queue.phase());
        assertEquals(0x200000007L,queue.result().generation());queue.clear();
        assertNull(queue.input());assertNull(queue.result());assertFalse(queue.completed(completed(input,hit(0,0,.25f))));
        assertTrue(queue.enqueue(PackageFreePickQueue.Action.ATTACK,RAY,"next"));assertTrue(queue.queued().sequence()>input.sequence());
    }
    @Test void clearAndLateCompletionCannotRetargetNewInputOrEpoch() {
        var queue=new PackageFreePickQueue(7,()->0);queue.enqueue(PackageFreePickQueue.Action.USE,RAY,"old");
        var old=queue.queued();queue.submitted(old);queue.clear();queue.enqueue(PackageFreePickQueue.Action.ATTACK,RAY,"new");
        var next=queue.queued();queue.submitted(next);
        assertFalse(queue.completed(completed(old,hit(0,0,.25f))));assertEquals(PackageFreePickQueue.Phase.QUERY,queue.phase());
        var other=new PackageFreePickQueue(8,()->0);other.enqueue(PackageFreePickQueue.Action.USE,RAY,"other");
        assertFalse(queue.completed(completed(other.queued(),hit(0,0,.25f))));
        assertTrue(queue.completed(completed(next,PackagePoseQueryGpu.Result.NONE)));
        assertEquals(PackageFreePickQueue.Phase.READY,queue.phase());assertFalse(queue.result().present());
    }
    @Test void twoTickTimeoutCoversQueuedInFlightAndCompletedWorkWithoutWaiting() {
        for(int stage=0;stage<3;stage++) {
            var clock=new AtomicLong();var queue=new PackageFreePickQueue(7,clock::get);queue.enqueue(PackageFreePickQueue.Action.USE,RAY,"input");
            var input=queue.queued();if(stage>0)queue.submitted(input);if(stage>1)queue.completed(completed(input,hit(0,0,.25f)));
            clock.set(PackageFreePickQueue.PROCESSING_NANOS);assertNotEquals(PackageFreePickQueue.Phase.TIMED_OUT,queue.phase());
            clock.incrementAndGet();assertEquals(PackageFreePickQueue.Phase.TIMED_OUT,queue.phase());assertNull(queue.result());
            assertFalse(queue.completed(completed(input,hit(0,0,.25f))));assertNull(queue.queued());
        }
    }
    @Test void onlyPickWithOneFreeVisibleActiveSegmentHitCanCompleteInput() {
        for(var invalid:List.of(hit(1,0,.25f),hit(4,0,.25f),hit(0,-3,.25f),hit(0,0,-.1f),hit(0,0,1),hit(0,0,Float.NaN),hit(0,Float.NaN,.25f))) {
            var queue=new PackageFreePickQueue(7,()->0);queue.enqueue(PackageFreePickQueue.Action.USE,RAY,"input");var input=queue.queued();queue.submitted(input);
            assertThrows(IllegalStateException.class,()->queue.completed(completed(input,invalid)));
        }
        var queue=new PackageFreePickQueue(7,()->0);queue.enqueue(PackageFreePickQueue.Action.USE,RAY,"input");var input=queue.queued();queue.submitted(input);
        assertFalse(queue.completed(new PackagePoseQueryGpu.Completed(0,4,0,PackagePoseQueryGpu.Kind.POSES,input,List.of(hit(0,0,.25f)))));
        assertThrows(IllegalStateException.class,()->queue.completed(new PackagePoseQueryGpu.Completed(0,4,0,PackagePoseQueryGpu.Kind.PICK,input,List.of())));
        assertTrue(queue.completed(completed(input,hit(0,0,0))));
    }
}
