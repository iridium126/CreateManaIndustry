package com.iridium126.createmanaindustry.datagen;

import java.util.concurrent.CompletableFuture;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.api.registrate.CreateRegistrateRegistrationCallback;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Project Registrate instance with the additional data providers used by CMI.
 *
 * <p>The conditional loot provider is added after Registrate's aggregate
 * provider.  DataGenerator runs top-level providers in insertion order, while
 * Registrate runs its own providers concurrently; this ordering lets the
 * conditional table replace Registrate's validation placeholder reliably.</p>
 */
public final class CMIRegistrate extends CreateRegistrate {
    private CMIRegistrate(String modid) {
        super(modid);
    }

    public static CMIRegistrate create(String modid) {
        CMIRegistrate registrate = new CMIRegistrate(modid);
        CreateRegistrateRegistrationCallback.provideRegistrate(registrate);
        return registrate;
    }

    @Override
    protected void onData(GatherDataEvent event) {
        super.onData(event);
        if (event.includeServer()) {
            PackOutput output = event.getGenerator().getPackOutput();
            CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();
            event.getGenerator().addProvider(true,
                    new ConditionalLootTableProvider(output, lookup));
        }
    }
}
