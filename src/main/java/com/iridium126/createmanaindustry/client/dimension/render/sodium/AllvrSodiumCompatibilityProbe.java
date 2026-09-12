package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import net.neoforged.fml.loading.FMLLoader;

/**
 * Sodium compatibility probe (sodium-parity plan §4.1/§4.2). Sodium is a
 * CLIENT required dependency declared in {@code neoforge.mods.toml} with the
 * same narrow version range, so the loader already fails startup with a clear
 * dependency error on a missing build. This probe is the second line of
 * defense: it verifies the pinned version prefix and smoke-loads every Sodium
 * class family the bridge binds to, so an ABI drift inside the pinned range
 * surfaces as one clean log line + a disabled bridge instead of a
 * half-initialized mixin crash inside the allay dimension.
 * <p>
 * The bridge only registers sections while the probe passed; ordinary
 * dimensions are untouched in every failure case (plan §7.4: each hook has a
 * normal-dimension regression guarantee).
 */
public final class AllvrSodiumCompatibilityProbe {

    /** Must match the mods.toml range and the CMIMixinPlugin sodium gate. */
    public static final String VERSION_PREFIX = "0.8.13";
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
        String version = null;
        try {
            var mods = file.getClass().getMethod("getMods").invoke(file);
            for (Object mod : (Iterable<?>) mods) {
                if (MOD_ID.equals(String.valueOf(mod.getClass().getMethod("getModId").invoke(mod)))) {
                    version = String.valueOf(mod.getClass().getMethod("getVersion").invoke(mod));
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fall through; the loader range already pinned the artifact family
        }
        if (version == null || !version.startsWith(VERSION_PREFIX)) {
            return Availability.fail("unsupported sodium version " + version
                + " (pinned " + VERSION_PREFIX + ")");
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
