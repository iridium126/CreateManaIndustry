package com.iridium126.createtricks.content.kinetics.bnb;

import com.iridium126.createtricks.CreateTricks;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;

public final class BnBChainCompatibility {
	public static final String BNB_MOD_ID = "bits_n_bobs";

	private BnBChainCompatibility() {}

	public static boolean isLoaded() {
		return ModList.get().isLoaded(BNB_MOD_ID);
	}

	public static void register() {
		if (!isLoaded())
			return;
		if (!BnBChainInteractionEvents.isAvailable()) {
			CreateTricks.LOGGER.warn("Create: Bits 'n' Bobs is installed, but its cogwheel chain API was not found");
			return;
		}

		NeoForge.EVENT_BUS.register(BnBChainInteractionEvents.class);
		if (FMLEnvironment.dist == Dist.CLIENT)
			BnBChainClientEvents.register();
		CreateTricks.LOGGER.info("Create: Bits 'n' Bobs cogwheel chain compatibility enabled");
	}
}
