package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import net.neoforged.fml.loading.FMLLoader;

/** Checks the classes and members used by the Allay terrain adapter. NeoForge checks the dependency range. */
public final class AllvrSodiumCompatibilityProbe {

    public static final String MOD_ID = "sodium";

    private static volatile Availability cached;

    private AllvrSodiumCompatibilityProbe() {}

    public record Availability(boolean available, String reason) {
        static Availability ok() {
            return new Availability(true, "ok");
        }

        static Availability fail(String reason) {
            return new Availability(false, reason);
        }
    }

    public static Availability probe() {
        Availability availability = cached;
        if (availability == null) {
            availability = probe0();
            cached = availability;
        }
        return availability;
    }

    private static Availability probe0() {
        var file = FMLLoader.getLoadingModList().getModFileById(MOD_ID);
        if (file == null) {
            return Availability.fail("sodium mod not installed");
        }
        // Smoke-load the ABI surface. Class initialization must succeed and
        // the referenced members must resolve (verification walks the constant
        // pool of THIS class' call sites — any drift fails the load).
        String missing = SodiumApi_0813_1211.probeAbi();
        if (missing != null) {
            return Availability.fail("sodium ABI mismatch: " + missing);
        }
        return Availability.ok();
    }
}
