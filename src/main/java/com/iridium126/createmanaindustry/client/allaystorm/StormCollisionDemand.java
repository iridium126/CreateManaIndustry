package com.iridium126.createmanaindustry.client.allaystorm;

import java.util.*;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Section;

/** Preserve retired geometry for the vanilla twenty-tick MODEL corpse window. */
public final class StormCollisionDemand {
    private Set<Section> active=Set.of();
    private final Map<Section,Long> retired=new HashMap<>();
    private long nextExpiry=Long.MAX_VALUE;
    public boolean due(long tick){return tick>=nextExpiry;}
    public Set<Section> update(Set<Section> next,long tick){
        for(Section key:active)if(!next.contains(key))retired.put(key,tick+20);
        retired.entrySet().removeIf(e->next.contains(e.getKey())||e.getValue()<=tick);
        active=next;nextExpiry=retired.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
        var union=new LinkedHashSet<>(next);union.addAll(retired.keySet());return union;
    }
}
