package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;

/** Pending handback only. Never interpret an in-flight pre-admission native teleport as a
 * server recovery, and never replace a confirmed server recovery with an older GPU cache. */
public final class PackageFreeNativeRecovery {
    public record Pose(double x,double y,double z,float yaw,float pitch,boolean ground) {
        public Pose {if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("Native recovery pose");}
    }
    private final PackageRegion region;
    private final long epoch;
    private final PackageAuthorityRegion.Baseline expected;
    private volatile boolean released;
    private volatile Pose pose;
    public PackageFreeNativeRecovery(PackageRegion region,long epoch,PackageAuthorityRegion.Baseline expected) {
        this.region=java.util.Objects.requireNonNull(region);this.expected=java.util.Objects.requireNonNull(expected);
        if(epoch<=0)throw new IllegalArgumentException("Native recovery epoch");this.epoch=epoch;
    }
    public boolean released(PackageRegion candidateRegion,long candidateEpoch,PackageAuthorityRegion.Baseline candidate) {
        if(candidate==null||!region.equals(candidateRegion)||epoch!=candidateEpoch
                ||expected.index()!=candidate.index()||!expected.identity().equals(candidate.identity())
                ||expected.leaseEpoch()!=candidate.leaseEpoch()||candidate.revision()<expected.revision())return false;
        released=true;return true;
    }
    public void teleported(Pose value){if(released)pose=java.util.Objects.requireNonNull(value);}
    public Pose pose(){return pose;}
}
