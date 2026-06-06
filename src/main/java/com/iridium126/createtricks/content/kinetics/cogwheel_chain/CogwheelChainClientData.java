package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

public final class CogwheelChainClientData {

	private static final Map<BlockPos, CogwheelChainData> chains = new HashMap<>();

	private CogwheelChainClientData() {}

	public static void receiveSync(List<CogwheelChainData> allChains) {
		chains.clear();
		for (CogwheelChainData chain : allChains) {
			chains.put(chain.getControllerPos(), chain);
		}
	}

	public static Collection<CogwheelChainData> getAllChains() {
		return Collections.unmodifiableCollection(chains.values());
	}

	public static List<CogwheelChainData> getChainsAt(BlockPos pos) {
		List<CogwheelChainData> result = new ArrayList<>();
		for (CogwheelChainData chain : chains.values()) {
			if (chain.containsPos(pos))
				result.add(chain);
		}
		return result;
	}

	public static void clear() {
		chains.clear();
	}
}
