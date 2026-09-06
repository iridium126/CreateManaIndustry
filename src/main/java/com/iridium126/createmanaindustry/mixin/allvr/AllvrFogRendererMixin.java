package com.iridium126.createmanaindustry.mixin.allvr;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.dimension.AllvrLodClientState;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

/**
 * Extends the allay dimension's terrain fog to the ALLVR view extent
 * (2026-09-06; approach mirrors voxy's MixinFogRenderer for 1.21.1 —
 * .refs/voxy-backport): vanilla derives terrain fog from the render distance
 * (end = renderDistance×16 ≈ 192 blocks at 12 chunks, fade band
 * clamp(far/10, 4, 64)), which fogs out the full-res seam at 256 and the
 * entire LOD far field. Vanilla re-runs {@code setupFog} every frame, so no
 * restore path is needed on dimension change.
 * <p>
 * Only the <b>normal terrain-fog branch</b> is touched: vanilla assigns
 * {@code end = farPlaneDistance} there and nowhere else reachable with a
 * NONE fluid type (mob-effect fog ends at 5/15, thick fog at
 * {@code min(far,192)/2}, fluid fog at 1–96), so the exact
 * {@code end == farPlaneDistance} float comparison identifies the branch.
 * Sky fog keeps its vanilla horizon band (it already blends terrain into sky
 * at the vanilla distance and the dome is drawn fog-colored near the
 * horizon), and the NeoForge {@code onFogRender} hook is useless here — it
 * fires after the RenderSystem values were already written — hence the TAIL
 * inject instead. Priority 2000 (after voxy's default-priority inject) keeps
 * our values authoritative inside the allay dimension should the voxy mod
 * also inject; voxy ingests no content there (no vanilla chunks), so this
 * never fights its LOD fog. Every other dimension returns early — their fog
 * is byte-identical whether or not voxy is installed.
 * <p>
 * The fog SHAPE stays vanilla CYLINDER; only the pair is rescaled — end =
 * view extent, start × extent/far keeps the vanilla fade-band look.
 */
@Mixin(value = FogRenderer.class, priority = 2000)
public abstract class AllvrFogRendererMixin {

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void cmi$extendAllayFog(Camera camera, FogRenderer.FogMode mode, float farPlaneDistance,
                                           boolean thickFog, float partialTick, CallbackInfo ci) {
        if (mode != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return; // other dimensions: vanilla fog, voxy installed or not
        }
        if (camera.getFluidInCamera() != FogType.NONE) {
            return; // water/lava/powder-snow restriction fog is never extended
        }
        float end = RenderSystem.getShaderFogEnd();
        if (end != farPlaneDistance) {
            return; // blindness/darkness/thick fog produce other values — leave them
        }
        float extent = AllvrLodClientState.viewExtentBlocks();
        if (extent <= end) {
            return;
        }
        RenderSystem.setShaderFogStart(RenderSystem.getShaderFogStart() * (extent / end));
        RenderSystem.setShaderFogEnd(extent);
    }
}
