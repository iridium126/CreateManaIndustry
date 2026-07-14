package com.iridium126.createmanaindustry.content.fluids.mist;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Global per-tick handler that cleans up stale atomizer entries from the mist
 * field store. Mirrors the {@code TemporaryStressTicker} pattern.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class MistFieldTicker {
    private MistFieldTicker() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel)
            MistEmitter.tick(serverLevel);
    }
}
