package com.iridium126.createmanaindustry.compat.hextrick.bootstrap;

import com.iridium126.createmanaindustry.compat.hextrick.conversion.ConversionTickTime;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.FragmentConverter;
import com.iridium126.createmanaindustry.compat.hextrick.data.FragmentStructures;
import com.iridium126.createmanaindustry.compat.hextrick.data.InlineTrickData;
import com.iridium126.createmanaindustry.compat.hextrick.execution.BlockSpellExecutionManager;
import com.iridium126.createmanaindustry.compat.hextrick.execution.BridgeExecutionContext;
import com.iridium126.createmanaindustry.compat.hextrick.execution.BridgeSpellExecutor;
import com.iridium126.createmanaindustry.compat.hextrick.execution.SourceIdentity;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.CircleSlateManaPool;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.SlateKnotInteraction;
import com.samsthenerd.inline.api.InlineAPI;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Loaded only when Hexcasting and Trickster are both present. */
public final class HexTricksBootstrap {
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener((FMLLoadCompleteEvent event) -> event.enqueueWork(() -> {
            FragmentStructures.freeze();
            FragmentConverter.freeze();
        }));
        CMIHexIotaTypes.register(modEventBus);
        InlineAPI.INSTANCE.addDataType(InlineTrickData.InlineTrickDataType.INSTANCE);
        CMIHexTrickActions.register(modEventBus);
        ConversionTickTime.register();
        SlateKnotInteraction.register();
        CircleSlateManaPool.ensureTypeRegistered();
        BlockSpellExecutionManager.register();
        BridgeSpellExecutor.register();
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent event) -> {
            if (event.getLevel() instanceof ServerLevel level
                    && level.getBlockEntity(event.getPos()) instanceof SourceIdentity source)
                source.cmi$newSourceIdentity();
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) ->
                BridgeExecutionContext.stopped(event.getServer()));
    }
    private HexTricksBootstrap() {}
}
