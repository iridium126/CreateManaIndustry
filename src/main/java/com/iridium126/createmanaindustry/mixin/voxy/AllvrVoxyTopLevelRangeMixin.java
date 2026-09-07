package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyTopLevelRange;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

/**
 * Top-level Y roots for the allay dimension (voxy integration plan §7.5-2):
 * Voxy derives the L4 top-level node Y range from the vanilla build height
 * ({@code getMinSection()>>5 .. (getMaxSection()-1)>>5}), which for the
 * allay dimension's 384-block formal window yields only two L4 keys and
 * would leave everything outside it unrenderable. For the allay level the
 * getters are remapped so the derived range becomes exactly
 * {@code [-8, 7]} — the 16 L4 keys that tile the L0 hard window
 * {@code [-128, 127]} exactly. Every other dimension keeps Voxy's own value.
 * <p>
 * Applied only when the voxy mod is present (the mixin plugin gate); the
 * injection is require=1 against the pinned voxy artifact.
 */
@Mixin(value = me.cortex.voxy.client.core.VoxyRenderSystem.class, remap = false)
public abstract class AllvrVoxyTopLevelRangeMixin {

    /** {@code (minSection >> 5) == -8} for the allay dimension. */
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinSection()I"), remap = false)
    private static int allvr$topLevelMinSectionY(int minSection) {
        if (isAllayLevel()) {
            return AllvrVoxyTopLevelRange.TOP_LEVEL_MIN_SECTION_Y;
        }
        return minSection;
    }

    /** {@code (maxSection - 1 >> 5) == 7} for the allay dimension. */
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxSection()I"), remap = false)
    private static int allvr$topLevelMaxSectionY(int maxSection) {
        if (isAllayLevel()) {
            return AllvrVoxyTopLevelRange.TOP_LEVEL_MAX_SECTION_EXCLUSIVE;
        }
        return maxSection;
    }

    @Unique
    private static boolean isAllayLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && AllvrDimensions.isAllay(level);
    }
}
