package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.function.LongSupplier;

/** Shared tick budget for discovery, collision capture and optional lighting work. */
final class PackageCollisionCaptureSchedule {
    interface Work {
        void discoverMoving(long deadline);
        void invalidateLight(long deadline);
        void captureLight(long budget);
        void captureMoving(long budget);
        void captureWorld(long budget);
    }

    static void tick(Work work,boolean movingFirst,boolean lightFirst,long budget,LongSupplier clock) {
        long started=clock.getAsLong(),deadline=started+budget;
        // Membership is an immutable physics input. Optional lighting must never
        // spend its budget first and turn a known scene into a missing history tick.
        work.discoverMoving(deadline);
        work.invalidateLight(deadline);
        if(lightFirst)work.captureLight(remaining(clock,deadline));
        // captureMoving still samples poses when remaining geometry time is zero.
        if(movingFirst){work.captureMoving(remaining(clock,deadline));work.captureWorld(remaining(clock,deadline));}
        else{work.captureWorld(remaining(clock,deadline));work.captureMoving(remaining(clock,deadline));}
        if(!lightFirst)work.captureLight(remaining(clock,deadline));
    }

    private static long remaining(LongSupplier clock,long deadline){return Math.max(0,deadline-clock.getAsLong());}
    private PackageCollisionCaptureSchedule() {}
}
