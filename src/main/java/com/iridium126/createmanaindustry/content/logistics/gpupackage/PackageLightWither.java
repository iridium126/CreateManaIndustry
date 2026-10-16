package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Wither is the block-applied effect that hurts packages; keep its native amplifier and hidden stack. */
final class PackageLightWither {
    private PackageLightWither(){}
    static MobEffectInstance copy(MobEffectInstance effect){return effect==null?null:MobEffectInstance.load((CompoundTag)effect.save());}
    static MobEffectInstance current(PackageLightStore.Entry entry){
        if(entry.witherEffect==null&&entry.witherTicks!=0)entry.witherEffect=new MobEffectInstance(MobEffects.WITHER,entry.witherTicks);
        return entry.witherEffect;
    }
    static void rose(PackageLightStore.Entry entry){
        var effect=current(entry);var incoming=new MobEffectInstance(MobEffects.WITHER,40);
        if(effect==null)entry.witherEffect=incoming;else effect.update(incoming);
        entry.witherTicks=entry.witherEffect.getDuration();
    }
    static boolean tick(ServerLevel level,PackageLightStore.Entry entry,int age){
        var effect=current(entry);if(effect==null)return false;
        if(!effect.isInfiniteDuration()&&effect.getDuration()<=0){entry.witherEffect=null;entry.witherTicks=0;return false;}
        int clock=effect.isInfiniteDuration()?age:effect.getDuration();
        if(effect.getEffect().value().shouldApplyEffectTickThisTick(clock,effect.getAmplifier())
                &&PackageLightNativeEnvironment.hurt(level,entry,level.damageSources().wither()))return true;
        var access=(PackageEffectAccess)(Object)effect;access.cmi$tickDuration();
        if(effect.getDuration()==0&&access.cmi$hidden()!=null){
            var hidden=access.cmi$hidden();access.cmi$details(hidden);access.cmi$hidden(((PackageEffectAccess)(Object)hidden).cmi$hidden());
        }
        entry.witherTicks=effect.getDuration();
        if(!effect.isInfiniteDuration()&&entry.witherTicks<=0){entry.witherEffect=null;entry.witherTicks=0;}
        return false;
    }
}
