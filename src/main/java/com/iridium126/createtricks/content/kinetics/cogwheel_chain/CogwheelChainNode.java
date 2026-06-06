package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

public record CogwheelChainNode(BlockPos pos, Direction.Axis axis, boolean isLarge) {

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("x", pos.getX());
		tag.putInt("y", pos.getY());
		tag.putInt("z", pos.getZ());
		tag.putString("axis", axis.getName());
		tag.putBoolean("large", isLarge);
		return tag;
	}

	public static CogwheelChainNode load(CompoundTag tag) {
		BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
		Direction.Axis axis = Direction.Axis.byName(tag.getString("axis"));
		if (axis == null)
			axis = Direction.Axis.Y;
		boolean isLarge = tag.getBoolean("large");
		return new CogwheelChainNode(pos, axis, isLarge);
	}
}
