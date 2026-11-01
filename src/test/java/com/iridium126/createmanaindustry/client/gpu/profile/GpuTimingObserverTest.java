package com.iridium126.createmanaindustry.client.gpu.profile;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GpuTimingObserverTest {
    @AfterEach void release(){GpuTimingObserver.install(null);}
    @Test void noObservationWithoutOptIn(){
        assertFalse(GpuTimingObserver.active());GpuTimingObserver.sample("draw",1,7,1,2);
    }
    @Test void tagsAndUnavailableNumbersArePreserved(){
        var seen=new ArrayList<List<Object>>();GpuTimingObserver.install((label,frame,generation,cpu,gpu)->seen.add(List.of(label,frame,generation,cpu,gpu)));
        GpuTimingObserver.sample("update.model",14,9,Double.NaN,.7);
        assertEquals(List.of("update.model",14L,9L,Double.NaN,.7),seen.getFirst());
        GpuTimingObserver.sample(null,14,9,1,1);GpuTimingObserver.sample("old",-1,9,1,1);assertEquals(1,seen.size());
    }
    @Test void aSecondCollectorCannotStealAnActiveWindow(){
        GpuTimingObserver.Sink owner=(label,frame,generation,cpu,gpu)->{};GpuTimingObserver.install(owner);GpuTimingObserver.install(owner);
        assertThrows(IllegalStateException.class,()->GpuTimingObserver.install((label,frame,generation,cpu,gpu)->{}));
    }
}
