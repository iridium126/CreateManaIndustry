package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import com.iridium126.createmanaindustry.client.dimension.lod.AllvrLodBackend;

/**
 * Exact-build Voxy compatibility probe (voxy integration plan §8.1): this
 * integration compiles against one pinned NeoForge artifact —
 * {@code voxy-0.2.15-beta+1.21.1-neoforge.jar} (commit aab0ab95) — and the
 * probe verifies the runtime build matches before the adapter is allowed
 * to load. Checks, in order:
 * <ol>
 *   <li>the voxy mod is present (checked again here, though the mixin plugin
 *       gate already prevented the voxy mixins from applying);</li>
 *   <li>{@code VoxyCommon.MOD_VERSION} is the pinned semantic version;</li>
 *   <li>reflective descriptor probes over the handful of internals the
 *       integration touches (section key encode/decode, section child-mask
 *       write, mapper block-id lookup, client-instance storage/ingest
 *       overrides, static render-system accessor).</li>
 * </ul>
 * An unknown build degrades to UNAVAILABLE with the reason logged once; the
 * backend manager then enters the disabled (near-only) backend without
 * touching anything Voxy-shaped at runtime — there is no fallback renderer.
 */
public final class VoxyCompatibilityProbe {

    /** The pinned artifact's semantic version (run/mods build, P0 matrix). */
    public static final String PINNED_VERSION = "0.2.15-beta";

    private static volatile AllvrLodBackend.Availability cached;

    private VoxyCompatibilityProbe() {}

    public static AllvrLodBackend.Availability probe() {
        AllvrLodBackend.Availability availability = cached;
        if (availability == null) {
            availability = probe0();
            cached = availability;
        }
        return availability;
    }

    /** Creates the adapter once the probe passed; null = fall back. */
    public static AllvrLodBackend createBackend() {
        if (!probe().available()) {
            return null;
        }
        VoxyLodBackend backend = new VoxyLodBackend();
        return currentBackend = backend;
    }

    /** True while the active backend is the voxy one AND it is refilling. */
    public static boolean refillActive() {
        return currentBackend != null && currentBackend.isRefilling();
    }

    /** The active voxy backend's virtual Y window, or null. */
    public static AllvrVoxyYWindow activeWindow() {
        return currentBackend == null ? null : currentBackend.window();
    }

    private static VoxyLodBackend currentBackend;

    private static AllvrLodBackend.Availability probe0() {
        if (!VoxyApi_0215_1211.modInstalled()) {
            return AllvrLodBackend.Availability.fail("voxy mod not installed");
        }
        String version = VoxyApi_0215_1211.modVersion();
        if (version == null || !version.startsWith(PINNED_VERSION)) {
            return AllvrLodBackend.Availability.fail("unsupported voxy version " + version
                + " (pinned " + PINNED_VERSION + ")");
        }
        String missing = VoxyApi_0215_1211.probeDescriptors();
        if (missing != null) {
            return AllvrLodBackend.Availability.fail("voxy ABI mismatch: missing " + missing);
        }
        return AllvrLodBackend.Availability.ok();
    }
}
