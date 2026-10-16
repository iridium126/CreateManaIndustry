package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatPattern;

/** Shared drawing/snap coordinates, independent of GUI scale and Minecraft renderer initialization. */
public final class GreatGridGeometry {
    private GreatGridGeometry() {}

    public static double x(int dot,double center,double radius) {
        return center+(GreatPattern.Q[dot]+GreatPattern.R[dot]*0.5)*radius/2;
    }

    public static double y(int dot,double center,double radius) {
        return center+GreatPattern.R[dot]*Math.sqrt(3)*radius/4;
    }

    public static double spacing(double radius) { return radius/2; }

    /** A threshold of 0.75 captures only within the final 0.25 of a grid step. */
    public static int snap(double mouseX,double mouseY,double centerX,double centerY,double radius,double threshold) {
        if (!(radius > 0) || !Double.isFinite(radius) || !Double.isFinite(mouseX) || !Double.isFinite(mouseY)) return -1;
        threshold = Double.isFinite(threshold) ? Math.clamp(threshold,0,1) : 0.75;
        double captureRadius = spacing(radius)*(1-threshold);
        // Include the exact boundary and compensate only for float coordinate transforms.
        double best = Math.pow(captureRadius+spacing(radius)*1e-7,2);
        int closest = -1;
        for (int i=0;i<GreatPattern.DOT_COUNT;i++) {
            double dx = mouseX-x(i,centerX,radius), dy = mouseY-y(i,centerY,radius);
            double distance = dx*dx+dy*dy;
            if (distance <= best) { closest = i; best = distance; }
        }
        return closest;
    }
}
