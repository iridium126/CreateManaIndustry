package com.iridium126.createmanaindustry.client.greattrick;

/** Two-block priority bands with hysteresis keep nearby bindings from exchanging budgets every frame. */
public final class SharedFatePriority {
    private SharedFatePriority() {}
    public static int band(int previous, double distance) {
        if (previous >= 0 && distance >= previous * 2.0 - .75 && distance < (previous + 1) * 2.0 + .75)
            return previous;
        return (int)Math.min(Integer.MAX_VALUE - 1, Math.max(0, Math.floor(distance / 2.0)));
    }
}
