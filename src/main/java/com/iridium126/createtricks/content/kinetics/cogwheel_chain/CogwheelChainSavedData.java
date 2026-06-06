package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public class CogwheelChainSavedData extends SavedData {

	private static final String DATA_NAME = "createtricks_cogwheel_chains";
	private final Map<BlockPos, CogwheelChainData> chains = new HashMap<>();

	public CogwheelChainSavedData() {
	}

	public static CogwheelChainSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(
			new Factory<>(CogwheelChainSavedData::new, CogwheelChainSavedData::load),
			DATA_NAME
		);
	}

	public void addChain(CogwheelChainData chain) {
		chains.put(chain.getControllerPos(), chain);
		setDirty();
	}

	public void removeChain(BlockPos controllerPos) {
		if (chains.remove(controllerPos) != null)
			setDirty();
	}

	public List<CogwheelChainData> getChainsAt(BlockPos pos) {
		List<CogwheelChainData> result = new ArrayList<>();
		for (CogwheelChainData chain : chains.values()) {
			if (chain.containsPos(pos))
				result.add(chain);
		}
		return result;
	}

	public Collection<CogwheelChainData> getAllChains() {
		return Collections.unmodifiableCollection(chains.values());
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag chainList = new ListTag();
		for (CogwheelChainData chain : chains.values()) {
			chainList.add(chain.save());
		}
		tag.put("chains", chainList);
		return tag;
	}

	private static CogwheelChainSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		CogwheelChainSavedData data = new CogwheelChainSavedData();
		ListTag chainList = tag.getList("chains", Tag.TAG_COMPOUND);
		for (int i = 0; i < chainList.size(); i++) {
			CogwheelChainData chain = CogwheelChainData.load(chainList.getCompound(i));
			data.chains.put(chain.getControllerPos(), chain);
		}
		return data;
	}
}
