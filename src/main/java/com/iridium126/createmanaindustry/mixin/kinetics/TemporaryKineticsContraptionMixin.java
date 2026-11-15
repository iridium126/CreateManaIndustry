package com.iridium126.createmanaindustry.mixin.kinetics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.contraptions.Contraption;

import net.minecraft.nbt.CompoundTag;

@Mixin(value = Contraption.class, remap = false)
public abstract class TemporaryKineticsContraptionMixin {
    @ModifyReturnValue(method = "getBlockEntityNBT", at = @At("RETURN"))
    private CompoundTag createmanaindustry$excludeTemporaryEffect(CompoundTag captured) {
        if (captured != null)
            captured.remove(TemporaryKinetics.SAVED_EFFECT_KEY);
        return captured;
    }
}
