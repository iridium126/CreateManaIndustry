package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.utils.TreeList;
import net.minecraft.nbt.CompoundTag;

/** Internal bridge for committing a proven private fast-loop Tick image in place. */
public interface CastingImageLoopAccess {
    void cmi$applyFastTickOutput(TreeList<Iota> stack, long opsConsumed, CompoundTag userData);
}
