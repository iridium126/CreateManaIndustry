package com.iridium126.createmanaindustry.client.particles.emitter;

/** Frame-local feather metadata. Particle records retain the shared 64-byte ABI. */
public final class AllayFeatherLayout {
    private AllayFeatherLayout() {}
    public static final int TYPE = 7;
    public static final int MAX_FEATHERS = 131072;
    public static final int BINDING = 20;
    public static final int HEADER = 32;
    public static final int GROUND = HEADER, VISIBLE = GROUND + MAX_FEATHERS;
    public static final int IMPULSE_SLOTS = 16384, IMPULSES = VISIBLE + MAX_FEATHERS;
    public static final int REQUEST_SLOTS = 8192, MAX_REQUESTS = 4096;
    public static final int REQUEST_HASH = IMPULSES + IMPULSE_SLOTS * 8;
    public static final int REQUESTS = REQUEST_HASH + REQUEST_SLOTS * 4;
    public static final int WORDS = (REQUESTS + MAX_REQUESTS * 4 + 63) & ~63;
    public static final int BYTES = WORDS * 4;
    public static final int READBACK_BYTES = 16 + MAX_REQUESTS * 16;
    public static final int REQUEST_READBACK = 16;
    public static final int SNAPSHOT_BYTES = 32 + READBACK_BYTES;
    public static final float LIFE = 60f;
    public static boolean zeroCovers(long snapshot, long lastCreation, int count) {
        return count == 0 && snapshot >= lastCreation;
    }

    /** Desired total rate over the full radial distribution, before coverage/budget rejection. */
    public static double rate(double reference,double radius) {
        return radius>0?Math.max(reference,0)*radius*radius/1024.:0;
    }
    public static double proposals(double reference,double radius){return rate(reference,radius)*12./7.;}
    public static double weight(double radius,double distance){return radius>0?Math.clamp((2*radius-distance)/radius,0,1):0;}
}
