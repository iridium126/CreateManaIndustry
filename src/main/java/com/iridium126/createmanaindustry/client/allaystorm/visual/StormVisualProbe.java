package com.iridium126.createmanaindustry.client.allaystorm.visual;

import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.render.shaderpack.*;
import foundry.veil.api.client.render.shader.program.UniformAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Local, opt-in diagnostic geometry; no entities, packets, saved state or gameplay rules. */
public final class StormVisualProbe {
    private static boolean initialized,enabled,baseline;
    private static Vec3 anchor=Vec3.ZERO;
    private static ResourceLocation dimension;
    private static float lastTime;
    private StormVisualProbe(){}
    public static void init(){
        if(initialized)return;initialized=true;
        IrisWorldEffects.register(new WorldEffectPass("storm-probe","storm_visual_probe",StormVisualProbe::enabled,()->{},StormVisualProbe::uniforms,false,true));
    }
    public static boolean enabled(){
        var level=Minecraft.getInstance().level;
        if(level==null||!level.dimension().location().equals(dimension))enabled=false;
        return enabled;
    }
    public static void setEnabled(boolean value){
        init();var mc=Minecraft.getInstance();enabled=value&&mc.level!=null&&mc.player!=null;
        if(enabled){
            var frame=StormVisualFrame.current();Vec3 center=frame!=null&&AllayStormRuntime.INSTANCE.active()?frame.center():mc.player.position();
            anchor=new Vec3(center.x,Math.max(mc.level.getMaxBuildHeight()+64,mc.gameRenderer.getMainCamera().getPosition().y+64),center.z);
            dimension=mc.level.dimension().location();
        }
    }
    public static void baseline(boolean value){baseline=value;}
    public static boolean baseline(){return baseline;}
    public static void clear(){enabled=false;dimension=null;}
    public static String status(){return "probe="+(enabled()?"on":"off")+" mode="+(baseline?"baseline":"auto")+" anchor="+anchor;}
    private static void uniforms(UniformAccess shader){
        var a=shader.getUniform("ProbeAnchor");if(a!=null)a.setVector((float)anchor.x,(float)anchor.y,(float)anchor.z,24);
        var mc=Minecraft.getInstance();float time=0;
        if(mc.level!=null){if(!mc.isPaused())lastTime=(mc.level.getGameTime()&0xFFFFF)/20f+mc.getTimer().getGameTimeDeltaPartialTick(false)/20f;time=lastTime;}
        var t=shader.getUniform("ProbeTime");if(t!=null)t.setFloat(time);
    }
}
