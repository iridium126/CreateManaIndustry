package com.iridium126.createmanaindustry.client.render.fuelrod;
import com.iridium126.createmanaindustry.client.render.shaderpack.*;
/** Registers ring radiance with the shared native/display-space compositor. */
public final class FuelRodBloomIrisHook {
    private static boolean registered;
    private FuelRodBloomIrisHook(){}
    public static void init(){
        if(registered)return;registered=true;
        IrisWorldEffects.register(new WorldEffectPass("fuel-rod-glow","fuel_rod_glow_iris",FuelRodBloomHandler::isGlowActive,
                FuelRodBloomHandler::tickGlow,FuelRodBloomHandler::applyGlowUniforms,false,false));
    }
    public static boolean isActivePath(){return IrisWorldEffects.shaderPackActive()&&WorldEffectRenderer.shaderReady("fuel_rod_glow_iris");}
}
