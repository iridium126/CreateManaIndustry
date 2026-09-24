package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.lib.HexRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Fixed pure-data YSM patterns. */
public final class CMIYsmActions {
    private static final DeferredRegister<ActionRegistryEntry> ACTIONS =
            DeferredRegister.create(HexRegistries.ACTION, CreateManaIndustry.MODID);

    static {
        ACTIONS.register("ysm_cube_create", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("wewewewewewdwew", HexDir.SOUTH_WEST),
                new OpCreateYsmGeometry(true)));
        ACTIONS.register("ysm_group_create", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("qwqwqwqwqwqeqqqdqqqdqqqdqqqdqqqdqqq", HexDir.WEST),
                new OpCreateYsmGeometry(false)));
        ACTIONS.register("ysm_geometry_get", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("aqqqqqeawqwaw", HexDir.EAST),
                new OpGeometry(false)));
        ACTIONS.register("ysm_geometry_set", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("deeeeeqawqwaw", HexDir.EAST),
                new OpGeometry(true)));
        ACTIONS.register("ysm_model_read", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("wqqqdqqqaedewqqqdqqqdqqqaedewqqqwwqwawqqawd", HexDir.SOUTH_WEST), new OpReadYsmModel()));
        ACTIONS.register("ysm_model_apply", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("wqqqdqqqaedewqqqdqqqaedewqqqdqqqaedwqqqqq", HexDir.SOUTH_WEST), new OpApplyYsmModel()));
        ACTIONS.register("ysm_model_export", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("qqqwwqwawqwwqqqeqeqqqdqqqeqqwwwqqqwwwwqqq", HexDir.SOUTH_WEST), new OpExportYsmModel()));
        ACTIONS.register("ysm_model_restore", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("wdedwqwdedwqwdedw", HexDir.NORTH_WEST), new OpRestoreYsmModel()));
    }

    public static void register(IEventBus bus) { ACTIONS.register(bus); }
    private CMIYsmActions() {}
}
