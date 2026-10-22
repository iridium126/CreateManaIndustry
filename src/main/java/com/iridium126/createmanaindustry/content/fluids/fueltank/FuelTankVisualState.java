package com.iridium126.createmanaindustry.content.fluids.fueltank;

import net.createmod.catnip.animation.LerpedFloat;

/** Client-owned animation and mesh cache; never serialized or used for fluid accounting. */
public final class FuelTankVisualState {
    private final FuelTankGeometry geometry;
    private final LerpedFloat[] surfaces;
    public Object renderCache;

    public FuelTankVisualState(FuelTankFluidState state) {
        geometry = state.geometry(); surfaces = new LerpedFloat[geometry.basins.size()];
        for (int i = 0; i < surfaces.length; i++)
            surfaces[i] = LerpedFloat.linear().startWithValue(state.relativeSurface(i));
    }
    public boolean matches(FuelTankFluidState state) { return geometry == state.geometry(); }
    public void tick(FuelTankFluidState state) {
        for (int i = 0; i < surfaces.length; i++) {
            surfaces[i].chase(state.relativeSurface(i), .5, LerpedFloat.Chaser.EXP);
            surfaces[i].tickChaser();
        }
    }
    public float surface(int basin, float partialTicks) {
        return geometry.origin.getY() + surfaces[basin].getValue(partialTicks);
    }
}
