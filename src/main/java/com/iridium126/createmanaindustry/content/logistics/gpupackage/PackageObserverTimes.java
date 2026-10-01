package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.Arrays;

/** Immutable primitive timestamps shared by journal batches and wire payloads. */
public final class PackageObserverTimes {
    private final long[] ticks;
    public PackageObserverTimes(long... ticks) {
        if(ticks==null || ticks.length>PackageDeltaCodec.MAX_ENTRIES)throw new IllegalArgumentException("Observer time count");
        this.ticks=ticks.clone();for(long tick:this.ticks)if(tick<0)throw new IllegalArgumentException("Observer state tick");
    }
    public static PackageObserverTimes zeros(int count) {
        if(count<0 || count>PackageDeltaCodec.MAX_ENTRIES)throw new IllegalArgumentException("Observer time count");
        return new PackageObserverTimes(new long[count]);
    }
    public int size(){return ticks.length;}
    public long get(int index){return ticks[index];}
    @Override public boolean equals(Object other){return other instanceof PackageObserverTimes times && Arrays.equals(ticks,times.ticks);}
    @Override public int hashCode(){return Arrays.hashCode(ticks);}
    @Override public String toString(){return Arrays.toString(ticks);}
}
