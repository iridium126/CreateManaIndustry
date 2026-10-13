package com.iridium126.createmanaindustry.compat.trickster;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class AnimaMath {
    public static final long SCALE = 1000;
    private AnimaMath() {}
    public static long capacity(long maxMedia, long dustMedia, double ratio) {
        if (maxMedia <= 0 || dustMedia <= 0 || !Double.isFinite(ratio) || ratio <= 0) return 0;
        var value = BigDecimal.valueOf(maxMedia).multiply(BigDecimal.valueOf(ratio)).multiply(BigDecimal.valueOf(SCALE))
                .divide(BigDecimal.valueOf(dustMedia), 0, RoundingMode.DOWN);
        return value.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
    }
    public static long exchange(long amount, long numerator, long denominator) {
        if (amount <= 0) return 0;
        if (numerator <= 0 || denominator <= 0) throw new IllegalArgumentException("Invalid exchange rate");
        var n = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(numerator)).multiply(BigInteger.TWO);
        return n.add(BigInteger.valueOf(denominator - 1)).divide(BigInteger.valueOf(denominator))
                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }
    public static String format(long amount) {
        return BigDecimal.valueOf(Math.max(0, amount), 3).stripTrailingZeros().toPlainString();
    }
}
