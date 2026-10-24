package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;

/** CMI's late world effects, after translucency and before Iris' final composites. */
public final class IrisWorldEffects {
    private static final Map<String, Pass> PASSES = new LinkedHashMap<>();

    private IrisWorldEffects() {}

    public static void register(String id, int[] drawBuffers, BooleanSupplier enabled, BooleanSupplier draw) {
        PASSES.putIfAbsent(id, new Pass(drawBuffers.clone(), enabled, draw));
    }

    public static void render() {
        if (!IrisVeilCompat.isShaderPackInUse() || ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                || VeilLevelPerspectiveRenderer.isRenderingPerspective()) return;
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipelineAccessor pipeline)) return;
        for (var entry : PASSES.entrySet()) {
            Pass pass = entry.getValue();
            try {
                // The first gate also ticks animations, including when no pass needs drawing.
                if (!pass.enabled().getAsBoolean()) continue;
                try {
                    pipeline.irisveil$bindCompatGbufferFramebuffer(pass.drawBuffers());
                    pass.draw().getAsBoolean();
                } finally {
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                }
            } catch (RuntimeException | LinkageError error) {
                CreateManaIndustry.LOGGER.warn("[CMI compat] Iris world effect '{}' failed", entry.getKey(), error);
            }
        }
    }

    private record Pass(int[] drawBuffers, BooleanSupplier enabled, BooleanSupplier draw) {}
}
