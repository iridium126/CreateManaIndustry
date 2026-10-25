package com.iridium126.createmanaindustry.client.render.mist;
import com.iridium126.createmanaindustry.client.render.shaderpack.*;
/** Registers mist with the shared native/display-space compositor. */
public final class MistIrisHook {
    private static boolean registered;
    private MistIrisHook(){}
    public static void init(){
        if(registered)return;registered=true;
        IrisWorldEffects.register(new WorldEffectPass("mist","mist_volumetric_iris",MistClientHandler::isMistActive,
                MistClientHandler::tickMist,MistClientHandler::applyMistUniforms,true,false));
    }
    public static boolean isActivePath(){return IrisWorldEffects.shaderPackActive()&&WorldEffectRenderer.shaderReady("mist_volumetric_iris");}
}
