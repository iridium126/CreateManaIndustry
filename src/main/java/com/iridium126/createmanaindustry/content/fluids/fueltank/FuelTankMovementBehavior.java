package com.iridium126.createmanaindustry.content.fluids.fueltank;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Keeps the fuel tank's liquid-level chaser ticking while the tank is carried on
 * a contraption (mirrors Create's {@code FluidTankMovementBehavior}).
 */
public class FuelTankMovementBehavior implements MovementBehaviour {

	@Override
	public boolean mustTickWhileDisabled() {
		return true;
	}

	@Override
	public void tick(MovementContext context) {
		if (context.world.isClientSide) {
			BlockEntity be = context.contraption.getBlockEntityClientSide(context.localPos);
			if (be instanceof FuelTankBlockEntity tank) {
				tank.tickVisuals();
			}
		}
	}
}
