package com.iridium126.createmanaindustry.client.particles.engine;

/** Relative world time for external effects; suspension never advances their lifetime. */
public final class ParticleEffectClock {
    private Object world;
    private long origin;
    private float seconds;
    public float sample(Object world,long tick,float partial,boolean paused){
        if(world==null){this.world=null;seconds=0;return 0;}
        if(this.world!=world||tick<origin){this.world=world;origin=tick;seconds=0;}
        if(!paused){float fraction=Float.isFinite(partial)?Math.clamp(partial,0,1):0;seconds=Math.max(seconds,((tick-origin)+fraction)*.05f);}
        return seconds;
    }
    public float now(){
        var mc=net.minecraft.client.Minecraft.getInstance();var level=mc.level;
        return sample(level,level==null?0:level.getGameTime(),mc.getTimer().getGameTimeDeltaPartialTick(false),mc.isPaused());
    }
}
