package com.iridium126.createmanaindustry.client.particles.packages;

/** Capture between rendered uploads and the next upload share one allowance.
 * Half remains reserved for upload; budget exhaustion never drops an input tick. */
final class PackageFrameBudget {
    private long spent;
    long capture(long allowance){return Math.max(0,Math.min(allowance/2-spent,allowance-spent));}
    long upload(long allowance){return Math.max(0,allowance-spent);}
    void consumed(long nanos){spent=Math.addExact(spent,Math.max(0,nanos));}
    void nextFrame(){spent=0;}
}
