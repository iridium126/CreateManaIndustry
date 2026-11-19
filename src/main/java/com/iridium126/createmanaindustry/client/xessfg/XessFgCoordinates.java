package com.iridium126.createmanaindustry.client.xessfg;

/** Maps output pixels to a shader pack's sampled gbuffer region. Jitter is in gbuffer pixels. */
public record XessFgCoordinates(float scaleX, float scaleY, float jitterX, float jitterY,
                                Warp warp, float time, float strength) {
    public static final XessFgCoordinates VANILLA = new XessFgCoordinates(1, 1, 0, 0, Warp.NONE, 0, 0);

    public enum Warp {
        NONE, BSL_WATER, COMPLEMENTARY_WATER, SILDUR_REFRACTION, SUNDIAL_LENS, SILDUR_RAIN
    }
}
