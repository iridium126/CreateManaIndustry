package com.iridium126.createmanaindustry.compat.hexcasting;

import com.iridium126.createmanaindustry.compat.hexcasting.circle.CircleSlateManaPool;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.SlateKnotInteraction;
import com.iridium126.createmanaindustry.compat.trickster.CMITricksterIotaRegister;
import com.samsthenerd.inline.api.InlineAPI;
import net.neoforged.bus.api.IEventBus;

/** Loaded only when Hexcasting and Trickster are both present. */
public final class HexTricksBootstrap {
    public static void register(IEventBus modEventBus) {
        CMIHexIotaTypes.register(modEventBus);
        InlineAPI.INSTANCE.addDataType(InlineTrickData.InlineTrickDataType.INSTANCE);
        CMIHexTrickActions.register(modEventBus);
        CMITricksterIotaRegister.register();
        SlateKnotInteraction.register();
        CircleSlateManaPool.ensureTypeRegistered();
    }
    private HexTricksBootstrap() {}
}
