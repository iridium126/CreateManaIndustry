package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;

/** Immutable input maps and reference counts, independent of world/GL objects. */
final class PackageCollisionHistory {
    private final LinkedHashMap<Long,Map<PackageCollisionCache.Section,Long>> frames=new LinkedHashMap<>();
    private final Map<PackageCollisionGpu.GeometryRevision,Integer> references=new HashMap<>();
    private Set<PackageCollisionGpu.GeometryRevision> retained=Set.of();
    private boolean dirty;
    private long consumedThrough=Long.MIN_VALUE;
    boolean contains(long tick){return frames.containsKey(tick);}
    Map<PackageCollisionCache.Section,Long> get(long tick){return frames.get(tick);}
    void capture(long first,long last,Map<PackageCollisionCache.Section,Long> versions,int capacity) {
        if(capacity<1||last<first||last-first>=capacity)throw new IllegalArgumentException("World geometry history range");
        var immutable=Map.copyOf(versions);
        for(long tick=first;tick<=last;tick++)if(frames.putIfAbsent(tick,immutable)==null&&tick>consumedThrough)reference(immutable,1);
        while(frames.size()>capacity){
            long tick=frames.keySet().iterator().next();var expired=frames.remove(tick);
            if(tick>consumedThrough)reference(expired,-1);
        }
    }
    /** Submitted GPU tables keep their own fences. Input retention is needed
     * only until a tick is consumed, not until its diagnostic map expires. */
    void consumed(long tick) {
        if(tick<=consumedThrough)return;
        for(var frame:frames.entrySet())if(frame.getKey()>consumedThrough&&frame.getKey()<=tick)reference(frame.getValue(),-1);
        consumedThrough=tick;
    }
    private void reference(Map<PackageCollisionCache.Section,Long> versions,int change) {
        versions.forEach((section,revision)->{
            var key=new PackageCollisionGpu.GeometryRevision(section,revision);
            int old=references.getOrDefault(key,0),next=old+change;
            if(next<0)throw new IllegalStateException("Missing world geometry history reference");
            if(next==0){references.remove(key);dirty=true;}
            else {references.put(key,next);if(old==0)dirty=true;}
        });
    }
    Set<PackageCollisionGpu.GeometryRevision> retained() {
        if(dirty){retained=Set.copyOf(references.keySet());dirty=false;}
        return retained;
    }
}
