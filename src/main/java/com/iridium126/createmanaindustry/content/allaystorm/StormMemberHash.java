package com.iridium126.createmanaindustry.content.allaystorm;

/**
 * FLOAT-EXACT hash chains shared with the GPU: a line-by-line port of the GLSL
 * {@code cmiHash1} and the {@code emit.comp} identity chain. Java float ops do
 * not fuse into FMA, and the GLSL side keeps single-mul statements, so the
 * server and every client derive identical member seeds bit-for-bit. Do not
 * "simplify" these expressions.
 */
public final class StormMemberHash {
    private StormMemberHash() {
    }

    /** GLSL {@code cmiHash1} (chunks/allay_pose.glsl), verbatim. */
    public static float hash1(float p) {
        p = p * 0.1031f;
        p = p - (float) Math.floor(p);
        p = p * (p + 33.33f);
        p = p * (p + p);
        p = p - (float) Math.floor(p);
        return p;
    }

    /**
     * Member identity seed: GLSL {@code emit.comp}'s {@code hash(stormSeed, memberIdx)}
     * in [0,1). {@code stormSeed} is pre-masked to 24 bits and
     * {@code memberIdx < 2^24}, so both are exact in float.
     */
    public static float memberSeed(int stormSeed, int memberIdx) {
        float s0 = hash1((float) stormSeed + 31.7f);
        float shift = s0 * 97.0f;
        float t = (float) memberIdx * 0.6123f;
        float p = t + 0.7f;
        float q = p + shift;
        float hA = hash1(q);
        float q2 = q * 7.31f;
        float hB = hash1(q2);
        float mA = hA * 0.618f;
        float mB = hB * 0.382f;
        return mA + mB;
    }
}