package com.iridium126.createmanaindustry.client.dimension.lod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.lod.voxy.VoxyCompatibilityProbe;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.net.ServerboundAllvrLodRequestPacket;

/**
 * Backend selection, lifecycle and stats (voxy integration plan §7.1): AUTO
 * picks the voxy adapter when the probed Voxy build is present and falls
 * back to the legacy renderer otherwise; VOXY/LEGACY/OFF force a backend.
 * Selection happens on level join (or a config reload) — switching
 * backends clears the client's mesh/pending bookkeeping so the request walk
 * re-issues every node against the new backend's wire format.
 * <p>
 * All entry points run on the main thread (client tick / enqueueWork), so
 * backend swaps are naturally serialized.
 */
public final class AllvrLodBackendManager {

    /** Explicit backend choice (voxy integration plan §9.3). */
    public enum Mode {
        AUTO, VOXY, LEGACY, OFF
    }

    private static final LegacyAllvrLodBackend LEGACY = new LegacyAllvrLodBackend();
    private static final DisabledLodBackend DISABLED = new DisabledLodBackend();

    private static AllvrLodBackend active;
    private static AllvrLodBackend.Availability voxyAvailability;
    private static long appliedSections;
    private static long rejectedSections;
    private static long forgottenNodes;

    private AllvrLodBackendManager() {}

    /**
     * Binds the manager to the client level: selects the backend per the
     * config mode and the voxy probe, then enters it. Non-allay levels enter
     * the disabled backend (no LOD work anywhere else).
     */
    public static void enter(ClientLevel level) {
        AllvrLodBackend selected = AllvrDimensions.isAllay(level) ? select() : DISABLED;
        boolean switched = active != null && active != selected;
        if (switched) {
            active.leave();
        }
        active = selected;
        active.enter(level);
        CreateManaIndustry.LOGGER.info("[Allvr] LOD backend: {} ({})", active.debugState(), modeDescription());
    }

    /**
     * Config reload: re-run selection against the current level. A backend
     * switch drops the previous backend's nodes; the caller clears the
     * request bookkeeping so the walk refills through the new wire format.
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

    /** True when the active backend is the legacy quad renderer. */
    public static boolean legacyActive() {
        return active == LEGACY;
    }

    /** The wire capability the ACTIVE backend consumes — sent with every
     *  request batch so the server's answer format always matches. */
    public static int wireCapability() {
        if (active == null || active == DISABLED || active == LEGACY) {
            return ServerboundAllvrLodRequestPacket.CAPABILITY_LEGACY_MESH;
        }
        return ServerboundAllvrLodRequestPacket.CAPABILITY_VOXEL_SECTION;
    }

    /** True while the backend accepts new work (rebase freeze blocks it). */
    public static boolean requestsOpen() {
        return active != null && active != DISABLED;
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
        ClientConfig.AllvrLodBackendMode mode = ClientConfig.allvrLodBackend;
        return switch (mode) {
            case OFF -> DISABLED;
            case LEGACY -> LEGACY;
            case VOXY -> selectVoxy("explicit VOXY mode");
            case AUTO -> voxyAvailable().available() ? selectVoxy("AUTO") : legacyFallback("AUTO");
        };
    }

    private static AllvrLodBackend selectVoxy(String via) {
        AllvrLodBackend.Availability availability = voxyAvailable();
        if (availability.available()) {
            return voxyBackend();
        }
        CreateManaIndustry.LOGGER.warn(
            "[Allvr] {} requested the voxy LOD backend but it is unavailable ({}); falling back to legacy",
            via, availability.reason());
        return LEGACY;
    }

    private static AllvrLodBackend legacyFallback(String via) {
        CreateManaIndustry.LOGGER.info("[Allvr] {} selects the legacy LOD backend ({} )",
            via, voxyAvailable().reason());
        return LEGACY;
    }

    private static AllvrLodBackend.Availability voxyAvailable() {
        if (voxyAvailability == null) {
            voxyAvailability = VoxyCompatibilityProbe.probe();
        }
        return voxyAvailability;
    }

    private static AllvrLodBackend voxyBackend() {
        AllvrLodBackend backend = VoxyCompatibilityProbe.createBackend();
        if (backend != null) {
            return backend;
        }
        return LEGACY;
    }

    private static String modeDescription() {
        return switch (ClientConfig.allvrLodBackend) {
            case AUTO -> "config AUTO";
            case VOXY -> "config VOXY";
            case LEGACY -> "config LEGACY";
            case OFF -> "config OFF";
        };
    }
}
