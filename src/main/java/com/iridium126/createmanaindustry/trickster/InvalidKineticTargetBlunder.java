package com.iridium126.createmanaindustry.trickster;

import dev.enjarai.trickster.spell.blunder.TrickBlunderException;
import dev.enjarai.trickster.spell.trick.Trick;
import net.minecraft.core.BlockPos;

public class InvalidKineticTargetBlunder extends TrickBlunderException {
	private final BlockPos pos;

	public InvalidKineticTargetBlunder(Trick<?> source, BlockPos pos) {
		super(source);
		this.pos = pos;
	}

	public BlockPos getPos() {
		return pos;
	}
}
