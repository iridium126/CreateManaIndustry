package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.common.lib.HexRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Loaded only when Hexcasting and YSM are installed; independent of Trickster. */
public final class CMIYsmIotaTypes {
    private static final DeferredRegister<IotaType<?>> TYPES = DeferredRegister.create(HexRegistries.IOTA_TYPE, CreateManaIndustry.MODID);
    static {
        TYPES.register("cube", () -> CubeIota.TYPE);
        TYPES.register("group", () -> GroupIota.TYPE);
        TYPES.register("cube_ref", () -> CubeRefIota.TYPE);
        TYPES.register("group_ref", () -> GroupRefIota.TYPE);
    }
    public static void register(IEventBus bus) { TYPES.register(bus); }
    private CMIYsmIotaTypes() {}
}
