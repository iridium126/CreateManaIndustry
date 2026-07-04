package com.iridium126.createmanaindustry.content.kinetics.stressmanaconverter;

import java.util.function.Consumer;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import net.minecraft.core.Direction;

// Large cogs sometimes have to offset their teeth by 11.25 degrees in order to
// mesh properly
public class StressManaConverterVisual extends KineticBlockEntityVisual<StressManaConverterBlockEntity> implements SimpleTickableVisual {

	protected final RotatingInstance rotatingModel;
	protected final RotatingInstance additionalShaft;
	protected final Direction facing;
	
	public StressManaConverterVisual(VisualizationContext context, StressManaConverterBlockEntity blockEntity, float partialTick) {
		super(context, blockEntity, partialTick);

		facing = blockState.getValue(StressManaConverterBlock.FACING);
		Direction.Axis axis = KineticBlockEntityRenderer.getRotationAxisOf(blockEntity);

		rotatingModel = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(CMIPartialModels.STRESS_MANA_CONVERTER_OUTER))
			.createInstance();
		additionalShaft = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(CMIPartialModels.STRESS_MANA_CONVERTER_INNER))
			.createInstance();

		rotatingModel.rotateToFace(Direction.UP, facing)
			.setup(blockEntity)
			.setPosition(getVisualPosition())
			.setChanged();

		additionalShaft.rotateToFace(Direction.UP, facing)
			.setup(blockEntity)
			.setRotationOffset(StressManaConverterRenderer.getShaftAngleOffset(axis, pos))
			.setPosition(getVisualPosition())
			.setChanged();
	}

	@Override
	public void update(float pt) {
		rotatingModel.setup(blockEntity)
			.setChanged();
		additionalShaft.setup(blockEntity)
			.setRotationOffset(StressManaConverterRenderer.getShaftAngleOffset(rotationAxis(), pos))
			.setChanged();
	}

	@Override
	public void tick(Context context) {
		applyOverstressEffect(blockEntity, rotatingModel, additionalShaft);
	}

	@Override
	public void updateLight(float partialTick) {
		relight(rotatingModel);
		relight(additionalShaft);
	}

	@Override
	protected void _delete() {
		rotatingModel.delete();
		additionalShaft.delete();
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		consumer.accept(rotatingModel);
		consumer.accept(additionalShaft);
	}
}
