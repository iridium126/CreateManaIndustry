package com.iridium126.createmanaindustry.compat.hextrick.conversion;

/** Conversion inputs are displayed points; amounts are integer storage units. */
public final class ResourceConversionMath {
    private ResourceConversionMath() {}

    public record Amounts(long cost, long output) {}

    public static double rate(double mspt) {
        if (!Double.isFinite(mspt) || mspt < 0) throw new IllegalArgumentException("Invalid tick time");
        return 25.6 + 76.8 / (1.0 + Math.exp(-0.1 * (mspt - 25.0)));
    }

    public static Amounts mediaToMana(double input, double mspt, long manaScale) {
        validate(input, manaScale);
        return new Amounts(units(input * 10000.0), units(input * rate(mspt) * manaScale));
    }

    public static Amounts manaToMedia(double input, double mspt, long manaScale) {
        validate(input, manaScale);
        return new Amounts(units(input * manaScale), units(input / rate(mspt) * 10000.0));
    }

    private static void validate(double input, long manaScale) {
        if (!Double.isFinite(input) || input <= 0 || manaScale <= 0)
            throw new IllegalArgumentException("Expected finite positive conversion amount");
    }

    private static long units(double amount) {
        // Long.MAX_VALUE rounds up to 2^63 as a double. Reject before Java's
        // saturating cast, and reject a conversion that would cost/produce zero.
        if (!Double.isFinite(amount) || amount < 1.0 || amount >= 0x1.0p63)
            throw new IllegalArgumentException("Conversion amount outside integer storage range");
        return (long) amount;
    }
}
