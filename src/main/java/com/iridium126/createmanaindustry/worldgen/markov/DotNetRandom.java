package com.iridium126.createmanaindustry.worldgen.markov;

/** Seeded System.Random compatibility, including its draw order. */
public final class DotNetRandom {
    private final int[] seed = new int[56];
    private int next, nextp = 21;

    public DotNetRandom(int value) {
        int mj = 161803398 - (value == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(value));
        seed[55] = mj;
        int mk = 1;
        for (int i = 1; i < 55; i++) {
            int ii = (21 * i) % 55;
            seed[ii] = mk;
            mk = mj - mk;
            if (mk < 0) mk += Integer.MAX_VALUE;
            mj = seed[ii];
        }
        for (int k = 0; k < 4; k++) for (int i = 1; i < 56; i++) {
            seed[i] -= seed[1 + (i + 30) % 55];
            if (seed[i] < 0) seed[i] += Integer.MAX_VALUE;
        }
    }

    public int nextInt() {
        if (++next >= 56) next = 1;
        if (++nextp >= 56) nextp = 1;
        int result = seed[next] - seed[nextp];
        if (result == Integer.MAX_VALUE) result--;
        if (result < 0) result += Integer.MAX_VALUE;
        seed[next] = result;
        return result;
    }

    public double nextDouble() { return nextInt() * (1.0 / Integer.MAX_VALUE); }
    public int nextInt(int bound) { return (int) (nextDouble() * bound); }
}
