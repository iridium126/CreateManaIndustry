package com.iridium126.createmanaindustry.client.dimension.lod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.lod.voxy.VoxyCompatibilityProbe;
import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyYWindow;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

/**
 * Backend selection and lifecycle: Voxy is the ONLY far-terrain backend
 * (sodium-parity plan §2.1). The probed Voxy adapter is the only far-terrain
 * implementation; when it is absent the dimension runs near-only with no far
 * requests and no fallback renderer. Selection happens on level join (or a
 * config reload) — switching availability clears the client's pending/
 * resident bookkeeping so the request walk re-issues every node.
 * <p>
 * All entry points run on the main thread (client tick / enqueueWork), so
 * backend swaps are naturally serialized.
 */
public final class AllvrLodBackendManager {

    private static final DisabledLodBackend DISABLED = new DisabledLodBackend();

    private static AllvrLodBackend active;
    private static AllvrLodBackend.Availability voxyAvailability;
    private static long appliedSections;
    private static long rejectedSections;
    private static long forgottenNodes;
    private static boolean warnedVoxyUnavailable;

    private AllvrLodBackendManager() {}

    /**
     * Binds the manager to the client level: selects Voxy when the client LOD
     * switch and compatibility probe allow it, then enters it. Non-allay
     * levels enter the disabled backend (no LOD work anywhere else).
     */
    public static void enter(ClientLevel level) {
        AllvrLodBackend selected = AllvrDimensions.isAllay(level) ? select() : DISABLED;
        boolean switched = active != null && active != selected;
        if (switched) {
            active.leave();
        }
        active = selected;
        active.enter(level);
        CreateManaIndustry.LOGGER.info("[Allvr] LOD backend: {}", active.debugState());
    }

    /**
     * Config reload: re-run selection against the current level. A backend
     * switch drops the previous backend's nodes; the caller clears the
     * request bookkeeping so the walk refills through the voxy backend.
     */
    public static void reselect(ClientLevel level) {
        enter(level);
    }

    /** Level unload / dimension switch / logout: release everything. */
    public static void leave() {
        if (active != null) {
            active.leave();
        }
    }

    /** Per-tick driver — runs before the request walk. */
    public static void tick(double cameraX, double cameraY, double cameraZ) {
        if (active != null) {
            active.tick(cameraX, cameraY, cameraZ);
        }
    }

    /** Publishes a decoded section payload; false = rejected, re-request later. */
    public static boolean apply(com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData data,
                                Holder<Biome> biome) {
        if (active == null) {
            return false;
        }
        boolean accepted = active.apply(data, biome);
        if (accepted) {
            appliedSections++;
        } else {
            rejectedSections++;
        }
        return accepted;
    }

    /** Drops one node in the active backend (idempotent). */
    public static void forget(int level, long cellLong) {
        if (active != null) {
            active.forget(level, cellLong);
            forgottenNodes++;
        }
    }

    /** True while the voxy backend accepts new work (rebase freeze blocks it). */
    public static boolean requestsOpen() {
        return active != null && active != DISABLED && active.requestsOpen();
    }

    public static java.util.List<AllvrLodBackend.Failure> drainFailures() {
        return active == null ? java.util.List.of() : active.drainFailures();
    }

    /** True when the far-terrain backend is the active voxy adapter — the
     *  fog/debug extent reports the far radius only in this state. */
    public static boolean farTerrainActive() {
        return requestsOpen();
    }

    /** True during the rebase REFILL phase — the walk then serves coarsest
     *  levels first so the horizon fills rough before it fills fine. */
    public static boolean refillMode() {
        return VoxyCompatibilityProbe.refillActive();
    }

    /**
     * The active voxy backend's virtual Y window (the viewport camera patch
     * reads it), or null when the active backend is not the voxy one.
     */
    public static com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyYWindow voxyWindow() {
        return VoxyCompatibilityProbe.activeWindow();
    }

    /** Debug/overlay line. */
    public static String debugState() {
        if (active == null) {
            return "backend: unbound";
        }
        return "backend: " + active.debugState()
            + " (applied=" + appliedSections + " rejected=" + rejectedSections
            + " forgotten=" + forgottenNodes + ")";
    }

    private static AllvrLodBackend select() {
        return ClientConfig.allvrLod && voxyAvailable().available()
            ? selectVoxy("configured") : DISABLED;
    }

    private static AllvrLodBackend selectVoxy(String via) {
        AllvrLodBackend.Availability availability = voxyAvailable();
        if (availability.available()) {
            AllvrLodBackend backend = VoxyCompatibilityProbe.createBackend(
                new AllvrVoxyYWindow(AllvrSodiumBridge.window()));
            if (backend != null) {
                warnedVoxyUnavailable = false;
                return backend;
            }
            availability = AllvrLodBackend.Availability.fail("adapter construction failed");
        }
        // Near-only is a defined product state, not a renderer failure: log it
        // once per session and do not activate an alternate terrain path.
        if (!warnedVoxyUnavailable) {
            warnedVoxyUnavailable = true;
            CreateManaIndustry.LOGGER.warn(
                "[Allvr] {} wanted the voxy LOD backend but it is unavailable ({}); "
                    + "far terrain disabled — near-only",
                via, availability.reason());
        }
        return DISABLED;
    }

    private static AllvrLodBackend.Availability voxyAvailable() {
        if (voxyAvailability == null) {
            voxyAvailability = VoxyCompatibilityProbe.probe();
        }
        return voxyAvailability;
    }

}
