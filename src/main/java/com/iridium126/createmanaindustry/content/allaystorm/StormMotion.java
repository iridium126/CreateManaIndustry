package com.iridium126.createmanaindustry.content.allaystorm;

public final class StormMotion {
    public static final float CRUISE=6, INTERCEPT=8, COLUMN_ORBIT=3, HELIX_THRUST=12, HELIX_LIMIT=32, HELIX_TURNS=.75f;
    private StormMotion() {}
    public static double helixPitch(){return 2*Math.PI*HELIX_TURNS/64;}
    public static double helixAngle(double progress){return -helixPitch()*progress;}
}
