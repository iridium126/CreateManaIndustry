package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import net.minecraft.world.effect.MobEffectInstance;

/** Pure duration/hidden-effect operations; never ticks an entity. */
public interface PackageEffectAccess {
    int cmi$tickDuration();
    MobEffectInstance cmi$hidden();
    void cmi$hidden(MobEffectInstance hidden);
    void cmi$details(MobEffectInstance other);
}
