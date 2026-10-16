package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEffectAccess;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MobEffectInstance.class)
public interface PackageEffectInstanceMixin extends PackageEffectAccess {
    @Invoker("tickDownDuration") int cmi$tickDuration();
    @Accessor("hiddenEffect") MobEffectInstance cmi$hidden();
    @Accessor("hiddenEffect") void cmi$hidden(MobEffectInstance hidden);
    @Invoker("setDetailsFrom") void cmi$details(MobEffectInstance other);
}
