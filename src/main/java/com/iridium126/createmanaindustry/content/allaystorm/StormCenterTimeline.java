package com.iridium126.createmanaindustry.content.allaystorm;

/** GPU-independent, bounded two-sample stream shared by members and distant sky effects. */
public final class StormCenterTimeline {
    public static final int LAG_TICKS = AllayStormManager.CENTER_INTERVAL_TICKS;
    public record Frame(double x,double y,double z,double count) {
        public double radius() { return AllayStormData.vortexRadius(count); }
        public double envelopeRadius() { return 1.2 * radius(); }
    }
    private StormIdentity identity;
    private final java.util.Set<StormIdentity> retired=new java.util.HashSet<>();
    private StormCenterSample previous,current;
    public StormIdentity identity() { return identity; }
    public StormCenterSample previous() { return previous; }
    public StormCenterSample current() { return current; }
    /** Epochs are hashes, not ordered generation counters. Selection comes from server lifecycle. */
    public boolean select(StormIdentity id) {
        if(id==null||retired.contains(id))return false;
        if(identity!=null&&!identity.equals(id)){retired.add(identity);previous=current=null;}
        identity=id;return true;
    }
    public void retire(StormIdentity id) {
        if(id==null)return;
        retired.add(id);
        // Keep the last bounded samples for the existing STOP cloud fade. Retired packets
        // cannot advance them, and selecting a new generation replaces both samples.
    }
    public boolean offer(StormIdentity id,StormCenterSample sample) {
        if(id==null||sample==null||!sample.valid()||!select(id))return false;
        if(current!=null&&sample.tick()<=current.tick())return false;
        identity=id;previous=current==null?sample:current;current=sample;return true;
    }
    public void offer(StormIdentity id,StormCenterSample before,StormCenterSample after) {
        // A late subscription needs both canonical samples, not a sample invented on arrival.
        if(id==null||retired.contains(id)||before==null||after==null||!before.valid()||!after.valid()||before.tick()>after.tick())return;
        if(contains(id)&&current.tick()==after.tick()&&previous.tick()==current.tick()&&before.tick()<current.tick()) {
            previous=before;return;
        }
        offer(id,before);offer(id,after);
    }
    public boolean contains(StormIdentity id) { return current!=null&&identity.equals(id); }
    public Frame sample(long tick,float partial) {
        if(current==null)return null;
        double span=current.tick()-previous.tick();
        // Subtract integer ticks before converting to double, retaining precision on old saves.
        double alpha=span<=0?1:Math.clamp(((tick-previous.tick())+Math.clamp(partial,0,1)-LAG_TICKS)/span,0,1);
        return new Frame(previous.x()+(current.x()-previous.x())*alpha,
                previous.y()+(current.y()-previous.y())*alpha,previous.z()+(current.z()-previous.z())*alpha,
                previous.count()+(current.count()-previous.count())*alpha);
    }
    public void clear() { identity=null;previous=null;current=null;retired.clear(); }
}
