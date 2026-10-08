package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.function.LongSupplier;

/** Shared tick budget for discovery, collision capture and optional lighting work. */
final class PackageCollisionCaptureSchedule {
    interface Work {
        void discoverMoving(long deadline);
        void invalidateLight(long deadline);
        void captureLight(long budget);
        void captureMoving(long budget);
        /** Continue geometry without taking a second mutable pose sample in this tick. */
        void captureMovingGeometry(long budget);
        void captureWorld(long budget);
    }

    static void tick(Work work,boolean movingFirst,boolean lightFirst,long budget,LongSupplier clock) {
        long started=clock.getAsLong(),deadline=started+budget;
        // Membership is an immutable physics input. Optional lighting must never
        // spend its budget first and turn a known scene into a missing history tick.
        work.discoverMoving(deadline);
        // Coalescing optional light notifications must leave time for collision capture.
        work.invalidateLight(clock.getAsLong()+remaining(clock,deadline)/4);
        // Every category gets a bounded first opportunity. A continuously dirty
        // light column or large sublevel must not leave world capture at zero.
        long share=remaining(clock,deadline)/3;
        if(lightFirst)work.captureLight(Math.min(share,remaining(clock,deadline)));
        // captureMoving still samples poses when remaining geometry time is zero.
        if(movingFirst){work.captureMoving(Math.min(share,remaining(clock,deadline)));work.captureWorld(Math.min(share,remaining(clock,deadline)));}
        else{work.captureWorld(Math.min(share,remaining(clock,deadline)));work.captureMoving(Math.min(share,remaining(clock,deadline)));}
        if(!lightFirst)work.captureLight(Math.min(share,remaining(clock,deadline)));
        // Idle categories return their time to the original priority order.
        if(lightFirst)spareLight(work,clock,deadline);
        if(movingFirst){spareMoving(work,clock,deadline);spareWorld(work,clock,deadline);}
        else{spareWorld(work,clock,deadline);spareMoving(work,clock,deadline);}
        if(!lightFirst)spareLight(work,clock,deadline);
    }

    private static void spareLight(Work work,LongSupplier clock,long deadline){long budget=remaining(clock,deadline);if(budget>0)work.captureLight(budget);}
    private static void spareWorld(Work work,LongSupplier clock,long deadline){long budget=remaining(clock,deadline);if(budget>0)work.captureWorld(budget);}
    private static void spareMoving(Work work,LongSupplier clock,long deadline){long budget=remaining(clock,deadline);if(budget>0)work.captureMovingGeometry(budget);}

    private static long remaining(LongSupplier clock,long deadline){return Math.max(0,deadline-clock.getAsLong());}
    private PackageCollisionCaptureSchedule() {}
}
