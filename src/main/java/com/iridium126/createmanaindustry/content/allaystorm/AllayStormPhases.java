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
        float age = (float)(((now - created) + (double)Math.clamp(partial,0,1)) / 20.0);
        float g = Math.max(growth, 1e-6f);
        // The radius law is count = A·R + B·R² (linear radial density).
        // With count(t) = count0 + g·t the phase integrals are:
        //   ∫dt          = [A·R + B·R²]/g
        //   ∫(1/R)dt     = [A·ln(R) + 2B·R]/g
        //   ∫(1/(2R))dt  = [A·ln(R) + 2B·R]/(2g)
        // so only powers, a logarithm, multiplication and division are used.
        double to = Math.min(radius, rf);
        double from = Math.max(r0, 1.0e-6);
        double rotationGrown = 0, conveyorGrown = 0;
        if (to > from) {
            double integral = AllayStormData.RADIUS_COUNT_PER_BLOCK * Math.log(to / from)
                    + 2.0 * AllayStormData.RADIUS_DENSITY_SLOPE * (to - from);
            rotationGrown = (double)AllayStormData.SPIN_K / g * integral;
            conveyorGrown = integral / (2.0 * g);
        }
        double duration = 0;
        if (growth > 0 && rf > r0) {
            duration = Math.max(0.0, (countAt(rf) - countAt(r0)) / g);
        }
        double post = Math.max(0, age - duration);
        double rotation = rotationGrown + (double)AllayStormData.SPIN_K / rf * post;
        if((seed&1)!=0&&rotation!=0)rotation=-rotation;
        return new AllayStormPhases((float)(rotation % (2 * Math.PI)), (float)(conveyorGrown + post / (2 * rf)),
                (radius < rf ? 1 / (2 * radius) : 0) + (post > 0 ? 1 / (2 * rf) : 0),
                AllayStormData.vortexOmega(radius, seed));
    }
    private static double countAt(double radius) {
        return AllayStormData.RADIUS_COUNT_PER_BLOCK * radius
                + AllayStormData.RADIUS_DENSITY_SLOPE * radius * radius;
    }
}
