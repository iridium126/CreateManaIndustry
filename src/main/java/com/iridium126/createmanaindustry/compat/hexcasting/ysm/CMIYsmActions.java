package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import at.petrak.hexcasting.common.lib.HexRegistries;

/** Fixed pure-data patterns. IDs and pattern indices must remain stable after release. */
public final class CMIYsmActions {
    private static final DeferredRegister<ActionRegistryEntry> ACTIONS = DeferredRegister.create(HexRegistries.ACTION, CreateManaIndustry.MODID);
    static {
        add(0, true, "create", false);
        String[] cubes = {"origin", "size", "pivot", "rotation", "scale", "inflate", "visible", "uv"};
        for (int i = 0; i < cubes.length; i++) {
            add(1 + 2 * i, true, cubes[i], false);
            add(2 + 2 * i, true, cubes[i], true);
        }
        add(17, false, "create", false);
        String[] groups = {"name", "pivot", "rotation", "scale", "visible", "cubes", "children", "texture_size"};
        for (int i = 0; i < groups.length; i++) {
            add(18 + 2 * i, false, groups[i], false);
            add(19 + 2 * i, false, groups[i], true);
        }
        add(34, false, "part", false);
        add(35, false, "source", false);
        ACTIONS.register("ysm_model_read", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("qwwqweqwwqw", HexDir.EAST), new OpReadYsmModel()));
        ACTIONS.register("ysm_model_apply", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("qwwqweqwwqe", HexDir.EAST), new OpApplyYsmModel()));
        ACTIONS.register("ysm_model_export", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("qwwqweqwwqa", HexDir.EAST), new OpExportYsmModel()));
        ACTIONS.register("ysm_model_restore", () -> new ActionRegistryEntry(
                HexPattern.fromAngleString("qwwqweqwwqq", HexDir.EAST), new OpRestoreYsmModel()));
    }
    private static void add(int index, boolean cube, String property, boolean setter) {
        String id = "ysm_" + (cube ? "cube_" : "group_") + property + (property.equals("create") ? "" : setter ? "_set" : "_get");
        // Every three-angle segment returns to EAST; x increases throughout, avoiding retraced edges.
        StringBuilder angles = new StringBuilder("wewqwwqwe");
        for (int bit = 5; bit >= 0; bit--) angles.append((index & (1 << bit)) == 0 ? "ewq" : "qwe");
        String pattern = angles.toString();
        ACTIONS.register(id, () -> new ActionRegistryEntry(HexPattern.fromAngleString(pattern, HexDir.EAST), new OpGeometry(cube, property, setter)));
    }
    public static void register(IEventBus bus) { ACTIONS.register(bus); }
    private CMIYsmActions() {}
}
