package com.iridium126.createmanaindustry.client.particles.engine;

/** Immutable update input. Key generation must never reread a newer storm's live bounds. */
public record StormShadowSnapshot(long generation, boolean enabled, double x, double y, double z,
                                  float radius, float low, float high) {
    public static StormShadowSnapshot of(long generation, boolean enabled, double x, double y, double z, double radius) {
        float r=(float)Math.max(2,radius),band=Math.clamp(r*.4f,3,8)*.5f;
        boolean finite=Float.isFinite((float)x)&&Float.isFinite((float)y)&&Float.isFinite((float)z)&&Float.isFinite(r);
        return new StormShadowSnapshot(generation,enabled&&finite,x,y,z,r*1.15f+.4f,
                -1.6f-band,Math.clamp(r*.2f,2,6)+band+.4f);
    }
    public boolean matches(long candidate){return enabled&&generation==candidate;}
}
