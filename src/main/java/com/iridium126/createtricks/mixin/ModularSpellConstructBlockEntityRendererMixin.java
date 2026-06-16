package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.client.render.KineticsSpellCoreRenderer;
import com.iridium126.createtricks.content.items.KineticsSpellCoreItem;
import com.iridium126.createtricks.content.kinetics.bnb.BnBChainRenderContext;
import com.iridium126.createtricks.content.kinetics.bnb.BnBReflection;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(targets = "dev.enjarai.trickster.render.ModularSpellConstructBlockEntityRenderer")
public abstract class ModularSpellConstructBlockEntityRendererMixin {
	/**
	 * Intercepts the coreStack assignment in the first rendering loop (the one that
	 * draws sprite-based core models). When the stack is a kinetics_spell_core,
	 * replaces it with EMPTY so the original loop skips it. The cogwheel is then
	 * rendered by {@link #createtricks$renderKineticsCores} at TAIL instead.
	 * <p>
	 * Ordinal 1 targets the second ItemStack local variable store in the render
	 * method — the coreStack inside the first {@code for (int i = 1; ...)} loop.
	 * Other spell cores pass through unchanged and render as normal.
	 */
	@ModifyVariable(method = "render", at = @At("STORE"), ordinal = 1, remap = false)
	private ItemStack createtricks$hideKineticsCoreStack(ItemStack coreStack) {
		if (KineticsSpellCoreItem.is(coreStack)) {
			return ItemStack.EMPTY;
		}
		return coreStack;
	}

	@Inject(method = "render", at = @At("TAIL"), remap = false)
	private void createtricks$renderKineticsCores(@Coerce Object entity, float partialTicks,
			PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay, CallbackInfo ci) {
		if (!(entity instanceof Container inventory) || !(entity instanceof BlockEntity blockEntity))
			return;

		Direction facing = getFacing(blockEntity);

		matrices.pushPose();
		matrices.translate(0.5f, 0.5f, 0.5f);
		matrices.mulPose(facing.getRotation());
		matrices.translate(-0.5f, -0.5f, -0.5f);

		for (int slot = 1; slot < inventory.getContainerSize(); slot++) {
			if (KineticsSpellCoreItem.is(inventory.getItem(slot)))
				renderCogwheelCore(slot, matrices, vertexConsumers, light, overlay,
						blockEntity.getBlockPos());
		}

		matrices.popPose();
	}

	private static Direction getFacing(BlockEntity blockEntity) {
		var prop = BnBReflection.facingProperty();
		if (prop != null) {
			try {
				return blockEntity.getBlockState().getValue(prop);
			} catch (IllegalArgumentException e) {
			}
		}
		return Direction.UP;
	}

	private static void renderCogwheelCore(int slot, PoseStack matrices, MultiBufferSource vertexConsumers,
			int light, int overlay, BlockPos spellPos) {
		int index = slot - 1;
		int x = index % 2;
		int z = index / 2;

		matrices.pushPose();
		matrices.translate((18f / 2 * x + 3.5f) / 16f, 10.5f / 16f, (18f / 2 * z + 3.5f) / 16f);

		// Match rotation speed / direction to the connected cogwheel chain.
		// Uses the same formula as CogwheelChainBlockEntityRenderer.renderSafe:
		//   angularVelocity = 2π × chainRotationFactor × speed / 1200
		//   angle = angularVelocity × AnimationTickHolder.getRenderTime()
		float angularVelocity = BnBChainRenderContext.getChainAngularVelocity(spellPos);
		if (angularVelocity != 0) {
			matrices.mulPose(Axis.YP.rotation(angularVelocity * AnimationTickHolder.getRenderTime()));
		}

		matrices.scale(0.28f, 0.28f, 0.28f);
		matrices.translate(-0.5f, -0.5f, -0.5f);

		KineticsSpellCoreRenderer.render(matrices, vertexConsumers, light, overlay);

		matrices.popPose();
	}
}
