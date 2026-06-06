package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public class CogwheelChainData {

	private final List<CogwheelChainNode> nodes;

	public CogwheelChainData(List<CogwheelChainNode> nodes) {
		this.nodes = new ArrayList<>(nodes);
	}

	public List<CogwheelChainNode> getNodes() {
		return Collections.unmodifiableList(nodes);
	}

	public BlockPos getControllerPos() {
		return nodes.isEmpty() ? BlockPos.ZERO : nodes.get(0).pos();
	}

	public boolean containsPos(BlockPos pos) {
		return nodes.stream().anyMatch(n -> n.pos().equals(pos));
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		ListTag nodeList = new ListTag();
		for (CogwheelChainNode node : nodes) {
			nodeList.add(node.save());
		}
		tag.put("nodes", nodeList);
		return tag;
	}

	public static CogwheelChainData load(CompoundTag tag) {
		ListTag nodeList = tag.getList("nodes", Tag.TAG_COMPOUND);
		List<CogwheelChainNode> nodes = new ArrayList<>();
		for (int i = 0; i < nodeList.size(); i++) {
			nodes.add(CogwheelChainNode.load(nodeList.getCompound(i)));
		}
		return new CogwheelChainData(nodes);
	}
}
