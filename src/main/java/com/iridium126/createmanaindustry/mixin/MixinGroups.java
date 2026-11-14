package com.iridium126.createmanaindustry.mixin;

import com.iridium126.createmanaindustry.infrastructure.config.ExperimentalStartup.Settings;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.HexJitMode;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Manifest packages own their default rule. Exact entries cover cross-feature hooks. */
enum MixinGroups {
    CORE(false),
    HEX(false, "hexcasting"),
    TRICKSTER(false, "trickster"),
    HEX_TRICKSTER(false, "hexcasting", "trickster"),
    BITS_AND_BOBS(false, "bits_n_bobs", "trickster"),
    HEX_JIT(false, "hexcasting"),
    IRIS(true, "iris"),
    IRIS_HEX(true, "iris", "hexcasting"),
    IRIS_VEIL(true, "iris", "irisveil"),
    SODIUM(true, "sodium"),
    FEATHER_SHADOWS(true, "iris", "sodium"),
    VOXY(true, "voxy"),
    JEI(true, "jei"),
    RIFT_SABLE(false, "sable"),
    PACKAGE_SERVER(false, "create"),
    PACKAGE_SHARED(false, "create"),
    PACKAGE_CLIENT(true, "create"),
    PACKAGE_IRIS(true, "create", "iris"),
    PACKAGE_SABLE(true, "create", "sable");

    static final String ROOT = "com.iridium126.createmanaindustry.mixin.";
    private static final Map<String, MixinGroups> PACKAGES = Map.ofEntries(
            Map.entry("", CORE),
            Map.entry("allaystorm", CORE),
            Map.entry("allaystorm.create", CORE),
            Map.entry("allaystorm.sable", RIFT_SABLE),
            Map.entry("allvr", CORE),
            Map.entry("basin", CORE),
            Map.entry("bnb", BITS_AND_BOBS),
            Map.entry("burner", CORE),
            Map.entry("fan", CORE),
            Map.entry("fluids", CORE),
            Map.entry("hexcasting", HEX),
            Map.entry("hexjit", HEX_JIT),
            Map.entry("hextrick", HEX_TRICKSTER),
            Map.entry("iris", IRIS),
            Map.entry("irisveil", IRIS_VEIL),
            Map.entry("jei", JEI),
            Map.entry("kinetics", CORE),
            Map.entry("packages", PACKAGE_SERVER),
            Map.entry("packages.sable", PACKAGE_SABLE),
            Map.entry("particles", CORE),
            Map.entry("render", CORE),
            Map.entry("sodium", SODIUM),
            Map.entry("trickster", TRICKSTER),
            Map.entry("vanilla", CORE),
            Map.entry("voxy", VOXY));

    private static final Map<String, MixinGroups> EXCEPTIONS = Map.ofEntries(
            Map.entry("packages.ChainPackageIdentityMixin", PACKAGE_SHARED),
            Map.entry("packages.ChainIdentityLifecycleMixin", PACKAGE_SHARED),
            Map.entry("packages.ChainOwnershipContainersMixin", PACKAGE_SHARED),
            Map.entry("packages.ContraptionCollisionMixin", PACKAGE_CLIENT),
            Map.entry("packages.ChainRendererOwnershipMixin", PACKAGE_CLIENT),
            Map.entry("packages.ChainVisualOwnershipMixin", PACKAGE_CLIENT),
            Map.entry("packages.ChainPackageGpuPickingMixin", PACKAGE_CLIENT),
            Map.entry("packages.PackageFreeGpuInputMixin", PACKAGE_CLIENT),
            Map.entry("packages.PackageCollisionPacketMixin", PACKAGE_CLIENT),
            Map.entry("packages.PackageCollisionChunkMixin", PACKAGE_CLIENT),
            Map.entry("packages.PackageAirCurrentMixin", PACKAGE_CLIENT),
            Map.entry("packages.PackageNozzleMixin", PACKAGE_CLIENT),
            Map.entry("render.PackageCameraFrustumAccessor", PACKAGE_CLIENT),
            Map.entry("particles.PackageLightChunkMixin", PACKAGE_CLIENT),
            Map.entry("iris.PackageMainRenderMixin", PACKAGE_IRIS),
            Map.entry("iris.PackageAdvancedFrustumAccessor", PACKAGE_IRIS),
            Map.entry("iris.PackageBoxFrustumAccessor", PACKAGE_IRIS),
            Map.entry("iris.PackageSafeFrustumAccessor", PACKAGE_IRIS),
            Map.entry("iris.PackageBoxCullerAccessor", PACKAGE_IRIS),
            Map.entry("iris.PackageShadowRenderMixin", PACKAGE_IRIS),
            Map.entry("iris.HexShadowRenderMixin", IRIS_HEX),
            Map.entry("iris.FeatherLeafPipelineMixin", FEATHER_SHADOWS),
            Map.entry("iris.FeatherLeafTerrainSettings", FEATHER_SHADOWS),
            Map.entry("sodium.FeatherLeafMeshingMixin", FEATHER_SHADOWS),
            Map.entry("sodium.FeatherLeafBuildOutputMixin", FEATHER_SHADOWS),
            Map.entry("sodium.FeatherLeafQuadMixin", FEATHER_SHADOWS),
            Map.entry("sodium.FeatherLeafUploadMixin", FEATHER_SHADOWS),
            Map.entry("sodium.FeatherLeafSectionMixin", FEATHER_SHADOWS));

    final boolean clientOnly;
    private final Set<String> mods;

    MixinGroups(boolean clientOnly, String... mods) {
        this.clientOnly = clientOnly;
        this.mods = Set.of(mods);
    }

    static MixinGroups of(String className) {
        if (!className.startsWith(ROOT)) return null;
        String name = className.substring(ROOT.length());
        MixinGroups override = EXCEPTIONS.get(name);
        if (override != null) return override;
        int split = name.lastIndexOf('.');
        return PACKAGES.get(split < 0 ? "" : name.substring(0, split));
    }

    static Set<String> overrides() {
        return EXCEPTIONS.keySet();
    }

    boolean enabled(boolean client, Settings settings, Predicate<String> isLoaded) {
        if ((clientOnly && !client) || !mods.stream().allMatch(isLoaded)) return false;
        return switch (this) {
            case HEX_JIT -> settings.hexJitMode() != HexJitMode.OFF;
            case PACKAGE_SERVER -> settings.serverPackages();
            case PACKAGE_SHARED -> settings.sharedPackages();
            case PACKAGE_CLIENT, PACKAGE_IRIS, PACKAGE_SABLE -> settings.clientPackages();
            default -> true;
        };
    }
}
