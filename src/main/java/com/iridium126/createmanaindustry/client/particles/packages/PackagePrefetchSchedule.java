package com.iridium126.createmanaindustry.client.particles.packages;

/** Changed inputs retry immediately; stable poses still refresh coverage/usage every 50 ms. */
final class PackagePrefetchSchedule {
    static final long RETRY_NANOS=50_000_000L;
    private long pose=Long.MIN_VALUE,world=Long.MIN_VALUE,input=Long.MIN_VALUE,next;
    boolean due(long now,long poseVersion,long worldVersion,long inputVersion){return now>=next||pose!=poseVersion||world!=worldVersion||input!=inputVersion;}
    void submitted(long now,long poseVersion,long worldVersion,long inputVersion){pose=poseVersion;world=worldVersion;input=inputVersion;next=now+RETRY_NANOS;}
    void reset(){pose=world=input=Long.MIN_VALUE;next=0;}
}
