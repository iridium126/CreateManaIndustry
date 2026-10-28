package com.iridium126.createmanaindustry.content.allaystorm;

/** Frozen-law phase evaluation, shared by the runtime and persistence regression tests. */
public record AllayStormPhases(float rotation, float conveyor, float conveyorRate, float omega) {
    public static AllayStormPhases evaluate(float r0, float finalRadius, float growth, long created,
            long now, double count, int seed) {
        return evaluate(r0,finalRadius,growth,created,now,0,count,seed);
    }
    public static AllayStormPhases evaluate(float r0, float finalRadius, float growth, long created,
            long now, float partial, double count, int seed) {
        float rf = Math.max(finalRadius, .5f), radius = AllayStormData.vortexRadius(count);
        float grown = Math.min(radius, rf) - r0;
        float age = (float)(((now - created) + (double)Math.clamp(partial,0,1)) / 20.0);
        float duration = growth > 0 ? (float)(64.0 * ((double)rf * rf - (double)r0 * r0) / growth) : 0;
        float post = Math.max(0, age - duration), g = Math.max(growth, 1e-6f);
        double rotation = (double)AllayStormData.SPIN_K * 128 * grown / g + (double)AllayStormData.SPIN_K / rf * post;
        if((seed&1)!=0&&rotation!=0)rotation=-rotation;
        return new AllayStormPhases((float)(rotation % (2 * Math.PI)), 64 * grown / g + post / (2 * rf),
                (radius < rf ? 1 / (2 * radius) : 0) + (post > 0 ? 1 / (2 * rf) : 0),
                AllayStormData.vortexOmega(radius, seed));
    }
}
