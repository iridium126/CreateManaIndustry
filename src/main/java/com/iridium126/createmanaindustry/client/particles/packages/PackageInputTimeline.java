package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageTickTiming;

/** Client captures run at min(20, target rate), independently of synchronized world time.
 * One capture may describe several game steps, with a distinct pose interval for each. */
public final class PackageInputTimeline {
    public record Range(long first,long last) {public int size(){return Math.toIntExact(last-first+1);}}
    private Range current=new Range(0,0);
    private double fractional;
    public Range current(){return current;}
    public Range advance(double rate){
        fractional+=Math.max(1,PackageTickTiming.rate(rate)/20);
        int count=(int)Math.floor(fractional);fractional-=count;
        current=new Range(Math.incrementExact(current.last()),Math.addExact(current.last(),count));
        return current;
    }
}
