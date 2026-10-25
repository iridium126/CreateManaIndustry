package com.iridium126.createmanaindustry.client.allaystorm;

import java.util.*;
import java.util.function.LongSupplier;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

/** Resumable owner-thread capture. A dirty section keeps its previous complete version. */
public final class StormCollisionCache {
    public interface Source {
        boolean loaded(Section key);
        /** Already immutable package data / positively known empty section, or null. */
        Page snapshot(Section key,long revision);
        boolean solid(Section key,int index);
    }
    private static final class Entry {Page page;int cursor;int[] bits;long revision;boolean dirty=true;}
    private final Map<Section,Entry> entries=new HashMap<>();
    private final LinkedHashSet<Section> pending=new LinkedHashSet<>();
    private final LongSupplier clock;
    private long revision,version,spent,overruns,captured,reused;
    public StormCollisionCache(){this(System::nanoTime);}
    public StormCollisionCache(LongSupplier clock){this.clock=clock;}
    public void demand(Set<Section> keys){
        if(entries.keySet().removeIf(k->!keys.contains(k)))version++;pending.removeIf(k->!keys.contains(k));
        for(Section key:keys)if(!entries.containsKey(key)){var e=new Entry();e.revision=++revision;entries.put(key,e);pending.add(key);version++;}
    }
    public void dirty(Section key){var e=entries.get(key);if(e==null)return;e.dirty=true;e.cursor=0;e.bits=null;e.revision=++revision;pending.add(key);}
    public void unavailable(Section key){var e=entries.get(key);if(e==null)return;dirty(key);if(e.page!=null){e.page=null;version++;}}
    public void dirtyColumn(int x,int z){for(Section key:entries.keySet())if(key.x()==x&&key.z()==z)unavailable(key);}
    public void capture(Source source,long nanos){
        long begin=clock.getAsLong();int attempts=pending.size();
        while(attempts-->0&&!pending.isEmpty()&&clock.getAsLong()-begin<nanos){
            Section key=pending.iterator().next();pending.remove(key);var e=entries.get(key);if(e==null)continue;
            if(!source.loaded(key)){pending.add(key);continue;}
            if(e.cursor==0){var shared=source.snapshot(key,e.revision);if(shared!=null){e.page=shared;e.dirty=false;version++;reused++;continue;}e.bits=new int[WORDS];}
            while(e.cursor<4096){
                int until=Math.min(4096,e.cursor+32);
                for(;e.cursor<until;e.cursor++)if(source.solid(key,e.cursor))e.bits[e.cursor>>>5]|=1<<(e.cursor&31);
                if(clock.getAsLong()-begin>=nanos)break;
            }
            if(e.cursor==4096){e.page=page(e.revision,e.bits);e.bits=null;e.cursor=0;e.dirty=false;version++;captured++;}
            else pending.add(key);
        }
        spent=clock.getAsLong()-begin;if(spent>nanos)overruns++;
    }
    public Map<Section,Page> ready(){var out=new HashMap<Section,Page>();entries.forEach((key,e)->{if(e.page!=null)out.put(key,e.page);});return out;}
    public Page get(Section key){var e=entries.get(key);return e==null?null:e.page;}
    public long version(){return version;}
    public int requested(){return entries.size();}
    public int pending(){return pending.size();}
    public long spent(){return spent;}
    public String stats(){long known=entries.values().stream().filter(e->e.page!=null).count();return "requested="+entries.size()+" covered="+known+" unknown="+(entries.size()-known)+" pending="+pending.size()+" captureNs="+spent+" overruns="+overruns+" captured="+captured+" reused="+reused;}
    public String profilingReport(){long known=entries.values().stream().filter(e->e.page!=null).count();return String.format(java.util.Locale.ROOT,
            "coverage=%d/%d; pending=%d; capture=%.3f ms; captured/reused=%d/%d",known,entries.size(),pending.size(),spent/1e6,captured,reused)+(overruns==0?"":"; overruns="+overruns);}
}
