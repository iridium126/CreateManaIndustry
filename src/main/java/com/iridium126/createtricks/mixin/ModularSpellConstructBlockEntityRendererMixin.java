package com.iridium126.createtricks.mixin;

import java.lang.reflect.Field;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.client.render.KineticsSpellCoreRenderer;
import com.iridium126.createtricks.content.items.KineticsSpellCoreItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.Property;

@Mixin(targets = "dev.enjarai.trickster.render.ModularSpellConstructBlockEntityRenderer")
public abstract class ModularSpellConstructBlockEntityRendererMixin {
	private static final String MODULAR_SPELL_CONSTRUCT_BLOCK = "dev.enjarai.trickster.block.ModularSpellConstructBlock";

	@Inject(method = "render", at = @At("TAIL"), remap = false)
	private void createtricks$renderKineticsCores(@Coerce Object entity, float partialTicks,
			PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay, CallbackInfo ci) {
		if (!(entity instanceof Container inventory) || !(entity instanceof BlockEntity blockEntity))
			return;

		Direction facing = getFacing(blockEntity);
		int age = getAge(entity);

		matrices.pushPose();
		matrices.translate(0.5f, 0.5f, 0.5f);
		matrices.mulPose(facing.getRotation());
		matrices.translate(-0.5f, -0.5f, -0.5f);

		for (int slot = 1; slot < inventory.getContainerSize(); slot++) {
			if (KineticsSpellCoreItem.is(inventory.getItem(slot)))
				renderCogwheelCore(age, slot, partialTicks, matrices, vertexConsumers, light, overlay);
		}

		matrices.popPose();
	}

	private static Direction getFacing(BlockEntity blockEntity) {
		try {
			Field facingField = Class.forName(MODULAR_SPELL_CONSTRUCT_BLOCK).getField("FACING");
			@SuppressWarnings("unchecked")
			Property<Direction> facingProperty = (Property<Direction>) facingField.get(null);
			return blockEntity.getBlockState().getValue(facingProperty);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return Direction.UP;
		}
	}

	private static int getAge(Object entity) {
		try {
			return entity.getClass().getField("age").getInt(entity);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	private static void renderCogwheelCore(int age, int slot, float partialTicks,
			PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		int index = slot - 1;
		int x = index % 2;
		int z = index / 2;

		matrices.pushPose();
		matrices.translate((18f / 2 * x + 3.5f) / 16f, 10.5f / 16f, (18f / 2 * z + 3.5f) / 16f);
		matrices.mulPose(Axis.YP.rotation((age + partialTicks) * 0.2f));
		matrices.scale(0.28f, 0.28f, 0.28f);
		matrices.translate(-0.5f, -0.5f, -0.5f);

		KineticsSpellCoreRenderer.render(matrices, vertexConsumers, light, overlay);

		matrices.popPose();
	}
}
