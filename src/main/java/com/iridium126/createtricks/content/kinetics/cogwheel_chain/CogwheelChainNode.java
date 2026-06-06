package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

public record CogwheelChainNode(BlockPos pos, Direction.Axis axis, boolean isLarge, Type type, int slot) {

	public enum Type {
		COGWHEEL,
		KINETICS_CORE;

		public String getSerializedName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		public static Type fromSerializedName(String name) {
			for (Type type : values()) {
				if (type.getSerializedName().equals(name))
					return type;
			}
			return COGWHEEL;
		}
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("x", pos.getX());
		tag.putInt("y", pos.getY());
		tag.putInt("z", pos.getZ());
		tag.putString("axis", axis.getName());
		tag.putBoolean("large", isLarge);
		tag.putString("type", type.getSerializedName());
		tag.putInt("slot", slot);
		return tag;
	}

	public static CogwheelChainNode load(CompoundTag tag) {
		BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
		Direction.Axis axis = Direction.Axis.byName(tag.getString("axis"));
		if (axis == null)
			axis = Direction.Axis.Y;
		boolean isLarge = tag.getBoolean("large");
		Type type = Type.fromSerializedName(tag.getString("type"));
		int slot = tag.getInt("slot");
		return new CogwheelChainNode(pos, axis, isLarge, type, slot);
	}

	public boolean isCogwheel() {
		return type == Type.COGWHEEL;
	}

	public boolean isKineticsCore() {
		return type == Type.KINETICS_CORE;
	}

	public boolean canLinkTo(CogwheelChainNode other) {
		return type != other.type() && !pos.equals(other.pos());
	}

}
