package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.Minecraft;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

/**
 * Makes Voxy's screen-space subdivision agree with ALLVR's server-owned LOD
 * bands. ALLVR supplies standalone L0/L1/L2/L3 leaves at successively doubled
 * distances; asking one of those leaves for a finer child can never succeed
 * and used to produce a permanent request/rebuild loop (and a saturated GPU).
 *
 * <p>Voxy normally interprets {@code subDivisionSize} as a pixel count and
 * squares it before dividing by the viewport area. For the allay dimension we
 * return a viewport-scaled value, leaving a resolution-independent normalized
 * screen-area threshold. The 2% boundary sits between an ALLVR band leaf and
 * its twice-as-large topology parent, so parents still open while the supplied
 * leaf remains the terminal render node. Other dimensions retain the user's
 * Voxy quality setting byte-for-byte.
 */
@Mixin(value = me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.class,
    remap = false)
public abstract class AllvrVoxyLodThresholdMixin {

    private static final float ALLVR_TERMINAL_SCREEN_AREA = 0.02f;

    @ModifyExpressionValue(method = "uploadUniform",
        at = @At(value = "FIELD",
            target = "Lme/cortex/voxy/client/config/VoxyConfig;subDivisionSize:F"),
        require = 2, remap = false)
    private float allvr$matchServerLodBands(float original,
            me.cortex.voxy.client.core.rendering.Viewport<?> viewport) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !AllvrDimensions.isAllay(mc.level)) {
            return original;
        }
        return (float) Math.sqrt(ALLVR_TERMINAL_SCREEN_AREA
            * Math.max(1, viewport.width) * Math.max(1, viewport.height));
    }
}
